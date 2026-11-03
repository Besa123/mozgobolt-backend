/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.core.modules

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

private const val TEST_JWT_SECRET = "test-jwt-secret-0123456789abcdef0123456789abcdef"
private const val TEST_PASSWORD_PEPPER = "test-password-pepper-0123456789abcdef"

class AppConfigValidationTest {
    @Test
    fun `nonproduction may use a local HTTP email-link origin`() {
        testConfig(baseUrl = "http://localhost:8080").validateForEnvironment("local")
    }

    @Test
    fun `password hash concurrency defaults to available CPU cores`() {
        val config = testConfig(baseUrl = "http://localhost:8080")

        assertEquals(
            RuntimeCapacityConfig.DEFAULT_DATABASE_POOL_SIZE,
            config.runtimeCapacity.databasePoolSize,
        )
        assertEquals(
            Runtime.getRuntime().availableProcessors().coerceAtLeast(1),
            config.runtimeCapacity.passwordHashConcurrency,
        )
        assertEquals(
            Runtime.getRuntime().availableProcessors().coerceAtLeast(1),
            config.runtimeCapacity.imageProcessingConcurrency,
        )
    }

    @Test
    fun `runtime capacities must be positive in every environment`() {
        val config = testConfig(baseUrl = "http://localhost:8080")
        val invalidDatabasePool =
            config.copy(
                runtimeCapacity =
                    RuntimeCapacityConfig(
                        databasePoolSize = 0,
                        passwordHashConcurrency = 1,
                        imageProcessingConcurrency = 1,
                    ),
            )
        val invalidHashConcurrency =
            config.copy(
                runtimeCapacity =
                    RuntimeCapacityConfig(
                        databasePoolSize = 1,
                        passwordHashConcurrency = 0,
                        imageProcessingConcurrency = 1,
                    ),
            )
        val invalidImageConcurrency =
            config.copy(
                runtimeCapacity =
                    RuntimeCapacityConfig(
                        databasePoolSize = 1,
                        passwordHashConcurrency = 1,
                        imageProcessingConcurrency = 0,
                    ),
            )

        assertFailsWith<IllegalArgumentException> {
            invalidDatabasePool.validateForEnvironment("local")
        }
        assertFailsWith<IllegalArgumentException> {
            invalidHashConcurrency.validateForEnvironment("local")
        }
        assertFailsWith<IllegalArgumentException> {
            invalidImageConcurrency.validateForEnvironment("local")
        }
    }

    @Test
    fun `production accepts an absolute HTTPS email-link origin`() {
        testConfig(baseUrl = "https://api.example.test/backend", clamAvEnabled = true)
            .validateForEnvironment("production")
    }

    @Test
    fun `production rejects HTTP malformed and credential-bearing email-link URLs`() {
        listOf(
            "http://api.example.test",
            "relative/path",
            "https://user:password@api.example.test",
            "https://api.example.test?token=secret",
            "https://api.example.test#fragment",
        ).forEach { baseUrl ->
            assertFailsWith<IllegalArgumentException>(baseUrl) {
                testConfig(baseUrl, clamAvEnabled = true).validateForEnvironment("production")
            }
        }
    }

    @Test
    fun `production rejects short or example signing secrets`() {
        val validConfig = testConfig("https://api.example.test", clamAvEnabled = true)

        assertFailsWith<IllegalArgumentException> {
            validConfig.copy(jwt = validConfig.jwt.copy(secret = "short")).validateForEnvironment("production")
        }
        assertFailsWith<IllegalArgumentException> {
            validConfig
                .copy(
                    security = validConfig.security.copy(passwordPepper = "change-me-to-a-long-random-string"),
                ).validateForEnvironment("production")
        }
    }

    @Test
    fun `production requires separate JWT and password pepper secrets`() {
        val config = testConfig("https://api.example.test", clamAvEnabled = true)
        val duplicateSecret =
            config.copy(
                security = config.security.copy(passwordPepper = config.jwt.secret),
            )

        assertFailsWith<IllegalArgumentException> {
            duplicateSecret.validateForEnvironment("production")
        }
    }

    @Test
    fun `production requires ClamAV to be enabled for vehicle-picture uploads`() {
        val config = testConfig("https://api.example.test")

        assertFailsWith<IllegalArgumentException> {
            config.validateForEnvironment("production")
        }
    }

    private fun testConfig(
        baseUrl: String,
        clamAvEnabled: Boolean = false,
    ): AppConfig =
        AppConfig(
            baseUrl = baseUrl,
            database = AppConfig.Database(url = "unused", user = "unused", password = "unused"),
            jwt = AppConfig.Jwt(secret = TEST_JWT_SECRET, issuer = "unused", audience = "unused"),
            security = AppConfig.Security(passwordPepper = TEST_PASSWORD_PEPPER),
            cors = AppConfig.Cors(),
            email = AppConfig.Email(),
            clamAv = AppConfig.ClamAv(enabled = clamAvEnabled),
        )
}
