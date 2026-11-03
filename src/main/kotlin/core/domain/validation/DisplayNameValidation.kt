/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.core.domain.validation

private val INVALID_CHARACTERS = Regex("[<>\"'&;]")

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
