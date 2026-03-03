package com.blogwriter.service

import com.blogwriter.model.GeneratedPost
import com.blogwriter.model.ImagePosition
import com.blogwriter.model.PostStyle
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

/**
 * 한국어 블로그 글 생성 관련 테스트
 */
class KoreanGenerationTest {

    // --- 시스템 프롬프트 검증 ---

    @Test
    fun `시스템 프롬프트가 한국어로 구성되는지 확인`() {
        val service = createServiceWithReflection()
        val prompt = invokePrivate<String>(service, "buildSystemPrompt", PostStyle.CASUAL, "")

        assertTrue(prompt.contains("네이버 블로그 전문 작가"), "블로그 작가 역할이 명시되어야 함")
        assertTrue(prompt.contains("---TITLE---"), "제목 마커가 포함되어야 함")
        assertTrue(prompt.contains("---CONTENT---"), "본문 마커가 포함되어야 함")
        assertTrue(prompt.contains("---CAPTIONS---"), "캡션 마커가 포함되어야 함")
        assertTrue(prompt.contains("[사진1]"), "사진 마커 설명이 포함되어야 함")
    }

    @Test
    fun `각 스타일별 프롬프트 톤이 올바르게 설정되는지 확인`() {
        val service = createServiceWithReflection()

        val casualPrompt = invokePrivate<String>(service, "buildSystemPrompt", PostStyle.CASUAL, "")
        assertTrue(casualPrompt.contains("친근하고 가벼운"), "CASUAL 스타일 설명이 포함되어야 함")

        val reviewPrompt = invokePrivate<String>(service, "buildSystemPrompt", PostStyle.REVIEW, "")
        assertTrue(reviewPrompt.contains("정보를 체계적으로"), "REVIEW 스타일 설명이 포함되어야 함")

        val travelPrompt = invokePrivate<String>(service, "buildSystemPrompt", PostStyle.TRAVEL, "")
        assertTrue(travelPrompt.contains("감성적인 여행"), "TRAVEL 스타일 설명이 포함되어야 함")
    }

    @Test
    fun `커스텀 인스트럭션이 프롬프트에 추가되는지 확인`() {
        val service = createServiceWithReflection()
        val prompt = invokePrivate<String>(service, "buildSystemPrompt", PostStyle.CASUAL, "반말로 작성해 주세요")

        assertTrue(prompt.contains("사용자 추가 조건"), "커스텀 인스트럭션 섹션이 포함되어야 함")
        assertTrue(prompt.contains("반말로 작성해 주세요"), "커스텀 인스트럭션 내용이 포함되어야 함")
    }

    @Test
    fun `빈 커스텀 인스트럭션은 프롬프트에 추가되지 않아야 함`() {
        val service = createServiceWithReflection()
        val prompt = invokePrivate<String>(service, "buildSystemPrompt", PostStyle.CASUAL, "")

        assertFalse(prompt.contains("사용자 추가 조건"), "빈 인스트럭션은 섹션 자체가 없어야 함")
    }

    // --- 한국어 응답 파싱 검증 ---

