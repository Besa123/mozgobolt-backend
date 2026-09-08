package com.shelflife.core.domain.media

import com.shelflife.core.domain.AppResult

@Suppress("UseDataClass")
class SanitizedImage(
    val bytes: ByteArray,
    val width: Int,
    val height: Int,
    val contentType: String,
)

enum class ImageSanitizationError {
    UNSUPPORTED_FORMAT,
    CORRUPT,
    DIMENSIONS_TOO_LARGE,
}

interface ImageSanitizer {
    suspend fun sanitize(rawBytes: ByteArray): AppResult<SanitizedImage, ImageSanitizationError>
}
