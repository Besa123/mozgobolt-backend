package com.shelflife.feature.storageLocation.data.mapper

import com.shelflife.feature.product.data.database.StorageLocationEntity
import com.shelflife.feature.storageLocation.domain.model.StorageLocation

fun StorageLocationEntity.toStorageLocation() =
    StorageLocation(
        id = id.value,
        userId = user.id.value,
        name = name,
    )
