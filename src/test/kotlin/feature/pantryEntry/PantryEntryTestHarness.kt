package com.shelflife.feature.pantryEntry

import com.shelflife.core.database.TransactionalRunner
import com.shelflife.core.withRealDatabase
import com.shelflife.feature.pantryEntry.data.repository.PantryEntryRepositoryI
import com.shelflife.feature.pantryEntry.service.PantryEntryServiceI
import com.shelflife.feature.product.data.repository.ProductRepositoryI
import com.shelflife.feature.product.service.ProductServiceI
import com.shelflife.feature.quantityUnit.data.repository.QuantityUnitRepositoryI
import com.shelflife.feature.quantityUnit.service.QuantityUnitServiceI
import com.shelflife.feature.storageLocation.data.repository.StorageLocationRepositoryI
import com.shelflife.feature.storageLocation.service.StorageLocationServiceI

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
        val pantryEntryRepository = PantryEntryRepositoryI()
        val productRepository = ProductRepositoryI()
        val productService = ProductServiceI(productRepository = productRepository, tx = tx)
        val storageLocationRepository = StorageLocationRepositoryI()
        val storageLocationService =
            StorageLocationServiceI(storageLocationRepository = storageLocationRepository, tx = tx)
        val quantityUnitRepository = QuantityUnitRepositoryI()
        val quantityUnitService = QuantityUnitServiceI(quantityUnitRepository = quantityUnitRepository, tx = tx)
        val service =
            PantryEntryServiceI(
                pantryEntryRepository = pantryEntryRepository,
                productService = productService,
                storageLocationRepository = storageLocationRepository,
                quantityUnitRepository = quantityUnitRepository,
                tx = tx,
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
