package com.mozgobolt.feature.user.routing.dto.request

import kotlin.test.Test
import kotlin.test.assertTrue

class LoginRequestDtoTest {
    @Test
    fun `a well-formed request has no validation errors`() {
        val request = LoginRequestDto(email = "user@example.com", password = "whatever-the-user-typed")

        assertTrue(request.validate().isEmpty())
    }

    @Test
    fun `a blank email is rejected`() {
        val request = LoginRequestDto(email = "", password = "whatever-the-user-typed")

        assertTrue(request.validate().isNotEmpty())
    }

    @Test
    fun `a blank password is rejected`() {
        val request = LoginRequestDto(email = "user@example.com", password = "")

        assertTrue(request.validate().isNotEmpty())
    }

    @Test
    fun `a whitespace-only email is rejected`() {
        val request = LoginRequestDto(email = "   ", password = "whatever-the-user-typed")

        assertTrue(request.validate().isNotEmpty())
    }

    @Test
    fun `a whitespace-only password is rejected`() {
        val request = LoginRequestDto(email = "user@example.com", password = "   ")

        assertTrue(request.validate().isNotEmpty())
    }

    @Test
    fun `login intentionally does not enforce password strength rules`() {
        val request = LoginRequestDto(email = "user@example.com", password = "weak")

        assertTrue(request.validate().isEmpty())
    }
}
