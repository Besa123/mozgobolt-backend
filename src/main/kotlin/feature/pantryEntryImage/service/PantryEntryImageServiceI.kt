package com.shelflife.feature.pantryEntryImage.service

import com.shelflife.core.database.TransactionalRunner
import com.shelflife.core.domain.AppResult
import com.shelflife.core.domain.media.ImageSanitizationError
import com.shelflife.core.domain.media.ImageSanitizer
import com.shelflife.core.domain.media.ImageStorage
import com.shelflife.core.domain.media.SanitizedImage
import com.shelflife.core.domain.security.VirusScanResult
import com.shelflife.core.domain.security.VirusScanner
import com.shelflife.core.utility.functions.runSuspendCatching
import com.shelflife.core.utility.functions.sha256Hex
import com.shelflife.feature.pantryEntry.domain.PantryEntryRepository
import com.shelflife.feature.pantryEntryImage.domain.PantryEntryImageRepository
import com.shelflife.feature.pantryEntryImage.domain.PantryEntryImageService
import com.shelflife.feature.pantryEntryImage.domain.model.ImageContent
import com.shelflife.feature.pantryEntryImage.domain.model.PantryEntryImage
import com.shelflife.feature.pantryEntryImage.domain.model.PantryEntryImageContent
import com.shelflife.feature.pantryEntryImage.domain.model.PantryEntryImageError
import com.shelflife.feature.pantryEntryImage.domain.model.PantryEntryImagePolicy
import com.shelflife.feature.sync.domain.SyncService
import com.shelflife.feature.sync.domain.model.SyncEntityType
import com.shelflife.feature.sync.domain.model.SyncOperation
import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.UUID

private val logger = KotlinLogging.logger {}

