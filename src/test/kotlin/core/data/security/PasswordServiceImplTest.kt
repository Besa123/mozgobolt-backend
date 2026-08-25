package com.besa.shelflife.core.data.security

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PasswordServiceImplTest {
    private val service = PasswordServiceImpl(pepper = "unit-test-pepper")

    @Test
    fun `hashing then verifying the same password succeeds`() {
        val hash = service.hashPassword("Correct-Horse-1")

        assertTrue(service.verifyPassword(password = "Correct-Horse-1", hash = hash))
    }

    @Test
    fun `verifying the wrong password fails`() {
        val hash = service.hashPassword("Correct-Horse-1")

        assertFalse(service.verifyPassword(password = "Wrong-Password-1", hash = hash))
    }

    @Test
    fun `hashing the same password twice produces different hashes due to random salt`() {
        val first = service.hashPassword("Correct-Horse-1")
        val second = service.hashPassword("Correct-Horse-1")

        assertNotEquals(first, second)
    }

    @Test
    fun `a hash produced with a different pepper does not verify`() {
        val otherService = PasswordServiceImpl(pepper = "a-completely-different-pepper")
        val hash = otherService.hashPassword("Correct-Horse-1")

        assertFalse(service.verifyPassword(password = "Correct-Horse-1", hash = hash))
    }

    @Test
    fun `hashing a password over the max length is rejected`() {
        val tooLong = "a".repeat(PasswordServiceImpl.MAX_PASSWORD_LENGTH + 1)

        assertFailsWith<IllegalArgumentException> { service.hashPassword(tooLong) }
    }

    @Test
    fun `verifying a password over the max length returns false instead of throwing`() {
        val hash = service.hashPassword("Correct-Horse-1")
        val tooLong = "a".repeat(PasswordServiceImpl.MAX_PASSWORD_LENGTH + 1)

        assertFalse(service.verifyPassword(password = tooLong, hash = hash))
    }

    @Test
    fun `an empty password never verifies, even against its own hash`() {
        val hash = service.hashPassword("")

        assertFalse(service.verifyPassword(password = "", hash = hash))
    }

    @Test
    fun `a password at exactly the max length round-trips`() {
        val atLimit = "a".repeat(PasswordServiceImpl.MAX_PASSWORD_LENGTH)
        val hash = service.hashPassword(atLimit)

        assertTrue(service.verifyPassword(password = atLimit, hash = hash))
    }

    @Test
    fun `unicode passwords round-trip correctly`() {
        val unicodePassword = "Pässwörd-😀-日本語"
        val hash = service.hashPassword(unicodePassword)

        assertTrue(service.verifyPassword(password = unicodePassword, hash = hash))
    }

    @Test
    fun `password comparison is case sensitive`() {
        val hash = service.hashPassword("Correct-Horse-1")

        assertFalse(service.verifyPassword(password = "correct-horse-1", hash = hash))
    }

    @Test
    fun `verifying against a hash that is not a valid argon2 hash fails safely instead of throwing`() {
        val result = runCatching { service.verifyPassword(password = "anything", hash = "not-a-real-hash") }

        assertTrue(result.isSuccess, "verifyPassword threw for a malformed hash instead of returning false: $result")
        assertFalse(result.getOrDefault(true))
    }

    @Test
    fun `verifying against an empty hash fails safely instead of throwing`() {
        val result = runCatching { service.verifyPassword(password = "anything", hash = "") }

        assertTrue(result.isSuccess, "verifyPassword threw for an empty hash instead of returning false: $result")
        assertFalse(result.getOrDefault(true))
    }
}
