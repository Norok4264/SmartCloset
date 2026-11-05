# 프로젝트 설계

## 목적과 범위

스마트 옷장은 의류 사진과 수동 입력 정보를 기록하고 계절·종류별 관리, 통계, 부족 의류 안내, 구매·착용 이력, 소재 관리 정보를 제공하는 네이티브 Android 앱이다. 날씨 추천은 외부 날씨 연동, AI 착용 이미지는 별도 서버 연동으로 분리한다. 0단계에서는 프로젝트 구조, 공통 테마, 탭 이동과 준비 중 화면까지만 제공한다.

## 개발 단계

0. 설계 및 실행 가능한 기본 구조
1. 의류 CRUD, 즐겨찾기, 소재별 세탁 안내
2. 옷장 통계 및 부족 의류
3. 착용 이력, 구매 날짜 활용 및 빈도 분석
4. 날씨·랜덤 코디, 코디 저장
5. 전신 이미지 1장 생성

기존 경로와 데이터는 후속 단계에서 유지한다. 자동 분류는 후속 확장, 수동 입력을 우선한다. 실시간 AR, 동영상, 정확한 사이즈 측정 및 상·하의 동시 교체는 1차 범위에서 제외한다.

## 앱 구조

단일 `android/app` 모듈, Kotlin, Compose/Material 3, MVVM의 ViewModel → Repository → Room 구조를 사용한다. 비동기 작업은 Coroutines/Flow, 설정은 DataStore, 화면 이동은 Navigation Compose로 구성한다. 기본 옷장 기능은 네트워크 없이 작동하도록 설계하며 날씨와 AI만 외부 연동한다. 1단계에서 Clothing Room Repository를, 2단계에서 목표 설정용 Preferences DataStore Repository를 추가했다.

## 화면 이동

하단 탭은 홈(`home`), 내 옷장(`closet`), 옷장 분석(`analysis`), 즐겨찾기(`favorites`), 설정(`settings`)이다. 옷장 분석은 DB 기반 총계·종류·계절·즐겨찾기 통계, 개인 목표 설정 및 부족 수량과 착용 빈도 분석을 표시한다. 상세 화면에서 의류별 착용 이력을 추가·수정·삭제한다. 코디 상세(`outfit-detail`), AI 시각화(`try-on`)는 준비 중 화면이다. 아직 동작하지 않는 작업은 준비 중으로 표시한다.

## 데이터 모델 및 관계

- `Clothing`: id, name, photoPath, category, subcategory, seasons(복수), color, material, purchaseDate(nullable), favorite, memo, createdAt
- `WearHistory`: id, clothingId, wornDate. Clothing과 일대다이며 (clothingId, wornDate) 유일 제약으로 하루 한 번만 기록한다. 날짜는 시간대 무관 ISO `yyyy-MM-dd` 문자열이다.
- `Outfit`: id, topClothingId, bottomClothingId, outerClothingId(nullable), name, favorite, createdAt. 각 의류 참조는 Clothing을 가리킨다.
- `TryOnJob`/`TryOnResult`: requestId, status, selected clothing reference, resultImagePath, createdAt, errorInfo. 요청과 결과는 1:0..1 관계로 둔다.
- 사용자 설정(DataStore): weatherRegion, category 목표 수량, analysisPeriod, 알림 설정.

2단계 통계는 Clothing 실제 Room Flow에서 계산한다. 종류별 개수와 비율의 분모는 전체 등록 의류이며, 전체 0개면 비율은 0이다. 계절은 복수 선택이므로 계절별 수량과 계절×종류 수량은 겹쳐 셀 수 있다. 계절 목표는 Preferences DataStore에 계절×종류별 선택값으로 저장한다. 목표가 없는 조합은 부족 계산에서 제외하고, 부족 수량은 max(목표−현재, 0)으로 계산한다. 통계가 옷장에 등록된 항목 기준임을 밝히고 목표가 개인 설정임과 구매 권유가 아님, 미등록 의류 여부를 먼저 확인하라는 안내를 제공한다.

3단계 착용 분석은 Room 기록으로 계산한다. 기간 기준은 최근 착용 표시 30일, 미착용 분류 60일, 등록 후 미기록 분류 30일을 기본값으로 Preferences DataStore에 보존한다. 기준은 사용자 편집 가능하며 화면에 표시한다. 최근 미착용 분류는 착용 이력이 있는 의류만 대상이고, 기록이 전혀 없는 의류는 등록 후 기준 기간이 지난 경우 별도로 표시한다. 구매일이 알려진 의류만 구매 경과 일수를 계산한다. 날짜 분석 함수에는 기준 날짜와 시간대를 입력해 테스트한다. 구매일 전 착용 날짜는 저장 전에 사용자의 확인을 받는다.

