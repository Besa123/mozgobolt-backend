package com.shelflife.feature.pantryEntryImage.data.mapper

import com.shelflife.feature.pantryEntryImage.data.database.PantryEntryImagesTable
import com.shelflife.feature.pantryEntryImage.domain.model.PantryEntryImage
import org.jetbrains.exposed.v1.core.ResultRow

fun ResultRow.toPantryEntryImage() =
    PantryEntryImage(
        id = this[PantryEntryImagesTable.id].value,
        pantryEntryId = this[PantryEntryImagesTable.pantryEntryId].value,
        storageKey = this[PantryEntryImagesTable.storageKey],
        contentType = this[PantryEntryImagesTable.contentType],
        sizeBytes = this[PantryEntryImagesTable.sizeBytes],
        width = this[PantryEntryImagesTable.width],
        height = this[PantryEntryImagesTable.height],
        createdAt = this[PantryEntryImagesTable.createdAt],
    )
