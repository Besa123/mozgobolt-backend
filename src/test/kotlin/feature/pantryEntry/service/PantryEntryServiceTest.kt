package com.shelflife.feature.pantryEntry.service

import com.shelflife.core.domain.AppResult
import com.shelflife.feature.pantryEntry.domain.model.PantryEntryError
import com.shelflife.feature.pantryEntry.domain.model.PantryEntryFields
import com.shelflife.feature.pantryEntry.domain.model.ProductReference
import com.shelflife.feature.pantryEntry.domain.model.UpdateEntryOutcome
import com.shelflife.feature.product.domain.model.UnitCategory
import kotlinx.coroutines.runBlocking
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

class PantryEntryServiceTest {
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
    fun `finding an entry the caller owns by id returns it`() {
        runBlocking {
            val harness = newHarness()
            val entry = harness.pantryEntryRepository.seed(userId = 1)

            val result = harness.service.findByIdForUser(userId = 1, entryId = entry.id)

            assertEquals(entry.id, result?.id)
        }
    }

    @Test
    fun `finding an entry owned by another user returns null, never someone else's data`() {
        runBlocking {
            val harness = newHarness()
            val othersEntry = harness.pantryEntryRepository.seed(userId = 2)

            val result = harness.service.findByIdForUser(userId = 1, entryId = othersEntry.id)

            assertEquals(null, result)
        }
    }

