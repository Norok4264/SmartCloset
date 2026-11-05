from __future__ import annotations

import asyncio
import hashlib
import io
import os
import secrets
import sqlite3
import time
from contextlib import asynccontextmanager
from pathlib import Path
from typing import Annotated
from uuid import UUID, uuid4

from dotenv import load_dotenv
load_dotenv()

from fastapi import BackgroundTasks, Depends, FastAPI, File, Header, HTTPException, Request, UploadFile
from fastapi.responses import FileResponse, JSONResponse
from PIL import Image, ImageOps, UnidentifiedImageError

from .config import Settings
from .database import DailyLimitExceeded, Database, IdempotencyConflict
from .provider import GoogleVertexTryOnProvider, ProviderError, TryOnProvider, create_provider

_SESSION_CREATION_TIMES: dict[str, list[float]] = {}
_SESSION_RATE_LOCK = asyncio.Lock()


def _clean_paths(paths: list[Path]) -> None:
    for path in paths:
        try:
            path.unlink(missing_ok=True)
            parent = path.parent
            if parent.exists() and not any(parent.iterdir()):
                parent.rmdir()
        except OSError:
            pass


def _safe_image(raw: bytes, declared_mime: str, max_bytes: int) -> tuple[bytes, str]:
    if not raw:
        raise HTTPException(400, detail={"code": "empty_image", "message": "이미지 파일이 비어 있습니다."})
    if len(raw) > max_bytes:
        raise HTTPException(413, detail={"code": "image_too_large", "message": f"이미지 하나의 최대 크기는 {max_bytes // (1024 * 1024)}MiB입니다."})
    if declared_mime not in {"image/jpeg", "image/png"}:
        raise HTTPException(415, detail={"code": "unsupported_image_type", "message": "JPEG 또는 PNG 이미지만 사용할 수 있습니다."})
    try:
        with Image.open(io.BytesIO(raw)) as image:
            actual = "image/jpeg" if image.format == "JPEG" else "image/png" if image.format == "PNG" else ""
            if actual != declared_mime:
                raise HTTPException(415, detail={"code": "image_type_mismatch", "message": "파일 내용과 이미지 형식이 일치하지 않습니다."})
            if image.width < 64 or image.height < 64:
                raise HTTPException(400, detail={"code": "image_dimensions_too_small", "message": "이미지 해상도가 너무 작습니다."})
            if image.width * image.height > 30_000_000:
                raise HTTPException(413, detail={"code": "decoded_image_too_large", "message": "해상도가 너무 큰 이미지입니다."})
            normalized = ImageOps.exif_transpose(image).convert("RGB")
            output = io.BytesIO()
            normalized.save(output, format="JPEG", quality=88, optimize=True)
            content = output.getvalue()
            if len(content) > max_bytes:
                output = io.BytesIO()
                normalized.save(output, format="JPEG", quality=75, optimize=True)
                content = output.getvalue()
            normalized.close()
            if len(content) > max_bytes:
                raise HTTPException(413, detail={"code": "normalized_image_too_large", "message": "이미지를 제공사 입력 크기까지 줄일 수 없습니다."})
            return content, "image/jpeg"
    except HTTPException:
        raise
    except (UnidentifiedImageError, OSError, ValueError) as exc:
        raise HTTPException(400, detail={"code": "invalid_image", "message": "이미지 파일을 읽을 수 없습니다."}) from exc


def _job_json(row) -> dict:
    return {
        "request_id": row["id"],
        "status": row["status"].lower(),
        "created_at": row["created_at"],
        "updated_at": row["updated_at"],
        "error_code": row["error_code"],
        "message": row["error_message"],
        "is_mock": bool(row["is_mock"]),
        "result_url": f"/v1/try-on/jobs/{row['id']}/result" if row["status"] == "SUCCEEDED" else None,
    }