    @Test
    fun `한국어 응답이 올바르게 파싱되는지 확인`() {
        val service = createServiceWithReflection()
        val sampleResponse = """
            ---TITLE---
            🍜 을지로 맛집 탐방! 숨겨진 칼국수 맛집 발견
            ---CONTENT---
            안녕하세요 여러분~ 오늘은 을지로에서 정말 맛있는 칼국수 맛집을 발견해서 소개해 드릴게요! 😋

            [사진1]

            이 집은 을지로 3가역에서 도보 5분 거리에 있는 곳인데요, 골목 안쪽에 숨어 있어서 잘 모르면 지나치기 쉬운 곳이에요.

            [사진2]

            주문한 건 대표 메뉴인 손칼국수! 면발이 쫄깃쫄깃하고 국물이 진해서 정말 감동이었어요 🥺

            가격도 8,000원으로 합리적이라 자주 올 것 같아요. 을지로 근처 오시면 꼭 들러보세요!
            ---CAPTIONS---
            사진1: 을지로 골목 사이에 위치한 칼국수 맛집 외관
            사진2: 김이 모락모락 나는 손칼국수 한 그릇
        """.trimIndent()
        val fileNames = listOf("exterior.jpg", "noodle.jpg")

        val post = invokePrivate<GeneratedPost>(service, "parseGeneratedPost", sampleResponse, fileNames)

        // 제목 파싱 확인
        assertTrue(post.title.contains("을지로"), "제목에 장소명이 포함되어야 함")
        assertTrue(post.title.contains("칼국수"), "제목에 메뉴명이 포함되어야 함")

        // 본문 파싱 확인
        assertTrue(post.plainText.contains("안녕하세요"), "본문이 한국어로 시작해야 함")
        assertTrue(post.plainText.contains("칼국수"), "본문에 메뉴명이 포함되어야 함")
        assertTrue(post.plainText.contains("[사진1]"), "사진 마커가 포함되어야 함")

        // HTML 변환 확인
        assertTrue(post.htmlContent.contains("<p>"), "HTML 태그가 포함되어야 함")
        assertTrue(post.htmlContent.contains("photo-placeholder"), "사진 플레이스홀더가 포함되어야 함")
        assertTrue(post.htmlContent.contains("사진1 삽입 위치"), "사진 삽입 위치 안내가 포함되어야 함")

        // 캡션 파싱 확인
        assertEquals(2, post.imagePositions.size, "캡션이 2개여야 함")
        assertEquals("exterior.jpg", post.imagePositions[0].fileName)
        assertTrue(post.imagePositions[0].suggestedCaption.contains("칼국수"), "캡션에 한국어 설명이 있어야 함")
        assertEquals("noodle.jpg", post.imagePositions[1].fileName)
    }

    @Test
    fun `특수문자와 이모지가 포함된 한국어 응답 파싱`() {
        val service = createServiceWithReflection()
        val sampleResponse = """
            ---TITLE---
            ✨ 서울숲 카페 추천 | 인스타 감성 가득한 '카페 숲'
            ---CONTENT---
            오늘의 카페 탐방은 서울숲 근처 '카페 숲'이에요! ☕️✨

            [사진1]

            가격표:
            - 아메리카노: 5,500원
            - 카페라떼: 6,000원
            - 크로플: 7,500원

            인테리어가 정말 예쁘더라구요~ 😍
            ---CAPTIONS---
            사진1: 햇살이 들어오는 카페 숲의 창가 자리
        """.trimIndent()
        val fileNames = listOf("cafe.jpg")

        val post = invokePrivate<GeneratedPost>(service, "parseGeneratedPost", sampleResponse, fileNames)

        assertTrue(post.title.contains("카페 숲"), "따옴표 포함 제목이 파싱되어야 함")
        assertTrue(post.plainText.contains("5,500원"), "가격 정보가 유지되어야 함")
        assertTrue(post.plainText.contains("☕️"), "이모지가 유지되어야 함")
        assertEquals(1, post.imagePositions.size)
    }

    @Test
    fun `마커 없는 응답도 처리되는지 확인`() {
        val service = createServiceWithReflection()
        val rawKoreanText = "이것은 마커 없이 직접 반환된 한국어 텍스트입니다."

        val post = invokePrivate<GeneratedPost>(service, "parseGeneratedPost", rawKoreanText, listOf("img.jpg"))

        assertEquals("제목 없음", post.title, "마커 없으면 기본 제목이어야 함")
        assertEquals(rawKoreanText, post.plainText, "원본 텍스트가 본문에 들어가야 함")
        assertTrue(post.imagePositions.isEmpty(), "캡션이 없어야 함")
    }

    // --- Reflection 유틸 ---

    private fun createServiceWithReflection(): ClaudeApiService {
        val constructor = ClaudeApiService::class.java.getDeclaredConstructor(
            String::class.java, String::class.java, Int::class.java
        )
        constructor.isAccessible = true
        return constructor.newInstance("dummy-key", "claude-sonnet-4-6", 4096)
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> invokePrivate(obj: Any, methodName: String, vararg args: Any): T {
        val method = obj::class.java.declaredMethods.first { it.name == methodName }
        method.isAccessible = true
        return method.invoke(obj, *args) as T
    }
}
