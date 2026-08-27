package com.shelflife.feature.product.routing.dto.request

import com.shelflife.feature.product.domain.model.UnitCategory
import kotlin.test.Test
import kotlin.test.assertTrue

class CreateProductRequestDtoTest {
    @Test
    fun `a well-formed request has no validation errors`() {
        val request =
            CreateProductRequestDto(name = "Sonka", defaultLifespanDays = 5, defaultUnitCategory = UnitCategory.MASS)

        assertTrue(request.validate().isEmpty())
    }

    @Test
    fun `a request with only a name has no validation errors`() {
        val request = CreateProductRequestDto(name = "Sonka")

        assertTrue(request.validate().isEmpty())
    }

    @Test
    fun `a blank name is rejected`() {
        val request = CreateProductRequestDto(name = "")

        assertTrue(request.validate().isNotEmpty())
    }

    @Test
    fun `a name over one hundred characters is rejected`() {
        val request = CreateProductRequestDto(name = "a".repeat(101))

        assertTrue(request.validate().isNotEmpty())
    }

    @Test
    fun `a name at exactly one hundred characters is accepted`() {
        val request = CreateProductRequestDto(name = "a".repeat(100))

        assertTrue(request.validate().isEmpty())
    }

    @Test
    fun `a name with leading or trailing whitespace is rejected`() {
        val request = CreateProductRequestDto(name = " Sonka ")

        assertTrue(request.validate().isNotEmpty())
    }

    @Test
    fun `a name containing markup-like characters is rejected`() {
        val request = CreateProductRequestDto(name = "<script>Sonka</script>")

        assertTrue(request.validate().isNotEmpty())
    }

    @Test
    fun `a negative default lifespan is rejected`() {
        val request = CreateProductRequestDto(name = "Sonka", defaultLifespanDays = -1)

        assertTrue(request.validate().isNotEmpty())
    }

    @Test
    fun `a default lifespan of zero is accepted`() {
        val request = CreateProductRequestDto(name = "Sonka", defaultLifespanDays = 0)

        assertTrue(request.validate().isEmpty())
    }

    @Test
    fun `an absurdly large default lifespan is rejected`() {
        val request = CreateProductRequestDto(name = "Sonka", defaultLifespanDays = 100_000)

        assertTrue(request.validate().isNotEmpty())
    }
}