def create_app(settings: Settings | None = None, provider: TryOnProvider | None = None) -> FastAPI:
    config = settings or Settings()
    database = Database(config.database_path)
    selected_provider = provider if provider is not None else create_provider(config)
    provider_ready = bool(selected_provider is not None and config.provider_enabled)
    config.storage_dir.mkdir(parents=True, exist_ok=True)

    async def cleanup_loop():
        while True:
            await asyncio.sleep(3600)
            paths = database.expire_old(config.retention_hours)
            _clean_paths(paths)
            database.prune_idempotency_tombstones()

    @asynccontextmanager
    async def lifespan(_: FastAPI):
        nonlocal provider_ready
        database.initialize()
        if config.provider_name == "vertex" and config.provider_enabled and selected_provider is not None:
            provider_ready = await asyncio.to_thread(GoogleVertexTryOnProvider.credentials_ready)
        cleanup_task = asyncio.create_task(cleanup_loop())
        try:
            yield
        finally:
            cleanup_task.cancel()
            with __import__("contextlib").suppress(asyncio.CancelledError):
                await cleanup_task

    app = FastAPI(title="Smart Closet Try-On API", version="1.0", lifespan=lifespan)
    app.state.settings = config
    app.state.database = database
    app.state.provider = selected_provider

    @app.middleware("http")
    async def require_production_https(request: Request, call_next):
        if config.app_env == "production" and request.url.scheme != "https":
            return JSONResponse(status_code=400, content={"detail": {"code": "https_required", "message": "HTTPS 연결이 필요합니다."}})
        return await call_next(request)

    async def owner(request: Request, authorization: str | None = Header(default=None)) -> str:
        if not authorization or not authorization.startswith("Bearer "):
            raise HTTPException(401, detail={"code": "session_required", "message": "세션 인증이 필요합니다."})
        token_hash = database.hash_token(authorization[7:])
        if not database.valid_session(token_hash):
            raise HTTPException(401, detail={"code": "session_invalid", "message": "세션이 만료되었거나 유효하지 않습니다."})
        return token_hash

    @app.get("/v1/health")
    async def health():
        return {"status": "ok"}

    @app.get("/v1/try-on/config")
    async def public_config():
        return {
            "enabled": provider_ready,
            "provider": config.public_provider_name if selected_provider is None or not getattr(selected_provider, "is_mock", False) else "연동 테스트 모드",
            "max_image_bytes": config.max_image_bytes,
            "retention_hours": config.retention_hours,
            "message": None if provider_ready else "AI 제공사 또는 서버 인증 정보가 설정되지 않았습니다. 서버 운영자에게 확인하세요.",
        }

    @app.post("/v1/sessions", status_code=201)
    async def create_session(request: Request):
        ip = request.client.host if request.client else "unknown"
        now = time.monotonic()
        async with _SESSION_RATE_LOCK:
            attempts = [t for t in _SESSION_CREATION_TIMES.get(ip, []) if now - t < 3600]
            if len(attempts) >= 10:
                raise HTTPException(429, detail={"code": "session_rate_limited", "message": "잠시 후 세션을 다시 시작해 주세요."})
            attempts.append(now)
            _SESSION_CREATION_TIMES[ip] = attempts
        token, token_hash = database.create_session(config.session_ttl_days)
        return {"access_token": token, "token_type": "bearer", "expires_in_seconds": config.session_ttl_days * 86400, "session_id": token_hash[:16]}

    async def run_job(job_id: str):
        row = None
        result_artifacts: list[Path] = []
        with database.connect() as db:
            row = db.execute("SELECT * FROM tryon_jobs WHERE id=?", (job_id,)).fetchone()
        if row is None or row["status"] != "QUEUED":
            return
        database.set_status(job_id, "PROCESSING")
        if selected_provider is None:
            database.set_status(job_id, "FAILED", error_code="provider_not_configured", error_message="AI 제공사 설정이 없습니다.")
            return
        try:
            person = Path(row["person_path"]).read_bytes()
            clothing = Path(row["clothing_path"]).read_bytes()
            image = await asyncio.to_thread(selected_provider.generate_one, person, clothing)
            normalized_result, _ = await asyncio.to_thread(_safe_image, image.content, image.mime_type, 20 * 1024 * 1024)
            directory = config.storage_dir / row["owner_hash"] / job_id
            directory.mkdir(parents=True, exist_ok=True)
            target = directory / "result.jpg"
            temp = target.with_suffix(target.suffix + ".tmp")
            result_artifacts = [temp, target]
            temp.write_bytes(normalized_result)
            temp.replace(target)
            current = database.get_job(row["owner_hash"], job_id)
            if current is None or current["status"] == "DELETED":
                _clean_paths([target])
                return
            database.set_status(job_id, "SUCCEEDED", result_path=target, result_mime="image/jpeg", is_mock=image.is_mock)
        except ProviderError as exc:
            _clean_paths(result_artifacts)
            current = database.get_job(row["owner_hash"], job_id)
            if current is not None and current["status"] != "DELETED":
                database.set_status(job_id, "UNKNOWN" if exc.outcome_unknown else "FAILED", error_code=exc.code, error_message=str(exc))
        except Exception:
            _clean_paths(result_artifacts)
            current = database.get_job(row["owner_hash"], job_id)
            if current is not None and current["status"] != "DELETED":
                database.set_status(job_id, "UNKNOWN", error_code="server_processing_unknown", error_message="처리 결과를 확인할 수 없습니다. 자동 재전송하지 않았습니다.")

    @app.post("/v1/try-on/jobs", status_code=202)
    async def create_job(
        background_tasks: BackgroundTasks,
        person_image: Annotated[UploadFile, File()],
        clothing_image: Annotated[UploadFile, File()],
        idempotency_key: Annotated[str, Header(alias="Idempotency-Key")],
        owner_hash: str = Depends(owner),
    ):
        try:
            UUID(idempotency_key)
        except ValueError as exc:
            raise HTTPException(400, detail={"code": "invalid_idempotency_key", "message": "유효한 UUID 요청 키가 필요합니다."}) from exc
        if selected_provider is None or not config.provider_enabled or not provider_ready:
            raise HTTPException(503, detail={"code": "provider_not_configured", "message": "실제 AI 제공사 설정이 없어 요청할 수 없습니다."})
        person_raw = await person_image.read(config.max_image_bytes + 1)
        clothing_raw = await clothing_image.read(config.max_image_bytes + 1)
        person, _ = _safe_image(person_raw, person_image.content_type or "", config.max_image_bytes)
        clothing, _ = _safe_image(clothing_raw, clothing_image.content_type or "", config.max_image_bytes)
        payload_hash = hashlib.sha256(person + b"\x00" + clothing).hexdigest()
        previous = database.job_by_idempotency(owner_hash, idempotency_key)
        if previous is not None:
            if previous["payload_hash"] != payload_hash:
                raise HTTPException(409, detail={"code": "idempotency_payload_conflict", "message": "같은 요청 키가 다른 사진에 이미 사용되었습니다."})
            return _job_json(previous)
        job_id = str(UUID(idempotency_key))
        directory = config.storage_dir / owner_hash / f"pending-{uuid4()}"
        directory.mkdir(parents=True, exist_ok=True)
        person_path = directory / "person.jpg"
        clothing_path = directory / "clothing.jpg"
        try:
            person_path.write_bytes(person)
            clothing_path.write_bytes(clothing)
            row, created = database.create_job(owner_hash, idempotency_key, payload_hash, person_path, clothing_path, config.daily_limit)
        except DailyLimitExceeded as exc:
            _clean_paths([person_path, clothing_path])
            raise HTTPException(429, headers={"Retry-After": "3600"}, detail={"code": "daily_limit_reached", "message": "오늘 생성 요청 횟수를 모두 사용했습니다."}) from exc
        except IdempotencyConflict as exc:
            _clean_paths([person_path, clothing_path])
            raise HTTPException(409, detail={"code": "idempotency_payload_conflict", "message": "같은 요청 키에 다른 사진을 사용할 수 없습니다."}) from exc
        except sqlite3.IntegrityError as exc:
            _clean_paths([person_path, clothing_path])
            existing = database.job_by_idempotency(owner_hash, idempotency_key)
            if existing is not None and existing["payload_hash"] == payload_hash:
                return _job_json(existing)
            if existing is not None:
                raise HTTPException(409, detail={"code": "idempotency_payload_conflict", "message": "같은 요청 키에 다른 사진을 사용할 수 없습니다."}) from exc
            raise
        except Exception:
            _clean_paths([person_path, clothing_path])
            raise
        if created:
            background_tasks.add_task(run_job, row["id"])
        else:
            _clean_paths([person_path, clothing_path])
        return _job_json(row)

    @app.get("/v1/try-on/jobs/by-key/{idempotency_key}")
    async def get_by_key(idempotency_key: str, owner_hash: str = Depends(owner)):
        row = database.job_by_idempotency(owner_hash, idempotency_key)
        if row is None:
            raise HTTPException(404, detail={"code": "job_not_found", "message": "해당 요청 키의 작업이 없습니다."})
        return _job_json(row)

    @app.get("/v1/try-on/jobs")
    async def list_jobs(owner_hash: str = Depends(owner)):
        return {"jobs": [_job_json(row) for row in database.list_jobs(owner_hash)]}

    @app.get("/v1/try-on/jobs/{job_id}")
    async def get_job(job_id: str, owner_hash: str = Depends(owner)):
        row = database.get_job(owner_hash, job_id)
        if row is None:
            raise HTTPException(404, detail={"code": "job_not_found", "message": "작업을 찾을 수 없습니다."})
        return _job_json(row)

    @app.get("/v1/try-on/jobs/{job_id}/result")
    async def get_result(job_id: str, owner_hash: str = Depends(owner)):
        row = database.get_job(owner_hash, job_id)
        if row is None or row["status"] != "SUCCEEDED" or not row["result_path"]:
            raise HTTPException(404, detail={"code": "result_unavailable", "message": "결과 이미지를 사용할 수 없습니다."})
        path = Path(row["result_path"])
        if not path.is_file():
            raise HTTPException(404, detail={"code": "result_expired", "message": "결과가 삭제되었거나 보관 기한이 끝났습니다."})
        return FileResponse(path, media_type=row["result_mime"] or "image/jpeg", filename=f"try-on-{job_id}.jpg", headers={"Cache-Control": "private, no-store", "X-Content-Type-Options": "nosniff"})

    @app.delete("/v1/try-on/jobs/{job_id}", status_code=204)
    async def delete_job(job_id: str, owner_hash: str = Depends(owner)):
        row = database.get_job(owner_hash, job_id)
        if row is None:
            raise HTTPException(404, detail={"code": "job_not_found", "message": "작업을 찾을 수 없습니다."})
        _clean_paths(database.mark_deleted(job_id))

    @app.post("/internal/cleanup", include_in_schema=False)
    async def cleanup_internal(request: Request):
        if config.app_env != "development" or request.client is None or request.client.host not in {"127.0.0.1", "::1", "testclient"}:
            raise HTTPException(404)
        _clean_paths(database.expire_old(config.retention_hours))
        database.prune_idempotency_tombstones()
        return {"status": "cleaned"}

    return app


app = create_app()