착용 횟수와 마지막 착용일은 WearHistory에서 계산하며 수동 횟수 필드는 두지 않는다. 계절은 정규화된 다대다 테이블 또는 검증된 직렬화 변환기로 복수 저장한다. 1단계 Clothing.seasons는 쉼표 구분 문자열로 저장하고 목록 필터는 Room Flow 쿼리로 적용한다. 사진 파일은 앱 내부 파일 저장소에 두고 DB에는 파일명 참조만 저장한다.

의류 삭제 정책: 확인 후 연관 WearHistory는 함께 삭제한다. 저장 Outfit에서 해당 의류 참조를 제거하고 필수 상의/하의가 사라져 유효하지 않은 코디는 사용자가 확인하도록 별도 삭제/보존 정책을 구현 단계에서 명시한다. 실제 연결된 이미지 파일은 DB 트랜잭션과 파일 삭제를 분리해 고아 파일 정리 가능하도록 처리한다. AI 전신 입력 사진은 의류 사진 저장과 분리한다.

Room schema export와 명시적 Migration을 버전별로 제공한다. 기존 사용자 데이터를 보존하고 destructive migration은 금지한다. 1단계 Room v1 Clothing 테이블은 유지하고 v2 `MIGRATION_1_2`에서 `wear_history`와 인덱스만 추가한다. 착용 이력의 clothingId FK는 Clothing 삭제 시 cascade 된다. 일괄 착용 기록은 하나의 Room 트랜잭션으로 저장해 실패 시 부분 저장을 방지한다. 의류 사진은 먼저 앱 전용 파일로 가져온 뒤 Room에 저장하며 DB 실패 시 새 파일을 제거한다. 수정 시 새 파일 저장 성공 이후 이전 파일을 지우고, 삭제는 Room 행 삭제 후 앱 소유 파일만 지운다. 시작 시 DB 참조가 없는 앱 사진 파일을 정리해 비정상 종료 등으로 생긴 고아 파일도 처리한다. 선택 취소는 저장하지 않으며 임시 촬영/가져오기 파일은 화면 이탈 때 정리한다.

## 보안 및 외부 연동

AI 호출 구조와 선택 제공사, 현재 API 수명 제한, 동의 및 보관·삭제 경계는 [AI_PROVIDER.md](AI_PROVIDER.md)에 기록한다. Android 앱에는 비밀 키를 넣지 않는다. 전신 사진은 옷장 사진과 분리하며 외부 전송 전에 실제 제공사와 보관 정책을 설명하고 동의를 받는다. 개발 Mock은 개발 환경에서만 테스트 안내 이미지를 반환한다. 날씨 역시 공급자 인터페이스를 앱의 로컬 기능과 분리한다.

## 기술 기준

Kotlin 2.2.21, KSP 2.2.21-2.0.5, AGP 8.13.2, Gradle 8.13 계열, compile/target SDK 36, min SDK 26, Compose BOM 2025.10.00, Navigation Compose 2.9.5, Room 2.8.5, DataStore Preferences 1.2.1, ExifInterface 1.4.2를 기준으로 둔다. Room, DataStore, ExifInterface는 Android 공식 릴리스 노트, KSP는 Google KSP 릴리스에서 버전을 확인했다. Android Studio/Gradle 환경에서 실제 동기화와 빌드 호환성은 아직 확인하지 못했다.

## 세탁 안내 근거

