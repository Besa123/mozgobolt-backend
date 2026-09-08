package com.shelflife.feature.pantryEntryImage

import com.shelflife.core.domain.AppResult
import com.shelflife.core.skipIfNoDocker
import com.shelflife.feature.pantryEntryImage.domain.model.PantryEntryImageError
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

class PantryEntryImageIntegrationTest {
    private fun realJpegBytes(
        width: Int = 40,
        height: Int = 30,
    ): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        graphics.color = Color.BLUE
        graphics.fillRect(0, 0, width, height)
        graphics.dispose()
        val output = ByteArrayOutputStream()
        ImageIO.write(image, "jpg", output)
        return output.toByteArray()
    }

    @Test
    fun `uploading an image persists a real row and a real file on disk`() {
        skipIfNoDocker()

        withRealPantryEntryImageDatabase { harness ->
            val entryId = harness.seedPantryEntry(userId = 1)

            val result =
                harness.service.addImage(
                    userId = 1,
                    entryId = entryId,
                    rawBytes = realJpegBytes(),
                    originDeviceId = null,
                )

            val image = (result as? AppResult.Success)?.data ?: fail("expected success but got $result")
            assertTrue(Files.exists(harness.storageDirectory.resolve(image.storageKey)))

            val content = harness.service.loadContent(userId = 1, entryId = entryId, imageId = image.id)
            assertTrue(content is AppResult.Success)
        }
    }

    @Test
    fun `uploading beyond the configured cap is rejected against the real row count`() {
        skipIfNoDocker()

        withRealPantryEntryImageDatabase(maxImagesPerEntry = 2) { harness ->
            val entryId = harness.seedPantryEntry(userId = 1)

            harness.service.addImage(userId = 1, entryId = entryId, rawBytes = realJpegBytes(), originDeviceId = null)
            harness.service.addImage(userId = 1, entryId = entryId, rawBytes = realJpegBytes(), originDeviceId = null)
            val third =
                harness.service.addImage(
                    userId = 1,
                    entryId = entryId,
                    rawBytes = realJpegBytes(),
                    originDeviceId = null,
                )

            assertEquals(AppResult.Error(PantryEntryImageError.TOO_MANY_IMAGES), third)
        }
    }

    @Test
    fun `replacing an image updates the row and leaves only the new file on disk`() {
        skipIfNoDocker()

        withRealPantryEntryImageDatabase { harness ->
            val entryId = harness.seedPantryEntry(userId = 1)
            val addImageResult =
                harness.service.addImage(
                    userId = 1,
                    entryId = entryId,
                    rawBytes = realJpegBytes(),
                    originDeviceId = null,
                ) as AppResult.Success
            val created = addImageResult.data

            val replaceImageResult =
                harness.service.replaceImage(
                    userId = 1,
                    entryId = entryId,
                    imageId = created.id,
                    rawBytes = realJpegBytes(width = 80, height = 60),
                    originDeviceId = null,
                ) as AppResult.Success
            val replaced = replaceImageResult.data

            assertEquals(created.id, replaced.id)
            assertTrue(Files.exists(harness.storageDirectory.resolve(replaced.storageKey)))
            assertTrue(Files.notExists(harness.storageDirectory.resolve(created.storageKey)))
        }
    }

    @Test
    fun `deleting the parent pantry entry purges both the row and the physical file`() {
        skipIfNoDocker()

        withRealPantryEntryImageDatabase { harness ->
            val entryId = harness.seedPantryEntry(userId = 1)
            val addImageResult =
                harness.service.addImage(
                    userId = 1,
                    entryId = entryId,
                    rawBytes = realJpegBytes(),
                    originDeviceId = null,
                ) as AppResult.Success
            val image = addImageResult.data

            harness.pantryEntryService.deleteEntry(userId = 1, entryId = entryId)

            val remaining =
                harness.tx.transactional {
                    harness.pantryEntryImageRepository.findByIdAndEntryId(
                        image.id,
                        entryId,
                    )
                }
            assertEquals(null, remaining)
            assertTrue(Files.notExists(harness.storageDirectory.resolve(image.storageKey)))
        }
    }
}
