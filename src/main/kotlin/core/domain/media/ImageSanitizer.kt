/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.core.domain.media

import com.mozgobolt.core.domain.AppResult

/** A bounded, re-encoded image ready for safe storage and delivery. */
@Suppress("UseDataClass")
class SanitizedImage(
    /** Re-encoded image bytes. */
    val bytes: ByteArray,
    /** Width of the sanitized image in pixels. */
    val width: Int,
    /** Height of the sanitized image in pixels. */
    val height: Int,
    /** MIME type of the sanitized image bytes. */
    val contentType: String,
)

/** Expected failures returned by image sanitization. */
enum class ImageSanitizationError {
    /** The supplied image format is not supported. */
    UNSUPPORTED_FORMAT,

    /** The image bytes could not be decoded. */
    CORRUPT,

    /** The decoded image exceeds a configured size limit. */
    DIMENSIONS_TOO_LARGE,
}

/** Validates image content and re-encodes accepted images into a bounded format. */
interface ImageSanitizer {
    /** Sanitizes [rawBytes] or returns an expected validation error. */
    suspend fun sanitize(rawBytes: ByteArray): AppResult<SanitizedImage, ImageSanitizationError>
}