    @Test
    fun `finding a nonexistent entry returns null`() {
        runBlocking {
            val harness = newHarness()

            val result = harness.service.findByIdForUser(userId = 1, entryId = 999)

            assertEquals(null, result)
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

            val result =
                harness.service.createEntry(
                    userId = 1,
                    product = ProductReference.New("  Sertéshús  "),
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
    fun `listing returns only the caller's own entries`() {
        runBlocking {
            val harness = newHarness()
            harness.pantryEntryRepository.seed(userId = 1)
            harness.pantryEntryRepository.seed(userId = 2)

            val results = harness.service.listForUser(userId = 1, afterId = null, limit = null).items

            assertEquals(1, results.size)
            assertEquals(1, results.single().userId)
        }
    }

    @Test
    fun `updating an entry the caller owns succeeds`() {
        runBlocking {
            val harness = newHarness()
            val unit = harness.quantityUnitRepository.seed("kg", UnitCategory.MASS, BigDecimal.ONE)
            val entry =
                harness.pantryEntryRepository.seed(
                    userId = 1,
                    unitId = unit.id,
                    quantityAmount = BigDecimal.ONE,
                )

            val result =
                harness.service.updateEntry(
                    userId = 1,
                    entryId = entry.id,
                    fields = fields(unitId = entry.unitId, quantityAmount = BigDecimal("2.5")),
                )

            result.fold(
                onSuccess = { outcome ->
                    val updated = outcome as? UpdateEntryOutcome.Updated ?: fail("expected Updated but got $outcome")
                    assertEquals(BigDecimal("2.5"), updated.entry.quantityAmount)
                },
                onError = { fail("expected success but got $it") },
            )
        }
    }

    @Test
    fun `updating an entry owned by another user is not found`() {
        runBlocking {
            val harness = newHarness()
            val entry = harness.pantryEntryRepository.seed(userId = 2)

            val result =
                harness.service.updateEntry(userId = 1, entryId = entry.id, fields = fields(unitId = entry.unitId))

            assertEquals(AppResult.Error(PantryEntryError.NOT_FOUND), result)
        }
    }

    @Test
    fun `updating another user's entry with an invalid unit is still not-found, not unit-not-found`() {
        runBlocking {
            val harness = newHarness()
            val entry = harness.pantryEntryRepository.seed(userId = 2)

            // Ownership must be checked before storageLocationId/unitId are validated — an
            // unauthorized caller must never learn anything about a resource they don't own.
            val result = harness.service.updateEntry(userId = 1, entryId = entry.id, fields = fields(unitId = 999))

            assertEquals(AppResult.Error(PantryEntryError.NOT_FOUND), result)
        }
    }

    @Test
    fun `updating a nonexistent entry is not found`() {
        runBlocking {
            val harness = newHarness()

            val result = harness.service.updateEntry(userId = 1, entryId = 999, fields = fields(unitId = 1))

            assertEquals(AppResult.Error(PantryEntryError.NOT_FOUND), result)
        }
    }

    @Test
    fun `updating quantity to zero deletes the entry instead of updating it`() {
        runBlocking {
            val harness = newHarness()
            val entry = harness.pantryEntryRepository.seed(userId = 1, quantityAmount = BigDecimal.ONE)

            val result =
                harness.service.updateEntry(
                    userId = 1,
                    entryId = entry.id,
                    fields = fields(unitId = entry.unitId, quantityAmount = BigDecimal.ZERO),
                )

            assertEquals(AppResult.Success(UpdateEntryOutcome.Deleted), result)
            assertEquals(null, harness.pantryEntryRepository.findByIdAndUserId(entry.id, 1))
        }
    }

    @Test
    fun `updating quantity below zero also deletes the entry`() {
        runBlocking {
            val harness = newHarness()
            val entry = harness.pantryEntryRepository.seed(userId = 1, quantityAmount = BigDecimal.ONE)

            val result =
                harness.service.updateEntry(
                    userId = 1,
                    entryId = entry.id,
                    fields = fields(unitId = entry.unitId, quantityAmount = BigDecimal("-1")),
                )

            assertEquals(AppResult.Success(UpdateEntryOutcome.Deleted), result)
        }
    }

    @Test
    fun `deleting via a zero quantity skips validating an otherwise-invalid storage location`() {
        runBlocking {
            val harness = newHarness()
            val entry = harness.pantryEntryRepository.seed(userId = 1, quantityAmount = BigDecimal.ONE)

            // storageLocationId 999 doesn't exist — but since qty is zero, the entry is deleted
            // and the other fields never get validated at all.
            val result =
                harness.service.updateEntry(
                    userId = 1,
                    entryId = entry.id,
                    fields = fields(unitId = entry.unitId, quantityAmount = BigDecimal.ZERO, storageLocationId = 999),
                )

            assertEquals(AppResult.Success(UpdateEntryOutcome.Deleted), result)
        }
    }

    @Test
    fun `updating to a storage location owned by another user is rejected`() {
        runBlocking {
            val harness = newHarness()
            val entry = harness.pantryEntryRepository.seed(userId = 1)
            val othersLocation = harness.storageLocationRepository.seed(userId = 2, name = "Fridge")

            val result =
                harness.service.updateEntry(
                    userId = 1,
                    entryId = entry.id,
                    fields = fields(unitId = entry.unitId, storageLocationId = othersLocation.id),
                )

            assertEquals(AppResult.Error(PantryEntryError.STORAGE_LOCATION_NOT_FOUND), result)
        }
    }

    @Test
    fun `updating to a unit that does not exist is rejected`() {
        runBlocking {
            val harness = newHarness()
            val entry = harness.pantryEntryRepository.seed(userId = 1)

            val result = harness.service.updateEntry(userId = 1, entryId = entry.id, fields = fields(unitId = 999))

            assertEquals(AppResult.Error(PantryEntryError.UNIT_NOT_FOUND), result)
        }
    }

    @Test
    fun `deleting an entry the caller owns succeeds`() {
        runBlocking {
            val harness = newHarness()
            val entry = harness.pantryEntryRepository.seed(userId = 1)

            val result = harness.service.deleteEntry(userId = 1, entryId = entry.id)

            result.fold(onSuccess = {}, onError = { fail("expected success but got $it") })
        }
    }

    @Test
    fun `deleting an entry owned by another user is not found`() {
        runBlocking {
            val harness = newHarness()
            val entry = harness.pantryEntryRepository.seed(userId = 2)

            val result = harness.service.deleteEntry(userId = 1, entryId = entry.id)

            assertEquals(AppResult.Error(PantryEntryError.NOT_FOUND), result)
        }
    }

    private fun fields(
        unitId: Int,
        storageLocationId: Int? = null,
        quantityAmount: BigDecimal = BigDecimal.ONE,
    ) = PantryEntryFields(
        storageLocationId = storageLocationId,
        unitId = unitId,
        quantityAmount = quantityAmount,
        expirationDate = null,
        brandOrNote = null,
    )
}
