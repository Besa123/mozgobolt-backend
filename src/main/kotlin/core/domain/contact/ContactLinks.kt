package com.mozgobolt.core.domain.contact

private val NON_DIGIT = Regex("[^0-9]")

/** Meta's official WhatsApp "Click to Chat" link — opens directly into a chat with this number. */
fun whatsAppLink(phoneNumber: String): String = "https://wa.me/${phoneNumber.replace(NON_DIGIT, "")}"

/** Viber's official deep-link scheme for starting a chat with a number. */
fun viberLink(phoneNumber: String): String = "viber://chat?number=${phoneNumber.replace(NON_DIGIT, "")}"

/** Meta's official Messenger deep link — reliable for Pages/business profiles. */
fun messengerLink(username: String): String = "https://m.me/$username"
