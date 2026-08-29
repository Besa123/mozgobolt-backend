package com.shelflife.feature.user

import com.shelflife.core.data.idempotency.ExposedIdempotencyStore
import com.shelflife.core.data.security.JwtTokenManager
import com.shelflife.core.data.security.PasswordServiceImpl
import com.shelflife.core.data.validator.StandardEmailValidator
import com.shelflife.core.data.validator.StandardPasswordValidator
import com.shelflife.core.database.DatabaseFactory
import com.shelflife.core.database.ExposedTransactionalRunner
import com.shelflife.core.domain.AppResult
import com.shelflife.core.domain.email.EmailService
import com.shelflife.core.modules.AppConfig
import com.shelflife.feature.user.data.repository.UserRepositoryI
import com.shelflife.feature.user.domain.model.RefreshError
import com.shelflife.feature.user.domain.model.RegisterError
import com.shelflife.feature.user.service.UserServiceI
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
import org.junit.Assume.assumeTrue
import org.testcontainers.DockerClientFactory
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.test.fail

class AuthFlowIntegrationTest {
    @Test
    fun `register, verify, log in and rotate refresh tokens against a real database`() {
        skipIfNoDocker()

        withRealDatabase { harness, database ->
            exerciseAuthFlow(harness.service, harness.emailService)
            exerciseIdempotencyKeysTable(database)
        }
    }

    @Test
    fun `registering the same email from two concurrent requests only lets one succeed`() {
        skipIfNoDocker()

        withRealDatabase { harness, _ ->
            coroutineScope {
                val results =
                    listOf(
                        async {
                            harness.service.createUser(
                                password = "Str0ngPass1",
                                email = "racer@example.com",
                                name = "A",
                            )
                        },
                        async {
                            harness.service.createUser(
                                password = "Str0ngPass1",
                                email = "racer@example.com",
                                name = "B",
                            )
                        },
                    ).awaitAll()

                val successes = results.count { it is AppResult.Success }
                val alreadyExistsErrors =
                    results.count { it is AppResult.Error && it.errorType == RegisterError.ALREADY_EXISTS }

                assertEquals(1, successes, "exactly one concurrent registration should win: $results")
                assertEquals(1, alreadyExistsErrors, "the loser should see a clean ALREADY_EXISTS, not crash: $results")
            }
        }
    }

    private fun skipIfNoDocker() {
        assumeTrue(
            "Docker is not available locally — skipping (this still runs in CI)",
            DockerClientFactory.instance().isDockerAvailable,
        )
    }

    private data class Harness(
        val service: UserServiceI,
        val emailService: RecordingEmailService,
    )

    private fun withRealDatabase(block: suspend (Harness, Database) -> Unit) {
        val postgres = PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"))
        postgres.start()

        try {
            val dataSource =
                DatabaseFactory.createHikariDataSource(
                    dbUrl = postgres.jdbcUrl,
                    dbUser = postgres.username,
                    dbPassword = postgres.password,
                    poolSize = 5,
                )

            try {
                DatabaseFactory.runFlywayMigration(dataSource)
                val database = DatabaseFactory.createDatabase(dataSource)

                val emailService = RecordingEmailService()
                val service =
                    UserServiceI(
                        userRepository = UserRepositoryI(),
                        passwordService = PasswordServiceImpl(pepper = "integration-test-pepper"),
                        tokenManager =
                            JwtTokenManager(
                                secret = "integration-test-secret",
                                issuer = "shelflife-test",
                                audience = "shelflife-test",
                            ),
                        passwordValidator = StandardPasswordValidator(),
                        emailValidator = StandardEmailValidator(),
                        emailService = emailService,
                        appConfig = testAppConfig(),
                        tx = ExposedTransactionalRunner(database),
                    )

                runBlocking { block(Harness(service, emailService), database) }
            } finally {
                (dataSource as HikariDataSource).close()
            }
        } finally {
            postgres.stop()
        }
    }

    private suspend fun exerciseAuthFlow(
        service: UserServiceI,
        emailService: RecordingEmailService,
    ) {
        service
            .createUser(password = "Str0ngPass1", email = "integration@example.com", name = "Integration Tester")
            .fold(onSuccess = {}, onError = { fail("register failed: $it") })

        val (_, verificationToken) = emailService.sentTokens.single()
        service.verifyEmail(verificationToken).fold(onSuccess = {}, onError = { fail("verify failed: $it") })

        val signIn =
            service
                .signInUser(password = "Str0ngPass1", email = "integration@example.com")
                .fold(onSuccess = { it }, onError = { fail("login failed: $it") })
        assertTrue(signIn.accessToken.isNotBlank())
        assertTrue(signIn.refreshToken.isNotBlank())

        val rotated =
            service
                .refreshToken(signIn.refreshToken)
                .fold(onSuccess = { it }, onError = { fail("refresh failed: $it") })
        assertNotEquals(signIn.refreshToken, rotated.refreshToken)

        service.refreshToken(signIn.refreshToken).fold(
            onSuccess = { fail("expected the reused refresh token to be rejected") },
            onError = { assertEquals(RefreshError.TOKEN_REUSE_DETECTED, it) },
        )
    }

    private suspend fun exerciseIdempotencyKeysTable(database: Database) {
        val store = ExposedIdempotencyStore(database)

        assertTrue(store.acquireLock(key = "integration-test-key", fingerprint = "fp"))
        store.complete(key = "integration-test-key", statusCode = 201, body = """{"ok":true}""")

        val record = store.find("integration-test-key")
        assertEquals(201, record?.statusCode)
        assertEquals("""{"ok":true}""", record?.responseBody)
    }

    private fun testAppConfig() =
        AppConfig(
            database = AppConfig.Database(url = "unused", user = "unused", password = "unused"),
            jwt = AppConfig.Jwt(secret = "unused", issuer = "unused", audience = "unused"),
            security = AppConfig.Security(passwordPepper = "unused"),
            cors = AppConfig.Cors(),
            email = AppConfig.Email(verificationTokenExpirationHours = 24),
        )

    private class RecordingEmailService : EmailService {
        val sentTokens = mutableListOf<Pair<String, String>>()

        override suspend fun sendVerificationEmail(
            to: String,
            token: String,
        ) {
            sentTokens += to to token
        }

        override suspend fun sendPasswordResetEmail(
            to: String,
            token: String,
        ) {
            sentTokens += to to token
        }
    }
}
