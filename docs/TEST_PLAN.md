# 테스트 계획 및 결과

## 기능별 계획

| 영역 | 검증 항목 | 3단계 결과 |
| --- | --- | --- |
| 빌드 | Android Studio Gradle sync, `assembleDebug` | 시도했으나 실패 전 환경 차단: `gradle` 명령이 없음; SDK/Java 경로 미확인 |
| 앱 실행 | 에뮬레이터와 실기기 실행을 별도 기록 | 미실행 |
| 탭 이동 | 5개 탭 이동, 선택 탭 표시, 재선택/뒤로가기 | 코드 변경 후 런타임 회귀 검증 미실행 |
| 적응형 UI | 작은 화면, 글자 확대, 긴 한국어 문구 | 목록/폼 스크롤 적용, 기기 검증 미실행 |
| 의류 | CRUD, 검색/필터, 사진 선택/촬영, 즐겨찾기 | 구현 완료, 기기 시나리오 미실행 |
| 사진 | 취소, 읽기 불가, 큰 이미지, 회전, 재실행 유지, 수정, 삭제, 원본 보존 | 코드 경로 구현, 실제 선택기/카메라 기기 검증 미실행 |
| 오프라인 | 데이터 조회·변경에 네트워크 연결이 불필요한지 확인 | 코드상 로컬 Room/파일만 사용; airplane mode 미실행 |
| 통계 계산 단위 테스트 | 0개, 상의만/종류 분모, 복수 계절, 목표 미만·같음·초과·미설정 | JUnit 테스트 작성; Gradle 부재로 미실행 |
| 통계 UI 대 DB | 의류 추가·수정·삭제 직후 통계 자동 반영, 실제 Room 수와 화면 비교 | Room Flow 결합으로 구현; 에뮬레이터 검증 미실행 |
| 목표 저장 | 목표 편집 후 DataStore Flow 반영 및 앱 재실행 후 유지 | Preferences DataStore로 구현; 재실행 검증 미실행 |
| 통계 0개 | 0 나눗셈/NaN 없는 요약과 막대 그래프 | 계산기 0 처리 테스트 작성; UI 기기 검증 미실행 |
| 착용 분석 계산 | 0/상의만, 복수 기록, 최근/미착용/등록 유예, 구매일 경과 및 모름 | 날짜 주입형 JUnit 테스트 작성; Gradle 부재로 미실행 |
| 기록 날짜/중복 | 첫 기록, 같은 날짜 재요청, 다른 날짜, 미래 날짜, 구매일 이전 확인, 수정 충돌 | Room unique 인덱스·저장소 validation·UI 확인 대화상자 구현; 미실행 |
| 기록 삭제/요약 | 최근 기록 삭제 후 마지막 날짜, 전부 삭제 후 0/null, 의류 삭제 cascade | DAO COUNT/MAX와 FK cascade 구현; Android 테스트 실행 불가 |
| 일괄 기록 | 신규+기존 혼합, 요청 ID 중복, 잘못된 ID 시 부분 저장 방지 | Room transaction API와 Android 통합 테스트 작성; 미실행 |
| DB 마이그레이션 | v1 의류/사진 참조 유지, v2 착용 테이블과 유일 제약 | 기존 v1 DB를 만드는 Android 통합 테스트 작성; 미실행 |
| 통계 UI 대 DB | 상세/분석 착용 수·최근 날짜 대 실제 Room 행 | Room Flow 연결; 에뮬레이터 검증 미실행 |
| 분석 기간 | 기준 편집 및 앱 재실행 후 DataStore 보존 | Preferences DataStore 구현; 재실행 검증 미실행 |
| 코디/AI | 의류 조합, 저장, 동의/삭제/실패 상태 | 4·5단계 계획 |
| 데이터 | Room migration, 앱 재시작 보존, 파일 고아 처리 | Room v1 로컬 저장 구현; DB 재생성/복원 및 고아 파일 검증 미실행 |

3단계 검증 명령 `gradle :android:app:assembleDebug`, `gradle :android:app:testDebugUnitTest`, `gradle :android:app:connectedDebugAndroidTest`를 실행했으나 모두 `gradle` 명령을 찾지 못해 시작되지 않았다. Java, Android SDK 및 에뮬레이터도 확인되지 않아 통합 테스트 결과는 미검증이다.

테스트를 실제 실행하지 않은 항목을 통과로 표시하지 않는다. 빌드 성공과 에뮬레이터/실기기 실행은 각각 따로 기록한다. 현재 실행 환경에는 Gradle/Android SDK가 없어 2·3단계 테스트를 실행하지 못했다. 실행 명령은 `gradle :android:app:testDebugUnitTest` 및 에뮬레이터에서 `gradle :android:app:connectedDebugAndroidTest`다.

## 4단계 테스트 계획 및 결과

