package com.mozgobolt.feature.user.routing.dto.response

import com.mozgobolt.feature.user.domain.model.UserContactInfo
import kotlinx.serialization.Serializable

@Serializable
data class ContactInfoResponseDto(
    val phoneNumber: String?,
    val phoneNumberVisible: Boolean,
    val whatsappNumber: String?,
    val whatsappVisible: Boolean,
    val viberNumber: String?,
    val viberVisible: Boolean,
    val messengerUsername: String?,
    val messengerVisible: Boolean,
)

fun UserContactInfo.toResponseDto() =
    ContactInfoResponseDto(
        phoneNumber = phoneNumber,
        phoneNumberVisible = phoneNumberVisible,
        whatsappNumber = whatsappNumber,
        whatsappVisible = whatsappVisible,
        viberNumber = viberNumber,
        viberVisible = viberVisible,
        messengerUsername = messengerUsername,
        messengerVisible = messengerVisible,
    )
