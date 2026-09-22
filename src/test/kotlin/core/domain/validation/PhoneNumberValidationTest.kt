package com.mozgobolt.core.domain.validation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PhoneNumberValidationTest {
    @Test
    fun `null is accepted — a phone number is always optional`() {
        assertTrue(validatePhoneNumber(null).isEmpty())
    }

    @Test
    fun `a blank string is rejected — omit the field entirely instead`() {
        assertTrue(validatePhoneNumber("").isNotEmpty())
        assertTrue(validatePhoneNumber("   ").isNotEmpty())
    }

    @Test
    fun `a Hungarian mobile number in international format is accepted`() {
        assertTrue(validatePhoneNumber("+36201234567").isEmpty())
    }

    @Test
    fun `a Hungarian mobile number with the domestic trunk prefix is accepted`() {
        assertTrue(validatePhoneNumber("06201234567").isEmpty())
    }

    @Test
    fun `a Hungarian mobile number with human formatting is accepted`() {
        assertTrue(validatePhoneNumber("+36 20 123 4567").isEmpty())
        assertTrue(validatePhoneNumber("06-20-123-4567").isEmpty())
    }

    @Test
    fun `a Hungarian Budapest landline number is accepted`() {
        assertTrue(validatePhoneNumber("+36 1 234 5678").isEmpty())
    }

    @Test
    fun `a valid but non-Hungarian number is rejected for being the wrong country, not for being malformed`() {
        // A real, validly-formatted US number (Google's own libphonenumber example) — this must
        // fail specifically because it's not Hungarian, exercising isValidNumberForRegion's
        // region check rather than parse failure.
        assertTrue(validatePhoneNumber("+14155552671").isNotEmpty())
    }

    @Test
    fun `structurally invalid text is rejected`() {
        assertTrue(validatePhoneNumber("call-me-maybe").isNotEmpty())
    }

    @Test
    fun `too few digits for any Hungarian number plan is rejected`() {
        assertTrue(validatePhoneNumber("123456").isNotEmpty())
    }

    @Test
    fun `a Hungarian-looking prefix with too few trailing digits is rejected`() {
        assertTrue(validatePhoneNumber("+3620123").isNotEmpty())
    }

    @Test
    fun `normalize returns null for null or blank`() {
        assertNull(normalizePhoneNumber(null))
        assertNull(normalizePhoneNumber(""))
        assertNull(normalizePhoneNumber("   "))
    }

    @Test
    fun `normalize returns null for an invalid or non-Hungarian number`() {
        assertNull(normalizePhoneNumber("call-me-maybe"))
        assertNull(normalizePhoneNumber("+14155552671"))
    }

    @Test
    fun `normalize collapses every equivalent input format to the same E164 string`() {
        val fromInternational = normalizePhoneNumber("+36201234567")
        val fromDomesticTrunk = normalizePhoneNumber("06201234567")
        val fromHumanFormatting = normalizePhoneNumber("+36 20 123 4567")
        val fromDashes = normalizePhoneNumber("06-20-123-4567")

        assertEquals("+36201234567", fromInternational)
        assertEquals(fromInternational, fromDomesticTrunk)
        assertEquals(fromInternational, fromHumanFormatting)
        assertEquals(fromInternational, fromDashes)
    }

    @Test
    fun `international validation accepts a valid Hungarian number too`() {
        assertTrue(validateInternationalPhoneNumber("+36201234567", "WhatsApp number").isEmpty())
    }

    @Test
    fun `international validation accepts a valid non-Hungarian number, unlike the HU-locked validator`() {
        assertTrue(validateInternationalPhoneNumber("+14155552671", "WhatsApp number").isEmpty())
        assertTrue(validateInternationalPhoneNumber("+421901234567", "WhatsApp number").isEmpty()) // Slovakia
    }

    @Test
    fun `international validation rejects structurally invalid text`() {
        assertTrue(validateInternationalPhoneNumber("call-me-maybe", "Viber number").isNotEmpty())
    }

    @Test
    fun `international validation accepts null and rejects blank`() {
        assertTrue(validateInternationalPhoneNumber(null, "Viber number").isEmpty())
        assertTrue(validateInternationalPhoneNumber("", "Viber number").isNotEmpty())
    }

    @Test
    fun `international normalize accepts a non-Hungarian number to E164`() {
        assertEquals("+14155552671", normalizeInternationalPhoneNumber("+14155552671"))
    }

    @Test
    fun `international normalize returns null for null, blank, or invalid`() {
        assertNull(normalizeInternationalPhoneNumber(null))
        assertNull(normalizeInternationalPhoneNumber(""))
        assertNull(normalizeInternationalPhoneNumber("call-me-maybe"))
    }
}
