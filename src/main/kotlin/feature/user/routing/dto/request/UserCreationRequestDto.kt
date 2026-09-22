package com.mozgobolt.feature.user.routing.dto.request

import com.mozgobolt.core.domain.validation.DISPLAY_NAME_MAX_LENGTH
import com.mozgobolt.core.domain.validation.ValidatedRequest
import com.mozgobolt.core.domain.validation.validateDisplayName
import com.mozgobolt.core.domain.validation.validateInternationalPhoneNumber
import com.mozgobolt.core.domain.validation.validateMessengerUsername
import com.mozgobolt.core.domain.validation.validatePhoneNumber
import com.mozgobolt.feature.user.domain.model.UserRole
import kotlinx.serialization.Serializable

@Serializable
data class UserCreationRequestDto(
    val password: String,
    val email: String,
    val name: String,
    val role: String,
    val phoneNumber: String? = null,
    val whatsappNumber: String? = null,
    val viberNumber: String? = null,
    val messengerUsername: String? = null,
) : ValidatedRequest {
    override fun validate() =
        buildList {
            if (email.isBlank()) add("Email is required")
            if (password.isBlank()) add("Password is required")
            if (UserRole.entries.none { it.name == role }) {
                add("Role must be one of ${UserRole.entries.joinToString { it.name }}")
            }
            addAll(validateDisplayName(name, DISPLAY_NAME_MAX_LENGTH))
            addAll(validatePhoneNumber(phoneNumber))
            addAll(validateInternationalPhoneNumber(whatsappNumber, "WhatsApp number"))
            addAll(validateInternationalPhoneNumber(viberNumber, "Viber number"))
            addAll(validateMessengerUsername(messengerUsername))
        }
}
