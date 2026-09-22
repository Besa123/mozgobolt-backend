package com.mozgobolt.core.domain.validation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DisplayNameValidationTest {
    @Test
    fun `a well-formed name has no errors`() {
        assertTrue(validateDisplayName("Jane Doe", maxLength = 100).isEmpty())
    }

    @Test
    fun `a blank name is rejected`() {
        assertTrue(validateDisplayName("", maxLength = 100).isNotEmpty())
    }

    @Test
    fun `a name over the max length is rejected`() {
        assertTrue(validateDisplayName("a".repeat(101), maxLength = 100).isNotEmpty())
    }

    @Test
    fun `a name at exactly the max length is accepted`() {
        assertTrue(validateDisplayName("a".repeat(100), maxLength = 100).isEmpty())
    }

    @Test
    fun `leading or trailing whitespace is rejected`() {
        assertTrue(validateDisplayName(" Jane Doe", maxLength = 100).isNotEmpty())
        assertTrue(validateDisplayName("Jane Doe ", maxLength = 100).isNotEmpty())
    }

    @Test
    fun `markup-like characters are rejected`() {
        assertTrue(validateDisplayName("<script>x</script>", maxLength = 100).isNotEmpty())
        assertTrue(validateDisplayName("Jane & Bob", maxLength = 100).isNotEmpty())
    }

    @Test
    fun `accented characters are accepted`() {
        assertTrue(validateDisplayName("Vöröshagyma", maxLength = 100).isEmpty())
    }

    @Test
    fun `the field label is used in the returned messages`() {
        val errors = validateDisplayName("", maxLength = 100, fieldLabel = "Product name")

        assertEquals(listOf("Product name is required"), errors)
    }

    @Test
    fun `a completely invalid name produces one message per violated rule`() {
        val errors = validateDisplayName(" ${"a".repeat(101)}& ", maxLength = 100)

        assertEquals(3, errors.size)
    }
}