앱은 소재별 온도나 세탁 공정을 추정하지 않는다. 등록 소재가 면·울·실크·폴리에스터인 경우에도 혼용률/가공을 고려해 케어라벨의 세탁·표백·건조·다림질·전문 관리 기호를 확인하라는 안내만 제공한다. 기준은 GINETEX의 ISO 3758:2023 케어 심볼 개요(https://www.ginetex.net/article/GB/care-symbols)이며 개별 의류에는 제조사 케어라벨이 우선한다.

## 4단계: 날씨·코디 추천과 저장

- `Outfit`은 이름, 상의/하의 필수 Clothing FK, 선택 아우터 FK, 즐겨찾기, 생성 시각을 저장한다. Room v2→v3 마이그레이션은 테이블과 인덱스만 추가하고 기존 의류/착용 이력을 그대로 둔다. 의류 삭제 시 FK cascade로 관련 착용 기록 및 해당 의류를 참조한 저장 코디를 함께 지운다. 중복 조합은 트랜잭션 안에서 상의·하의·아우터 조합을 검사하고 저장 전 안내한다.
- 규칙 기반 추천은 실제 등록된 상의·하의만 조합한다. 아우터 포함은 선택 사항이다. 계절이 우선이고 즐겨찾기와 최근 7일 착용 여부는 보조 점수다. 사진으로 두께를 추측하지 않는다. 기온 계절 경계(≤8°C 겨울, ≤17°C 봄, ≤24°C 가을, 그 외 여름), 보조 점수는 `RecommendationRules`에서 바꿀 수 있다. 이 값은 앱의 안내용 휴리스틱이며 개인별 정답이 아니다.
- 랜덤 생성은 주입된 `kotlin.random.Random`을 사용해 테스트에서 고정할 수 있다. 한 화면 세션에서 직전 조합을 우선 제외하며 가능한 조합을 모두 확인하면 소진 안내 후 새 순서로 시작한다.
- `WeatherRepository` 인터페이스 뒤에 `OpenMeteoWeatherRepository`를 둔다. 지역은 사용자가 직접 검색·선택하며 위치 권한은 요청하지 않는다. Geocoding API와 Forecast API의 HTTPS GET은 인증 키 없이 호출 가능하고, Open-Meteo는 비상업 사용만 무료 API 접근을 제공한다. 상업 이용은 별도 구독이 필요하다. 비상업 사용 기준 공개 제한은 일 10,000회이며 데이터 사용 시 CC BY 4.0 저작자 표시를 적용한다. 앱 설명/정보 화면에 “날씨: Open-Meteo” 출처를 표시한다. 참고: https://open-meteo.com/en/docs/geocoding-api , https://open-meteo.com/en/docs , https://open-meteo.com/en/terms
- 날씨 상태는 최신, 마지막 성공 캐시, 실패, 지역 미설정, 수동 모드를 구분한다. 캐시에는 원래 갱신 시각을 유지한다. 수동 추천은 사용자가 입력한 기온 또는 계절에만 근거하며 관측값으로 표시하지 않는다. 기본 옷장/코디 생성은 네트워크 없이 계속 가능하다.
- 데일리 알림은 사용자가 켜고 시각을 저장하면 WorkManager의 지연 가능한 24시간 작업으로 예약한다. Android 13+ 알림 권한 거부 시 알림을 켜지 않고 주요 기능은 유지한다. 정시 실행은 보장하지 않는다.
- 코디 착용 기록은 기존 `WearHistoryRepository.addMany` 트랜잭션을 사용하고, 사용자가 명시적으로 선택한 때만 기록한다.

## 5단계: AI 전신 착용 이미지 1장

Android 앱은 Photo Picker로 사용자가 고른 전신 사진을 EXIF 방향 보정·메타데이터 제거 후 `filesDir/tryon`에 보관한다. Room의 실제 `상의` 항목에서 상품 이미지를 고르고 앱은 Google API를 직접 호출하지 않는다. 동의 뒤 Android → FastAPI → 제공사 순서로 한 사람 이미지와 한 상의 사진을 전송하고 `sampleCount=1`을 요청한다. 결과는 style 참고용이며 실제 핏·사이즈를 보장하지 않는다. 이 기능은 실제 착용 기록을 추가하지 않는다.

`backend/`은 FastAPI + SQLite의 소규모 단일 앱 서버다. `TryOnProvider` 인터페이스와 Google Cloud `virtual-try-on-001` 어댑터, 개발 환경 전용 테스트 카드 Mock을 분리했다. 기본 provider는 disabled다. 실제 Google 인증은 서버 ADC/워크로드 아이덴티티를 사용한다. 설정 조회는 서버에서 ADC 인증이 확인되기 전 `enabled=false`를 반환한다. API 상태와 수명 제약, 공식 출처는 [AI_PROVIDER.md](AI_PROVIDER.md)에 기록한다.

작업·소유 세션·idempotency key는 SQLite에 저장한다. 앱은 AES-GCM Android Keystore 키로 세션을 암호화 저장하고 UUID 키를 요청 전 로컬 저장한다. 서버는 세션 소유자만 작업·결과를 조회·삭제하게 하며 키/세션당 중복 접수를 제한한다. 일일 한도는 DB 트랜잭션으로 확인한다. 서버가 재시작하면 처리 중이던 작업을 `unknown`으로 바꾸고 외부 호출을 자동 재시도하지 않는다. 요청 결과가 불명확하면 앱은 동일 키의 상태만 조회한다.

서버 입력·결과 이미지는 기본 24시간 후 정리한다. 사용자 전신 사진은 옷장 사진과 별도 저장한다. 서버 삭제는 서버 파일만 지우며 이미 제공사로 전송된 사본의 삭제나 실행 중 요청 취소를 뜻하지 않는다. Google 정책상 의심 요청의 안전성 모니터링 자료는 최대 90일 보관될 수 있으므로 동의 화면에서 구분해 고지한다.

배포는 HTTPS 종료 프록시 뒤에서 하며 FastAPI production 설정은 HTTPS를 요구한다. `.env.example`에는 키가 없고 실제 Google 인증은 백엔드 환경/비밀 관리로 제공한다. 계정 체계 대신 설치별 익명 세션을 사용한다. 배포 시 신뢰 프록시 IP 전달과 신규 세션 제한도 운영 설정이 필요하다.
