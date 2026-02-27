package com.blogwriter.service

import com.blogwriter.model.ExifData
import com.blogwriter.model.GeneratedPost
import com.blogwriter.model.ImagePosition
import com.blogwriter.model.PostStyle
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.awaitBody
import java.util.UUID

@Service
class ClaudeApiService(
    @Value("\${claude.api-key}") private val apiKey: String,
    @Value("\${claude.model:claude-sonnet-4-6}") private val model: String,
    @Value("\${claude.max-tokens:4096}") private val maxTokens: Int
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val mapper: ObjectMapper = jacksonObjectMapper()

    private val webClient = WebClient.builder()
        .baseUrl("https://api.anthropic.com")
        .defaultHeader("x-api-key", apiKey)
        .defaultHeader("anthropic-version", "2023-06-01")
        .codecs { it.defaultCodecs().maxInMemorySize(50 * 1024 * 1024) }
        .build()

    suspend fun generatePost(
        imageDataList: List<Pair<String, String>>,
        fileNames: List<String>,
        memo: String,
        style: PostStyle,
        exifDataList: List<ExifData?>
    ): GeneratedPost {
        val systemPrompt = buildSystemPrompt(style)
        val userContent = buildUserContent(imageDataList, memo, exifDataList)

        val requestBody = buildRequestBody(systemPrompt, listOf(mapOf("role" to "user", "content" to userContent)))
        val responseText = callApi(requestBody)

        return parseGeneratedPost(responseText, fileNames)
    }

    suspend fun revisePost(
        conversationHistory: List<Map<String, Any>>,
        instruction: String
    ): String {
        val messages = conversationHistory.toMutableList()
        messages.add(mapOf(
            "role" to "user",
            "content" to "다음과 같이 수정해 주세요: $instruction"
        ))

        val requestBody = buildRequestBody(
            buildSystemPrompt(PostStyle.CASUAL),
            messages
        )

        return callApi(requestBody)
    }

    private fun buildSystemPrompt(style: PostStyle): String = """
        당신은 네이버 블로그 전문 작가입니다.
        사용자가 보낸 사진과 메모를 바탕으로 블로그 글을 작성합니다.

        ## 규칙
        - 톤: ${style.promptDescription}
        - 각 사진에 대한 자연스러운 설명을 포함하세요
        - 사진 삽입 위치를 [사진1], [사진2] 등의 마커로 표시하세요
        - 제목은 검색에 잘 걸리도록 장소명, 메뉴명 등 키워드를 포함하세요
        - 500~1500자 분량으로 작성하세요
        - 이모지를 적절히 활용하세요

        ## 출력 형식
        반드시 아래 형식으로만 출력하세요. 다른 설명은 붙이지 마세요.

        ---TITLE---
        (제목)
        ---CONTENT---
        (본문 - [사진N] 마커 포함)
        ---CAPTIONS---
        사진1: (사진 설명)
        사진2: (사진 설명)
        ...
    """.trimIndent()

    private fun buildUserContent(
        imageDataList: List<Pair<String, String>>,
        memo: String,
        exifDataList: List<ExifData?>
    ): List<Map<String, Any>> {
        val content = mutableListOf<Map<String, Any>>()

        imageDataList.forEachIndexed { index, (base64Data, mediaType) ->
            content.add(mapOf(
                "type" to "image",
                "source" to mapOf(
                    "type" to "base64",
                    "media_type" to mediaType,
                    "data" to base64Data
                )
            ))

            val exif = exifDataList.getOrNull(index)
            if (exif != null) {
                val exifInfo = buildString {
                    append("사진${index + 1} 정보: ")
                    exif.dateTaken?.let { append("촬영일: $it ") }
                    if (exif.latitude != null && exif.longitude != null) {
                        append("위치: ${exif.latitude}, ${exif.longitude}")
                    }
                }
                content.add(mapOf("type" to "text", "text" to exifInfo))
            }
        }

        content.add(mapOf(
            "type" to "text",
            "text" to "메모:\n$memo\n\n위 사진들과 메모를 바탕으로 블로그 글을 작성해 주세요."
        ))

        return content
    }

    private fun buildRequestBody(
        systemPrompt: String,
        messages: List<Map<String, Any>>
    ): Map<String, Any> = mapOf(
        "model" to model,
        "max_tokens" to maxTokens,
        "system" to systemPrompt,
        "messages" to messages
    )

    private suspend fun callApi(requestBody: Map<String, Any>): String {
        val response: String = webClient.post()
            .uri("/v1/messages")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(requestBody)
            .retrieve()
            .awaitBody()

        val root = mapper.readTree(response)
        val contentArray = root.get("content")

        return contentArray
            ?.filter { it.get("type")?.asText() == "text" }
            ?.joinToString("") { it.get("text").asText() }
            ?: throw RuntimeException("Claude API 응답에서 텍스트를 찾을 수 없습니다")
    }

    private fun parseGeneratedPost(responseText: String, fileNames: List<String>): GeneratedPost {
        val titleMatch = Regex("---TITLE---\\s*(.+?)\\s*---CONTENT---", RegexOption.DOT_MATCHES_ALL)
            .find(responseText)
        val contentMatch = Regex("---CONTENT---\\s*(.+?)\\s*---CAPTIONS---", RegexOption.DOT_MATCHES_ALL)
            .find(responseText)
        val captionsMatch = Regex("---CAPTIONS---\\s*(.+)", RegexOption.DOT_MATCHES_ALL)
            .find(responseText)

        val title = titleMatch?.groupValues?.get(1)?.trim() ?: "제목 없음"
        val content = contentMatch?.groupValues?.get(1)?.trim() ?: responseText
        val captionsRaw = captionsMatch?.groupValues?.get(1)?.trim() ?: ""

        val imagePositions = Regex("사진(\\d+):\\s*(.+)")
            .findAll(captionsRaw)
            .map { match ->
                val idx = match.groupValues[1].toInt()
                ImagePosition(
                    index = idx,
                    fileName = fileNames.getOrElse(idx - 1) { "image_$idx" },
                    suggestedCaption = match.groupValues[2].trim()
                )
            }
            .toList()

        val htmlContent = content
            .replace("\n\n", "</p><p>")
            .replace("\n", "<br>")
            .let { "<p>$it</p>" }
            .replace(Regex("\\[사진(\\d+)]")) { match ->
                val idx = match.groupValues[1].toInt()
                val caption = imagePositions.find { it.index == idx }?.suggestedCaption ?: ""
                """<div class="photo-placeholder" data-index="$idx">📷 [사진$idx 삽입 위치] $caption</div>"""
            }

        return GeneratedPost(
            title = title,
            htmlContent = htmlContent,
            plainText = content,
            imagePositions = imagePositions,
            sessionId = UUID.randomUUID().toString()
        )
    }
}
