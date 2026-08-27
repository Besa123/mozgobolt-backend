package com.shelflife.core.domain.validation

private val INVALID_CHARACTERS = Regex("[<>\"'&;]")

/**
 * Shared rules for a user-supplied display name (user name, product name, ...): required,
 * length-bounded, no leading/trailing whitespace, no markup-like characters. [fieldLabel] is
 * capitalized as it appears in the returned messages, e.g. "Name is required".
 */
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
