package com.shelflife.feature.product.service

import com.shelflife.core.domain.AppResult
import com.shelflife.feature.product.domain.model.ProductError
import com.shelflife.feature.product.domain.model.UnitCategory
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

class ProductServiceTest {
    @Test
    fun `creating a private product succeeds and trims the name`() {
        runBlocking {
            val harness = newHarness()

            val result =
                harness.service.createPrivateProduct(
                    userId = 1,
                    name = "  Sonka  ",
                    defaultLifespanDays = 5,
                    defaultUnitCategory = UnitCategory.MASS,
                )

            result.fold(
                onSuccess = { assertEquals("Sonka", it.name) },
                onError = { fail("expected success but got $it") },
            )
        }
    }

    @Test
    fun `creating a product that collides with a global one is rejected`() {
        runBlocking {
            val harness = newHarness()
            harness.repository.seed(name = "Sonka", ownerId = null)

            val result =
                harness.service.createPrivateProduct(
                    userId = 1,
                    name = "Sonka",
                    defaultLifespanDays = null,
                    defaultUnitCategory = null,
                )

            assertEquals(AppResult.Error(ProductError.DUPLICATE_NAME), result)
        }
    }

    @Test
    fun `creating a product that collides case-insensitively with the user's own is rejected`() {
        runBlocking {
            val harness = newHarness()
            harness.service.createPrivateProduct(
                userId = 1,
                name = "Sonka",
                defaultLifespanDays = null,
                defaultUnitCategory = null,
            )

            val result =
                harness.service.createPrivateProduct(
                    userId = 1,
                    name = "sonka",
                    defaultLifespanDays = null,
                    defaultUnitCategory = null,
                )

            assertEquals(AppResult.Error(ProductError.DUPLICATE_NAME), result)
        }
    }

    @Test
    fun `the same name is not a collision across two different users`() {
        runBlocking {
            val harness = newHarness()
            harness.service.createPrivateProduct(
                userId = 1,
                name = "Sonka",
                defaultLifespanDays = null,
                defaultUnitCategory = null,
            )

            val result =
                harness.service.createPrivateProduct(
                    userId = 2,
                    name = "Sonka",
                    defaultLifespanDays = null,
                    defaultUnitCategory = null,
                )

            result.fold(onSuccess = {}, onError = { fail("expected success but got $it") })
        }
    }

    @Test
    fun `search returns global and the caller's own private products, but not another user's`() {
        runBlocking {
            val harness = newHarness()
            harness.repository.seed(name = "Sajt", ownerId = null)
            harness.repository.seed(name = "Sonka Lidl", ownerId = 1)
            harness.repository.seed(name = "Sonka Spar", ownerId = 2)

            val results = harness.service.search(userId = 1, query = "son")

            assertEquals(listOf("Sonka Lidl"), results.map { it.name })
        }
    }

    @Test
    fun `a blank search query returns nothing rather than the full dictionary`() {
        runBlocking {
            val harness = newHarness()
            harness.repository.seed(name = "Sajt", ownerId = null)

            val results = harness.service.search(userId = 1, query = "   ")

            assertEquals(emptyList(), results)
        }
    }

    @Test
    fun `search results are ordered alphabetically`() {
        runBlocking {
            val harness = newHarness()
            harness.repository.seed(name = "Sonka Spar", ownerId = 1)
            harness.repository.seed(name = "Sonka Lidl", ownerId = 1)
            harness.repository.seed(name = "Sonka Aldi", ownerId = 1)

            val results = harness.service.search(userId = 1, query = "sonka")

            assertEquals(listOf("Sonka Aldi", "Sonka Lidl", "Sonka Spar"), results.map { it.name })
        }
    }

    @Test
    fun `renaming a product the caller owns succeeds and trims the name`() {
        runBlocking {
            val harness = newHarness()
            val product = harness.repository.seed(name = "Snoka", ownerId = 1)

            val result = harness.service.renameProduct(userId = 1, productId = product.id, newName = "  Sonka  ")

            result.fold(
                onSuccess = { assertEquals("Sonka", it.name) },
                onError = { fail("expected success but got $it") },
            )
        }
    }

