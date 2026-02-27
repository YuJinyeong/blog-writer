package com.blogwriter.service

import com.blogwriter.model.ExifData
import com.drew.imaging.ImageMetadataReader
import com.drew.metadata.exif.ExifSubIFDDirectory
import com.drew.metadata.exif.GpsDirectory
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.web.multipart.MultipartFile
import java.awt.Image
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Base64
import javax.imageio.ImageIO

@Service
class ImageService {

    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        private const val MAX_DIMENSION = 1024
    }

    fun processForApi(file: MultipartFile): String {
        val contentType = file.contentType ?: "image/jpeg"
        val bytes = if (contentType.contains("gif")) {
            file.bytes // GIF는 애니메이션 보존을 위해 리사이즈하지 않음
        } else {
            resize(file.bytes, contentType)
        }
        return Base64.getEncoder().encodeToString(bytes)
    }

    fun getMediaType(file: MultipartFile): String {
        return file.contentType ?: "image/jpeg"
    }

    fun extractExifData(file: MultipartFile): ExifData? {
        return try {
            val metadata = ImageMetadataReader.readMetadata(ByteArrayInputStream(file.bytes))

            val exifDir = metadata.getFirstDirectoryOfType(ExifSubIFDDirectory::class.java)
            val gpsDir = metadata.getFirstDirectoryOfType(GpsDirectory::class.java)

            val dateTaken = exifDir?.getDate(ExifSubIFDDirectory.TAG_DATETIME_ORIGINAL)
                ?.toInstant()
                ?.atZone(ZoneId.systemDefault())
                ?.toLocalDateTime()

            val geoLocation = gpsDir?.geoLocation

            ExifData(
                dateTaken = dateTaken,
                latitude = geoLocation?.latitude,
                longitude = geoLocation?.longitude
            )
        } catch (e: Exception) {
            log.debug("EXIF 데이터 추출 실패: {}", e.message)
            null
        }
    }

    private fun resize(imageBytes: ByteArray, contentType: String): ByteArray {
        val original = ImageIO.read(ByteArrayInputStream(imageBytes))
            ?: return imageBytes

        if (original.width <= MAX_DIMENSION && original.height <= MAX_DIMENSION) {
            return imageBytes
        }

        val scale = MAX_DIMENSION.toDouble() / maxOf(original.width, original.height)
        val newWidth = (original.width * scale).toInt()
        val newHeight = (original.height * scale).toInt()

        val scaled = original.getScaledInstance(newWidth, newHeight, Image.SCALE_SMOOTH)
        val buffered = BufferedImage(newWidth, newHeight, BufferedImage.TYPE_INT_RGB)
        buffered.graphics.apply {
            drawImage(scaled, 0, 0, null)
            dispose()
        }

        val formatName = when {
            contentType.contains("png") -> "png"
            contentType.contains("webp") -> "png"
            else -> "jpg"
        }

        val output = ByteArrayOutputStream()
        ImageIO.write(buffered, formatName, output)
        return output.toByteArray()
    }
}
