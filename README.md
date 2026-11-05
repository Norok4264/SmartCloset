# 스마트 옷장 관리 앱

Kotlin과 Jetpack Compose로 만드는 Android 네이티브 앱입니다. 현재 4단계 날씨 기반 코디 추천, 랜덤 조합, 저장 코디와 착용 기록 연결까지 구현되어 있습니다. AI 착용 이미지는 아직 구현하지 않았습니다.

## 실행 및 빌드

1. Android Studio의 최신 안정 버전에서 이 저장소를 엽니다.
2. Android SDK Platform 36과 JDK 17을 설치합니다.
3. Gradle 동기화 후 `:android:app:assembleDebug`를 실행합니다.

Gradle Wrapper가 포함되어 있습니다. Windows에서는 프로젝트 루트에서 `.\gradlew.bat :android:app:assembleDebug`를 실행하세요. Wrapper가 Gradle 8.13을 자동으로 사용합니다.

기본 패키지: `com.smartcloset`. 포함 기능은 Room 기반 의류 CRUD, 검색·필터, 의류/코디 즐겨찾기, 옷장 통계, 착용 기록과 분석, 날씨 및 수동 계절 기반 코디, 저장 코디입니다. Room v1→v2→v3 마이그레이션은 기존 의류·착용 기록을 유지하고 Outfit 테이블을 추가합니다. 저장 코디는 실제 의류 ID를 참조하며, 의류 삭제 시 관련 기록과 저장 코디도 정리됩니다.

날씨 지역은 홈 화면에서 직접 선택합니다. 위치 권한을 요구하지 않으며, 비상업용 Open-Meteo API를 사용합니다. 네트워크가 없으면 실제 값을 꾸며 보여주지 않고 마지막 갱신 시각이 있는 캐시, 실패 상태 또는 수동 입력 모드를 표시합니다. API 조건과 출처는 [PROJECT_SPEC](docs/PROJECT_SPEC.md)에 기록되어 있습니다.

설정에서 선택할 수 있는 데일리 코디 알림은 WorkManager를 사용해 지연될 수 있으며 정확한 정시 알림을 보장하지 않습니다. Android 13 이상에서 알림 권한이 거부되어도 옷장 기능은 사용할 수 있습니다.

Android Studio에서 Gradle 8.13, JDK 17, SDK 36 설정으로 Debug APK 빌드를 확인했습니다. WorkManager 2.12.0은 Android 공식 릴리스 문서에서 안정 버전을 확인했습니다.

단위 테스트는 `.\gradlew.bat :android:app:testDebugUnitTest`, Room 마이그레이션·기록 Android 테스트는 에뮬레이터에서 `.\gradlew.bat :android:app:connectedDebugAndroidTest`로 실행합니다. 단위 테스트와 Android 계측 테스트는 아직 실행하지 않았습니다.


## Android Studio 빌드 문제 해결 기록

2026-11-05에 Windows 환경에서 Android Studio 실행 및 Debug APK 빌드 중 확인한 문제와 해결 사항입니다.

- **Gradle 9.6.0과 Android Gradle Plugin 8.13.2 비호환:** AGP가 Gradle 9.6에서 제거된 내부 API를 사용해 플러그인 적용에 실패했습니다. Gradle Wrapper를 생성해 Gradle 8.13을 고정했습니다. 이후에는 프로젝트 루트의 `gradlew.bat`을 사용합니다.
- **Gradle/JDK 실행 환경:** 시스템에 `gradle` 명령이 없었으므로 Gradle 8.13 배포 파일로 Wrapper를 생성했습니다. Gradle JVM은 JDK 17로 설정합니다. Gradle 8.13은 JDK 25 실행을 지원하지 않습니다.
- **한글 프로젝트 경로:** Android Gradle Plugin의 Windows 경로 검사에서 한글 경로가 거부되어 `gradle.properties`에 `android.overridePathCheck=true`를 추가했습니다. 이 설정은 실험적 옵션 경고를 표시합니다.
- **Java/Kotlin JVM 대상 불일치:** Java가 1.8, Kotlin이 17로 설정되어 컴파일이 실패했습니다. `android/app/build.gradle.kts`의 `compileOptions`를 Java 17로 맞췄습니다.
- **Kotlin 컴파일 오류:** 날짜 표시에서 `ZonedDateTime`을 `LocalDateTime`으로 변환하고, 한국어 단위가 붙는 문자열 템플릿을 `${변수}` 형식으로 수정했습니다. `ClothingRepository.importPhoto()`의 반환값을 명시하고, Material3 실험 API 사용에 `@OptIn`을 추가했습니다. 이 과정에서 중복된 `@Composable` 어노테이션도 제거했습니다.

