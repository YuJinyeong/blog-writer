package com.blogwriter.service

import com.blogwriter.model.ExifData
import com.blogwriter.model.PostStyle
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import java.awt.Color
import java.awt.Font
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.time.LocalDateTime
import java.util.Base64
import javax.imageio.ImageIO

/**
 * Claude API를 실제로 호출하여 한국어 블로그 글 생성을 검증하는 통합 테스트.
 * 실행 조건: .env 파일에 유효한 CLAUDE_API_KEY가 설정되어 있어야 함.
 */
@SpringBootTest
@Tag("integration")
class KoreanGenerationIntegrationTest {

    @Autowired
    lateinit var claudeApiService: ClaudeApiService

    @Test
    fun `카페 리뷰 블로그 글이 한국어로 생성되는지 확인`() = runBlocking {
        val image = createTestImage("Cafe Latte ☕", Color(0xF5, 0xE6, 0xD3))
        val imageData = listOf(image to "image/jpeg")
        val fileNames = listOf("cafe_latte.jpg")
        val exifDataList = listOf(
            ExifData(
                dateTaken = LocalDateTime.of(2026, 3, 1, 14, 30),
                latitude = 37.5447,
                longitude = 127.0565
            )
        )

        val post = claudeApiService.generatePost(
            imageDataList = imageData,
            fileNames = fileNames,
            memo = "성수동 카페 탐방. 라떼가 정말 맛있었어요. 인테리어도 예쁘고 분위기 좋았음.",
            style = PostStyle.CASUAL,
            exifDataList = exifDataList
        )

        println("=== 생성된 블로그 글 ===")
        println("제목: ${post.title}")
        println("본문:\n${post.plainText}")
        println("캡션: ${post.imagePositions.map { "${it.index}: ${it.suggestedCaption}" }}")
        println("세션ID: ${post.sessionId}")
        println("========================")

        // 제목 검증
        assertNotEquals("제목 없음", post.title, "제목이 생성되어야 함")
        assertTrue(post.title.any { it.code in 0xAC00..0xD7A3 }, "제목에 한국어가 포함되어야 함")

        // 본문 검증
        assertTrue(post.plainText.length >= 200, "본문이 충분한 길이여야 함 (현재: ${post.plainText.length}자)")
        assertTrue(post.plainText.any { it.code in 0xAC00..0xD7A3 }, "본문이 한국어로 작성되어야 함")
        assertTrue(post.plainText.contains("[사진1]"), "사진 마커가 포함되어야 함")

        // 캡션 검증
        assertTrue(post.imagePositions.isNotEmpty(), "캡션이 1개 이상이어야 함")
        assertTrue(
            post.imagePositions[0].suggestedCaption.any { it.code in 0xAC00..0xD7A3 },
            "캡션이 한국어로 작성되어야 함"
        )

        // HTML 변환 검증
        assertTrue(post.htmlContent.contains("<p>"), "HTML 변환이 되어야 함")
        assertTrue(post.htmlContent.contains("photo-placeholder"), "사진 플레이스홀더가 포함되어야 함")
    }

