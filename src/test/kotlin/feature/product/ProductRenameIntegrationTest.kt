package com.shelflife.feature.product

import com.shelflife.core.domain.AppResult
import com.shelflife.feature.product.domain.model.ProductError
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * ProductRepositoryI.renamePrivate() has no "updateIgnore" to fall back on (unlike create's
 * insertIgnore), so it catches the real unique-violation SQLState from Postgres directly. That's
 * invisible to the FakeProductRepository-backed unit tests — only a real DB proves it works
 * instead of surfacing as a 500.
 *
 * None of these tests rename *to* "Sonka" unless the test is specifically about the global-name
 * collision — "Sonka" ships as a seeded global product (V3 migration), so using it as an
 * incidental target name elsewhere would silently change what the test actually exercises.
 */
class ProductRenameIntegrationTest {
    @Test
    fun `renaming to a name that collides with a seeded global product returns a clean error, not a 500`() {
        skipIfNoDocker()

        withRealProductDatabase { harness ->
            val created = harness.service.createPrivateProduct(1, "Snoka", null, null)
            val productId = (created as AppResult.Success).data.id

            // "Sajt" ships as a seeded global product (V3 migration).
            val result = harness.service.renameProduct(userId = 1, productId = productId, newName = "  sajt  ")

            assertEquals(AppResult.Error(ProductError.DUPLICATE_NAME), result)
        }
    }

    @Test
    fun `renaming to a name that collides with another of the caller's own products returns a clean error`() {
        skipIfNoDocker()

        withRealProductDatabase { harness ->
            harness.service.createPrivateProduct(1, "Kolbász", null, null)
            val toRename = harness.service.createPrivateProduct(1, "Szalonna", null, null)
            val productId = (toRename as AppResult.Success).data.id

            val result = harness.service.renameProduct(userId = 1, productId = productId, newName = "Kolbász")

            assertEquals(AppResult.Error(ProductError.DUPLICATE_NAME), result)
        }
    }

    @Test
    fun `a successful rename is visible to a subsequent search`() {
        skipIfNoDocker()

        withRealProductDatabase { harness ->
            val created = harness.service.createPrivateProduct(1, "Snoka", null, null)
            val productId = (created as AppResult.Success).data.id

            harness.service.renameProduct(userId = 1, productId = productId, newName = "Kolbász")
            val results = harness.service.search(userId = 1, query = "Kolbász")

            assertEquals(listOf("Kolbász"), results.map { it.name })
        }
    }

    @Test
    fun `renaming another user's private product leaves it untouched`() {
        skipIfNoDocker()

        withRealProductDatabase { harness ->
            val created = harness.service.createPrivateProduct(2, "Snoka", null, null)
            val productId = (created as AppResult.Success).data.id

            val result = harness.service.renameProduct(userId = 1, productId = productId, newName = "Kolbász")
            val stillThere = harness.service.search(userId = 2, query = "Snoka")

            assertEquals(AppResult.Error(ProductError.NOT_FOUND), result)
            assertEquals(listOf("Snoka"), stillThere.map { it.name })
        }
    }
}
