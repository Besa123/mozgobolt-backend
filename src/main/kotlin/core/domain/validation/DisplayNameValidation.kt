package com.mozgobolt.core.domain.validation

private val INVALID_CHARACTERS = Regex("[<>\"'&;]")
const val DISPLAY_NAME_MAX_LENGTH = 100

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
