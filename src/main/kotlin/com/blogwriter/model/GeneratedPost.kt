package com.blogwriter.model

data class GeneratedPost(
    val title: String,
    val htmlContent: String,
    val plainText: String,
    val imagePositions: List<ImagePosition>,
    val sessionId: String
)

data class ImagePosition(
    val index: Int,
    val fileName: String,
    val suggestedCaption: String
)
