package com.mozgobolt.core.domain.validation

// Facebook's own real limit for a page/profile username.
const val MESSENGER_USERNAME_MAX_LENGTH = 50
private const val MESSENGER_USERNAME_MIN_LENGTH = 5

// Letters/digits throughout; a period is allowed anywhere except leading, trailing, or doubled —
// the negative lookahead rejects ".." without needing three separate checks.
private val MESSENGER_USERNAME_PATTERN = Regex("^[a-zA-Z0-9](?:[a-zA-Z0-9]|\\.(?!\\.))*[a-zA-Z0-9]$")

/**
 * Format-only validation — there's no official, free way to confirm a username actually resolves
 * to a real Messenger account without Meta's paid Business API, which is out of scope. This just
 * rejects strings that couldn't possibly be a valid Facebook username.
 */
fun validateMessengerUsername(username: String?): List<String> {
    if (username == null) return emptyList()
    if (username.isBlank()) return listOf("Messenger username must not be blank if provided")
    if (username.length !in MESSENGER_USERNAME_MIN_LENGTH..MESSENGER_USERNAME_MAX_LENGTH) {
        return listOf(
            "Messenger username must be between $MESSENGER_USERNAME_MIN_LENGTH and " +
                "$MESSENGER_USERNAME_MAX_LENGTH characters",
        )
    }

    return if (MESSENGER_USERNAME_PATTERN.matches(username)) {
        emptyList()
    } else {
        listOf("Messenger username may only contain letters, digits, and single periods (not leading/trailing/doubled)")
    }
}
