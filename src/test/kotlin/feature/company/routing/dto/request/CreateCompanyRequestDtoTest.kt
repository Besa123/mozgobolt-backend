package com.mozgobolt.feature.company.routing.dto.request

import kotlin.test.Test
import kotlin.test.assertTrue

class CreateCompanyRequestDtoTest {
    @Test
    fun `a well-formed name has no validation errors`() {
        assertTrue(CreateCompanyRequestDto(name = "FamilyFrost").validate().isEmpty())
    }

    @Test
    fun `a blank name is rejected`() {
        assertTrue(CreateCompanyRequestDto(name = "").validate().isNotEmpty())
    }

    @Test
    fun `a whitespace-only name is rejected`() {
        assertTrue(CreateCompanyRequestDto(name = "   ").validate().isNotEmpty())
    }

    @Test
    fun `a name with leading or trailing whitespace is rejected`() {
        assertTrue(CreateCompanyRequestDto(name = " FamilyFrost").validate().isNotEmpty())
        assertTrue(CreateCompanyRequestDto(name = "FamilyFrost ").validate().isNotEmpty())
    }

    @Test
    fun `a name over one hundred characters is rejected`() {
        assertTrue(CreateCompanyRequestDto(name = "a".repeat(101)).validate().isNotEmpty())
    }

    @Test
    fun `a name at exactly one hundred characters is accepted`() {
        assertTrue(CreateCompanyRequestDto(name = "a".repeat(100)).validate().isEmpty())
    }

    @Test
    fun `a name containing markup-like characters is rejected`() {
        assertTrue(CreateCompanyRequestDto(name = "<script>FamilyFrost</script>").validate().isNotEmpty())
    }

    @Test
    fun `a name with accented Hungarian characters is accepted`() {
        // This app's real target audience — a validator regex tightened later for some other
        // reason must not accidentally start rejecting legitimate Hungarian business names.
        assertTrue(CreateCompanyRequestDto(name = "Kürtőskalács Kft").validate().isEmpty())
    }
}
