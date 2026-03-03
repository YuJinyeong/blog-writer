# Blog-Writer 기술 문서

## 1. 프로젝트 개요

사진과 메모를 입력하면 Claude AI가 네이버 블로그 스타일의 한국어 글을 자동 생성하는 웹 애플리케이션.

| 항목 | 값 |
|---|---|
| 언어 | Kotlin 2.1.0 |
| 프레임워크 | Spring Boot 3.4.2 |
| JDK | 17 |
| 빌드 | Gradle 9.3.1 (Kotlin DSL) |
| AI 모델 | Claude Sonnet (`claude-sonnet-4-6`) |
| 포트 | 8080 |

---

## 2. 아키텍처

```
┌──────────────────────────────────────────────────────┐
│                     Browser (SPA)                    │
│  index.html + app.js                                 │
│  - 드래그앤드롭 이미지 업로드                            │
│  - AJAX 폼 제출 (FormData)                            │
│  - 결과 표시 / 수정 요청 / 클립보드 복사                  │
└──────────────┬──────────────────────┬────────────────┘
               │ POST /generate       │ POST /revise
               ▼                      ▼
┌──────────────────────────────────────────────────────┐
│                   PostController                     │
│  @Controller — HTTP 엔드포인트                         │
│  runBlocking으로 코루틴 ↔ 서블릿 브릿지                  │
└──────────────┬──────────────────────┬────────────────┘
               │                      │
               ▼                      ▼
┌──────────────────────────────────────────────────────┐
│               PostGenerationService                  │
│  - 워크플로 오케스트레이션                                │
│  - ConcurrentHashMap 기반 세션 관리                     │
│  - 수정 응답 파싱                                       │
└────┬──────────────────────────────────────┬──────────┘
     │                                      │
     ▼                                      ▼
┌─────────────────────┐     ┌──────────────────────────┐
│    ImageService      │     │    ClaudeApiService       │
│  - EXIF 추출          │     │  - 시스템 프롬프트 구성      │
│  - 리사이즈 (≤1024px) │     │  - API 호출 (WebClient)    │
│  - Base64 인코딩      │     │  - 응답 파싱 (정규식)        │
│  - 포맷 감지          │     │  - HTML 변환               │
└─────────────────────┘     └───────────┬──────────────┘
                                        │
                                        ▼
                            ┌───────────────────────┐
                            │  Anthropic Messages API│
                            │  POST /v1/messages     │
                            └───────────────────────┘
```

---

## 3. API 엔드포인트

### `GET /`

메인 입력 폼 렌더링. Thymeleaf로 `PostStyle` 목록을 전달.

**응답:** `index.html` 템플릿

---

### `POST /generate`

블로그 글 생성.

**Content-Type:** `multipart/form-data`

| 파라미터 | 타입 | 필수 | 설명 |
|---|---|---|---|
| `images` | `MultipartFile[]` | O | 사진 1~10장 (JPEG/PNG/GIF/WebP, 개별 최대 10MB) |
| `memo` | `String` | O | 사용자 메모 |
| `style` | `PostStyle` | O | `CASUAL` \| `REVIEW` \| `TRAVEL` |
| `customInstruction` | `String` | X | 추가 생성 조건 |

**성공 응답 (200):**
```json
{
  "title": "성수동 카페 추천 | 분위기 좋은 라떼 맛집 ☕",
  "htmlContent": "<p>안녕하세요~...</p><div class=\"photo-placeholder\" data-index=\"1\">...</div>",
  "plainText": "안녕하세요~...\n\n[사진1]\n\n...",
  "imagePositions": [
    { "index": 1, "fileName": "cafe.jpg", "suggestedCaption": "감성적인 카페 외관" }
  ],
  "sessionId": "550e8400-e29b-41d4-a716-446655440000"
}
```

**에러 응답 (400):**
```json
{ "error": "사진을 1장 이상 업로드해 주세요." }
```

**에러 응답 (500):**
```json
{ "error": "글 생성 중 오류가 발생했습니다: ..." }
```

---

### `POST /revise`

기존 글 수정 요청. 세션 기반 대화 히스토리를 활용.

**Content-Type:** `multipart/form-data`

