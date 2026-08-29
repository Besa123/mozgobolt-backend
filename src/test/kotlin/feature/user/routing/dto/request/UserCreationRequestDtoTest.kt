package com.shelflife.feature.user.routing.dto.request

import kotlin.test.Test
import kotlin.test.assertTrue

class UserCreationRequestDtoTest {
    @Test
    fun `a well-formed request has no validation errors`() {
        val request = UserCreationRequestDto(password = "Str0ngPass", email = "user@example.com", name = "Jane Doe")

        assertTrue(request.validate().isEmpty())
    }

    @Test
    fun `a blank email is rejected`() {
        val request = UserCreationRequestDto(password = "Str0ngPass", email = "", name = "Jane Doe")

        assertTrue(request.validate().isNotEmpty())
    }

    @Test
    fun `a blank password is rejected`() {
        val request = UserCreationRequestDto(password = "", email = "user@example.com", name = "Jane Doe")

        assertTrue(request.validate().isNotEmpty())
    }

    @Test
    fun `a whitespace-only email is rejected`() {
        val request = UserCreationRequestDto(password = "Str0ngPass", email = "   ", name = "Jane Doe")

        assertTrue(request.validate().isNotEmpty())
    }

    @Test
    fun `a whitespace-only password is rejected`() {
        val request = UserCreationRequestDto(password = "   ", email = "user@example.com", name = "Jane Doe")

        assertTrue(request.validate().isNotEmpty())
    }

    @Test
    fun `a blank name is rejected`() {
        val request = UserCreationRequestDto(password = "Str0ngPass", email = "user@example.com", name = "")

        assertTrue(request.validate().isNotEmpty())
    }

    @Test
    fun `a name over one hundred characters is rejected`() {
        val request =
            UserCreationRequestDto(password = "Str0ngPass", email = "user@example.com", name = "a".repeat(101))

        assertTrue(request.validate().isNotEmpty())
    }

    @Test
    fun `a name at exactly one hundred characters is accepted`() {
        val request =
            UserCreationRequestDto(password = "Str0ngPass", email = "user@example.com", name = "a".repeat(100))

        assertTrue(request.validate().isEmpty())
    }

    @Test
    fun `a name with leading whitespace is rejected`() {
        val request = UserCreationRequestDto(password = "Str0ngPass", email = "user@example.com", name = " Jane Doe")

        assertTrue(request.validate().isNotEmpty())
    }

    @Test
    fun `a name with trailing whitespace is rejected`() {
        val request = UserCreationRequestDto(password = "Str0ngPass", email = "user@example.com", name = "Jane Doe ")

        assertTrue(request.validate().isNotEmpty())
    }

    @Test
    fun `a name containing markup-like characters is rejected`() {
        val request =
            UserCreationRequestDto(password = "Str0ngPass", email = "user@example.com", name = "<script>Jane</script>")

        assertTrue(request.validate().isNotEmpty())
    }

    @Test
    fun `a name containing an ampersand or quote is rejected`() {
        val request = UserCreationRequestDto(password = "Str0ngPass", email = "user@example.com", name = "Jane & Bob")

        assertTrue(request.validate().isNotEmpty())
    }

    @Test
    fun `all fields blank produces one error per field`() {
        val request = UserCreationRequestDto(password = "", email = "", name = "")

        assertTrue(request.validate().size >= 3)
    }
}
