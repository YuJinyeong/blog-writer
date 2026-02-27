package com.blogwriter.controller

import com.blogwriter.model.GeneratedPost
import com.blogwriter.model.PostStyle
import com.blogwriter.service.PostGenerationService
import kotlinx.coroutines.runBlocking
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.*
import org.springframework.web.multipart.MultipartFile

@Controller
class PostController(
    private val postGenerationService: PostGenerationService
) {
    @GetMapping("/")
    fun index(model: Model): String {
        model.addAttribute("styles", PostStyle.entries)
        return "index"
    }

    @PostMapping("/generate")
    @ResponseBody
    fun generate(
        @RequestParam("images") images: List<MultipartFile>,
        @RequestParam("memo") memo: String,
        @RequestParam("style") style: PostStyle,
        @RequestParam("customInstruction", required = false, defaultValue = "") customInstruction: String
    ): ResponseEntity<Any> = runBlocking {
        val filteredImages = images.filter { !it.isEmpty }

        if (filteredImages.isEmpty()) {
            return@runBlocking ResponseEntity.badRequest()
                .body(mapOf("error" to "사진을 1장 이상 업로드해 주세요.") as Any)
        }

        try {
            val post = postGenerationService.generate(filteredImages, memo, style, customInstruction)
            ResponseEntity.ok(post as Any)
        } catch (e: Exception) {
            ResponseEntity.internalServerError()
                .body(mapOf("error" to "글 생성 중 오류가 발생했습니다: ${e.message}") as Any)
        }
    }

    @PostMapping("/revise")
    @ResponseBody
    fun revise(
        @RequestParam("sessionId") sessionId: String,
        @RequestParam("instruction") instruction: String
    ): ResponseEntity<Any> = runBlocking {
        try {
            val post = postGenerationService.revise(sessionId, instruction)
            ResponseEntity.ok(post as Any)
        } catch (e: Exception) {
            ResponseEntity.internalServerError()
                .body(mapOf("error" to "수정 중 오류가 발생했습니다: ${e.message}") as Any)
        }
    }
}
