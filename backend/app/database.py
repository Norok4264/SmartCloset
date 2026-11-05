from __future__ import annotations

import hashlib
import secrets
import sqlite3
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any
import uuid


class ClosingConnection(sqlite3.Connection):
    def __exit__(self, exc_type, exc_value, traceback):
        try:
            return super().__exit__(exc_type, exc_value, traceback)
        finally:
            self.close()


def utc_now() -> datetime:
    return datetime.now(timezone.utc)


def timestamp(value: datetime | None = None) -> str:
    return (value or utc_now()).isoformat(timespec="seconds")


class Database:
    def __init__(self, path: Path):
        self.path = path

    def connect(self) -> sqlite3.Connection:
        conn = sqlite3.connect(self.path, timeout=10, isolation_level="IMMEDIATE", factory=ClosingConnection)
        conn.row_factory = sqlite3.Row
        conn.execute("PRAGMA foreign_keys=ON")
        return conn

    def initialize(self) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        with self.connect() as db:
            db.executescript("""
                CREATE TABLE IF NOT EXISTS sessions (
                    token_hash TEXT PRIMARY KEY,
                    created_at TEXT NOT NULL,
                    expires_at TEXT NOT NULL
                );
                CREATE TABLE IF NOT EXISTS tryon_jobs (
                    id TEXT PRIMARY KEY,
                    owner_hash TEXT NOT NULL REFERENCES sessions(token_hash),
                    idempotency_key TEXT NOT NULL,
                    payload_hash TEXT NOT NULL,
                    status TEXT NOT NULL,
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL,
                    person_path TEXT,
                    clothing_path TEXT,
                    result_path TEXT,
                    result_mime TEXT,
                    error_code TEXT,
                    error_message TEXT,
                    is_mock INTEGER NOT NULL DEFAULT 0,
                    UNIQUE(owner_hash, idempotency_key)
                );
                CREATE INDEX IF NOT EXISTS idx_tryon_owner_created ON tryon_jobs(owner_hash, created_at DESC);
                CREATE INDEX IF NOT EXISTS idx_tryon_status_created ON tryon_jobs(status, created_at);
            """)
            # Never replay an uncertain external operation after a process restart.
            db.execute("UPDATE tryon_jobs SET status='UNKNOWN', error_code='server_restarted', error_message='서버 재시작으로 제공사 처리 여부를 확인할 수 없습니다.', updated_at=? WHERE status IN ('QUEUED','PROCESSING')", (timestamp(),))

    @staticmethod
    def hash_token(token: str) -> str:
        return hashlib.sha256(token.encode("utf-8")).hexdigest()

    def create_session(self, ttl_days: int) -> tuple[str, str]:
        raw = secrets.token_urlsafe(32)
        token_hash = self.hash_token(raw)
        created = utc_now()
        expires = created + timedelta(days=ttl_days)
        with self.connect() as db:
            db.execute("INSERT INTO sessions(token_hash,created_at,expires_at) VALUES(?,?,?)", (token_hash, timestamp(created), timestamp(expires)))
        return raw, token_hash

    def valid_session(self, token_hash: str) -> bool:
        with self.connect() as db:
            row = db.execute("SELECT 1 FROM sessions WHERE token_hash=? AND expires_at>?", (token_hash, timestamp())).fetchone()
            return row is not None

    def daily_count(self, owner_hash: str) -> int:
        today = utc_now().date().isoformat()
        with self.connect() as db:
            return int(db.execute("SELECT COUNT(*) FROM tryon_jobs WHERE owner_hash=? AND created_at>=?", (owner_hash, today)).fetchone()[0])

    def job_by_idempotency(self, owner_hash: str, key: str) -> sqlite3.Row | None:
        with self.connect() as db:
            return db.execute("SELECT * FROM tryon_jobs WHERE owner_hash=? AND idempotency_key=?", (owner_hash, key)).fetchone()

    def create_job(self, owner_hash: str, key: str, payload_hash: str, person_path: Path, clothing_path: Path, daily_limit: int) -> tuple[sqlite3.Row, bool]:
        job_id = str(uuid.uuid4())
        now = timestamp()
        with self.connect() as db:
            db.execute("BEGIN IMMEDIATE")
            existing = db.execute("SELECT * FROM tryon_jobs WHERE owner_hash=? AND idempotency_key=?", (owner_hash, key)).fetchone()
            if existing is not None:
                if existing["payload_hash"] != payload_hash:
                    raise IdempotencyConflict
                return existing, False
            today = utc_now().date().isoformat()
            count = int(db.execute("SELECT COUNT(*) FROM tryon_jobs WHERE owner_hash=? AND created_at>=?", (owner_hash, today)).fetchone()[0])
            if count >= daily_limit:
                raise DailyLimitExceeded
            db.execute("INSERT INTO tryon_jobs(id,owner_hash,idempotency_key,payload_hash,status,created_at,updated_at,person_path,clothing_path) VALUES(?,?,?,?,?,?,?,?,?)", (job_id, owner_hash, key, payload_hash, "QUEUED", now, now, str(person_path), str(clothing_path)))
            return db.execute("SELECT * FROM tryon_jobs WHERE id=?", (job_id,)).fetchone(), True

    def get_job(self, owner_hash: str, job_id: str) -> sqlite3.Row | None:
        with self.connect() as db:
            return db.execute("SELECT * FROM tryon_jobs WHERE owner_hash=? AND id=?", (owner_hash, job_id)).fetchone()

    def list_jobs(self, owner_hash: str, limit: int = 30) -> list[sqlite3.Row]:
        with self.connect() as db:
            return list(db.execute("SELECT * FROM tryon_jobs WHERE owner_hash=? AND status!='DELETED' ORDER BY created_at DESC LIMIT ?", (owner_hash, limit)).fetchall())

    def set_status(self, job_id: str, status: str, *, result_path: Path | None = None, result_mime: str | None = None, error_code: str | None = None, error_message: str | None = None, is_mock: bool | None = None) -> None:
        fields: dict[str, Any] = {"status": status, "updated_at": timestamp(), "error_code": error_code, "error_message": error_message}
        if result_path is not None:
            fields.update(result_path=str(result_path), result_mime=result_mime)
        if is_mock is not None:
            fields["is_mock"] = int(is_mock)
        assignments = ",".join(f"{field}=?" for field in fields)
        with self.connect() as db:
            db.execute(f"UPDATE tryon_jobs SET {assignments} WHERE id=?", (*fields.values(), job_id))

    def mark_deleted(self, job_id: str) -> list[Path]:
        with self.connect() as db:
            row = db.execute("SELECT person_path,clothing_path,result_path FROM tryon_jobs WHERE id=?", (job_id,)).fetchone()
            if row is None:
                return []
            db.execute("UPDATE tryon_jobs SET status='DELETED', person_path=NULL, clothing_path=NULL, result_path=NULL, error_code=NULL, error_message=NULL, updated_at=? WHERE id=?", (timestamp(), job_id))
            return [Path(row[k]) for k in ("person_path", "clothing_path", "result_path") if row[k]]

    def expire_old(self, retention_hours: int) -> list[Path]:
        cutoff = timestamp(utc_now() - timedelta(hours=retention_hours))
        with self.connect() as db:
            rows = db.execute("SELECT id,person_path,clothing_path,result_path FROM tryon_jobs WHERE status IN ('SUCCEEDED','FAILED','UNKNOWN','DELETED') AND updated_at<?", (cutoff,)).fetchall()
            paths = [Path(row[k]) for row in rows for k in ("person_path", "clothing_path", "result_path") if row[k]]
            db.executemany("UPDATE tryon_jobs SET status='EXPIRED', person_path=NULL, clothing_path=NULL, result_path=NULL, error_code=NULL, error_message=NULL, updated_at=? WHERE id=?", [(timestamp(), row["id"]) for row in rows])
            return paths

    def prune_idempotency_tombstones(self, days: int = 30) -> None:
        cutoff = timestamp(utc_now() - timedelta(days=days))
        with self.connect() as db:
            db.execute("DELETE FROM tryon_jobs WHERE status='EXPIRED' AND updated_at<?", (cutoff,))
            db.execute("DELETE FROM sessions WHERE expires_at<? AND NOT EXISTS (SELECT 1 FROM tryon_jobs WHERE owner_hash=sessions.token_hash)", (timestamp(),))


class DailyLimitExceeded(Exception):
    pass


class IdempotencyConflict(Exception):
    pass
