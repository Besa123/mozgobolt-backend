package com.mozgobolt.feature.user.routing.dto.request

import kotlin.test.Test
import kotlin.test.assertTrue

class LogoutRequestDtoTest {
    @Test
    fun `a non-blank refresh token has no validation errors`() {
        assertTrue(LogoutRequestDto(refreshToken = "some-token").validate().isEmpty())
    }

    @Test
    fun `a blank refresh token is rejected`() {
        assertTrue(LogoutRequestDto(refreshToken = "").validate().isNotEmpty())
    }

    @Test
    fun `a whitespace-only refresh token is rejected`() {
        assertTrue(LogoutRequestDto(refreshToken = "   ").validate().isNotEmpty())
    }
}
