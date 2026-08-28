package com.shelflife.feature.sync.domain.model

data class SyncPage(
    val events: List<SyncEvent>,
    val nextCursor: Long,
)
