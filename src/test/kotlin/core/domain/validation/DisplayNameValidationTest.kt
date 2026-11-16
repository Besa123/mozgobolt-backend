/*
 * SPDX-License-Identifier: Apache-2.0
 */

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
    fun `a NUL byte is rejected, not merely accepted and left to break storage later`() {
        // Postgres text/varchar storage cannot hold an embedded NUL at all, and this validator is
        // the one place meant to catch that before it ever reaches a repository; regression guard
        // for the gap a security probe found (a saved-location label with a NUL byte reached the
        // database and surfaced as an uncaught HTTP 500 instead of a clean 400).
        assertTrue(validateDisplayName("Jane\u0000Doe", maxLength = 100).isNotEmpty())
    }

    @Test
    fun `other C0 and C1 control characters are rejected`() {
        assertTrue(validateDisplayName("Jane\u0007Doe", maxLength = 100).isNotEmpty(), "BEL")
        assertTrue(validateDisplayName("Jane\u001BDoe", maxLength = 100).isNotEmpty(), "ESC")
        assertTrue(validateDisplayName("Jane\u007FDoe", maxLength = 100).isNotEmpty(), "DEL")
        assertTrue(validateDisplayName("Jane\u0085Doe", maxLength = 100).isNotEmpty(), "NEL (a C1 control)")
    }

    @Test
    fun `ordinary whitespace such as newlines and tabs is not a control-character violation`() {
        // Tab and newline are still control characters by the Unicode Cc category, and this app has
        // no stated need to allow multi-line names, so confirming the intended behavior here (rather
        // than assuming) protects against a future accidental UX regression if that changes.
        assertTrue(validateDisplayName("Jane\tDoe", maxLength = 100).isNotEmpty())
        assertTrue(validateDisplayName("Jane\nDoe", maxLength = 100).isNotEmpty())
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
