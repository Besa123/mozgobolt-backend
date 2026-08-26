package com.shelflife.feature.user.routing.dto.request

import kotlin.test.Test
import kotlin.test.assertTrue

class PasswordResetRequestDtoTest {
    @Test
    fun `a non-blank email has no validation errors`() {
        assertTrue(PasswordResetRequestDto(email = "user@example.com").validate().isEmpty())
    }

    @Test
    fun `a blank email is rejected`() {
        assertTrue(PasswordResetRequestDto(email = "").validate().isNotEmpty())
    }

    @Test
    fun `a whitespace-only email is rejected`() {
        assertTrue(PasswordResetRequestDto(email = "   ").validate().isNotEmpty())
    }
}

class PasswordResetValidateRequestDtoTest {
    @Test
    fun `a non-blank token has no validation errors`() {
        assertTrue(PasswordResetValidateRequestDto(token = "some-token").validate().isEmpty())
    }

    @Test
    fun `a blank token is rejected`() {
        assertTrue(PasswordResetValidateRequestDto(token = "").validate().isNotEmpty())
    }

    @Test
    fun `a whitespace-only token is rejected`() {
        assertTrue(PasswordResetValidateRequestDto(token = "   ").validate().isNotEmpty())
    }
}

class PasswordResetConfirmRequestDtoTest {
    @Test
    fun `a well-formed request has no validation errors`() {
        val request = PasswordResetConfirmRequestDto(token = "some-token", newPassword = "Str0ngPass1")

        assertTrue(request.validate().isEmpty())
    }

    @Test
    fun `a blank token is rejected`() {
        val request = PasswordResetConfirmRequestDto(token = "", newPassword = "Str0ngPass1")

        assertTrue(request.validate().isNotEmpty())
    }

    @Test
    fun `a blank new password is rejected`() {
        val request = PasswordResetConfirmRequestDto(token = "some-token", newPassword = "")

        assertTrue(request.validate().isNotEmpty())
    }

    @Test
    fun `both fields blank produce one error per field`() {
        val request = PasswordResetConfirmRequestDto(token = "", newPassword = "")

        assertTrue(request.validate().size >= 2)
    }
}
