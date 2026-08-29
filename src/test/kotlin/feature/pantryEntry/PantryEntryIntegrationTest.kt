package com.shelflife.feature.pantryEntry

import com.shelflife.core.domain.AppResult
import com.shelflife.core.skipIfNoDocker
import com.shelflife.feature.pantryEntry.domain.model.PantryEntryError
import com.shelflife.feature.pantryEntry.domain.model.PantryEntryFields
import com.shelflife.feature.pantryEntry.domain.model.ProductReference
import com.shelflife.feature.pantryEntry.domain.model.UpdateEntryOutcome
import java.math.BigDecimal
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * PantryEntryRepositoryI.findAllByUserId builds a raw 4-way JOIN + ORDER BY directly against
 * Exposed — none of this (including whether it even compiles to valid SQL) is visible to the
 * fake-repository-backed unit tests, so it's only proven here.
 */
class PantryEntryIntegrationTest {
    @Test
    fun `creating an entry against real seeded global data succeeds and joins the display fields`() {
        skipIfNoDocker()

        withRealPantryEntryDatabase { harness ->
            val product = harness.globalProduct("Sajt")
            val unit = harness.unit("kg")

            val result =
                harness.service.createEntry(
                    userId = 1,
                    product = ProductReference.Existing(product.id),
                    fields = fields(unitId = unit.id, quantityAmount = BigDecimal("0.5")),
                )

            val entry = (result as AppResult.Success).data
            assertEquals("Sajt", entry.productName)
            assertEquals("kg", entry.unitName)
        }
    }

