package com.blogwriter.model

import java.time.LocalDateTime

data class ExifData(
    val dateTaken: LocalDateTime?,
    val latitude: Double?,
    val longitude: Double?
)
