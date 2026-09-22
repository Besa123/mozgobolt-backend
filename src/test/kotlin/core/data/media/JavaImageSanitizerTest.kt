package com.mozgobolt.core.data.media

import com.mozgobolt.core.domain.AppResult
import com.mozgobolt.core.domain.media.ImageSanitizationError
import com.mozgobolt.core.modules.AppConfig
import kotlinx.coroutines.runBlocking
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

class JavaImageSanitizerTest {
    private fun config(
        maxImageDimensionPixels: Int = 2048,
        maxImagePixelCount: Long = 40_000_000,
    ) = AppConfig.Media(
        localStorageDirectory = "unused",
        maxImagesPerEntry = 3,
        maxImageDimensionPixels = maxImageDimensionPixels,
        maxImagePixelCount = maxImagePixelCount,
        jpegQuality = 0.85f,
    )

    private fun encodedImage(
        format: String,
        width: Int,
        height: Int,
        type: Int = BufferedImage.TYPE_INT_RGB,
    ): ByteArray {
        val image = BufferedImage(width, height, type)
        val graphics = image.createGraphics()
        graphics.color = Color.RED
        graphics.fillRect(0, 0, width, height)
        graphics.dispose()
        val output = ByteArrayOutputStream()
        ImageIO.write(image, format, output)
        return output.toByteArray()
    }

    @Test
    fun `a valid JPEG is accepted and re-encoded as JPEG`() {
        runBlocking {
            val sanitizer = JavaImageSanitizer(config())

            val result = sanitizer.sanitize(encodedImage("jpg", 100, 50))

            val sanitized = (result as? AppResult.Success)?.data ?: fail("expected success but got $result")
            assertEquals("image/jpeg", sanitized.contentType)
            assertEquals(100, sanitized.width)
            assertEquals(50, sanitized.height)
            assertTrue(sanitized.bytes.isNotEmpty())
        }
    }

    @Test
    fun `a valid PNG is accepted and re-encoded as JPEG`() {
        runBlocking {
            val sanitizer = JavaImageSanitizer(config())

            val result = sanitizer.sanitize(encodedImage("png", 60, 40))

            val sanitized = (result as? AppResult.Success)?.data ?: fail("expected success but got $result")
            assertEquals("image/jpeg", sanitized.contentType)
        }
    }

    @Test
    fun `an image larger than the configured max dimension is downscaled proportionally`() {
        runBlocking {
            val sanitizer = JavaImageSanitizer(config(maxImageDimensionPixels = 50))

            val result = sanitizer.sanitize(encodedImage("png", 200, 100))

            val sanitized = (result as? AppResult.Success)?.data ?: fail("expected success but got $result")
            assertEquals(50, sanitized.width)
            assertEquals(25, sanitized.height)
        }
    }

    @Test
    fun `an image within the max dimension is never upscaled`() {
        runBlocking {
            val sanitizer = JavaImageSanitizer(config(maxImageDimensionPixels = 2048))

            val result = sanitizer.sanitize(encodedImage("png", 20, 10))

            val sanitized = (result as? AppResult.Success)?.data ?: fail("expected success but got $result")
            assertEquals(20, sanitized.width)
            assertEquals(10, sanitized.height)
        }
    }

    @Test
    fun `an image exceeding the pixel budget is rejected before decoding`() {
        runBlocking {
            // 20x20 = 400 pixels, comfortably over a budget of 100 — this only works as a cheap unit
            // test because the rejection happens from the header alone, before any full decode.
            val sanitizer = JavaImageSanitizer(config(maxImagePixelCount = 100))

            val result = sanitizer.sanitize(encodedImage("png", 20, 20))

            assertEquals(AppResult.Error(ImageSanitizationError.DIMENSIONS_TOO_LARGE), result)
        }
    }

    @Test
    fun `non-image bytes are rejected as unsupported format, not a crash`() {
        runBlocking {
            val sanitizer = JavaImageSanitizer(config())

            val result = sanitizer.sanitize("definitely not an image".toByteArray())

            assertEquals(AppResult.Error(ImageSanitizationError.UNSUPPORTED_FORMAT), result)
        }
    }

    @Test
    fun `a truncated image file is rejected as corrupt, not a crash`() {
        runBlocking {
            val sanitizer = JavaImageSanitizer(config())
            val validBytes = encodedImage("jpg", 50, 50)

            val result = sanitizer.sanitize(validBytes.copyOf(40))

            assertTrue(result is AppResult.Error)
        }
    }

    @Test
    fun `the reported width and height always match what was actually encoded, for an awkward aspect ratio`() {
        runBlocking {
            val sanitizer = JavaImageSanitizer(config(maxImageDimensionPixels = 777))

            val result = sanitizer.sanitize(encodedImage("png", 1000, 333))

            val sanitized = (result as? AppResult.Success)?.data ?: fail("expected success but got $result")
            val actual = ImageIO.read(java.io.ByteArrayInputStream(sanitized.bytes))
            assertEquals(actual.width, sanitized.width, "reported width must match the actual encoded image")
            assertEquals(actual.height, sanitized.height, "reported height must match the actual encoded image")
        }
    }

    @Test
    fun `a transparent PNG is flattened onto an opaque background without error`() {
        runBlocking {
            val sanitizer = JavaImageSanitizer(config())

            val result = sanitizer.sanitize(encodedImage("png", 30, 30, type = BufferedImage.TYPE_INT_ARGB))

            assertTrue(result is AppResult.Success)
        }
    }
}
