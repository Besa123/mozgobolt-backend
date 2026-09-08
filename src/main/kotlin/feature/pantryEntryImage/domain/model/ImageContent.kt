package com.shelflife.feature.pantryEntryImage.domain.model

@Suppress("UseDataClass")
class ImageContent(
    val bytes: ByteArray,
    val contentType: String,
    val eTag: String,
)
