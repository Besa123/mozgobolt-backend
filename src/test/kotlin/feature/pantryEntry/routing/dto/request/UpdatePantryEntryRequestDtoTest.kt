package com.shelflife.feature.pantryEntry.routing.dto.request

import kotlin.test.Test
import kotlin.test.assertTrue

class UpdatePantryEntryRequestDtoTest {
    @Test
    fun `a well-formed request has no validation errors`() {
        val request = UpdatePantryEntryRequestDto(unitId = 1, quantityAmount = 1.5)

        assertTrue(request.validate().isEmpty())
    }

    @Test
    fun `zero quantity is accepted — it means delete, not an error`() {
        val request = UpdatePantryEntryRequestDto(unitId = 1, quantityAmount = 0.0)

        assertTrue(request.validate().isEmpty())
    }

    @Test
    fun `a negative quantity is rejected`() {
        val request = UpdatePantryEntryRequestDto(unitId = 1, quantityAmount = -1.0)

        assertTrue(request.validate().isNotEmpty())
    }

    @Test
    fun `an explicit null storage location is valid — it means no location`() {
        val request = UpdatePantryEntryRequestDto(storageLocationId = null, unitId = 1, quantityAmount = 1.0)

        assertTrue(request.validate().isEmpty())
    }

    @Test
    fun `a malformed expiration date is rejected`() {
        val request = UpdatePantryEntryRequestDto(unitId = 1, quantityAmount = 1.0, expirationDate = "not-a-date")

        assertTrue(request.validate().isNotEmpty())
    }

    @Test
    fun `a note over 255 characters is rejected`() {
        val request = UpdatePantryEntryRequestDto(unitId = 1, quantityAmount = 1.0, brandOrNote = "a".repeat(256))

        assertTrue(request.validate().isNotEmpty())
    }
}
