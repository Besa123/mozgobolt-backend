package com.mozgobolt.feature.sync.domain.model

data class SyncHint(
    val eventId: Long,
    val originDeviceId: String?,
) {
    fun isOwnAction(deviceId: String?): Boolean = deviceId != null && originDeviceId == deviceId
}
