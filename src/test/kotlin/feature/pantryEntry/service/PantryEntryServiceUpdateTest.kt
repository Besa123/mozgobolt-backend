package com.shelflife.feature.pantryEntry.service

import com.shelflife.core.domain.AppResult
import com.shelflife.feature.pantryEntry.domain.model.PantryEntryError
import com.shelflife.feature.pantryEntry.domain.model.UpdateEntryOutcome
import com.shelflife.feature.product.domain.model.UnitCategory
import kotlinx.coroutines.runBlocking
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

class PantryEntryServiceUpdateTest {
    @Test
    fun `updating an entry with storageLocationId null clears a previously-set location`() {
        runBlocking {
            val harness = newHarness()
            val unit = harness.quantityUnitRepository.seed("kg", UnitCategory.MASS, BigDecimal.ONE)
            val location = harness.storageLocationRepository.seed(userId = 1, name = "Fridge")
            val entry =
                harness.pantryEntryRepository.seed(userId = 1, unitId = unit.id, storageLocationId = location.id)

            val result =
                harness.service.updateEntry(
                    userId = 1,
                    entryId = entry.id,
                    fields = fields(unitId = entry.unitId, storageLocationId = null),
                )

            result.fold(
                onSuccess = { outcome ->
                    val updated = outcome as? UpdateEntryOutcome.Updated ?: fail("expected Updated but got $outcome")
                    assertEquals(null, updated.entry.storageLocationId)
                },
                onError = { fail("expected success but got $it") },
            )
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
}
