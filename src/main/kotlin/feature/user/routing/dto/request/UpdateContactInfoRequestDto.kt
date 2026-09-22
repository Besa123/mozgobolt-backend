package com.mozgobolt.feature.user.routing.dto.request

import com.mozgobolt.core.domain.validation.ValidatedRequest
import com.mozgobolt.core.domain.validation.validateInternationalPhoneNumber
import com.mozgobolt.core.domain.validation.validateMessengerUsername
import com.mozgobolt.core.domain.validation.validatePhoneNumber
import com.mozgobolt.feature.user.domain.model.UserContactInfo
import kotlinx.serialization.Serializable

/**
 * Full-replace, not a partial patch — the client always sends every field's current desired
 * state, so `null` unambiguously means "remove this" rather than "leave unchanged".
 */
@Serializable
data class UpdateContactInfoRequestDto(
    val phoneNumber: String? = null,
    val phoneNumberVisible: Boolean = true,
    val whatsappNumber: String? = null,
    val whatsappVisible: Boolean = true,
    val viberNumber: String? = null,
    val viberVisible: Boolean = true,
    val messengerUsername: String? = null,
    val messengerVisible: Boolean = true,
) : ValidatedRequest {
    override fun validate() =
        buildList {
            addAll(validatePhoneNumber(phoneNumber))
            addAll(validateInternationalPhoneNumber(whatsappNumber, "WhatsApp number"))
            addAll(validateInternationalPhoneNumber(viberNumber, "Viber number"))
            addAll(validateMessengerUsername(messengerUsername))
        }
}

fun UpdateContactInfoRequestDto.toDomain() =
    UserContactInfo(
        phoneNumber = phoneNumber,
        phoneNumberVisible = phoneNumberVisible,
        whatsappNumber = whatsappNumber,
        whatsappVisible = whatsappVisible,
        viberNumber = viberNumber,
        viberVisible = viberVisible,
        messengerUsername = messengerUsername,
        messengerVisible = messengerVisible,
    )
