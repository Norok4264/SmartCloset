from io import BytesIO
from pathlib import Path
from uuid import uuid4

import pytest
from fastapi.testclient import TestClient
from PIL import Image

from app.config import Settings
from app.main import create_app
from app.provider import DevelopmentMockTryOnProvider, ProviderError, ProviderImage, TryOnProvider, create_provider


class CountingProvider(TryOnProvider):
    def __init__(self):
        self.calls = 0

    def generate_one(self, person_jpeg: bytes, clothing_jpeg: bytes) -> ProviderImage:
        self.calls += 1
        image = Image.new("RGB", (96, 160), "#789")
        stream = BytesIO()
        image.save(stream, "JPEG")
        return ProviderImage(stream.getvalue(), "image/jpeg")


class UnknownOutcomeProvider(TryOnProvider):
    def __init__(self):
        self.calls = 0

    def generate_one(self, person_jpeg: bytes, clothing_jpeg: bytes) -> ProviderImage:
        self.calls += 1
        raise ProviderError("timeout_unknown", "provider outcome unknown", outcome_unknown=True)


def jpeg(color: str = "red") -> bytes:
    image = Image.new("RGB", (96, 160), color)
    stream = BytesIO()
    image.save(stream, "JPEG")
    return stream.getvalue()


@pytest.fixture
def client(tmp_path: Path):
    settings = Settings(
        app_env="development", provider_name="mock", storage_dir=tmp_path / "images",
        database_path=tmp_path / "db.sqlite", daily_limit=2, retention_hours=24,
    )
    provider = CountingProvider()
    app = create_app(settings, provider)
    with TestClient(app) as test_client:
        yield test_client, provider, settings


def new_session(client: TestClient) -> str:
    response = client.post("/v1/sessions")
    assert response.status_code == 201
    return response.json()["access_token"]


def submit(client: TestClient, token: str, key: str, person: bytes | None = None, garment: bytes | None = None):
    return client.post(
        "/v1/try-on/jobs", headers={"Authorization": f"Bearer {token}", "Idempotency-Key": key},
        files={
            "person_image": ("person.jpg", person if person is not None else jpeg(), "image/jpeg"),
            "clothing_image": ("top.jpg", garment if garment is not None else jpeg("blue"), "image/jpeg"),
        },
    )


def test_disabled_configuration_and_authentication(tmp_path: Path):
    settings = Settings(app_env="development", provider_name="disabled", storage_dir=tmp_path / "images", database_path=tmp_path / "db.sqlite")
    with TestClient(create_app(settings)) as client:
        assert client.get("/v1/try-on/config").json()["enabled"] is False
        assert client.get("/v1/try-on/jobs").status_code == 401


def test_mock_is_development_only_and_explicitly_labeled(tmp_path: Path):
    development = Settings(app_env="development", provider_name="mock", storage_dir=tmp_path / "dev", database_path=tmp_path / "dev.sqlite")
    mock = create_provider(development)
    assert isinstance(mock, DevelopmentMockTryOnProvider)
    assert mock.generate_one(jpeg(), jpeg("blue")).is_mock is True
    production = Settings(app_env="production", provider_name="mock", storage_dir=tmp_path / "prod", database_path=tmp_path / "prod.sqlite")
    assert create_provider(production) is None


def test_idempotency_result_and_user_isolation(client):
    api, provider, _ = client
    token_a, token_b = new_session(api), new_session(api)
    key = str(uuid4())
    response = submit(api, token_a, key)
    assert response.status_code == 202
    job_id = response.json()["request_id"]
    assert api.get(f"/v1/try-on/jobs/{job_id}", headers={"Authorization": f"Bearer {token_a}"}).json()["status"] == "succeeded"
    assert response.json()["is_mock"] is False
    assert provider.calls == 1
    repeated = submit(api, token_a, key)
    assert repeated.status_code == 200
    assert repeated.json()["request_id"] == response.json()["request_id"]
    assert provider.calls == 1
    assert api.get(f"/v1/try-on/jobs/{job_id}", headers={"Authorization": f"Bearer {token_b}"}).status_code == 404
    assert api.get(f"/v1/try-on/jobs/{job_id}/result", headers={"Authorization": f"Bearer {token_b}"}).status_code == 404
    result = api.get(f"/v1/try-on/jobs/{job_id}/result", headers={"Authorization": f"Bearer {token_a}"})
    assert result.status_code == 200 and result.content.startswith(b"\xff\xd8")
    owner_hash = api.app.state.database.hash_token(token_a)
    stored = api.app.state.database.get_job(owner_hash, job_id)
    local_paths = [Path(stored[key]) for key in ("person_path", "clothing_path", "result_path")]
    assert all(path.is_file() for path in local_paths)
    assert api.delete(f"/v1/try-on/jobs/{job_id}", headers={"Authorization": f"Bearer {token_a}"}).status_code == 204
    assert api.get(f"/v1/try-on/jobs/{job_id}/result", headers={"Authorization": f"Bearer {token_a}"}).status_code == 404
    assert all(not path.exists() for path in local_paths)


def test_same_key_with_different_photos_conflicts(client):
    api, _, _ = client
    token = new_session(api)
    key = str(uuid4())
    assert submit(api, token, key).status_code == 202
    assert submit(api, token, key, person=jpeg("green")).status_code == 409


def test_unknown_provider_outcome_is_never_auto_replayed(tmp_path: Path):
    settings = Settings(app_env="development", provider_name="mock", storage_dir=tmp_path / "images", database_path=tmp_path / "db.sqlite")
    provider = UnknownOutcomeProvider()
    with TestClient(create_app(settings, provider)) as api:
        token, key = new_session(api), str(uuid4())
        response = submit(api, token, key)
        job_id = response.json()["request_id"]
        headers = {"Authorization": f"Bearer {token}"}
        assert api.get(f"/v1/try-on/jobs/{job_id}", headers=headers).json()["status"] == "unknown"
        assert submit(api, token, key).json()["request_id"] == job_id
        assert provider.calls == 1


def test_invalid_images_and_daily_limit(client):
    api, _, _ = client
    token = new_session(api)
    assert submit(api, token, str(uuid4()), person=b"not an image").status_code == 400
    assert submit(api, token, str(uuid4()), person=b"x" * (7 * 1024 * 1024 + 1)).status_code == 413
    assert submit(api, token, str(uuid4())).status_code == 202
    assert submit(api, token, str(uuid4())).status_code == 202
    assert submit(api, token, str(uuid4())).status_code == 429


def test_startup_marks_in_flight_as_unknown_without_replay(tmp_path: Path):
    settings = Settings(app_env="development", provider_name="mock", storage_dir=tmp_path / "images", database_path=tmp_path / "db.sqlite")
    app = create_app(settings)
    database = app.state.database
    database.initialize()
    _, owner = database.create_session(30)
    key = str(uuid4())
    row, _ = database.create_job(owner, key, "payload", tmp_path / "p", tmp_path / "c", daily_limit=3)
    database.set_status(row["id"], "PROCESSING")
    database.initialize()
    assert database.get_job(owner, row["id"])["status"] == "UNKNOWN"
