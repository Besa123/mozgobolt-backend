package com.shelflife.feature.pantryEntryImage.routing

import com.shelflife.core.domain.AppResult
import com.shelflife.feature.pantryEntryImage.domain.PantryEntryImageService
import com.shelflife.feature.pantryEntryImage.domain.model.ImageContent
import com.shelflife.feature.pantryEntryImage.domain.model.PantryEntryImage
import com.shelflife.feature.pantryEntryImage.domain.model.PantryEntryImageError
import java.time.Instant

class FakePantryEntryImageService : PantryEntryImageService {
    var addResult: AppResult<PantryEntryImage, PantryEntryImageError> = AppResult.Success(sampleImage())
    var addCallCount: Int = 0
        private set

    var replaceResult: AppResult<PantryEntryImage, PantryEntryImageError> = AppResult.Success(sampleImage())

    var deleteResult: AppResult<Unit, PantryEntryImageError> = AppResult.Success(Unit)

    var listResult: AppResult<List<PantryEntryImage>, PantryEntryImageError> = AppResult.Success(emptyList())

    var loadContentResult: AppResult<ImageContent, PantryEntryImageError> =
        AppResult.Success(ImageContent(bytes = byteArrayOf(1, 2, 3), contentType = "image/jpeg", eTag = "sample-etag"))

    override suspend fun addImage(
        userId: Int,
        entryId: Int,
        rawBytes: ByteArray,
        originDeviceId: String?,
    ): AppResult<PantryEntryImage, PantryEntryImageError> {
        addCallCount++
        return addResult
    }

    override suspend fun replaceImage(
        userId: Int,
        entryId: Int,
        imageId: Int,
        rawBytes: ByteArray,
        originDeviceId: String?,
    ): AppResult<PantryEntryImage, PantryEntryImageError> = replaceResult

    override suspend fun deleteImage(
        userId: Int,
        entryId: Int,
        imageId: Int,
        originDeviceId: String?,
    ): AppResult<Unit, PantryEntryImageError> = deleteResult

    override suspend fun listImages(
        userId: Int,
        entryId: Int,
    ): AppResult<List<PantryEntryImage>, PantryEntryImageError> = listResult

    override suspend fun loadContent(
        userId: Int,
        entryId: Int,
        imageId: Int,
    ): AppResult<ImageContent, PantryEntryImageError> = loadContentResult

    companion object {
        fun sampleImage() =
            PantryEntryImage(
                id = 1,
                pantryEntryId = 1,
                storageKey = "key",
                contentType = "image/jpeg",
                sizeBytes = 100,
                width = 10,
                height = 20,
                createdAt = Instant.EPOCH,
            )
    }
}