| 파라미터 | 타입 | 필수 | 설명 |
|---|---|---|---|
| `sessionId` | `String` | O | 생성 시 반환된 세션 ID |
| `instruction` | `String` | O | 수정 지시 (예: "제목 좀 더 짧게") |

**성공 응답 (200):** `POST /generate`와 동일한 `GeneratedPost` JSON

---

## 4. 데이터 모델

### `GeneratedPost`
```kotlin
data class GeneratedPost(
    val title: String,           // 블로그 제목
    val htmlContent: String,     // HTML 변환된 본문 (photo-placeholder 포함)
    val plainText: String,       // 원본 텍스트 ([사진N] 마커 포함)
    val imagePositions: List<ImagePosition>,
    val sessionId: String        // UUID, 수정 요청 시 사용
)
```

### `ImagePosition`
```kotlin
data class ImagePosition(
    val index: Int,              // 사진 번호 (1부터 시작)
    val fileName: String,        // 원본 파일명
    val suggestedCaption: String // AI 생성 캡션
)
```

### `ExifData`
```kotlin
data class ExifData(
    val dateTaken: LocalDateTime?,  // 촬영일시
    val latitude: Double?,          // GPS 위도
    val longitude: Double?          // GPS 경도
)
```

### `PostStyle`
```kotlin
enum class PostStyle(val displayName: String, val promptDescription: String) {
    CASUAL("친근한 일상", "친근하고 가벼운 일상 블로그 톤. '~했어요', '~인데요' 같은 구어체 사용"),
    REVIEW("정보성 리뷰", "정보를 체계적으로 전달하는 리뷰 톤. 위치, 가격, 메뉴 등을 정리하여 작성"),
    TRAVEL("여행 에세이", "감성적인 여행 에세이 톤. 여행의 느낌과 감동을 담아 서술")
}
```

### `SessionData`
```kotlin
data class SessionData(
    val conversationHistory: MutableList<Map<String, Any>>,  // Claude 대화 이력
    val fileNames: List<String>,                              // 원본 파일명 목록
    val lastPost: GeneratedPost                               // 최신 생성 결과
)
```

---

## 5. 서비스 상세

### 5.1 ClaudeApiService

Claude Messages API와 통신하는 핵심 서비스.

**API 설정:**
- 엔드포인트: `https://api.anthropic.com/v1/messages`
- 인증: `x-api-key` 헤더
- API 버전: `2023-06-01`
- 최대 토큰: 4096
- 메모리 버퍼: 50MB

**시스템 프롬프트 구조:**
```
당신은 네이버 블로그 전문 작가입니다.

## 규칙
- 톤: {스타일별 설명}
- 사진 설명 포함
- [사진N] 마커로 삽입 위치 표시
- SEO 키워드 포함 제목
- 500~1500자
- 이모지 활용

## 사용자 추가 조건        ← customInstruction이 있을 때만 추가
{사용자 입력}

## 출력 형식
---TITLE---
(제목)
---CONTENT---
(본문)
---CAPTIONS---
사진1: (설명)
```

**사용자 메시지 구조:**
1. 이미지 (base64 인코딩) + EXIF 텍스트 (촬영일, GPS)
2. 메모 텍스트

**응답 파싱:**
- `---TITLE---`, `---CONTENT---`, `---CAPTIONS---` 마커로 정규식 분리
- 본문의 `\n\n` → `</p><p>`, `\n` → `<br>` 변환
- `[사진N]` → `<div class="photo-placeholder">` 변환
- 마커가 없는 응답은 전체를 본문으로 사용 (제목: "제목 없음")

### 5.2 PostGenerationService

워크플로 오케스트레이션 및 세션 관리.

**생성 플로우:**
1. `ImageService`로 각 이미지 처리 (리사이즈 + base64 + EXIF)
2. `ClaudeApiService.generatePost()` 호출
3. 세션 데이터 저장 (`ConcurrentHashMap<String, SessionData>`)
4. `GeneratedPost` 반환

**수정 플로우:**
1. 세션 ID로 기존 대화 히스토리 조회
2. 히스토리에 수정 지시 추가
3. `ClaudeApiService.revisePost()` 호출
4. 응답을 동일한 정규식으로 파싱
5. 세션 업데이트 후 반환

**세션 저장소:** `ConcurrentHashMap` (인메모리, 서버 재시작 시 소실)

