package com.shelflife.core.domain.validation

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IsoDateValidationTest {
    @Test
    fun `a well-formed ISO date is valid`() {
        assertTrue(isValidIsoDate("2024-03-15"))
    }

    @Test
    fun `a leap day on a leap year is valid`() {
        assertTrue(isValidIsoDate("2024-02-29"))
    }

    @Test
    fun `the same day on a non-leap year is invalid`() {
        assertFalse(isValidIsoDate("2023-02-29"))
    }

    @Test
    fun `an empty string is invalid`() {
        assertFalse(isValidIsoDate(""))
    }

    @Test
    fun `a blank string is invalid`() {
        assertFalse(isValidIsoDate("   "))
    }

    @Test
    fun `a month past twelve is invalid`() {
        assertFalse(isValidIsoDate("2024-13-01"))
    }

    @Test
    fun `a day that doesn't exist in the given month is invalid`() {
        assertFalse(isValidIsoDate("2024-02-30"))
    }

    @Test
    fun `a day of zero is invalid`() {
        assertFalse(isValidIsoDate("2024-01-00"))
    }

    @Test
    fun `a non-ISO slash-separated format is invalid`() {
        assertFalse(isValidIsoDate("02/30/2024"))
    }

    @Test
    fun `a date-time string is invalid, since expiration_date has no time component`() {
        assertFalse(isValidIsoDate("2024-03-15T00:00:00"))
    }

    @Test
    fun `leading whitespace around an otherwise valid date is invalid`() {
        assertFalse(isValidIsoDate(" 2024-03-15"))
    }

    @Test
    fun `trailing whitespace around an otherwise valid date is invalid`() {
        assertFalse(isValidIsoDate("2024-03-15 "))
    }

    @Test
    fun `a completely non-date string is invalid`() {
        assertFalse(isValidIsoDate("not-a-date"))
    }
}
