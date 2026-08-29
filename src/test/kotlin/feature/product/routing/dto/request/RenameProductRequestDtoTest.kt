package com.shelflife.feature.product.routing.dto.request

import kotlin.test.Test
import kotlin.test.assertTrue

class RenameProductRequestDtoTest {
    @Test
    fun `a well-formed name has no validation errors`() {
        val request = RenameProductRequestDto(name = "Sonka")

        assertTrue(request.validate().isEmpty())
    }

    @Test
    fun `a blank name is rejected`() {
        val request = RenameProductRequestDto(name = "")

        assertTrue(request.validate().isNotEmpty())
    }

    @Test
    fun `a name over one hundred characters is rejected`() {
        val request = RenameProductRequestDto(name = "a".repeat(101))

        assertTrue(request.validate().isNotEmpty())
    }

    @Test
    fun `a name at exactly one hundred characters is accepted`() {
        val request = RenameProductRequestDto(name = "a".repeat(100))

        assertTrue(request.validate().isEmpty())
    }

    @Test
    fun `a name with leading or trailing whitespace is rejected`() {
        val request = RenameProductRequestDto(name = " Sonka ")

        assertTrue(request.validate().isNotEmpty())
    }

    @Test
    fun `a name containing markup-like characters is rejected`() {
        val request = RenameProductRequestDto(name = "<script>Sonka</script>")

        assertTrue(request.validate().isNotEmpty())
    }
}
