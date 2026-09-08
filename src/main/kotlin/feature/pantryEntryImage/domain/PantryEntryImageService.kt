package com.shelflife.feature.pantryEntryImage.domain

import com.shelflife.core.domain.AppResult
import com.shelflife.feature.pantryEntryImage.domain.model.ImageContent
import com.shelflife.feature.pantryEntryImage.domain.model.PantryEntryImage
import com.shelflife.feature.pantryEntryImage.domain.model.PantryEntryImageError

interface PantryEntryImageService {
    suspend fun addImage(
        userId: Int,
        entryId: Int,
        rawBytes: ByteArray,
        originDeviceId: String?,
    ): AppResult<PantryEntryImage, PantryEntryImageError>

    suspend fun replaceImage(
        userId: Int,
        entryId: Int,
        imageId: Int,
        rawBytes: ByteArray,
        originDeviceId: String?,
    ): AppResult<PantryEntryImage, PantryEntryImageError>

    suspend fun deleteImage(
        userId: Int,
        entryId: Int,
        imageId: Int,
        originDeviceId: String?,
    ): AppResult<Unit, PantryEntryImageError>

    suspend fun listImages(
        userId: Int,
        entryId: Int,
    ): AppResult<List<PantryEntryImage>, PantryEntryImageError>

    suspend fun loadContent(
        userId: Int,
        entryId: Int,
        imageId: Int,
    ): AppResult<ImageContent, PantryEntryImageError>
}