| 항목 | 검증 시나리오 | 구현/결과 |
| --- | --- | --- |
| 순수 추천 규칙 | 상의/하의 누락, 실제 등록 항목만 사용, 계절 점수, 설정된 기온 구간, 난수 고정, 이전 조합 회피 | `OutfitRecommendationTest` 작성; 실행 불가 |
| 날씨 제공자 | 성공, 캐시, 캐시가 있는 실패, 최초 실패, 지역 미설정, 수동 입력 표시 상태 | `WeatherPresentationTest` 작성; 실행 불가. 실 API 통합 미실행 |
| DB 마이그레이션 | v1 기존 의류와 사진 참조가 v3에서도 유지, 착용 기록 보존 | Android 테스트에 v1→v2→v3 경로 반영; 에뮬레이터 테스트 미실행 |
| 저장 코디 | 저장 뒤 Room Flow 반영, 중복 조합 안내, 즐겨찾기 보존, 구성 의류 삭제 시 cascade | 통합 테스트 코드 추가; 실행 불가 |
| 착용 연결 | 추천/저장만으로 기록 없음, 명시 탭 시 일괄 기록, 같은 날짜 반복 시 중복 없음, 구매일 확인 | 기존 `addMany` 호출로 연결; 기기 UI 검증 미실행 |
| 오프라인 | 날씨 실패/미설정이어도 실제 의류 조합 생성과 저장 가능 | 코드 흐름상 로컬 동작; airplane mode 기기 검증 미실행 |
| 알림 | Android 13 권한 허용/거부, 끄기, 지연 가능한 시간대 예약 | WorkManager/권한 코드 추가; OS 버전별 검증 미실행 |
| 기존 기능 회귀 | 의류 CRUD/사진/즐겨찾기/통계/착용 분석 | 코드 경로 유지 및 FK migration 추가; 회귀 빌드/테스트 미실행 |
| 빌드·단위테스트 | `gradle :android:app:testDebugUnitTest :android:app:assembleDebug` | 2026-11-05 시도: PowerShell이 `gradle` 명령을 찾지 못해 시작하지 못함. 빌드 결과 미확인 |

기기에서 확인할 때 Android Studio에서 프로젝트를 열어 Gradle sync 후 `:android:app:testDebugUnitTest`, `:android:app:assembleDebug`, `:android:app:connectedDebugAndroidTest`를 각각 실행한다. 실제 기온·습도는 Open-Meteo 호출 결과만 보여주며 통신 테스트를 실행하지 않은 현재 작업 결과를 실측 성공으로 간주하지 않는다.

## 5단계 AI 착용 이미지 테스트 계획 및 결과

| 구분 | 사례 | 결과 |
| --- | --- | --- |
| 자동화 코드 | 설정 미사용·인증 없음, 사용자 세션 소유 격리, 결과 다운로드·삭제, 중복 키 및 payload 충돌, 잘못된 이미지/용량 초과, 일일 제한, 재시작 중 처리 상태 복원, 알 수 없는 결과 자동 재시도 방지 | `backend/tests/test_api.py` 작성. 실행 시도는 시스템 Python의 `_ssl` DLL 로딩 오류로 pytest 초기화 단계에서 중단. 통과로 판정하지 않음 |
| 서버 정적 검사 | Python 소스 compileall | Python 3.9.7의 `compileall -q backend` 성공(의존성/실행 검증 아님). 별도 SQLite 실행 확인은 `_sqlite3` DLL 로딩 오류 |
| Android 단위·빌드 | `gradle :android:app:testDebugUnitTest :android:app:assembleDebug` | Gradle/Wrapper 없음, 미실행 |
| 사용자 입력 | 사진·상의 없음, 동의 취소, 잘못된/과도한 입력, 서버 설정 없음 | UI 로직 작성. 기기 검증 미실행 |
| 네트워크·복원 | 인증 실패, HTTP 429, 네트워크/타임아웃, 같은 요청 키, 앱 재실행 후 상태 복원 | API/UI 코드 작성. 모의 서버/기기 실행 미검증 |
| 개인정보 | 전신 사진 변경·삭제, 서버 이미지 삭제, 타 사용자 접근 거부, 시스템 파일 선택기로 결과 내보내기 | 서버 테스트 코드와 앱 코드 작성. 실행 미검증 |
| 회귀 | 기존 의류 CRUD·사진·통계·기록·코디 기능, AI 실패·오프라인 사용 | 기존 앱 코드를 재사용하도록 수정. Android 빌드/기기 회귀 검증 미실행 |
| 실연동 | Google 인증, 한 장 생성, 처리 시간·비용, 전신 구도·상의 표현·얼굴 유지 | GCP 자격 증명 미설정. 호출하지 않음 |

테스트 명령은 README를 참고한다. 빌드 성공과 에뮬레이터/실기기 검증, 실제 AI 이미지 품질 평가는 별개로 기록한다.
