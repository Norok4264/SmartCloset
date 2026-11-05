# AI 전신 착용 이미지 제공사 및 운영 정책

기준일: 2026-11-05

## 제공사 조사와 선택

Google Cloud Vertex AI의 공식 Virtual Try-On API는 사람 이미지(`personImage`)와 상품 이미지(`productImages`)를 함께 입력받아 해당 상품을 입은 사람의 이미지를 생성하는 API를 문서화합니다. 상의 사진 하나를 상품 입력으로 전달하고 `sampleCount=1`을 설정하도록 서버 어댑터를 작성했습니다. 호출은 동기식 `:predict`이며, 공식 예시는 생성된 이미지 바이트를 응답에 반환합니다. 이 앱은 사용자 사진 업로드나 GCS 저장 URI를 사용하지 않고 결과 바이트를 받아 우리 서버에서 보관합니다.

선택 이유는 웹 UI에만 존재하는 기능을 API로 추정하지 않고, 별도의 공식 API 계약에서 사람·상품 이미지 입력과 결과 수량을 확인할 수 있기 때문입니다. 다만 `virtual-try-on-001`은 2026-09-14 폐기 상태로 전환되었고 2027-03-15 지원 종료 예정입니다. 따라서 이는 현재 문서화된 API의 어댑터 구현이며 장기 배포에 적합한 보장으로 보지 않습니다. 출시 전에 Google의 대체 모델 또는 다른 업체의 공식 API 문서를 다시 검토해야 합니다. 앱은 Google Cloud 인증이 확인되기 전에는 생성 사용 가능 상태를 표시하지 않습니다.

확인된 기술 조건:

- 공식 호출은 서버에서 `POST https://{LOCATION}-aiplatform.googleapis.com/v1/projects/{PROJECT}/locations/{LOCATION}/publishers/google/models/virtual-try-on-001:predict`이며 Google Cloud OAuth/ADC 인증을 사용합니다. 앱에서 직접 호출하지 않습니다.
- GCP 프로젝트에서 Vertex AI API와 결제 설정, 해당 위치와 모델 접근 권한이 필요합니다. 기본 예시 위치는 `asia-northeast1`입니다.
- 사람 이미지 1장과 상품 이미지 1장을 전송합니다. 허용 입력 형식은 JPEG/PNG이고 각 이미지 최대 용량은 7 MiB입니다. 구현은 EXIF 방향을 반영하고 이미지 메타데이터를 제거한 JPEG를 제공합니다.
- 결과는 입력 인물 사진의 프레이밍/해상도 특성을 따르는 형식으로 요청됩니다. API가 사람의 배경·얼굴·신체 특징을 완전히 보존한다고 보장하지 않으므로 화면은 결과를 참고 이미지로 한정합니다. 앱은 원본 사용자 사진을 최대 긴 변 2048 픽셀 JPEG로 축소해 전송합니다.
- API는 동기식 예측입니다. API에는 작업 상태 조회·취소나 멱등성 키 계약이 문서화되어 있지 않습니다. 우리 서버의 상태 API는 아래에 별도로 정의합니다.
- 결과 장수는 공식 `sampleCount` 파라미터로 제어하며 1~4 범위 중 1을 지정합니다.
- 공식 가격표의 Virtual Try-On 가격은 출력 이미지 1장당 미화 $0.06입니다. 실제 청구는 프로젝트·지역·세금·가격표 변경에 따를 수 있으므로 배포 전에 확인해야 합니다. HTTP 오류 응답은 가격표의 정상 출력 이미지와 다르게 청구되지 않는다고 안내되어 있지만, 네트워크 타임아웃으로 결과를 받지 못한 경우 제공사 처리 여부는 서버가 단정할 수 없습니다.
- 요청량은 GCP 프로젝트/리전별 Vertex AI quota와 계정 상태에 따릅니다. 문서에 확인되지 않은 무료 할당량이나 고정 요청 한도는 가정하지 않으며 배포 전 프로젝트 Quotas 화면에서 사용 가능량을 확인해야 합니다.
- Google Cloud는 고객 데이터를 기반 모델 학습에 사용하지 않는다고 설명합니다. 안전성 분류기가 악용 의심 요청으로 표시하면 안전성 모니터링을 위해 프롬프트/응답이 해당 지역에서 최대 90일 보관될 수 있습니다. 이 보관은 앱 또는 우리 서버의 삭제 API로 제거할 수 있다고 확인되지 않았습니다. 따라서 “외부에 저장되지 않는다”고 약속하지 않습니다.

공식 출처:

