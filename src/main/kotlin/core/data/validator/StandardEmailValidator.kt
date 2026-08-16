package com.besa.boardShare.core.data.validator

import com.besa.boardShare.core.domain.validation.EmailValidator

class StandardEmailValidator : EmailValidator {

    override fun normalize(email: String): String {
        return email.trim().lowercase()
    }

    override fun isValid(email: String): Boolean {
        val normalized = normalize(email)

        if (!EMAIL_REGEX.matches(normalized)) return false
        if (normalized.length > 254) return false

        val domain = normalized.substringAfter("@")
        return domain !in disposableDomains
    }

    companion object {
        private val EMAIL_REGEX = Regex(
            "^[a-z0-9]([a-z0-9._%+\\-]*[a-z0-9])?@[a-z0-9]([a-z0-9\\-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9\\-]*[a-z0-9])?)*\\.[a-z]{2,}$"
        )

        private val disposableDomains: Set<String> by lazy {
            val resource = StandardEmailValidator::class.java.classLoader
                .getResourceAsStream("disposable-email-domains.txt")
                ?: error("disposable-email-domains.txt not found in resources")

            resource.bufferedReader()
                .lineSequence()
                .map { it.trim().lowercase() }
                .filter { it.isNotBlank() }
                .toHashSet()
        }
    }
}