    @Test
    fun `renaming a product owned by another user is not found`() {
        runBlocking {
            val harness = newHarness()
            val product = harness.repository.seed(name = "Snoka", ownerId = 2)

            val result = harness.service.renameProduct(userId = 1, productId = product.id, newName = "Sonka")

            assertEquals(AppResult.Error(ProductError.NOT_FOUND), result)
        }
    }

    @Test
    fun `renaming another user's product to a name that collides globally still returns not-found, not duplicate`() {
        runBlocking {
            val harness = newHarness()
            harness.repository.seed(name = "Sajt", ownerId = null)
            val product = harness.repository.seed(name = "Snoka", ownerId = 2)

            // Ownership must be checked before the new name is validated against anything —
            // an unauthorized caller should never learn a name they can't use is taken globally.
            val result = harness.service.renameProduct(userId = 1, productId = product.id, newName = "Sajt")

            assertEquals(AppResult.Error(ProductError.NOT_FOUND), result)
        }
    }

    @Test
    fun `renaming a global product is not found, not a permission error`() {
        runBlocking {
            val harness = newHarness()
            val product = harness.repository.seed(name = "Sajt", ownerId = null)

            val result = harness.service.renameProduct(userId = 1, productId = product.id, newName = "Sajt Új")

            assertEquals(AppResult.Error(ProductError.NOT_FOUND), result)
        }
    }

    @Test
    fun `renaming a nonexistent product is not found`() {
        runBlocking {
            val harness = newHarness()

            val result = harness.service.renameProduct(userId = 1, productId = 999, newName = "Sonka")

            assertEquals(AppResult.Error(ProductError.NOT_FOUND), result)
        }
    }

    @Test
    fun `renaming to a name that collides with another of the caller's products is rejected`() {
        runBlocking {
            val harness = newHarness()
            harness.repository.seed(name = "Sajt", ownerId = 1)
            val toRename = harness.repository.seed(name = "Snoka", ownerId = 1)

            val result = harness.service.renameProduct(userId = 1, productId = toRename.id, newName = "sajt")

            assertEquals(AppResult.Error(ProductError.DUPLICATE_NAME), result)
        }
    }

    @Test
    fun `renaming to a name that shadows an existing global product is rejected`() {
        runBlocking {
            val harness = newHarness()
            harness.repository.seed(name = "Sajt", ownerId = null)
            val toRename = harness.repository.seed(name = "Snoka", ownerId = 1)

            val result = harness.service.renameProduct(userId = 1, productId = toRename.id, newName = "sajt")

            assertEquals(AppResult.Error(ProductError.DUPLICATE_NAME), result)
        }
    }

    @Test
    fun `renaming a product to its own current name is a no-op success`() {
        runBlocking {
            val harness = newHarness()
            val product = harness.repository.seed(name = "Sonka", ownerId = 1)

            val result = harness.service.renameProduct(userId = 1, productId = product.id, newName = "Sonka")

            result.fold(onSuccess = {}, onError = { fail("expected success but got $it") })
        }
    }

    @Test
    fun `listing owned products returns only the caller's own, not global or another user's`() {
        runBlocking {
            val harness = newHarness()
            harness.repository.seed(name = "Sajt", ownerId = null)
            harness.repository.seed(name = "Sonka", ownerId = 1)
            harness.repository.seed(name = "Kolbász", ownerId = 2)

            val results = harness.service.listOwnedBy(userId = 1)

            assertEquals(listOf("Sonka"), results.map { it.name })
        }
    }

    @Test
    fun `listing owned products for a user with none returns an empty list`() {
        runBlocking {
            val harness = newHarness()

            val results = harness.service.listOwnedBy(userId = 1)

            assertEquals(emptyList(), results)
        }
    }
}