class PantryEntryImageServiceI(
    private val pantryEntryRepository: PantryEntryRepository,
    private val pantryEntryImageRepository: PantryEntryImageRepository,
    private val imageSanitizer: ImageSanitizer,
    private val virusScanner: VirusScanner,
    private val imageStorage: ImageStorage,
    private val syncService: SyncService,
    private val tx: TransactionalRunner,
    private val policy: PantryEntryImagePolicy,
) : PantryEntryImageService {
    override suspend fun addImage(
        userId: Int,
        entryId: Int,
        rawBytes: ByteArray,
        originDeviceId: String?,
    ): AppResult<PantryEntryImage, PantryEntryImageError> {
        val precheckError = tx.transactional { checkCanAddImage(userId, entryId) }
        if (precheckError != null) return AppResult.Error(precheckError)

        val (storageKey, sanitized) =
            when (val prepared = sanitizeAndStore(rawBytes)) {
                is AppResult.Error -> return AppResult.Error(prepared.errorType)
                is AppResult.Success -> prepared.data
            }

        val result =
            tx.transactional {
                if (pantryEntryRepository.findByIdAndUserId(entryId, userId) == null) {
                    return@transactional AppResult.Error(PantryEntryImageError.ENTRY_NOT_FOUND)
                }

                if (pantryEntryImageRepository.countByEntryId(entryId) >= policy.maxImagesPerEntry) {
                    return@transactional AppResult.Error(PantryEntryImageError.TOO_MANY_IMAGES)
                }

                val image =
                    pantryEntryImageRepository.create(
                        pantryEntryId = entryId,
                        content = sanitized.toContent(storageKey),
                    )

                syncService.recordChange(
                    userId,
                    SyncEntityType.PANTRY_ENTRY_IMAGE,
                    image.id,
                    SyncOperation.UPSERT,
                    originDeviceId,
                )
                AppResult.Success(image)
            }

        if (result is AppResult.Error) {
            imageStorage.deleteBestEffort(listOf(storageKey))
        }

        return result
    }

    private suspend fun checkCanAddImage(
        userId: Int,
        entryId: Int,
    ): PantryEntryImageError? =
        when {
            pantryEntryRepository.findByIdAndUserId(entryId, userId) == null -> PantryEntryImageError.ENTRY_NOT_FOUND
            pantryEntryImageRepository.countByEntryId(entryId) >= policy.maxImagesPerEntry ->
                PantryEntryImageError.TOO_MANY_IMAGES

            else -> null
        }

    override suspend fun replaceImage(
        userId: Int,
        entryId: Int,
        imageId: Int,
        rawBytes: ByteArray,
        originDeviceId: String?,
    ): AppResult<PantryEntryImage, PantryEntryImageError> {
        val (existing, sanitized, newStorageKey) =
            when (val prepared = prepareReplace(userId, entryId, imageId, rawBytes)) {
                is AppResult.Error -> return AppResult.Error(prepared.errorType)
                is AppResult.Success -> prepared.data
            }

        val updated =
            tx.transactional {
                pantryEntryImageRepository
                    .updateContent(id = imageId, entryId = entryId, content = sanitized.toContent(newStorageKey))
                    ?.also {
                        syncService.recordChange(
                            userId,
                            SyncEntityType.PANTRY_ENTRY_IMAGE,
                            it.id,
                            SyncOperation.UPSERT,
                            originDeviceId,
                        )
                    }
            }

        if (updated == null) {
            imageStorage.deleteBestEffort(listOf(newStorageKey))
            return AppResult.Error(PantryEntryImageError.NOT_FOUND)
        }

        imageStorage.deleteBestEffort(listOf(existing.storageKey))

        return AppResult.Success(updated)
    }

    private suspend fun prepareReplace(
        userId: Int,
        entryId: Int,
        imageId: Int,
        rawBytes: ByteArray,
    ): AppResult<Triple<PantryEntryImage, SanitizedImage, String>, PantryEntryImageError> {
        val existing =
            when (val result = tx.transactional { findExistingForReplace(userId, entryId, imageId) }) {
                is AppResult.Error -> return AppResult.Error(result.errorType)
                is AppResult.Success -> result.data
            }

        return when (val prepared = sanitizeAndStore(rawBytes)) {
            is AppResult.Error -> AppResult.Error(prepared.errorType)
            is AppResult.Success -> {
                val (storageKey, sanitized) = prepared.data
                AppResult.Success(Triple(existing, sanitized, storageKey))
            }
        }
    }

    private suspend fun findExistingForReplace(
        userId: Int,
        entryId: Int,
        imageId: Int,
    ): AppResult<PantryEntryImage, PantryEntryImageError> {
        if (pantryEntryRepository.findByIdAndUserId(entryId, userId) == null) {
            return AppResult.Error(PantryEntryImageError.ENTRY_NOT_FOUND)
        }

        val existing =
            pantryEntryImageRepository.findByIdAndEntryId(imageId, entryId)
                ?: return AppResult.Error(PantryEntryImageError.NOT_FOUND)

        return AppResult.Success(existing)
    }

    override suspend fun deleteImage(
        userId: Int,
        entryId: Int,
        imageId: Int,
        originDeviceId: String?,
    ): AppResult<Unit, PantryEntryImageError> {
        val deleted =
            tx.transactional {
                if (pantryEntryRepository.findByIdAndUserId(entryId, userId) == null) {
                    return@transactional AppResult.Error(PantryEntryImageError.ENTRY_NOT_FOUND)
                }

                val existing =
                    pantryEntryImageRepository.deleteByIdAndEntryId(imageId, entryId)
                        ?: return@transactional AppResult.Error(PantryEntryImageError.NOT_FOUND)

                syncService.recordChange(
                    userId,
                    SyncEntityType.PANTRY_ENTRY_IMAGE,
                    existing.id,
                    SyncOperation.DELETE,
                    originDeviceId,
                )
                AppResult.Success(existing)
            }

        return when (deleted) {
            is AppResult.Error -> deleted
            is AppResult.Success -> {
                imageStorage.deleteBestEffort(listOf(deleted.data.storageKey))
                AppResult.Success(Unit)
            }
        }
    }

    override suspend fun listImages(
        userId: Int,
        entryId: Int,
    ): AppResult<List<PantryEntryImage>, PantryEntryImageError> =
        tx.transactional {
            if (pantryEntryRepository.findByIdAndUserId(entryId, userId) == null) {
                AppResult.Error(PantryEntryImageError.ENTRY_NOT_FOUND)
            } else {
                AppResult.Success(pantryEntryImageRepository.listByEntryId(entryId))
            }
        }

    override suspend fun loadContent(
        userId: Int,
        entryId: Int,
        imageId: Int,
    ): AppResult<ImageContent, PantryEntryImageError> {
        val found =
            when (
                val result =
                    tx.transactional {
                        if (pantryEntryRepository.findByIdAndUserId(entryId, userId) == null) {
                            AppResult.Error(PantryEntryImageError.ENTRY_NOT_FOUND)
                        } else {
                            pantryEntryImageRepository
                                .findByIdAndEntryId(
                                    imageId,
                                    entryId,
                                )?.let { AppResult.Success(it) }
                                ?: AppResult.Error(PantryEntryImageError.NOT_FOUND)
                        }
                    }
            ) {
                is AppResult.Error -> return result
                is AppResult.Success -> result.data
            }

        val bytes =
            when (val result = loadBytesOrError(found.storageKey)) {
                is AppResult.Error -> return result
                is AppResult.Success -> result.data
            }

        return AppResult.Success(ImageContent(bytes = bytes, contentType = found.contentType, eTag = bytes.sha256Hex()))
    }

    private suspend fun loadBytesOrError(storageKey: String): AppResult<ByteArray, PantryEntryImageError> =
        runSuspendCatching { imageStorage.read(storageKey) }
            .fold(
                onSuccess = { bytes ->
                    bytes?.let { AppResult.Success(it) } ?: AppResult.Error(PantryEntryImageError.NOT_FOUND)
                },
                onFailure = {
                    logger.error(it) { "Failed to read image at storageKey=$storageKey" }
                    AppResult.Error(PantryEntryImageError.STORAGE_UNAVAILABLE)
                },
            )

    private suspend fun sanitizeAndStore(
        rawBytes: ByteArray,
    ): AppResult<Pair<String, SanitizedImage>, PantryEntryImageError> {
        val sanitized =
            when (val result = scanAndSanitize(rawBytes)) {
                is AppResult.Error -> return AppResult.Error(result.errorType)
                is AppResult.Success -> result.data
            }

        val storageKey = UUID.randomUUID().toString()
        val storeError = storeOrError(storageKey, sanitized.bytes)
        if (storeError != null) return AppResult.Error(storeError)

        return AppResult.Success(storageKey to sanitized)
    }

    private suspend fun storeOrError(
        key: String,
        bytes: ByteArray,
    ): PantryEntryImageError? =
        runSuspendCatching { imageStorage.store(key, bytes) }
            .fold(
                onSuccess = { null },
                onFailure = {
                    logger.error(it) { "Failed to store image at storageKey=$key" }
                    PantryEntryImageError.STORAGE_UNAVAILABLE
                },
            )

    private suspend fun scanAndSanitize(rawBytes: ByteArray): AppResult<SanitizedImage, PantryEntryImageError> {
        scanForMalware(rawBytes)?.let { return AppResult.Error(it) }
        return sanitizeOrError(rawBytes)
    }

    private suspend fun sanitizeOrError(rawBytes: ByteArray): AppResult<SanitizedImage, PantryEntryImageError> =
        when (val result = imageSanitizer.sanitize(rawBytes)) {
            is AppResult.Success -> AppResult.Success(result.data)
            is AppResult.Error -> AppResult.Error(result.errorType.toPantryEntryImageError())
        }

    private suspend fun scanForMalware(bytes: ByteArray): PantryEntryImageError? {
        if (!policy.clamAvEnabled) return null

        return when (virusScanner.scan(bytes)) {
            is VirusScanResult.Clean -> null
            is VirusScanResult.Infected -> PantryEntryImageError.MALWARE_DETECTED
            is VirusScanResult.Unavailable -> PantryEntryImageError.SCAN_UNAVAILABLE
        }
    }
}

private fun SanitizedImage.toContent(storageKey: String) =
    PantryEntryImageContent(
        storageKey = storageKey,
        contentType = contentType,
        sizeBytes = bytes.size,
        width = width,
        height = height,
    )

private fun ImageSanitizationError.toPantryEntryImageError() =
    when (this) {
        ImageSanitizationError.UNSUPPORTED_FORMAT -> PantryEntryImageError.UNSUPPORTED_FORMAT
        ImageSanitizationError.CORRUPT -> PantryEntryImageError.CORRUPT_IMAGE
        ImageSanitizationError.DIMENSIONS_TOO_LARGE -> PantryEntryImageError.DIMENSIONS_TOO_LARGE
    }
