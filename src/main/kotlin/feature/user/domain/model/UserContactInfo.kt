package com.mozgobolt.feature.user.domain.model

/**
 * A user's own full view of their contact methods — value *and* visibility together, since a user
 * manages both independently (update, remove by setting `null`, or hide without removing by
 * setting the paired `*Visible` to `false`). Unlike [DriverContactInfo] (already visibility-
 * filtered, for a buyer looking at someone else), this is what the owner themselves sees and edits.
 */
data class UserContactInfo(
    val phoneNumber: String?,
    val phoneNumberVisible: Boolean,
    val whatsappNumber: String?,
    val whatsappVisible: Boolean,
    val viberNumber: String?,
    val viberVisible: Boolean,
    val messengerUsername: String?,
    val messengerVisible: Boolean,
)