**검증 결과:** `gradlew.bat :android:app:assembleDebug`가 `BUILD SUCCESSFUL`로 완료되어 Debug APK를 생성했습니다. `android.overridePathCheck` 실험 옵션 및 일부 API 관련 경고는 남아 있지만 빌드를 막지 않습니다. 단위 테스트와 에뮬레이터 계측 테스트는 별도로 실행하지 않았습니다.

## 구조

- `android/app`: Android 앱
- `backend`: 향후 AI 연동 서버 설계 영역
- `docs`: 요구사항, 진행 상황, 테스트 계획

개발 단계와 데이터 정책은 [PROJECT_SPEC](docs/PROJECT_SPEC.md), [PROGRESS](docs/PROGRESS.md), [TEST_PLAN](docs/TEST_PLAN.md)을 참고하세요.

## 5단계 AI 전신 착용 이미지

앱은 사용자가 선택한 전신 사진과 Room에 등록된 상의 사진을 우리 서버로 전송하고, 서버가 Google Cloud Virtual Try-On API를 호출하는 구조입니다. AI 설정이 없거나 서버 URL이 없는 경우 옷장·통계·착용 기록·코디 기능은 계속 사용할 수 있으며 AI 요청은 비활성화됩니다. 제공사와 보관 정책, 수명 제한은 [AI_PROVIDER.md](docs/AI_PROVIDER.md)에 기록했습니다.

### Android 실행 및 APK 빌드

JDK 17, Android SDK Platform 36, Android Studio의 호환 Gradle 환경이 필요합니다. Gradle Wrapper를 사용합니다. Windows에서는 `gradlew.bat`을 실행하세요.

```powershell
.\gradlew.bat :android:app:assembleDebug
.\gradlew.bat :android:app:testDebugUnitTest
```

기본 URL은 비어 있어 AI 서버와 연결되지 않습니다. Android 에뮬레이터에서 개발 서버에 연결하는 Debug 빌드 예:

```powershell
.\gradlew.bat :android:app:assembleDebug -PtryonApiBaseUrl=http://10.0.2.2:8000
```

실기기에서는 개발 PC의 접근 가능한 로컬 주소를 사용하되 Debug에 한해 HTTP가 허용됩니다. Release 앱은 HTTPS 주소를 사용해야 합니다. 인증 키나 제공사 키는 Gradle 속성에 넣지 않습니다.

### FastAPI 서버 실행

Python 3.11 이상 환경에서:

```powershell
cd backend
py -m venv .venv
.venv\Scripts\Activate.ps1
py -m pip install -r requirements.txt
Copy-Item .env.example .env
py -m uvicorn app.main:app --host 127.0.0.1 --port 8000
```

기본 `.env.example`은 `TRYON_PROVIDER=disabled`입니다. Mock 테스트는 `APP_ENV=development`, `TRYON_PROVIDER=mock`으로 켜며 실제 결과가 아닌 테스트 안내 이미지를 반환합니다. Google 연동은 배포용 서버에서 프로젝트 설정과 Application Default Credentials 또는 워크로드 아이덴티티를 구성해야 합니다. 실서비스는 HTTPS 종료 프록시 뒤에 두고 서버를 직접 인터넷에 노출하지 마세요. 앱은 백엔드 HTTPS URL을 가진 Release 빌드에서 사용합니다.

서버 비밀은 `.env` 또는 비밀 관리 시스템으로 제공하고 `.env`는 저장소에 넣지 않습니다. 서버 이미지/SQLite 파일은 `TRYON_STORAGE_DIR`, `TRYON_DATABASE`로 지정하며 이 경로의 접근 통제·백업·삭제를 운영자가 관리해야 합니다. 기본 이미지 보관 기간은 24시간, 세션별 생성 한도는 하루 3회입니다. Google API 호출 전 사용자 동의를 받고, 현재 API 가격은 공식 가격 문서를 다시 확인하세요.

### 현재 검증 상태

2026-11-05 현재 Android Debug APK 빌드는 `BUILD SUCCESSFUL`로 확인했습니다. 단위 테스트, 에뮬레이터 계측 테스트, FastAPI 자동화 테스트는 실행하지 않았습니다. 실기기·에뮬레이터 기능 검증, GCP 인증 및 실제 생성 호출, 실제 이미지 품질·비용도 검증되지 않았습니다. 문서의 테스트 코드는 실행 결과로 간주하지 않습니다.