    @Test
    fun `creating an entry for a product owned by another user is rejected`() {
        skipIfNoDocker()

        withRealPantryEntryDatabase { harness ->
            val othersProduct = harness.createOthersPrivateProduct(userId = 2, name = "Kolbász")
            val unit = harness.unit("kg")

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
        skipIfNoDocker()

        withRealPantryEntryDatabase { harness ->
            val product = harness.globalProduct("Sajt")
            val unit = harness.unit("kg")
            val othersLocation = harness.createStorageLocation(userId = 2, name = "Fridge")

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
    fun `creating an entry with a nonexistent unit is rejected`() {
        skipIfNoDocker()

        withRealPantryEntryDatabase { harness ->
            val product = harness.globalProduct("Sajt")

            val result =
                harness.service.createEntry(
                    userId = 1,
                    product = ProductReference.Existing(product.id),
                    fields = fields(unitId = 9999),
                )

            assertEquals(AppResult.Error(PantryEntryError.UNIT_NOT_FOUND), result)
        }
    }

    @Test
    fun `a valid storage location is joined correctly into the response`() {
        skipIfNoDocker()

        withRealPantryEntryDatabase { harness ->
            val product = harness.globalProduct("Sajt")
            val unit = harness.unit("kg")
            val location = harness.createStorageLocation(userId = 1, name = "Fridge")

            val result =
                harness.service.createEntry(
                    userId = 1,
                    product = ProductReference.Existing(product.id),
                    fields = fields(unitId = unit.id, storageLocationId = location.id),
                )

            val entry = (result as AppResult.Success).data
            assertEquals("Fridge", entry.storageLocationName)
        }
    }

    @Test
    fun `creating the same entry twice always inserts two separate rows`() {
        skipIfNoDocker()

        withRealPantryEntryDatabase { harness ->
            val product = harness.globalProduct("Sajt")
            val unit = harness.unit("kg")

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
    fun `listing returns entries in insertion order, not sorted or grouped for display`() {
        skipIfNoDocker()

        withRealPantryEntryDatabase { harness ->
            val apple = harness.globalProduct("Alma")
            val cheese = harness.globalProduct("Sajt")
            val unit = harness.unit("db")

            // Insertion order deliberately doesn't match name or expiration-date order — sorting
            // and grouping for display is a frontend concern (see docs/domain/pantry.md).
            harness.service.createEntry(1, ProductReference.Existing(cheese.id), fields(unit.id, expirationDate = null))
            harness.service.createEntry(
                1,
                ProductReference.Existing(apple.id),
                fields(unit.id, expirationDate = LocalDate.of(2026, 12, 1)),
            )
            harness.service.createEntry(
                1,
                ProductReference.Existing(apple.id),
                fields(unit.id, expirationDate = LocalDate.of(2026, 1, 1)),
            )

            val results = harness.service.listForUser(userId = 1, afterId = null, limit = null).items

            assertEquals(
                listOf(
                    "Sajt" to null,
                    "Alma" to LocalDate.of(2026, 12, 1),
                    "Alma" to LocalDate.of(2026, 1, 1),
                ),
                results.map { it.productName to it.expirationDate },
            )
        }
    }

    @Test
    fun `paging with afterId resumes exactly where the previous page left off`() {
        skipIfNoDocker()

        withRealPantryEntryDatabase { harness ->
            val product = harness.globalProduct("Sajt")
            val unit = harness.unit("db")

            val created =
                (1..5).map {
                    val entry =
                        harness.service.createEntry(
                            userId = 1,
                            product = ProductReference.Existing(product.id),
                            fields = fields(unit.id),
                        )

                    (entry as AppResult.Success).data
                }

            val page1 = harness.service.listForUser(userId = 1, afterId = null, limit = 2)
            assertEquals(created.take(2).map { it.id }, page1.items.map { it.id })
            assertEquals(created[1].id, page1.nextCursor)

            val page2 = harness.service.listForUser(userId = 1, afterId = page1.nextCursor, limit = 2)
            assertEquals(created.drop(2).take(2).map { it.id }, page2.items.map { it.id })
            assertEquals(created[3].id, page2.nextCursor)

            val page3 = harness.service.listForUser(userId = 1, afterId = page2.nextCursor, limit = 2)
            assertEquals(listOf(created[4].id), page3.items.map { it.id })
            assertEquals(null, page3.nextCursor)
        }
    }

    @Test
    fun `paging through everything with a small page size matches an unpaged fetch exactly`() {
        skipIfNoDocker()

        withRealPantryEntryDatabase { harness ->
            val apple = harness.globalProduct("Alma")
            val cheese = harness.globalProduct("Sajt")
            val unit = harness.unit("db")

            repeat(7) { index ->
                val product = if (index % 2 == 0) apple else cheese
                harness.service.createEntry(
                    1,
                    ProductReference.Existing(product.id),
                    fields(unit.id, expirationDate = if (index % 3 == 0) null else LocalDate.of(2026, 1, 1 + index)),
                )
            }

            val everythingAtOnce = harness.service.listForUser(userId = 1, afterId = null, limit = 100).items

            val collected = mutableListOf<Int>()
            var cursor = harness.service.listForUser(userId = 1, afterId = null, limit = 2)
            collected += cursor.items.map { it.id }
            while (cursor.nextCursor != null) {
                cursor = harness.service.listForUser(userId = 1, afterId = cursor.nextCursor, limit = 2)
                collected += cursor.items.map { it.id }
            }

            assertEquals(everythingAtOnce.map { it.id }, collected)
            assertEquals(7, collected.size)
            assertEquals(collected.size, collected.distinct().size, "no item should appear twice across pages")
        }
    }

    @Test
    fun `a successful, non-deleting update is persisted and re-joined correctly`() {
        skipIfNoDocker()

        withRealPantryEntryDatabase { harness ->
            val product = harness.globalProduct("Sajt")
            val kg = harness.unit("kg")
            val dkg = harness.unit("dkg")
            val location = harness.createStorageLocation(userId = 1, name = "Fridge")
            val created =
                harness.service.createEntry(
                    userId = 1,
                    product = ProductReference.Existing(product.id),
                    fields = fields(unitId = kg.id, storageLocationId = location.id, quantityAmount = BigDecimal("1")),
                )
            val entry = (created as AppResult.Success).data

            val result =
                harness.service.updateEntry(
                    userId = 1,
                    entryId = entry.id,
                    fields =
                        fields(
                            unitId = dkg.id,
                            storageLocationId = location.id,
                            quantityAmount = BigDecimal("2.5"),
                        ),
                )

            val updated = (result as AppResult.Success).data as UpdateEntryOutcome.Updated
            assertEquals(BigDecimal("2.50"), updated.entry.quantityAmount)
            assertEquals("dkg", updated.entry.unitName)
            assertEquals("Fridge", updated.entry.storageLocationName)
        }
    }

    @Test
    fun `updating storageLocationId to null clears a previously-set location in the real database`() {
        skipIfNoDocker()

        withRealPantryEntryDatabase { harness ->
            val product = harness.globalProduct("Sajt")
            val unit = harness.unit("kg")
            val location = harness.createStorageLocation(userId = 1, name = "Fridge")
            val created =
                harness.service.createEntry(
                    userId = 1,
                    product = ProductReference.Existing(product.id),
                    fields = fields(unitId = unit.id, storageLocationId = location.id),
                )
            val entry = (created as AppResult.Success).data

            val result =
                harness.service.updateEntry(
                    userId = 1,
                    entryId = entry.id,
                    fields = fields(unitId = unit.id, storageLocationId = null),
                )

            val updated = (result as AppResult.Success).data as UpdateEntryOutcome.Updated
            assertEquals(null, updated.entry.storageLocationId)
            assertEquals(null, updated.entry.storageLocationName)
        }
    }

    @Test
    fun `an afterId past every existing row returns an empty page from the real database`() {
        skipIfNoDocker()

        withRealPantryEntryDatabase { harness ->
            val product = harness.globalProduct("Sajt")
            val unit = harness.unit("kg")
            val created =
                harness.service.createEntry(
                    userId = 1,
                    product = ProductReference.Existing(product.id),
                    fields = fields(unit.id),
                )
            val entry = (created as AppResult.Success).data

            val page = harness.service.listForUser(userId = 1, afterId = entry.id + 1000, limit = 50)

            assertEquals(emptyList(), page.items)
            assertEquals(null, page.nextCursor)
        }
    }

    @Test
    fun `updating quantity to zero deletes the row from the real database`() {
        skipIfNoDocker()

        withRealPantryEntryDatabase { harness ->
            val product = harness.globalProduct("Sajt")
            val unit = harness.unit("kg")
            val entry =
                harness.service.createEntry(
                    userId = 1,
                    product = ProductReference.Existing(product.id),
                    fields = fields(unit.id),
                )

            val created = (entry as AppResult.Success).data
            val result =
                harness.service.updateEntry(
                    userId = 1,
                    entryId = created.id,
                    fields = fields(unitId = unit.id, quantityAmount = BigDecimal.ZERO),
                )

            assertEquals(AppResult.Success(UpdateEntryOutcome.Deleted), result)
            assertTrue(
                harness.service.listForUser(userId = 1, afterId = null, limit = null).items.none {
                    it.id ==
                        created.id
                },
            )
        }
    }

    @Test
    fun `deleting an entry twice returns not found the second time, not a crash`() {
        skipIfNoDocker()

        withRealPantryEntryDatabase { harness ->
            val product = harness.globalProduct("Sajt")
            val unit = harness.unit("kg")
            val entry =
                harness.service.createEntry(
                    userId = 1,
                    product = ProductReference.Existing(product.id),
                    fields = fields(unit.id),
                )

            val created = (entry as AppResult.Success).data
            val first = harness.service.deleteEntry(userId = 1, entryId = created.id)
            val second = harness.service.deleteEntry(userId = 1, entryId = created.id)

            assertTrue(first is AppResult.Success)
            assertEquals(AppResult.Error(PantryEntryError.NOT_FOUND), second)
        }
    }

    @Test
    fun `creating an entry with a brand-new product name creates both atomically, joined correctly`() {
        skipIfNoDocker()

        withRealPantryEntryDatabase { harness ->
            val unit = harness.unit("kg")

            val result =
                harness.service.createEntry(
                    userId = 1,
                    product = ProductReference.New("Sertéshús"),
                    fields = fields(unit.id),
                )

            val entry = (result as AppResult.Success).data
            assertEquals("Sertéshús", entry.productName)
            assertEquals(listOf("Sertéshús"), harness.ownedProductNames(1))
        }
    }

    @Test
    fun `a new product name colliding with a seeded global product is rejected without creating anything`() {
        skipIfNoDocker()

        withRealPantryEntryDatabase { harness ->
            val unit = harness.unit("kg")

            // "Sajt" ships as a seeded global product (V3 migration).
            val result =
                harness.service.createEntry(
                    userId = 1,
                    product = ProductReference.New("sajt"),
                    fields = fields(unit.id),
                )

            assertEquals(AppResult.Error(PantryEntryError.PRODUCT_NAME_ALREADY_EXISTS), result)
            assertTrue(harness.ownedProductNames(1).isEmpty())
            assertTrue(
                harness.service
                    .listForUser(userId = 1, afterId = null, limit = null)
                    .items
                    .isEmpty(),
            )
        }
    }

    private suspend fun PantryEntryTestHarness.globalProduct(name: String) =
        checkNotNull(productService.search(userId = 1, query = name).find { it.name == name }) {
            "expected a seeded global product named \"$name\""
        }

    private suspend fun PantryEntryTestHarness.unit(name: String) =
        checkNotNull(quantityUnitService.listAll().find { it.name == name }) {
            "expected a seeded quantity unit named \"$name\""
        }

    private suspend fun PantryEntryTestHarness.createStorageLocation(
        userId: Int,
        name: String,
    ) = (storageLocationService.createForUser(userId, name) as AppResult.Success).data

    private suspend fun PantryEntryTestHarness.createOthersPrivateProduct(
        userId: Int,
        name: String,
    ) = (productService.createPrivateProduct(userId, name, null, null) as AppResult.Success).data

    private suspend fun PantryEntryTestHarness.ownedProductNames(userId: Int) =
        productService.listOwnedBy(userId).map { it.name }

    private fun fields(
        unitId: Int,
        storageLocationId: Int? = null,
        quantityAmount: BigDecimal = BigDecimal.ONE,
        expirationDate: LocalDate? = null,
    ) = PantryEntryFields(
        storageLocationId = storageLocationId,
        unitId = unitId,
        quantityAmount = quantityAmount,
        expirationDate = expirationDate,
        brandOrNote = null,
    )
}
