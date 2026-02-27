package com.blogwriter.service

import com.blogwriter.model.GeneratedPost
import com.blogwriter.model.ImagePosition
import com.blogwriter.model.PostStyle
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.web.multipart.MultipartFile
import java.util.concurrent.ConcurrentHashMap

data class SessionData(
    val conversationHistory: MutableList<Map<String, Any>>,
    val fileNames: List<String>,
    val lastPost: GeneratedPost
)

@Service
class PostGenerationService(
    private val claudeApiService: ClaudeApiService,
    private val imageService: ImageService
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val sessions = ConcurrentHashMap<String, SessionData>()

    suspend fun generate(
        images: List<MultipartFile>,
        memo: String,
        style: PostStyle
    ): GeneratedPost {
        val imageDataList = images.map { file ->
            imageService.processForApi(file) to imageService.getMediaType(file)
        }

        val fileNames = images.map { it.originalFilename ?: "image" }

        val exifDataList = images.map { imageService.extractExifData(it) }

        val post = claudeApiService.generatePost(아
            imageDataList = imageDataList,
            fileNames = fileNames,
            memo = memo,
            style = style,
            exifDataList = exifDataList
        )

        val userContent = buildString {
            append("사진 ${images.size}장과 메모를 보냈습니다.\n메모: $memo")
        }

        sessions[post.sessionId] = SessionData(
            conversationHistory = mutableListOf(
                mapOf("role" to "user", "content" to userContent),
                mapOf("role" to "assistant", "content" to post.plainText)
            ),
            fileNames = fileNames,
            lastPost = post
        )

        return post
    }

    suspend fun revise(sessionId: String, instruction: String): GeneratedPost {
        val session = sessions[sessionId]
            ?: throw IllegalStateException("세션을 찾을 수 없습니다. 새로 글을 생성해 주세요.")

        val responseText = claudeApiService.revisePost(
            conversationHistory = session.conversationHistory,
            instruction = instruction
        )

        session.conversationHistory.add(mapOf("role" to "user", "content" to "수정 요청: $instruction"))
        session.conversationHistory.add(mapOf("role" to "assistant", "content" to responseText))

        val updatedPost = parseRevisedPost(responseText, session.fileNames, sessionId)

        sessions[sessionId] = session.copy(lastPost = updatedPost)

        return updatedPost
    }

    fun getSession(sessionId: String): SessionData? = sessions[sessionId]

    private fun parseRevisedPost(responseText: String, fileNames: List<String>, sessionId: String): GeneratedPost {
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
            sessionId = sessionId
        )
    }
}
