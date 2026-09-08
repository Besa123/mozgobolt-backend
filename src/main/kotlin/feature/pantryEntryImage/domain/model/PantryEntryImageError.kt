package com.shelflife.feature.pantryEntryImage.domain.model

enum class PantryEntryImageError {
    ENTRY_NOT_FOUND,
    NOT_FOUND,
    TOO_MANY_IMAGES,
    UNSUPPORTED_FORMAT,
    DIMENSIONS_TOO_LARGE,
    CORRUPT_IMAGE,
    MALWARE_DETECTED,
    SCAN_UNAVAILABLE,
    STORAGE_UNAVAILABLE,
}
