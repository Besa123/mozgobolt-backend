package com.shelflife.core.data.media

import com.shelflife.core.domain.AppResult
import com.shelflife.core.domain.media.ImageSanitizationError
import com.shelflife.core.domain.media.ImageSanitizer
import com.shelflife.core.domain.media.SanitizedImage
import com.shelflife.core.modules.AppConfig
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.coobird.thumbnailator.Thumbnails
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageReader
import javax.imageio.ImageWriteParam
import kotlin.math.roundToInt

private val logger = KotlinLogging.logger {}

private val ALLOWED_FORMATS = setOf("JPEG", "PNG")

@Suppress("TooGenericExceptionCaught")
class JavaImageSanitizer(
    private val config: AppConfig.Media,
) : ImageSanitizer {
    override suspend fun sanitize(rawBytes: ByteArray): AppResult<SanitizedImage, ImageSanitizationError> =
        withContext(Dispatchers.IO) {
            val inputStream =
                ImageIO.createImageInputStream(ByteArrayInputStream(rawBytes))
                    ?: return@withContext AppResult.Error(ImageSanitizationError.UNSUPPORTED_FORMAT)

            inputStream.use {
                val reader =
                    ImageIO.getImageReaders(inputStream).asSequence().firstOrNull()
                        ?: return@withContext AppResult.Error(ImageSanitizationError.UNSUPPORTED_FORMAT)

                try {
                    reader.input = inputStream
                    sanitizeWithReader(reader)
                } finally {
                    reader.dispose()
                }
            }
        }

    private fun sanitizeWithReader(reader: ImageReader): AppResult<SanitizedImage, ImageSanitizationError> {
        if (reader.formatName.uppercase() !in ALLOWED_FORMATS) {
            return AppResult.Error(ImageSanitizationError.UNSUPPORTED_FORMAT)
        }

        return when (val decoded = decodeWithinBudget(reader)) {
            is AppResult.Error -> decoded
            is AppResult.Success -> AppResult.Success(reencode(decoded.data))
        }
    }

    private fun decodeWithinBudget(reader: ImageReader): AppResult<BufferedImage, ImageSanitizationError> {
        val dimensions =
            try {
                reader.getWidth(0) to reader.getHeight(0)
            } catch (e: Exception) {
                logger.debug(e) { "Failed to read image dimensions — treating as corrupt" }
                return AppResult.Error(ImageSanitizationError.CORRUPT)
            }

        val (width, height) = dimensions
        if (width.toLong() * height.toLong() > config.maxImagePixelCount) {
            return AppResult.Error(ImageSanitizationError.DIMENSIONS_TOO_LARGE)
        }

        return try {
            AppResult.Success(reader.read(0))
        } catch (e: Exception) {
            logger.debug(e) { "Failed to decode image — treating as corrupt" }
            AppResult.Error(ImageSanitizationError.CORRUPT)
        }
    }

    private fun reencode(decoded: BufferedImage): SanitizedImage {
        val longestEdge = maxOf(decoded.width, decoded.height)
        val scale =
            if (longestEdge > config.maxImageDimensionPixels) {
                config.maxImageDimensionPixels.toDouble() / longestEdge
            } else {
                1.0
            }
        val targetWidth = (decoded.width * scale).roundToInt().coerceAtLeast(1)
        val targetHeight = (decoded.height * scale).roundToInt().coerceAtLeast(1)

        val resized =
            Thumbnails
                .of(flattenToOpaqueRgb(decoded))
                .size(targetWidth, targetHeight)
                .asBufferedImage()

        return SanitizedImage(
            bytes = encodeAsJpeg(resized, config.jpegQuality),
            width = resized.width,
            height = resized.height,
            contentType = "image/jpeg",
        )
    }

    private fun encodeAsJpeg(
        image: BufferedImage,
        quality: Float,
    ): ByteArray {
        val writer = ImageIO.getImageWritersByFormatName("jpg").next()
        val writeParam =
            writer.defaultWriteParam.apply {
                compressionMode = ImageWriteParam.MODE_EXPLICIT
                compressionQuality = quality
            }

        val output = ByteArrayOutputStream()
        try {
            ImageIO.createImageOutputStream(output).use { imageOutputStream ->
                writer.output = imageOutputStream
                writer.write(null, IIOImage(image, null, null), writeParam)
            }
        } finally {
            writer.dispose()
        }
        return output.toByteArray()
    }

    private fun flattenToOpaqueRgb(image: BufferedImage): BufferedImage {
        if (!image.colorModel.hasAlpha()) return image

        val flattened = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_RGB)
        val graphics = flattened.createGraphics()
        try {
            graphics.color = Color.WHITE
            graphics.fillRect(0, 0, image.width, image.height)
            graphics.drawImage(image, 0, 0, null)
        } finally {
            graphics.dispose()
        }
        return flattened
    }
}
