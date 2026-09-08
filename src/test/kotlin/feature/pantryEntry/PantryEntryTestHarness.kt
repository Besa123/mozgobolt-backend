package com.shelflife.feature.pantryEntry

import com.shelflife.core.data.media.FakeImageStorage
import com.shelflife.core.database.TransactionalRunner
import com.shelflife.core.withRealDatabase
import com.shelflife.feature.pantryEntry.data.repository.PantryEntryRepositoryI
import com.shelflife.feature.pantryEntry.service.FakePantryEntryImageCleanup
import com.shelflife.feature.pantryEntry.service.PantryEntryServiceI
import com.shelflife.feature.product.data.repository.ProductRepositoryI
import com.shelflife.feature.product.service.ProductServiceI
import com.shelflife.feature.quantityUnit.data.repository.QuantityUnitRepositoryI
import com.shelflife.feature.quantityUnit.service.QuantityUnitServiceI
import com.shelflife.feature.storageLocation.data.repository.StorageLocationRepositoryI
import com.shelflife.feature.storageLocation.service.StorageLocationServiceI
import com.shelflife.feature.sync.data.repository.SyncRepositoryI
import com.shelflife.feature.sync.service.SyncServiceI

data class PantryEntryTestHarness(
    val service: PantryEntryServiceI,
    val pantryEntryRepository: PantryEntryRepositoryI,
    val productRepository: ProductRepositoryI,
    val storageLocationRepository: StorageLocationRepositoryI,
    val quantityUnitRepository: QuantityUnitRepositoryI,
    val productService: ProductServiceI,
    val storageLocationService: StorageLocationServiceI,
    val quantityUnitService: QuantityUnitServiceI,
    val tx: TransactionalRunner,
)

fun withRealPantryEntryDatabase(block: suspend (PantryEntryTestHarness) -> Unit) {
    withRealDatabase { _, tx ->
        val syncService = SyncServiceI(SyncRepositoryI(), tx)
        val pantryEntryRepository = PantryEntryRepositoryI()
        val productRepository = ProductRepositoryI()
        val productService = ProductServiceI(productRepository = productRepository, syncService = syncService, tx = tx)
        val storageLocationRepository = StorageLocationRepositoryI()
        val storageLocationService =
            StorageLocationServiceI(
                storageLocationRepository = storageLocationRepository,
                syncService = syncService,
                tx = tx,
            )
        val quantityUnitRepository = QuantityUnitRepositoryI()
        val quantityUnitService = QuantityUnitServiceI(quantityUnitRepository = quantityUnitRepository, tx = tx)
        val service =
            PantryEntryServiceI(
                pantryEntryRepository = pantryEntryRepository,
                productService = productService,
                storageLocationRepository = storageLocationRepository,
                quantityUnitRepository = quantityUnitRepository,
                syncService = syncService,
                tx = tx,
                pantryEntryImageCleanup = FakePantryEntryImageCleanup(),
                imageStorage = FakeImageStorage(),
            )

        block(
            PantryEntryTestHarness(
                service = service,
                pantryEntryRepository = pantryEntryRepository,
                productRepository = productRepository,
                storageLocationRepository = storageLocationRepository,
                quantityUnitRepository = quantityUnitRepository,
                productService = productService,
                storageLocationService = storageLocationService,
                quantityUnitService = quantityUnitService,
                tx = tx,
            ),
        )
    }
}
