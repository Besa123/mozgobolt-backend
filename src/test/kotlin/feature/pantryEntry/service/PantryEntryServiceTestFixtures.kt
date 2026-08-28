package com.shelflife.feature.pantryEntry.service

import com.shelflife.core.database.TransactionalRunner
import com.shelflife.feature.pantryEntry.domain.PantryEntryRepository
import com.shelflife.feature.pantryEntry.domain.model.PantryEntry
import com.shelflife.feature.pantryEntry.domain.model.PantryEntryFields
import com.shelflife.feature.pantryEntry.domain.model.PantryEntryPage
import com.shelflife.feature.product.domain.model.UnitCategory
import com.shelflife.feature.product.service.FakeProductRepository
import com.shelflife.feature.product.service.ProductServiceI
import com.shelflife.feature.quantityUnit.domain.QuantityUnitRepository
import com.shelflife.feature.quantityUnit.domain.model.QuantityUnit
import com.shelflife.feature.storageLocation.service.FakeStorageLocationRepository
import com.shelflife.feature.sync.service.NoOpSyncService
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

data class Harness(
    val service: PantryEntryServiceI,
    val pantryEntryRepository: FakePantryEntryRepository,
    val productRepository: FakeProductRepository,
    val storageLocationRepository: FakeStorageLocationRepository,
    val quantityUnitRepository: FakeQuantityUnitRepository,
)

fun newHarness(): Harness {
    val pantryEntryRepository = FakePantryEntryRepository()
    val productRepository = FakeProductRepository()
    // Real ProductServiceI, not a hand-stubbed fake — createEntry's "new product" path calls into
    // its actual dedup/trim logic, and that's exactly what's worth exercising here.
    val productService =
        ProductServiceI(
            productRepository = productRepository,
            syncService = NoOpSyncService(),
            tx = NoopTransactionalRunner(),
        )
    val storageLocationRepository = FakeStorageLocationRepository()
    val quantityUnitRepository = FakeQuantityUnitRepository()
    val service =
        PantryEntryServiceI(
            pantryEntryRepository = pantryEntryRepository,
            productService = productService,
            storageLocationRepository = storageLocationRepository,
            quantityUnitRepository = quantityUnitRepository,
            syncService = NoOpSyncService(),
            tx = NoopTransactionalRunner(),
        )
    return Harness(service, pantryEntryRepository, productRepository, storageLocationRepository, quantityUnitRepository)
}

class NoopTransactionalRunner : TransactionalRunner {
    override suspend fun <T> transactional(block: suspend () -> T): T = block()
}

class FakeQuantityUnitRepository : QuantityUnitRepository {
    private val unitsById = mutableMapOf<Int, QuantityUnit>()
    private var nextId = 1

    fun seed(
        name: String,
        category: UnitCategory,
        multiplier: BigDecimal,
    ): QuantityUnit {
        val unit = QuantityUnit(id = nextId++, name = name, category = category, multiplier = multiplier)
        unitsById[unit.id] = unit
        return unit
    }

    override suspend fun findAll(): List<QuantityUnit> = unitsById.values.sortedBy { it.name }

    override suspend fun findById(id: Int): QuantityUnit? = unitsById[id]
}

// The display fields (productName, unitName, ...) here are synthetic placeholders — the mapper
// that actually populates them from the DB is trivial and already covered by the integration
// test, so it isn't worth re-modeling the join here too.
class FakePantryEntryRepository : PantryEntryRepository {
    private val entriesById = mutableMapOf<Int, PantryEntry>()
    private var nextId = 1

    fun seed(
        userId: Int,
        productId: Int = 1,
        storageLocationId: Int? = null,
        unitId: Int = 1,
        quantityAmount: BigDecimal = BigDecimal.ONE,
        expirationDate: LocalDate? = null,
        brandOrNote: String? = null,
    ): PantryEntry {
        val entry =
            PantryEntry(
                id = nextId++,
                userId = userId,
                productId = productId,
                productName = "Product $productId",
                storageLocationId = storageLocationId,
                storageLocationName = storageLocationId?.let { "Location $it" },
                unitId = unitId,
                unitName = "Unit $unitId",
                unitCategory = UnitCategory.MASS,
                unitMultiplier = BigDecimal.ONE,
                quantityAmount = quantityAmount,
                expirationDate = expirationDate,
                brandOrNote = brandOrNote,
                createdAt = Instant.now(),
            )
        entriesById[entry.id] = entry
        return entry
    }

    override suspend fun create(
        userId: Int,
        productId: Int,
        fields: PantryEntryFields,
    ): PantryEntry =
        seed(
            userId = userId,
            productId = productId,
            storageLocationId = fields.storageLocationId,
            unitId = fields.unitId,
            quantityAmount = fields.quantityAmount,
            expirationDate = fields.expirationDate,
            brandOrNote = fields.brandOrNote,
        )

    override suspend fun findPageByUserId(
        userId: Int,
        afterId: Int?,
        limit: Int,
    ): PantryEntryPage {
        // Insertion order (id ASC) — no display ordering here, that's a frontend concern.
        val sorted = entriesById.values.filter { it.userId == userId }.sortedBy { it.id }
        val window = sorted.filter { afterId == null || it.id > afterId }.take(limit + 1)

        val hasNextPage = window.size > limit
        val page = window.take(limit)
        val nextCursor = if (hasNextPage) page.last().id else null

        return PantryEntryPage(items = page, nextCursor = nextCursor)
    }

    override suspend fun findByIdAndUserId(
        id: Int,
        userId: Int,
    ): PantryEntry? = entriesById[id]?.takeIf { it.userId == userId }

    override suspend fun update(
        id: Int,
        userId: Int,
        fields: PantryEntryFields,
    ): PantryEntry? {
        val existing = entriesById[id]?.takeIf { it.userId == userId } ?: return null
        val updated =
            existing.copy(
                storageLocationId = fields.storageLocationId,
                unitId = fields.unitId,
                quantityAmount = fields.quantityAmount,
                expirationDate = fields.expirationDate,
                brandOrNote = fields.brandOrNote,
            )
        entriesById[id] = updated
        return updated
    }

    override suspend fun deleteByIdAndUserId(
        id: Int,
        userId: Int,
    ): Boolean {
        entriesById[id]?.takeIf { it.userId == userId } ?: return false
        entriesById.remove(id)
        return true
    }
}
