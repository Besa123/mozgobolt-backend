package com.shelflife.feature.pantryEntry.service

import com.shelflife.core.domain.AppResult
import com.shelflife.feature.pantryEntry.domain.model.PantryEntryError
import com.shelflife.feature.pantryEntry.domain.model.ProductReference
import com.shelflife.feature.product.domain.model.UnitCategory
import kotlinx.coroutines.runBlocking
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

class PantryEntryServiceCreateTest {
    @Test
    fun `creating an entry succeeds when the product and unit are visible and no location was given`() {
        runBlocking {
            val harness = newHarness()
            val product = harness.productRepository.seed(name = "Sonka", ownerId = null)
            val unit = harness.quantityUnitRepository.seed("kg", UnitCategory.MASS, BigDecimal.ONE)

            val result =
                harness.service.createEntry(
                    userId = 1,
                    product = ProductReference.Existing(product.id),
                    fields = fields(unitId = unit.id, quantityAmount = BigDecimal("1.5")),
                )

            result.fold(
                onSuccess = { assertEquals(BigDecimal("1.5"), it.quantityAmount) },
                onError = { fail("expected success but got $it") },
            )
        }
    }

    @Test
    fun `creating an entry for a product not visible to the caller is rejected`() {
        runBlocking {
            val harness = newHarness()
            val othersProduct = harness.productRepository.seed(name = "Sonka", ownerId = 2)
            val unit = harness.quantityUnitRepository.seed("kg", UnitCategory.MASS, BigDecimal.ONE)

            val result =
                harness.service.createEntry(
                    userId = 1,
                    product = ProductReference.Existing(othersProduct.id),
                    fields = fields(unitId = unit.id),
                )

            assertEquals(AppResult.Error(PantryEntryError.PRODUCT_NOT_VISIBLE), result)
        }
    }

    @Test
    fun `creating an entry with a storage location owned by another user is rejected`() {
        runBlocking {
            val harness = newHarness()
            val product = harness.productRepository.seed(name = "Sonka", ownerId = null)
            val unit = harness.quantityUnitRepository.seed("kg", UnitCategory.MASS, BigDecimal.ONE)
            val othersLocation = harness.storageLocationRepository.seed(userId = 2, name = "Fridge")

            val result =
                harness.service.createEntry(
                    userId = 1,
                    product = ProductReference.Existing(product.id),
                    fields = fields(unitId = unit.id, storageLocationId = othersLocation.id),
                )

            assertEquals(AppResult.Error(PantryEntryError.STORAGE_LOCATION_NOT_FOUND), result)
        }
    }

    @Test
    fun `creating an entry with a unit that does not exist is rejected`() {
        runBlocking {
            val harness = newHarness()
            val product = harness.productRepository.seed(name = "Sonka", ownerId = null)

            val result =
                harness.service.createEntry(
                    userId = 1,
                    product = ProductReference.Existing(product.id),
                    fields = fields(unitId = 999),
                )

            assertEquals(AppResult.Error(PantryEntryError.UNIT_NOT_FOUND), result)
        }
    }

    @Test
    fun `creating an entry always inserts a new row, even if one already matches exactly`() {
        runBlocking {
            val harness = newHarness()
            val product = harness.productRepository.seed(name = "Sonka", ownerId = null)
            val unit = harness.quantityUnitRepository.seed("kg", UnitCategory.MASS, BigDecimal.ONE)

            harness.service.createEntry(
                userId = 1,
                product = ProductReference.Existing(product.id),
                fields = fields(unitId = unit.id),
            )
            harness.service.createEntry(
                userId = 1,
                product = ProductReference.Existing(product.id),
                fields = fields(unitId = unit.id),
            )

            assertEquals(
                2,
                harness.service
                    .listForUser(userId = 1, afterId = null, limit = null)
                    .items.size,
            )
        }
    }

    @Test
    fun `creating an entry with a brand-new product name creates the product and the entry atomically`() {
        runBlocking {
            val harness = newHarness()
            val unit = harness.quantityUnitRepository.seed("kg", UnitCategory.MASS, BigDecimal.ONE)

            // `newProductName` arrives here already whitespace-checked by `validateDisplayName`
            // (which rejects, rather than trims, leading/trailing whitespace) at the DTO layer —
            // see CreatePantryEntryRequestDto's validation.
            val result =
                harness.service.createEntry(
                    userId = 1,
                    product = ProductReference.New("Sertéshús"),
                    fields = fields(unitId = unit.id),
                )

            // productName here is the fake repository's synthetic placeholder, not the real
            // join — the point of this test is that the *product* actually got created, which
            // is checked below; the real display-field join is only provable at the integration
            // level (PantryEntryIntegrationTest).
            result.fold(onSuccess = {}, onError = { fail("expected success but got $it") })
            val created = harness.productRepository.findAllOwnedBy(1).single()
            assertEquals("Sertéshús", created.name)
        }
    }

    @Test
    fun `creating an entry with a new product name that collides with an existing global product is rejected`() {
        runBlocking {
            val harness = newHarness()
            harness.productRepository.seed(name = "Sonka", ownerId = null)
            val unit = harness.quantityUnitRepository.seed("kg", UnitCategory.MASS, BigDecimal.ONE)

            val result =
                harness.service.createEntry(
                    userId = 1,
                    product = ProductReference.New("sonka"),
                    fields = fields(unitId = unit.id),
                )

            assertEquals(AppResult.Error(PantryEntryError.PRODUCT_NAME_ALREADY_EXISTS), result)
        }
    }

    @Test
    fun `creating an entry with a new product name that collides with the caller's own product is rejected`() {
        runBlocking {
            val harness = newHarness()
            harness.productRepository.seed(name = "Sonka", ownerId = 1)
            val unit = harness.quantityUnitRepository.seed("kg", UnitCategory.MASS, BigDecimal.ONE)

            val result =
                harness.service.createEntry(
                    userId = 1,
                    product = ProductReference.New("Sonka"),
                    fields = fields(unitId = unit.id),
                )

            assertEquals(AppResult.Error(PantryEntryError.PRODUCT_NAME_ALREADY_EXISTS), result)
        }
    }

    @Test
    fun `two different users can each create a new product with the same name via this flow`() {
        runBlocking {
            val harness = newHarness()
            val unit = harness.quantityUnitRepository.seed("kg", UnitCategory.MASS, BigDecimal.ONE)

            val first =
                harness.service.createEntry(
                    userId = 1,
                    product = ProductReference.New("Sertéshús"),
                    fields = fields(unit.id),
                )
            val second =
                harness.service.createEntry(
                    userId = 2,
                    product = ProductReference.New("Sertéshús"),
                    fields = fields(unit.id),
                )

            first.fold(onSuccess = {}, onError = { fail("expected success but got $it") })
            second.fold(onSuccess = {}, onError = { fail("expected success but got $it") })
        }
    }

    @Test
    fun `creating an entry with a new product name and an invalid unit does not create an orphaned product`() {
        // Regression test for atomicity: fields must be validated before the new product is
        // created, since a returned AppResult.Error does not roll back the surrounding
        // transaction (see the comment in PantryEntryServiceI.createEntry).
        runBlocking {
            val harness = newHarness()

            val result =
                harness.service.createEntry(
                    userId = 1,
                    product = ProductReference.New("Sertéshús"),
                    fields = fields(unitId = 999),
                )

            assertEquals(AppResult.Error(PantryEntryError.UNIT_NOT_FOUND), result)
            assertEquals(emptyList(), harness.productRepository.findAllOwnedBy(1))
        }
    }
}
