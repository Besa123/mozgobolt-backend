package com.mozgobolt.feature.company.routing.dto.request

import kotlin.test.Test
import kotlin.test.assertTrue

class JoinCompanyRequestDtoTest {
    @Test
    fun `a non-blank invite code has no validation errors`() {
        assertTrue(JoinCompanyRequestDto(inviteCode = "abc123").validate().isEmpty())
    }

    @Test
    fun `a blank invite code is rejected`() {
        assertTrue(JoinCompanyRequestDto(inviteCode = "").validate().isNotEmpty())
    }

    @Test
    fun `a whitespace-only invite code is rejected`() {
        assertTrue(JoinCompanyRequestDto(inviteCode = "   ").validate().isNotEmpty())
    }
}
