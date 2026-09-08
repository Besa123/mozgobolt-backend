package com.shelflife.feature.pantryEntryImage

import com.shelflife.core.data.media.JavaImageSanitizer
import com.shelflife.core.data.media.LocalDiskImageStorage
import com.shelflife.core.data.security.NoOpVirusScanner
import com.shelflife.core.database.TransactionalRunner
import com.shelflife.core.domain.media.ImageStorage
import com.shelflife.core.modules.AppConfig
import com.shelflife.core.withRealDatabase
import com.shelflife.feature.pantryEntry.data.repository.PantryEntryRepositoryI
import com.shelflife.feature.pantryEntry.domain.PantryEntryRepository
import com.shelflife.feature.pantryEntry.domain.model.PantryEntryFields
import com.shelflife.feature.pantryEntry.service.PantryEntryServiceI
import com.shelflife.feature.pantryEntryImage.data.repository.PantryEntryImageRepositoryI
import com.shelflife.feature.pantryEntryImage.domain.PantryEntryImageRepository
import com.shelflife.feature.pantryEntryImage.domain.model.PantryEntryImagePolicy
import com.shelflife.feature.pantryEntryImage.service.PantryEntryImageCleanupI
import com.shelflife.feature.pantryEntryImage.service.PantryEntryImageServiceI
import com.shelflife.feature.product.data.repository.ProductRepositoryI
import com.shelflife.feature.product.service.ProductServiceI
import com.shelflife.feature.quantityUnit.data.repository.QuantityUnitRepositoryI
import com.shelflife.feature.storageLocation.data.repository.StorageLocationRepositoryI
import com.shelflife.feature.sync.data.repository.SyncRepositoryI
import com.shelflife.feature.sync.service.SyncServiceI
import java.math.BigDecimal
import java.nio.file.Files

data class PantryEntryImageTestHarness(
    val service: PantryEntryImageServiceI,
    // Wired with the *same* pantryEntryImageRepository/imageStorage as [service] above, so a
    // deleteEntry call through this real service exercises the actual PantryEntryImageCleanup
    // wiring end to end — the same interaction docs/domain/pantry.md decision 15 documents.
    val pantryEntryService: PantryEntryServiceI,
    val pantryEntryRepository: PantryEntryRepository,
    val pantryEntryImageRepository: PantryEntryImageRepository,
    val productRepository: ProductRepositoryI,
    val quantityUnitRepository: QuantityUnitRepositoryI,
    val imageStorage: ImageStorage,
    val storageDirectory: java.nio.file.Path,
    val tx: TransactionalRunner,
    val maxImagesPerEntry: Int,
) {
    /** Creates a real pantry entry (FKs and all) for [userId], seeded against real global data. */
    suspend fun seedPantryEntry(userId: Int): Int =
        tx.transactional {
            val product =
                checkNotNull(productRepository.findGlobalByName("Sajt")) { "expected seeded global product Sajt" }
            val unit =
                checkNotNull(quantityUnitRepository.findAll().find { it.name == "kg" }) { "expected seeded unit kg" }

            val entry =
                pantryEntryRepository.create(
                    userId = userId,
                    productId = product.id,
                    fields =
                        PantryEntryFields(
                            storageLocationId = null,
                            unitId = unit.id,
                            quantityAmount = BigDecimal.ONE,
                            expirationDate = null,
                            brandOrNote = null,
                        ),
                )
            entry.id
        }
}

fun withRealPantryEntryImageDatabase(
    maxImagesPerEntry: Int = 3,
    block: suspend (PantryEntryImageTestHarness) -> Unit,
) {
    withRealDatabase { _, tx ->
        val syncService = SyncServiceI(SyncRepositoryI(), tx)
        val pantryEntryRepository = PantryEntryRepositoryI()
        val pantryEntryImageRepository = PantryEntryImageRepositoryI()
        val productRepository = ProductRepositoryI()
        val productService =
            ProductServiceI(productRepository = productRepository, syncService = syncService, tx = tx)
        val quantityUnitRepository = QuantityUnitRepositoryI()
        val storageLocationRepository = StorageLocationRepositoryI()
        val storageDirectory = Files.createTempDirectory("pantry-entry-images-test")
        val imageStorage = LocalDiskImageStorage(storageDirectory.toString())
        val sanitizer =
            JavaImageSanitizer(
                AppConfig.Media(
                    localStorageDirectory = storageDirectory.toString(),
                    maxImagesPerEntry = maxImagesPerEntry,
                    maxImageDimensionPixels = 2048,
                    maxImagePixelCount = 40_000_000,
                    jpegQuality = 0.85f,
                ),
            )
        val service =
            PantryEntryImageServiceI(
                pantryEntryRepository = pantryEntryRepository,
                pantryEntryImageRepository = pantryEntryImageRepository,
                imageSanitizer = sanitizer,
                virusScanner = NoOpVirusScanner(),
                imageStorage = imageStorage,
                syncService = syncService,
                tx = tx,
                policy = PantryEntryImagePolicy(maxImagesPerEntry = maxImagesPerEntry, clamAvEnabled = false),
            )
        val pantryEntryService =
            PantryEntryServiceI(
                pantryEntryRepository = pantryEntryRepository,
                productService = productService,
                storageLocationRepository = storageLocationRepository,
                quantityUnitRepository = quantityUnitRepository,
                syncService = syncService,
                tx = tx,
                pantryEntryImageCleanup = PantryEntryImageCleanupI(pantryEntryImageRepository),
                imageStorage = imageStorage,
            )

        block(
            PantryEntryImageTestHarness(
                service = service,
                pantryEntryService = pantryEntryService,
                pantryEntryRepository = pantryEntryRepository,
                pantryEntryImageRepository = pantryEntryImageRepository,
                productRepository = productRepository,
                quantityUnitRepository = quantityUnitRepository,
                imageStorage = imageStorage,
                storageDirectory = storageDirectory,
                tx = tx,
                maxImagesPerEntry = maxImagesPerEntry,
            ),
        )
    }
}