### 5.3 ImageService

이미지 전처리 서비스.

| 기능 | 설명 |
|---|---|
| `processForApi()` | 리사이즈 → Base64 인코딩. GIF는 리사이즈 생략 |
| `getMediaType()` | `MultipartFile.contentType` 반환 (기본값: `image/jpeg`) |
| `extractExifData()` | `metadata-extractor`로 촬영일, GPS 추출. 실패 시 `null` |
| `resize()` | 장축 1024px 이하로 축소. `SCALE_SMOOTH` 알고리즘 |

**지원 포맷:** JPEG, PNG, GIF, WebP
**리사이즈 로직:** `max(width, height) > 1024`일 때만 축소. 비율 유지.

---

## 6. 프론트엔드

### 페이지 구조

단일 페이지(`index.html`)에서 입력 섹션과 결과 섹션을 `display: none/block`으로 전환.

### app.js 주요 함수

| 함수 | 설명 |
|---|---|
| `showPreviews(files)` | 이미지 FileReader로 미리보기 그리드 생성 |
| `form.submit` 이벤트 | `FormData`로 AJAX POST, 로딩 상태 관리 |
| `showResult(post)` | 결과 데이터를 DOM에 바인딩 |
| `backToEdit()` | 결과 숨기고 입력 폼 복원 (데이터 유지) |
| `copyHtml()` | `<h2>제목</h2> + htmlContent`를 클립보드 복사 |
| `copyText()` | `제목 + plainText`를 클립보드 복사 |
| `revisePost()` | 세션 ID + 수정 지시로 `/revise` AJAX 호출 |
| `showToast(message)` | 하단 토스트 알림 (2.5초) |

### 키보드 단축키

- `Enter` (수정 입력란 포커스 시) → `revisePost()` 실행

---

## 7. 설정

### application.yml

```yaml
spring.servlet.multipart:
  max-file-size: 10MB       # 개별 파일 최대 크기
  max-request-size: 100MB   # 전체 요청 최대 크기

server.port: 8080

claude:
  api-key: ${env.CLAUDE_API_KEY:your-api-key-here}
  model: claude-sonnet-4-6
  max-tokens: 4096
```

### 환경변수

| 변수 | 필수 | 설명 |
|---|---|---|
| `CLAUDE_API_KEY` | O | Anthropic API 키. `.env` 파일 또는 환경변수로 설정 |

### .env (spring-dotenv)

`springboot3-dotenv` 라이브러리가 프로젝트 루트의 `.env` 파일을 자동 로딩.
`application.yml`에서 `${env.VARIABLE_NAME}` 형식으로 참조.

```
CLAUDE_API_KEY=sk-ant-...
```

`.env`는 `.gitignore`에 등록되어 커밋에서 제외됨. `.env.example`이 템플릿으로 제공됨.

---

## 8. 의존성

| 라이브러리 | 버전 | 용도 |
|---|---|---|
| `spring-boot-starter-web` | 3.4.2 | MVC + 내장 Tomcat |
| `spring-boot-starter-thymeleaf` | 3.4.2 | 서버사이드 HTML 템플릿 |
| `spring-boot-starter-webflux` | 3.4.2 | `WebClient` (Claude API 비동기 HTTP) |
| `jackson-module-kotlin` | - | Kotlin 데이터 클래스 JSON 직렬화 |
| `kotlinx-coroutines-core` | 1.10.1 | 코루틴 지원 |
| `kotlinx-coroutines-reactor` | 1.10.1 | WebFlux ↔ 코루틴 브릿지 (`awaitBody`) |
| `kotlinx-serialization-json` | 1.8.0 | Kotlin 직렬화 |
| `springboot3-dotenv` (BOM 5.1.0) | - | `.env` 파일 자동 로딩 |
| `metadata-extractor` | 2.19.0 | 이미지 EXIF 데이터 추출 |
| `netty-resolver-dns-native-macos` | 4.1.118 | macOS ARM64 네이티브 DNS (런타임) |

---

## 9. 테스트

### 유닛 테스트: `KoreanGenerationTest`

리플렉션으로 `ClaudeApiService`의 private 메서드를 직접 테스트.

