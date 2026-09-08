package com.shelflife.feature.pantryEntryImage.service

import com.shelflife.core.data.media.FakeImageStorage
import com.shelflife.core.domain.AppResult
import com.shelflife.core.domain.media.ImageSanitizationError
import com.shelflife.core.domain.media.ImageSanitizer
import com.shelflife.core.domain.media.SanitizedImage
import com.shelflife.core.domain.security.VirusScanResult
import com.shelflife.core.domain.security.VirusScanner
import com.shelflife.feature.pantryEntry.service.FakePantryEntryRepository
import com.shelflife.feature.pantryEntry.service.NoopTransactionalRunner
import com.shelflife.feature.pantryEntryImage.domain.PantryEntryImageRepository
import com.shelflife.feature.pantryEntryImage.domain.model.PantryEntryImage
import com.shelflife.feature.pantryEntryImage.domain.model.PantryEntryImageContent
import com.shelflife.feature.pantryEntryImage.domain.model.PantryEntryImagePolicy
import com.shelflife.feature.sync.routing.FakeSyncService
import java.time.Instant

data class Harness(
    val service: PantryEntryImageServiceI,
    val pantryEntryRepository: FakePantryEntryRepository,
    val pantryEntryImageRepository: FakePantryEntryImageRepository,
    val imageSanitizer: FakeImageSanitizer,
    val virusScanner: FakeVirusScanner,
    val imageStorage: FakeImageStorage,
    val syncService: FakeSyncService,
)

fun newHarness(
    maxImagesPerEntry: Int = 3,
    clamAvEnabled: Boolean = false,
): Harness {
    val pantryEntryRepository = FakePantryEntryRepository()
    val pantryEntryImageRepository = FakePantryEntryImageRepository()
    val imageSanitizer = FakeImageSanitizer()
    val virusScanner = FakeVirusScanner()
    val imageStorage = FakeImageStorage()
    val syncService = FakeSyncService()
    val service =
        PantryEntryImageServiceI(
            pantryEntryRepository = pantryEntryRepository,
            pantryEntryImageRepository = pantryEntryImageRepository,
            imageSanitizer = imageSanitizer,
            virusScanner = virusScanner,
            imageStorage = imageStorage,
            syncService = syncService,
            tx = NoopTransactionalRunner(),
            policy = PantryEntryImagePolicy(maxImagesPerEntry = maxImagesPerEntry, clamAvEnabled = clamAvEnabled),
        )
    return Harness(
        service,
        pantryEntryRepository,
        pantryEntryImageRepository,
        imageSanitizer,
        virusScanner,
        imageStorage,
        syncService,
    )
}

class FakeImageSanitizer : ImageSanitizer {
    var result: AppResult<SanitizedImage, ImageSanitizationError> =
        AppResult.Success(sampleSanitizedImage())

    override suspend fun sanitize(rawBytes: ByteArray): AppResult<SanitizedImage, ImageSanitizationError> = result

    companion object {
        fun sampleSanitizedImage(bytes: ByteArray = byteArrayOf(1, 2, 3)) =
            SanitizedImage(bytes = bytes, width = 10, height = 20, contentType = "image/jpeg")
    }
}

class FakeVirusScanner : VirusScanner {
    var result: VirusScanResult = VirusScanResult.Clean

    override suspend fun scan(bytes: ByteArray): VirusScanResult = result
}

class FakePantryEntryImageRepository : PantryEntryImageRepository {
    private val imagesById = mutableMapOf<Int, PantryEntryImage>()
    private var nextId = 1

    var countByEntryIdCallCount: Int = 0
        private set

    var failNextUpdateContent: Boolean = false

    fun seed(
        pantryEntryId: Int,
        storageKey: String = "seed-key-$nextId",
    ): PantryEntryImage {
        val image =
            PantryEntryImage(
                id = nextId++,
                pantryEntryId = pantryEntryId,
                storageKey = storageKey,
                contentType = "image/jpeg",
                sizeBytes = 100,
                width = 10,
                height = 20,
                createdAt = Instant.now(),
            )
        imagesById[image.id] = image
        return image
    }

    override suspend fun create(
        pantryEntryId: Int,
        content: PantryEntryImageContent,
    ): PantryEntryImage {
        val image =
            PantryEntryImage(
                id = nextId++,
                pantryEntryId = pantryEntryId,
                storageKey = content.storageKey,
                contentType = content.contentType,
                sizeBytes = content.sizeBytes,
                width = content.width,
                height = content.height,
                createdAt = Instant.now(),
            )
        imagesById[image.id] = image
        return image
    }

    override suspend fun listByEntryId(entryId: Int): List<PantryEntryImage> =
        imagesById.values.filter { it.pantryEntryId == entryId }.sortedBy { it.id }

    override suspend fun countByEntryId(entryId: Int): Long {
        countByEntryIdCallCount++
        return imagesById.values.count { it.pantryEntryId == entryId }.toLong()
    }

    override suspend fun findByIdAndEntryId(
        id: Int,
        entryId: Int,
    ): PantryEntryImage? = imagesById[id]?.takeIf { it.pantryEntryId == entryId }

    override suspend fun updateContent(
        id: Int,
        entryId: Int,
        content: PantryEntryImageContent,
    ): PantryEntryImage? {
        if (failNextUpdateContent) {
            failNextUpdateContent = false
            return null
        }

        val existing = imagesById[id]?.takeIf { it.pantryEntryId == entryId } ?: return null
        val updated =
            existing.copy(
                storageKey = content.storageKey,
                contentType = content.contentType,
                sizeBytes = content.sizeBytes,
                width = content.width,
                height = content.height,
            )
        imagesById[id] = updated
        return updated
    }

    override suspend fun deleteByIdAndEntryId(
        id: Int,
        entryId: Int,
    ): PantryEntryImage? {
        val existing = imagesById[id]?.takeIf { it.pantryEntryId == entryId } ?: return null
        imagesById.remove(id)
        return existing
    }

    override suspend fun deleteAllByEntryId(entryId: Int): List<String> {
        val toRemove = imagesById.values.filter { it.pantryEntryId == entryId }
        toRemove.forEach { imagesById.remove(it.id) }
        return toRemove.map { it.storageKey }
    }
}
