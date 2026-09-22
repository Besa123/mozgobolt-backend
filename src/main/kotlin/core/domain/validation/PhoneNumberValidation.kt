package com.mozgobolt.core.domain.validation

import com.google.i18n.phonenumbers.PhoneNumberUtil
import com.google.i18n.phonenumbers.Phonenumber

// Hungary only — a buyer calling a vendor's number is calling a real phone, and a vendor
// registering a foreign number risks a buyer mistakenly placing an international call they never
// intended to make.
private const val PHONE_NUMBER_REGION = "HU"

// E.164's longest possible form is a leading '+' plus up to 15 digits (16 chars); this headroom
// is for the DB column (see UsersTable.phoneNumber), not for validation — libphonenumber is the
// actual source of truth for whether a number is valid.
const val PHONE_NUMBER_MAX_LENGTH = 20

private val phoneNumberUtil: PhoneNumberUtil by lazy { PhoneNumberUtil.getInstance() }

/**
 * A phone number is always optional (a vendor may not provide one) — `null` skips validation
 * entirely. Delegates the actual validation to Google's libphonenumber rather than a hand-rolled
 * pattern, restricted to Hungarian numbers only (see [PHONE_NUMBER_REGION]).
 */
fun validatePhoneNumber(phoneNumber: String?): List<String> {
    if (phoneNumber == null) return emptyList()
    if (phoneNumber.isBlank()) return listOf("Phone number must not be blank if provided")

    return if (isValidHungarianNumber(phoneNumber)) {
        emptyList()
    } else {
        listOf("Phone number must be a valid Hungarian phone number")
    }
}

/**
 * Formats an already-valid Hungarian number to one canonical E.164 form (e.g. `+36301234567`), so
 * `06301234567`, `+36301234567`, and `30 123 4567` all end up persisted identically. Returns
 * `null` for `null`/blank input or a number [validatePhoneNumber] would reject — callers that
 * skip validation get `null` rather than an exception, matching this codebase's rule against
 * throwing for expected/user-input failures.
 */
fun normalizePhoneNumber(phoneNumber: String?): String? {
    val trimmed = phoneNumber?.takeIf { it.isNotBlank() } ?: return null
    val validParsed =
        parseHungarianNumber(trimmed)?.takeIf { phoneNumberUtil.isValidNumberForRegion(it, PHONE_NUMBER_REGION) }
            ?: return null

    return phoneNumberUtil.format(validParsed, PhoneNumberUtil.PhoneNumberFormat.E164)
}

private fun isValidHungarianNumber(phoneNumber: String): Boolean {
    val parsed = parseHungarianNumber(phoneNumber) ?: return false
    return phoneNumberUtil.isValidNumberForRegion(parsed, PHONE_NUMBER_REGION)
}

// libphonenumber's parse() has exactly one documented failure mode (NumberParseException), so a
// blanket runCatching loses no meaningful distinction here — unlike a narrow catch guarding a real
// I/O or security boundary, there's no second exception type this should let through separately.
private fun parseHungarianNumber(phoneNumber: String): Phonenumber.PhoneNumber? =
    runCatching { phoneNumberUtil.parse(phoneNumber, PHONE_NUMBER_REGION) }.getOrNull()

/**
 * Unlike [validatePhoneNumber] (Hungary-only — that one is for a buyer to *call*, where a foreign
 * number risks an accidental international call), this accepts a valid number from ANY country —
 * for WhatsApp/Viber, where there's no such risk, and a cross-border driver may legitimately use a
 * non-Hungarian number. [PHONE_NUMBER_REGION] is only a parsing hint for numbers typed without a
 * leading `+` (a bare national-format number defaults to Hungarian); a `+`-prefixed number's real
 * country is always taken from the number itself, and validity is checked against ANY region.
 */
fun validateInternationalPhoneNumber(
    phoneNumber: String?,
    fieldName: String,
): List<String> {
    if (phoneNumber == null) return emptyList()
    if (phoneNumber.isBlank()) return listOf("$fieldName must not be blank if provided")

    return if (isValidInternationalNumber(phoneNumber)) {
        emptyList()
    } else {
        listOf("$fieldName must be a valid phone number")
    }
}

/** See [normalizePhoneNumber]'s kdoc — same normalization contract, just not region-restricted. */
fun normalizeInternationalPhoneNumber(phoneNumber: String?): String? {
    val trimmed = phoneNumber?.takeIf { it.isNotBlank() } ?: return null
    val validParsed = parseHungarianNumber(trimmed)?.takeIf { phoneNumberUtil.isValidNumber(it) } ?: return null

    return phoneNumberUtil.format(validParsed, PhoneNumberUtil.PhoneNumberFormat.E164)
}

private fun isValidInternationalNumber(phoneNumber: String): Boolean {
    val parsed = parseHungarianNumber(phoneNumber) ?: return false
    return phoneNumberUtil.isValidNumber(parsed)
}
