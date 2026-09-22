package com.mozgobolt.feature.user.routing.dto.request

import kotlin.test.Test
import kotlin.test.assertTrue

class UserCreationRequestDtoTest {
    @Test
    fun `a well-formed request has no validation errors`() {
        val request =
            UserCreationRequestDto(
                password = "Str0ngPass",
                email = "user@example.com",
                name = "Jane Doe",
                role = "BUYER",
            )

        assertTrue(request.validate().isEmpty())
    }

    @Test
    fun `a blank email is rejected`() {
        val request = UserCreationRequestDto(password = "Str0ngPass", email = "", name = "Jane Doe", role = "BUYER")

        assertTrue(request.validate().isNotEmpty())
    }

    @Test
    fun `a blank password is rejected`() {
        val request =
            UserCreationRequestDto(password = "", email = "user@example.com", name = "Jane Doe", role = "BUYER")

        assertTrue(request.validate().isNotEmpty())
    }

    @Test
    fun `a whitespace-only email is rejected`() {
        val request = UserCreationRequestDto(password = "Str0ngPass", email = "   ", name = "Jane Doe", role = "BUYER")

        assertTrue(request.validate().isNotEmpty())
    }

    @Test
    fun `a whitespace-only password is rejected`() {
        val request =
            UserCreationRequestDto(password = "   ", email = "user@example.com", name = "Jane Doe", role = "BUYER")

        assertTrue(request.validate().isNotEmpty())
    }

    @Test
    fun `a blank name is rejected`() {
        val request =
            UserCreationRequestDto(password = "Str0ngPass", email = "user@example.com", name = "", role = "BUYER")

        assertTrue(request.validate().isNotEmpty())
    }

    @Test
    fun `a name over one hundred characters is rejected`() {
        val request =
            UserCreationRequestDto(
                password = "Str0ngPass",
                email = "user@example.com",
                name = "a".repeat(101),
                role = "BUYER",
            )

        assertTrue(request.validate().isNotEmpty())
    }

    @Test
    fun `a name at exactly one hundred characters is accepted`() {
        val request =
            UserCreationRequestDto(
                password = "Str0ngPass",
                email = "user@example.com",
                name = "a".repeat(100),
                role = "BUYER",
            )

        assertTrue(request.validate().isEmpty())
    }

    @Test
    fun `a name with leading whitespace is rejected`() {
        val request =
            UserCreationRequestDto(
                password = "Str0ngPass",
                email = "user@example.com",
                name = " Jane Doe",
                role = "BUYER",
            )

        assertTrue(request.validate().isNotEmpty())
    }

    @Test
    fun `a name with trailing whitespace is rejected`() {
        val request =
            UserCreationRequestDto(
                password = "Str0ngPass",
                email = "user@example.com",
                name = "Jane Doe ",
                role = "BUYER",
            )

        assertTrue(request.validate().isNotEmpty())
    }

    @Test
    fun `a name containing markup-like characters is rejected`() {
        val request =
            UserCreationRequestDto(
                password = "Str0ngPass",
                email = "user@example.com",
                name = "<script>Jane</script>",
                role = "BUYER",
            )

        assertTrue(request.validate().isNotEmpty())
    }

    @Test
    fun `a name containing an ampersand or quote is rejected`() {
        val request =
            UserCreationRequestDto(
                password = "Str0ngPass",
                email = "user@example.com",
                name = "Jane & Bob",
                role = "BUYER",
            )

        assertTrue(request.validate().isNotEmpty())
    }

    @Test
    fun `all fields blank produces one error per field`() {
        val request = UserCreationRequestDto(password = "", email = "", name = "", role = "")

        assertTrue(request.validate().size >= 3)
    }

    @Test
    fun `a null phone number is accepted`() {
        val request =
            UserCreationRequestDto(
                password = "Str0ngPass",
                email = "user@example.com",
                name = "Jane Doe",
                role = "BUYER",
                phoneNumber = null,
            )

        assertTrue(request.validate().isEmpty())
    }

    @Test
    fun `a well-formed phone number is accepted`() {
        val request =
            UserCreationRequestDto(
                password = "Str0ngPass",
                email = "user@example.com",
                name = "Jane Doe",
                role = "BUYER",
                phoneNumber = "+36 20 123 4567",
            )

        assertTrue(request.validate().isEmpty())
    }

    @Test
    fun `a blank phone number is rejected, unlike an absent one`() {
        val request =
            UserCreationRequestDto(
                password = "Str0ngPass",
                email = "user@example.com",
                name = "Jane Doe",
                role = "BUYER",
                phoneNumber = "",
            )

        assertTrue(request.validate().isNotEmpty())
    }

    @Test
    fun `a phone number with letters is rejected`() {
        val request =
            UserCreationRequestDto(
                password = "Str0ngPass",
                email = "user@example.com",
                name = "Jane Doe",
                role = "BUYER",
                phoneNumber = "call-me-maybe",
            )

        assertTrue(request.validate().isNotEmpty())
    }

    @Test
    fun `an unrecognized role is rejected`() {
        val request =
            UserCreationRequestDto(
                password = "Str0ngPass",
                email = "user@example.com",
                name = "Jane Doe",
                role = "ADMIN",
            )

        assertTrue(request.validate().isNotEmpty())
    }
}
