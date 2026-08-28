package com.shelflife.feature.product.service

import com.shelflife.core.database.TransactionalRunner
import com.shelflife.feature.product.domain.ProductRepository
import com.shelflife.feature.product.domain.model.Product
import com.shelflife.feature.product.domain.model.RenameOutcome
import com.shelflife.feature.product.domain.model.UnitCategory
import com.shelflife.feature.sync.service.NoOpSyncService

data class Harness(
    val service: ProductServiceI,
    val repository: FakeProductRepository,
)

fun newHarness(): Harness {
    val repository = FakeProductRepository()
    val service =
        ProductServiceI(productRepository = repository, syncService = NoOpSyncService(), tx = NoopTransactionalRunner())
    return Harness(service, repository)
}

class NoopTransactionalRunner : TransactionalRunner {
    override suspend fun <T> transactional(block: suspend () -> T): T = block()
}

class FakeProductRepository : ProductRepository {
    private val productsById = mutableMapOf<Int, Product>()
    private var nextId = 1

    fun seed(
        name: String,
        ownerId: Int? = null,
        defaultLifespanDays: Int? = null,
        defaultUnitCategory: UnitCategory? = null,
    ): Product {
        val product =
            Product(
                id = nextId++,
                name = name,
                ownerId = ownerId,
                defaultLifespanDays = defaultLifespanDays,
                defaultUnitCategory = defaultUnitCategory,
            )
        productsById[product.id] = product
        return product
    }

    override suspend fun search(
        userId: Int,
        query: String,
        limit: Int,
    ): List<Product> =
        productsById.values
            .filter { (it.ownerId == null || it.ownerId == userId) && it.name.contains(query, ignoreCase = true) }
            .sortedBy { it.name.lowercase() }
            .take(limit)

    override suspend fun findGlobalByName(name: String): Product? =
        productsById.values.firstOrNull { it.ownerId == null && it.name.equals(name, ignoreCase = true) }

    override suspend fun findAllOwnedBy(userId: Int): List<Product> =
        productsById.values.filter { it.ownerId == userId }.sortedBy { it.name.lowercase() }

    override suspend fun findVisibleById(
        userId: Int,
        productId: Int,
    ): Product? = productsById[productId]?.takeIf { it.ownerId == null || it.ownerId == userId }

    override suspend fun existsOwnedBy(
        userId: Int,
        productId: Int,
    ): Boolean = productsById[productId]?.ownerId == userId

    override suspend fun createPrivate(
        userId: Int,
        name: String,
        defaultLifespanDays: Int?,
        defaultUnitCategory: UnitCategory?,
    ): Product? {
        val collides = productsById.values.any { it.ownerId == userId && it.name.equals(name, ignoreCase = true) }
        if (collides) return null

        return seed(
            name = name,
            ownerId = userId,
            defaultLifespanDays = defaultLifespanDays,
            defaultUnitCategory = defaultUnitCategory,
        )
    }

    override suspend fun renamePrivate(
        userId: Int,
        productId: Int,
        newName: String,
    ): RenameOutcome {
        val product = productsById[productId] ?: return RenameOutcome.NotFound
        if (product.ownerId != userId) return RenameOutcome.NotFound

        val collides =
            productsById.values.any {
                it.id != productId && it.ownerId == userId && it.name.equals(newName, ignoreCase = true)
            }
        if (collides) return RenameOutcome.DuplicateName

        val renamed = product.copy(name = newName)
        productsById[productId] = renamed
        return RenameOutcome.Renamed(renamed)
    }
}
