/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.core.domain.validation

// Also rejects C0/C1 control characters (including NUL, \u0000): Postgres's text/varchar storage
// cannot hold an embedded NUL byte at all, and the other control characters serve no legitimate
// purpose in a display name. Without this, such a character passes this validator and only fails
// later at the database layer, surfacing as an uncaught exception (HTTP 500) instead of a clean
// 400 — exactly the gap a disposable-local-instance security probe suite found in `label` fields.
private val INVALID_CHARACTERS = Regex("[<>\"'&;\\p{Cc}]")

/** Maximum accepted display-name length. */
const val DISPLAY_NAME_MAX_LENGTH = 100

/** Returns validation messages for [name] under the supplied length and character rules. */
fun validateDisplayName(
    name: String,
    maxLength: Int,
    fieldLabel: String = "Name",
): List<String> =
    buildList {
        if (name.isBlank()) add("$fieldLabel is required")
        if (name.length > maxLength) add("$fieldLabel must be $maxLength characters or less")
        if (name.trim() != name) add("$fieldLabel must not have leading/trailing spaces")
        if (name.contains(INVALID_CHARACTERS)) add("$fieldLabel contains invalid characters")
    }
