from __future__ import annotations

import base64
from dataclasses import dataclass
from io import BytesIO

import requests
from PIL import Image, ImageDraw

from .config import Settings


class ProviderError(Exception):
    def __init__(self, code: str, message: str, *, outcome_unknown: bool = False):
        super().__init__(message)
        self.code = code
        self.outcome_unknown = outcome_unknown


@dataclass(frozen=True)
class ProviderImage:
    content: bytes
    mime_type: str
    is_mock: bool = False


class TryOnProvider:
    name = "provider"

    def generate_one(self, person_jpeg: bytes, clothing_jpeg: bytes) -> ProviderImage:
        raise NotImplementedError


class GoogleVertexTryOnProvider(TryOnProvider):
    """Adapter for Google's documented Virtual Try-On predict API."""

    name = "Google Cloud Virtual Try-On"

    def __init__(self, settings: Settings):
        self.settings = settings

    @staticmethod
    def credentials_ready() -> bool:
        try:
            import google.auth
            from google.auth.transport.requests import Request
            credentials, _ = google.auth.default(scopes=["https://www.googleapis.com/auth/cloud-platform"])
            credentials.refresh(Request())
            return bool(credentials.token)
        except Exception:
            return False

    def generate_one(self, person_jpeg: bytes, clothing_jpeg: bytes) -> ProviderImage:
        try:
            import google.auth
            from google.auth.transport.requests import Request

            credentials, _ = google.auth.default(scopes=["https://www.googleapis.com/auth/cloud-platform"])
            credentials.refresh(Request())
        except Exception as exc:
            raise ProviderError("provider_auth_unavailable", "Google Cloud 서버 인증을 사용할 수 없습니다.") from exc

        url = (
            f"https://{self.settings.google_location}-aiplatform.googleapis.com/v1/"
            f"projects/{self.settings.google_project}/locations/{self.settings.google_location}/"
            f"publishers/google/models/{self.settings.google_model}:predict"
        )
        payload = {
            "instances": [{
                "personImage": {"image": {"bytesBase64Encoded": base64.b64encode(person_jpeg).decode("ascii")}},
                "productImages": [{"image": {"bytesBase64Encoded": base64.b64encode(clothing_jpeg).decode("ascii")}}],
            }],
            "parameters": {"sampleCount": 1},
        }
        try:
            # No automatic retry: the provider API has no documented idempotency key.
            response = requests.post(
                url,
                json=payload,
                headers={"Authorization": f"Bearer {credentials.token}"},
                timeout=(10, self.settings.provider_timeout_seconds),
            )
        except requests.Timeout as exc:
            raise ProviderError("provider_timeout_unknown", "제공사 응답 시간이 초과되어 과금/완료 여부를 확인할 수 없습니다.", outcome_unknown=True) from exc
        except requests.RequestException as exc:
            raise ProviderError("provider_network_unknown", "제공사 연결이 끊겨 요청 완료 여부를 확인할 수 없습니다.", outcome_unknown=True) from exc

        if not response.ok:
            unknown = response.status_code >= 500
            raise ProviderError(
                "provider_server_error" if unknown else "provider_rejected",
                f"제공사가 요청을 처리하지 못했습니다 (HTTP {response.status_code}).",
                outcome_unknown=unknown,
            )
        try:
            predictions = response.json().get("predictions", [])
            prediction = predictions[0]
            encoded = prediction.get("bytesBase64Encoded")
            if not encoded:
                reason = prediction.get("raiFilteredReason") or "결과 이미지가 반환되지 않았습니다."
                raise ValueError(reason)
            image = base64.b64decode(encoded, validate=True)
            mime_type = prediction.get("mimeType", "image/png")
            if mime_type not in {"image/png", "image/jpeg"}:
                raise ValueError("지원하지 않는 결과 이미지 형식입니다.")
            return ProviderImage(image, mime_type)
        except Exception as exc:
            raise ProviderError("invalid_provider_response", "제공사 응답에서 결과 이미지를 읽지 못했습니다.", outcome_unknown=True) from exc


class DevelopmentMockTryOnProvider(TryOnProvider):
    name = "연동 테스트 모드"

    def generate_one(self, person_jpeg: bytes, clothing_jpeg: bytes) -> ProviderImage:
        # This is deliberately a labeled test card, not a simulated try-on result.
        canvas = Image.new("RGB", (900, 1200), "#edf1ec")
        draw = ImageDraw.Draw(canvas)
        draw.text((90, 450), "INTEGRATION TEST ONLY", fill="#304638", stroke_width=0)
        draw.text((90, 515), "NOT REAL AI OUTPUT", fill="#304638", stroke_width=0)
        output = BytesIO()
        canvas.save(output, format="JPEG", quality=85)
        return ProviderImage(output.getvalue(), "image/jpeg", is_mock=True)


def create_provider(settings: Settings) -> TryOnProvider | None:
    if not settings.provider_enabled:
        return None
    if settings.provider_name == "mock" and settings.debug_mock_allowed:
        return DevelopmentMockTryOnProvider()
    if settings.provider_name == "vertex":
        return GoogleVertexTryOnProvider(settings)
    return None
