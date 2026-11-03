/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.feature.user

import com.mozgobolt.core.data.idempotency.ExposedIdempotencyStore
import com.mozgobolt.core.data.idempotency.IdempotencyExecution
import com.mozgobolt.core.data.idempotency.IdempotentResult
import com.mozgobolt.core.data.security.JwtTokenManager
import com.mozgobolt.core.data.security.PasswordServiceImpl
import com.mozgobolt.core.data.validator.StandardEmailValidator
import com.mozgobolt.core.data.validator.StandardPasswordValidator
import com.mozgobolt.core.database.DatabaseFactory
import com.mozgobolt.core.database.ExposedTransactionalRunner
import com.mozgobolt.core.database.TransactionalRunner
import com.mozgobolt.core.domain.AppResult
import com.mozgobolt.core.domain.security.PasswordBreachChecker
import com.mozgobolt.core.domain.security.PasswordService
import com.mozgobolt.core.domain.security.TokenManager
import com.mozgobolt.core.modules.AppConfig
import com.mozgobolt.feature.user.data.repository.UserRepositoryI
import com.mozgobolt.feature.user.domain.UserRepository
import com.mozgobolt.feature.user.domain.model.RefreshError
import com.mozgobolt.feature.user.domain.model.UserRole
import com.mozgobolt.feature.user.service.FakeVehicleAssignmentSessionTerminator
import com.mozgobolt.feature.user.service.RecordingUserEmailOutboxRepository
import com.mozgobolt.feature.user.service.UserServiceI
import com.mozgobolt.feature.user.service.UserSessionServiceI
import com.zaxxer.hikari.HikariDataSource
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
import org.junit.Assume.assumeTrue
import org.testcontainers.DockerClientFactory
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.test.fail

open class RealUserServiceDatabaseTestSupport {
    protected data class Harness(
        val service: UserServiceI,
        val sessionService: UserSessionServiceI,
        val emailOutbox: RecordingUserEmailOutboxRepository,
        val userRepository: UserRepository,
        val tx: TransactionalRunner,
        val tokenManager: TokenManager,
    )

    protected fun skipIfNoDocker() {
        assumeTrue(
            "Docker is not available locally — skipping (this still runs in CI)",
            DockerClientFactory.instance().isDockerAvailable,
        )
    }

    protected fun withRealDatabase(
        userRepository: UserRepository = UserRepositoryI(),
        passwordService: PasswordService = PasswordServiceImpl(pepper = "integration-test-pepper"),
        block: suspend (Harness, Database) -> Unit,
    ) {
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
                val emailOutbox = RecordingUserEmailOutboxRepository()
                val tokenManager =
                    JwtTokenManager(
                        secret = "integration-test-secret",
                        issuer = "mozgobolt-test",
                        audience = "mozgobolt-test",
                    )
                val tx = ExposedTransactionalRunner(database)
                val sessionTerminator = FakeVehicleAssignmentSessionTerminator()
                val service =
                    UserServiceI(
                        userRepository = userRepository,
                        passwordService = passwordService,
                        tokenManager = tokenManager,
                        passwordValidator =
                            StandardPasswordValidator(
                                PasswordBreachChecker { AppResult.Success(false) },
                            ),
                        emailValidator = StandardEmailValidator(),
                        emailOutboxRepository = emailOutbox,
                        appConfig = testAppConfig(),
                        tx = tx,
                    )
                val sessionService =
                    UserSessionServiceI(
                        userRepository = userRepository,
                        passwordService = passwordService,
                        vehicleAssignmentSessionTerminator = sessionTerminator,
                        tx = tx,
                    )

                runBlocking {
                    block(Harness(service, sessionService, emailOutbox, userRepository, tx, tokenManager), database)
                }
            } finally {
                (dataSource as HikariDataSource).close()
            }
        } finally {
            postgres.stop()
        }
    }

    protected suspend fun exerciseAuthFlow(
        service: UserServiceI,
        emailOutbox: RecordingUserEmailOutboxRepository,
    ) {
        service
            .createUser(
                password = "Str0ngPassphrase1",
                email = "integration@example.com",
                name = "Integration Tester",
                role = UserRole.BUYER,
            ).fold(onSuccess = {}, onError = { fail("register failed: $it") })

        val verificationToken = emailOutbox.history.single().rawToken
        service.verifyEmail(verificationToken).fold(onSuccess = {}, onError = { fail("verify failed: $it") })

        val signIn =
            service
                .signInUser(password = "Str0ngPassphrase1", email = "integration@example.com")
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

    protected suspend fun exerciseIdempotencyKeysTable(database: Database) {
        val store =
            ExposedIdempotencyStore(
                database = database,
                fingerprintSecret = "integration-test-idempotency-fingerprint-secret",
            )

        val fingerprint = store.hashFingerprint("fp")
        val body = """{"ok":true}"""
        assertIs<IdempotencyExecution.Completed>(
            store.execute(key = "integration-test-key", fingerprint = fingerprint) {
                IdempotentResult(HttpStatusCode.Created, body)
            },
        )

        val record = store.find("integration-test-key")
        assertEquals(201, record?.statusCode)
        assertEquals("""{"ok":true}""", record?.responseBody)
        assertEquals(fingerprint, record?.requestFingerprint)
        assertNotEquals("fp", record?.requestFingerprint)
    }

    private fun testAppConfig() =
        AppConfig(
            database = AppConfig.Database(url = "unused", user = "unused", password = "unused"),
            jwt = AppConfig.Jwt(secret = "unused", issuer = "unused", audience = "unused"),
            security = AppConfig.Security(passwordPepper = "unused"),
            cors = AppConfig.Cors(),
            email = AppConfig.Email(verificationTokenExpirationHours = 24),
        )
}
