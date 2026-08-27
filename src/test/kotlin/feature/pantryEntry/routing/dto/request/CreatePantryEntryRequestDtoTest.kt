package com.shelflife.feature.pantryEntry.routing.dto.request

import kotlin.test.Test
import kotlin.test.assertTrue

class CreatePantryEntryRequestDtoTest {
    @Test
    fun `a well-formed request has no validation errors`() {
        val request = CreatePantryEntryRequestDto(productId = 1, unitId = 1, quantityAmount = 1.5)

        assertTrue(request.validate().isEmpty())
    }

    @Test
    fun `a request with only the required fields has no validation errors`() {
        val request = CreatePantryEntryRequestDto(productId = 1, unitId = 1, quantityAmount = 1.0)

        assertTrue(request.validate().isEmpty())
    }

    @Test
    fun `a zero quantity is rejected`() {
        val request = CreatePantryEntryRequestDto(productId = 1, unitId = 1, quantityAmount = 0.0)

        assertTrue(request.validate().isNotEmpty())
    }

    @Test
    fun `a negative quantity is rejected`() {
        val request = CreatePantryEntryRequestDto(productId = 1, unitId = 1, quantityAmount = -1.0)

        assertTrue(request.validate().isNotEmpty())
    }

    @Test
    fun `a valid ISO expiration date is accepted`() {
        val request =
            CreatePantryEntryRequestDto(productId = 1, unitId = 1, quantityAmount = 1.0, expirationDate = "2026-09-01")

        assertTrue(request.validate().isEmpty())
    }

    @Test
    fun `a malformed expiration date is rejected`() {
        val request =
            CreatePantryEntryRequestDto(productId = 1, unitId = 1, quantityAmount = 1.0, expirationDate = "not-a-date")

        assertTrue(request.validate().isNotEmpty())
    }

    @Test
    fun `a note over 255 characters is rejected`() {
        val request =
            CreatePantryEntryRequestDto(productId = 1, unitId = 1, quantityAmount = 1.0, brandOrNote = "a".repeat(256))

        assertTrue(request.validate().isNotEmpty())
    }

    @Test
    fun `a note at exactly 255 characters is accepted`() {
        val request =
            CreatePantryEntryRequestDto(productId = 1, unitId = 1, quantityAmount = 1.0, brandOrNote = "a".repeat(255))

        assertTrue(request.validate().isEmpty())
    }

    @Test
    fun `providing only newProductName has no validation errors`() {
        val request = CreatePantryEntryRequestDto(newProductName = "Sertéshús", unitId = 1, quantityAmount = 1.0)

        assertTrue(request.validate().isEmpty())
    }

    @Test
    fun `providing neither productId nor newProductName is rejected`() {
        val request = CreatePantryEntryRequestDto(unitId = 1, quantityAmount = 1.0)

        assertTrue(request.validate().isNotEmpty())
    }

    @Test
    fun `providing both productId and newProductName is rejected`() {
        val request =
            CreatePantryEntryRequestDto(productId = 1, newProductName = "Sertéshús", unitId = 1, quantityAmount = 1.0)

        assertTrue(request.validate().isNotEmpty())
    }

    @Test
    fun `a blank newProductName is treated as not provided, not as a valid new-product request`() {
        val request = CreatePantryEntryRequestDto(newProductName = "   ", unitId = 1, quantityAmount = 1.0)

        assertTrue(request.validate().isNotEmpty())
    }

    @Test
    fun `a newProductName with leading or trailing whitespace is rejected`() {
        val request = CreatePantryEntryRequestDto(newProductName = " Sertéshús", unitId = 1, quantityAmount = 1.0)

        assertTrue(request.validate().isNotEmpty())
    }

    @Test
    fun `a newProductName with markup-like characters is rejected`() {
        val request =
            CreatePantryEntryRequestDto(newProductName = "<script>x</script>", unitId = 1, quantityAmount = 1.0)

        assertTrue(request.validate().isNotEmpty())
    }
}