| 테스트 | 검증 항목 |
|---|---|
| 시스템 프롬프트 한국어 구성 | 블로그 작가 역할, 마커 형식 |
| 스타일별 프롬프트 톤 | CASUAL/REVIEW/TRAVEL 각각 고유 설명 포함 |
| 커스텀 인스트럭션 추가 | 비어있지 않을 때만 섹션 생성 |
| 한국어 응답 파싱 | 제목/본문/캡션 분리, HTML 변환, 파일명 매핑 |
| 특수문자/이모지 파싱 | 따옴표, 원화 기호, 이모지 보존 |
| 마커 없는 응답 | 폴백 처리 (제목: "제목 없음", 본문: 원본 텍스트) |

### 통합 테스트: `KoreanGenerationIntegrationTest`

`@SpringBootTest` + `@Tag("integration")`. 실제 Claude API를 호출.

| 테스트 | 스타일 | 검증 항목 |
|---|---|---|
| 카페 리뷰 생성 | CASUAL | 한국어 제목/본문, 사진 마커, 캡션, HTML 변환 |
| 여행 에세이 생성 | TRAVEL | 감성적 표현, 사진 2장 마커, 캡션 2개 |
| 정보성 리뷰 + 커스텀 인스트럭션 | REVIEW | 가격 정보 포함, 별점 반영 |

**실행:**
```bash
# 유닛 테스트만
JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew test --tests "*.KoreanGenerationTest"

# 통합 테스트 (API 키 필요)
JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew test --tests "*.KoreanGenerationIntegrationTest"

# 전체
JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew test
```

---

## 10. 프로젝트 구조

```
blog-writer/
├── build.gradle.kts                          # 빌드 설정
├── settings.gradle.kts                       # 프로젝트 이름
├── .env                                      # API 키 (gitignore)
├── .env.example                              # API 키 템플릿
├── .gitignore
├── src/main/kotlin/com/blogwriter/
│   ├── BlogWriterApplication.kt              # @SpringBootApplication 진입점
│   ├── config/
│   │   └── AppConfig.kt                      # WebMvcConfigurer (빈 설정)
│   ├── controller/
│   │   └── PostController.kt                 # HTTP 엔드포인트 3개
│   ├── model/
│   │   ├── GeneratedPost.kt                  # 생성 결과 + ImagePosition
│   │   ├── ExifData.kt                       # 사진 메타데이터
│   │   └── PostStyle.kt                      # 글 스타일 enum
│   └── service/
│       ├── ClaudeApiService.kt               # Claude API 통신 + 프롬프트 + 파싱
│       ├── PostGenerationService.kt          # 오케스트레이션 + 세션 관리
│       └── ImageService.kt                   # 이미지 처리 + EXIF
├── src/main/resources/
│   ├── application.yml                       # 서버/API 설정
│   ├── templates/
│   │   ├── index.html                        # 입력 폼 (Thymeleaf)
│   │   └── result.html                       # 결과 표시 (Thymeleaf)
│   └── static/
│       ├── css/style.css                     # UI 스타일
│       └── js/app.js                         # 프론트엔드 로직
└── src/test/kotlin/com/blogwriter/service/
    ├── KoreanGenerationTest.kt               # 유닛 테스트 (7개)
    └── KoreanGenerationIntegrationTest.kt    # 통합 테스트 (3개)
```

---

## 11. 로컬 실행

```bash
# 1. API 키 설정
cp .env.example .env
# .env 파일에 실제 키 입력

# 2. 실행
JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew bootRun

# 3. 브라우저
open http://localhost:8080
```

---

## 12. 알려진 제한 사항

| 항목 | 설명 |
|---|---|
| 세션 저장소 | 인메모리 `ConcurrentHashMap`. 서버 재시작 시 세션 소실 |
| 이미지 제한 | 최대 10장, 개별 10MB. API 전송 시 base64로 ~33% 크기 증가 |
| 동시성 | `runBlocking` 사용으로 서블릿 스레드 블로킹. 높은 동시 요청 시 병목 가능 |
| 파싱 안정성 | AI 응답이 마커 형식을 벗어나면 폴백 (전체 텍스트를 본문으로 사용) |
| 수정 시 스타일 | `revisePost()`에서 항상 `PostStyle.CASUAL`의 시스템 프롬프트 사용 |