    @Test
    fun `여행 에세이 스타일로 한국어 글이 생성되는지 확인`() = runBlocking {
        val image1 = createTestImage("Beach 🏖️", Color(0x87, 0xCE, 0xEB))
        val image2 = createTestImage("Sunset 🌅", Color(0xFF, 0x8C, 0x69))
        val imageData = listOf(image1 to "image/jpeg", image2 to "image/jpeg")
        val fileNames = listOf("beach.jpg", "sunset.jpg")
        val exifDataList = listOf(
            ExifData(LocalDateTime.of(2026, 2, 20, 15, 0), 33.2541, 126.5700),
            ExifData(LocalDateTime.of(2026, 2, 20, 18, 30), 33.2480, 126.5650)
        )

        val post = claudeApiService.generatePost(
            imageDataList = imageData,
            fileNames = fileNames,
            memo = "제주도 협재해수욕장 여행. 바다가 정말 예뻤다. 일몰이 너무 아름다웠음.",
            style = PostStyle.TRAVEL,
            exifDataList = exifDataList
        )

        println("=== 여행 에세이 ===")
        println("제목: ${post.title}")
        println("본문:\n${post.plainText}")
        println("캡션: ${post.imagePositions.map { "${it.index}: ${it.suggestedCaption}" }}")
        println("===================")

        // 기본 검증
        assertTrue(post.title.any { it.code in 0xAC00..0xD7A3 }, "제목이 한국어여야 함")
        assertTrue(post.plainText.length >= 200, "본문 길이가 충분해야 함 (현재: ${post.plainText.length}자)")

        // 여행 에세이 스타일 검증 - 감성적 표현이 포함되어야 함
        val hasEmotionalTone = post.plainText.contains("느낌") ||
                post.plainText.contains("감동") ||
                post.plainText.contains("아름") ||
                post.plainText.contains("추억") ||
                post.plainText.contains("설레") ||
                post.plainText.contains("행복") ||
                post.plainText.contains("여행")
        assertTrue(hasEmotionalTone, "여행 에세이 톤이 반영되어야 함")

        // 사진 2장에 대한 마커
        assertTrue(post.plainText.contains("[사진1]"), "사진1 마커가 포함되어야 함")
        assertTrue(post.plainText.contains("[사진2]"), "사진2 마커가 포함되어야 함")

        // 캡션 2개
        assertEquals(2, post.imagePositions.size, "사진 2장에 대한 캡션이 있어야 함")
    }

    @Test
    fun `정보성 리뷰 스타일에 커스텀 인스트럭션이 반영되는지 확인`() = runBlocking {
        val image = createTestImage("Food 🍜", Color(0xFF, 0xE4, 0xB5))
        val imageData = listOf(image to "image/jpeg")
        val fileNames = listOf("food.jpg")
        val exifDataList = listOf(
            ExifData(LocalDateTime.of(2026, 3, 2, 12, 0), 37.5665, 126.9780)
        )

        val post = claudeApiService.generatePost(
            imageDataList = imageData,
            fileNames = fileNames,
            memo = "광화문 근처 칼국수 맛집. 손칼국수 8000원. 면이 쫄깃하고 국물이 시원했음. 주차 가능.",
            style = PostStyle.REVIEW,
            exifDataList = exifDataList,
            customInstruction = "가격 정보를 꼭 포함하고, 별점(⭐)을 5점 만점으로 매겨주세요."
        )

        println("=== 정보성 리뷰 ===")
        println("제목: ${post.title}")
        println("본문:\n${post.plainText}")
        println("===================")

        // 기본 검증
        assertTrue(post.title.any { it.code in 0xAC00..0xD7A3 }, "제목이 한국어여야 함")
        assertTrue(post.plainText.any { it.code in 0xAC00..0xD7A3 }, "본문이 한국어여야 함")

        // 커스텀 인스트럭션 반영 검증
        val hasPrice = post.plainText.contains("8,000") || post.plainText.contains("8000") || post.plainText.contains("원")
        assertTrue(hasPrice, "가격 정보가 포함되어야 함")

        val hasStar = post.plainText.contains("⭐") || post.plainText.contains("별점") || post.plainText.contains("점")
        assertTrue(hasStar, "별점이 포함되어야 함 (커스텀 인스트럭션 반영)")
    }

    /**
     * 테스트용 이미지를 프로그래밍 방식으로 생성하여 base64로 반환
     */
    private fun createTestImage(label: String, bgColor: Color): String {
        val width = 400
        val height = 300
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()

        // 배경
        g.color = bgColor
        g.fillRect(0, 0, width, height)

        // 라벨
        g.color = Color(0x33, 0x33, 0x33)
        g.font = Font("SansSerif", Font.BOLD, 28)
        val fm = g.fontMetrics
        val x = (width - fm.stringWidth(label)) / 2
        val y = (height + fm.ascent - fm.descent) / 2
        g.drawString(label, x, y)

        g.dispose()

        val output = ByteArrayOutputStream()
        ImageIO.write(image, "jpg", output)
        return Base64.getEncoder().encodeToString(output.toByteArray())
    }
}
