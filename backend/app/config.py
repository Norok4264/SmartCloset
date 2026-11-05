from dataclasses import dataclass
import os
from pathlib import Path


@dataclass(frozen=True)
class Settings:
    app_env: str = os.getenv("APP_ENV", "development")
    provider_name: str = os.getenv("TRYON_PROVIDER", "disabled").lower()
    google_project: str = os.getenv("GOOGLE_CLOUD_PROJECT", "")
    google_location: str = os.getenv("GOOGLE_CLOUD_LOCATION", "asia-northeast1")
    google_model: str = os.getenv("TRYON_MODEL", "virtual-try-on-001")
    storage_dir: Path = Path(os.getenv("TRYON_STORAGE_DIR", "./var/tryon"))
    database_path: Path = Path(os.getenv("TRYON_DATABASE", "./var/tryon.sqlite3"))
    max_image_bytes: int = int(os.getenv("TRYON_MAX_IMAGE_BYTES", str(7 * 1024 * 1024)))
    daily_limit: int = int(os.getenv("TRYON_DAILY_LIMIT", "3"))
    retention_hours: int = int(os.getenv("TRYON_RETENTION_HOURS", "24"))
    provider_timeout_seconds: int = int(os.getenv("TRYON_PROVIDER_TIMEOUT_SECONDS", "180"))
    session_ttl_days: int = int(os.getenv("TRYON_SESSION_TTL_DAYS", "30"))

    @property
    def debug_mock_allowed(self) -> bool:
        return self.app_env == "development"

    @property
    def provider_enabled(self) -> bool:
        if self.provider_name == "mock":
            return self.debug_mock_allowed
        return self.provider_name == "vertex" and bool(self.google_project and self.google_location and self.google_model)

    @property
    def public_provider_name(self) -> str:
        if self.provider_name == "mock" and self.debug_mock_allowed:
            return "연동 테스트 모드"
        if self.provider_name == "vertex":
            return "Google Cloud Virtual Try-On"
        return "설정되지 않음"