- [Virtual Try-On API 요청·응답 가이드](https://docs.cloud.google.com/gemini-enterprise-agent-platform/models/capabilities/generate-virtual-try-on-images)
- [모델 수명 주기와 이미지 사양](https://docs.cloud.google.com/gemini-enterprise-agent-platform/models/vto/virtual-try-on-001)
- [Vertex AI 생성형 AI 가격](https://cloud.google.com/gemini-enterprise-agent-platform/generative-ai/pricing)
- [악용 모니터링과 프롬프트 로깅](https://docs.cloud.google.com/gemini-enterprise-agent-platform/models/abuse-monitoring)
- [Vertex AI 데이터 보관 및 Zero Data Retention 조건](https://docs.cloud.google.com/gemini-enterprise-agent-platform/resources/zero-data-retention)
- [Google Cloud 애플리케이션 기본 인증](https://docs.cloud.google.com/docs/authentication)

## 앱 자체 서버 계약

아래 경로는 우리 FastAPI 서버 계약이며 Google 제공사 엔드포인트가 아닙니다.

| 메서드 및 경로 | 목적 |
| --- | --- |
| `GET /v1/try-on/config` | 실제 어댑터·서버 인증 상태, 제공사, 이미지 용량 제한 안내 |
| `POST /v1/sessions` | 설치별 무작위 256-bit 세션 발급. 토큰 원문은 서버에 저장하지 않고 해시만 저장 |
| `POST /v1/try-on/jobs` | Bearer 세션, `Idempotency-Key` UUID와 `person_image`, `clothing_image` multipart 입력으로 작업 접수 |
| `GET /v1/try-on/jobs` | 현재 세션 소유 작업 목록 |
| `GET /v1/try-on/jobs/by-key/{key}` | 요청 결과가 불명확한 경우 같은 키로 접수 여부 조회 |
| `GET /v1/try-on/jobs/{id}` | 작업 상태 조회 |
| `GET /v1/try-on/jobs/{id}/result` | 소유 세션의 결과 이미지 조회 |
| `DELETE /v1/try-on/jobs/{id}` | 소유 세션의 입력·결과 파일 삭제 요청. 제공사 작업 취소를 의미하지 않음 |

상태는 `queued`, `processing`, `succeeded`, `failed`, `unknown`, `expired`입니다. 서버 재시작 중 `queued`/`processing`이었던 작업은 `unknown`으로 바꾸며 제공사에 자동 재전송하지 않습니다. 같은 사용자·요청 키는 DB unique 제약으로 한 번만 접수되고, 같은 키에 다른 이미지면 409입니다. 일일 작업 한도는 DB 트랜잭션에서 검사합니다. 처리 응답이 타임아웃되면 같은 키를 조회하고, 접수 전인지 확인되지 않은 상태에서는 자동으로 새 키를 만들어 재전송하지 않습니다.

`unknown`은 제공사의 결제 결과를 알 수 없다는 의미입니다. 화면 닫기·상태 조회 중단·우리 서버 삭제 요청은 이미 시작된 외부 처리를 취소하지 않습니다. 삭제는 우리 서버의 입력/결과 복사본만 즉시 정리하고, 외부 서비스에서 이미 처리된 자료는 제공사 정책을 따릅니다.

익명 설치 세션은 사용자 계정이 아닙니다. 배포 서비스는 HTTPS TLS 종료 프록시 뒤에서만 공개하고, 프록시의 신뢰된 전달 헤더 설정 및 신규 세션 발급 제한을 적용해야 합니다. 요청별 세션 소유 확인과 세션별 일일 한도는 앱 서버가 적용합니다. 기본 FastAPI 서버를 인증·TLS 프록시 없이 인터넷에 공개하지 마세요. 세션 토큰은 앱의 Android Keystore AES-GCM 키로 암호화해 저장합니다.

## 사진·동의·삭제

사용자가 선택한 전신 사진은 옷장 의류 사진과 분리된 `filesDir/tryon` 내부 파일입니다. 이미지 메타데이터를 제거하고 크기를 조정하며, 선택·변경·삭제할 수 있습니다. 사용자는 외부 전송 전에 사진, 생성 목적, 실제 Google 제공사, 확인된 서버/제공사 보관 정책, 결과 한계를 읽고 명시적으로 동의해야 합니다. 본인 또는 사용 허락을 받은 사진만 선택하도록 안내합니다.

서버는 작업 입력과 결과 파일을 기본 24시간 보관하고 시간별 정리 작업을 실행합니다. DB에는 작업 상태와 소유 세션 해시를 저장하며 사진 내용/키를 로그에 남기지 않습니다. 제공사 측 안전성 모니터링 보관은 별도이며 우리 삭제 기능으로 제거할 수 없습니다. 결과는 앱 전용 저장소에 유지하고, 외부 파일로 내보내기는 사용자가 시스템 파일 선택기를 통해 선택했을 때에만 복사합니다.

## 개발·배포 설정

- 기본값 `TRYON_PROVIDER=disabled`는 실제 요청을 거부합니다.
- `TRYON_PROVIDER=mock`은 `APP_ENV=development`에서만 동작하며 결과는 테스트 안내 카드입니다. 실제 착용 합성 결과로 표시하지 않습니다.
- 실제 연동은 `APP_ENV=production`, `TRYON_PROVIDER=vertex`, GCP 프로젝트·위치 설정 및 서버 ADC/워크로드 아이덴티티가 필요합니다. 비밀은 환경변수 또는 서버 비밀 저장소/워크로드 아이덴티티로 제공하며 앱에 넣지 않습니다.
- 앱 URL은 Gradle 속성 `tryonApiBaseUrl`로 주입합니다. Debug에서만 Android 에뮬레이터 `http://10.0.2.2:8000`을 허용합니다. Release URL은 HTTPS만 허용합니다.
- GCP 모델의 폐기 일정과 가격이 바뀔 수 있으므로 실제 배포 전 공식 문서를 다시 확인해야 합니다.
