package com.mozgobolt.feature.vehicleTracking.domain.model

/** A spatial index's cell address (an H3 cell today) — opaque outside [CellIndexer]. */
@JvmInline
value class CellId(
    val value: String,
)
