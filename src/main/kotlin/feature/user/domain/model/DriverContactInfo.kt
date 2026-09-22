package com.mozgobolt.feature.user.domain.model

/** A driver's optional contact methods, resolved together in one lookup for a vehicle's response. */
data class DriverContactInfo(
    val phoneNumber: String?,
    val whatsappNumber: String?,
    val viberNumber: String?,
    val messengerUsername: String?,
)
