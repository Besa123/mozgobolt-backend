/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.core.modules.plugin

import com.mozgobolt.core.configureTestEnvironment
import com.mozgobolt.core.installTestModules
import com.mozgobolt.core.modules.AppConfig
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.plugins.di.provide
import io.ktor.server.testing.testApplication
import io.sentry.Hint
import io.sentry.Sentry
import io.sentry.SentryEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SentryConfigTest {
    @Test
    fun `a blank DSN leaves Sentry disabled`() {
        Sentry.close()
        try {
            testApplication {
                configureTestEnvironment()
                application {
                    installTestModules()
                    dependencies { provide<AppConfig> { testConfig(dsn = "") } }
                    configureSentry()
                }

                assertFalse(Sentry.isEnabled())
            }
        } finally {
            Sentry.close()
        }
    }

    @Test
    fun `a configured DSN installs environment stacktrace tags and remote host filtering`() {
        Sentry.close()
        try {
            testApplication {
                configureTestEnvironment(environmentName = "production")
                val dsn = "https://public@o0.ingest.sentry.io/123"
                application {
                    installTestModules()
                    dependencies { provide<AppConfig> { testConfig(dsn) } }
                    val config: AppConfig by dependencies
                    assertEquals(dsn, config.sentry.dsn)
                    configureSentry()
                    val options = Sentry.getCurrentScopes().options
                    assertEquals(dsn, options.dsn)
                    assertTrue(Sentry.isEnabled(), "Sentry should be enabled for DSN ${options.dsn ?: "<null>"}")
                    assertEquals("production", options.environment)
                    assertFalse(options.isDebug)
                    assertTrue(options.isAttachStacktrace)
                    assertEquals(listOf("requestId", "method", "path"), options.contextTags)

                    val event = SentryEvent()
                    val mdc =
                        mutableMapOf(
                            "remoteHost" to "127.0.0.1",
                            "userId" to "123",
                            "deviceId" to "device-1",
                            "userAgent" to "browser",
                            "requestId" to "request-1",
                            "method" to "GET",
                            "path" to "/api/v1/users/me",
                        )
                    event.contexts["MDC"] = mdc
                    val filtered = options.beforeSend!!.execute(event, Hint())

                    assertSame(event, filtered)
                    assertEquals(
                        mapOf(
                            "requestId" to "request-1",
                            "method" to "GET",
                            "path" to "/api/v1/users/me",
                        ),
                        mdc,
                    )
                }
            }
        } finally {
            Sentry.close()
        }
    }

    private fun testConfig(dsn: String) =
        AppConfig(
            database = AppConfig.Database(url = "unused", user = "unused", password = "unused"),
            jwt = AppConfig.Jwt(secret = "unused", issuer = "unused", audience = "unused"),
            security = AppConfig.Security(passwordPepper = "unused"),
            cors = AppConfig.Cors(),
            email = AppConfig.Email(),
            sentry = AppConfig.Sentry(dsn = dsn),
        )
}
