/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.feature.user.service

import com.mozgobolt.core.domain.security.PasswordService
import com.mozgobolt.feature.user.domain.model.AccountDeletionError
import com.mozgobolt.feature.user.domain.model.RefreshError
import com.mozgobolt.feature.vehicleAssignment.domain.model.VehicleAssignment
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.fail

class UserServiceLogoutTest {
    @Test
    fun `logging out revokes only the presented refresh token`() {
        runBlocking {
            val harness = newHarness()
            val user = harness.repository.seedVerifiedUser(email = "user@example.com", password = "Str0ngPass")
            val signIn =
                harness.service
                    .signInUser(password = "Str0ngPass", email = "user@example.com")
                    .fold(onSuccess = { it }, onError = { fail("expected success but got $it") })
            val familyId = checkNotNull(harness.tokenManager.lastGeneratedAccessFamilyId)

            harness.sessionService.logoutUser(userId = user.id, familyId = familyId)

            val result = harness.service.refreshToken(signIn.refreshToken)
            assertError(RefreshError.TOKEN_REUSE_DETECTED, result)
            assertEquals(listOf(user.id), harness.assignmentTerminator.endedForUsers)
        }
    }

    @Test
    fun `logging out with an unknown refresh token does not throw`() {
        runBlocking {
            val harness = newHarness()

            harness.sessionService.logoutUser(userId = 1, familyId = "unknown-family")
        }
    }

    @Test
    fun `logging out of all sessions revokes every refresh token for that user`() {
        runBlocking {
            val harness = newHarness()
            val user = harness.repository.seedVerifiedUser(email = "user@example.com", password = "Str0ngPass")
            val sessionA =
                harness.service
                    .signInUser(password = "Str0ngPass", email = "user@example.com")
                    .fold(onSuccess = { it }, onError = { fail("expected success but got $it") })
            val sessionB =
                harness.service
                    .signInUser(password = "Str0ngPass", email = "user@example.com")
                    .fold(onSuccess = { it }, onError = { fail("expected success but got $it") })

            harness.sessionService.logoutAllSessions(user.id)

            assertError(RefreshError.TOKEN_REUSE_DETECTED, harness.service.refreshToken(sessionA.refreshToken))
            assertError(RefreshError.TOKEN_REUSE_DETECTED, harness.service.refreshToken(sessionB.refreshToken))
            assertEquals(listOf(user.id), harness.assignmentTerminator.endedForUsers)
        }
    }

    @Test
    fun `deleting an account verifies the current password revokes sessions and clears the active vehicle`() {
        runBlocking {
            val harness = newHarness()
            val user = harness.repository.seedVerifiedUser(email = "user@example.com", password = "Str0ngPass")
            val session =
                harness.service
                    .signInUser(password = "Str0ngPass", email = "user@example.com")
                    .fold(onSuccess = { it }, onError = { fail("expected success but got $it") })
            val assignment =
                VehicleAssignment(
                    id = 12,
                    vehicleId = 4,
                    vendorUserId = user.id,
                    startedAt = java.time.Instant.parse("2026-01-01T00:00:00Z"),
                )
            harness.assignmentTerminator.activeAssignment = assignment

            val result = harness.sessionService.deleteAccount(user.id, "Str0ngPass")

            result.fold(onSuccess = {}, onError = { fail("expected success but got $it") })
            assertNull(harness.repository.findUserById(user.id))
            assertEquals(listOf(assignment), harness.assignmentTerminator.notifiedAssignments)
            assertError(RefreshError.INVALID_CREDENTIALS, harness.service.refreshToken(session.refreshToken))
        }
    }

    @Test
    fun `deleting an account with the wrong password changes no state`() {
        runBlocking {
            val harness = newHarness()
            val user = harness.repository.seedVerifiedUser(email = "user@example.com", password = "Str0ngPass")
            val assignment =
                VehicleAssignment(
                    id = 12,
                    vehicleId = 4,
                    vendorUserId = user.id,
                    startedAt = java.time.Instant.parse("2026-01-01T00:00:00Z"),
                )
            harness.assignmentTerminator.activeAssignment = assignment

            val result = harness.sessionService.deleteAccount(user.id, "wrong password")

            assertError(com.mozgobolt.feature.user.domain.model.AccountDeletionError.INVALID_CREDENTIALS, result)
            assertEquals(user, harness.repository.findUserById(user.id))
            assertEquals(emptyList(), harness.assignmentTerminator.notifiedAssignments)
        }
    }

    @Test
    fun `deleting an account fails if its password changes after verification`() {
        runBlocking {
            val harness = newHarness()
            val user = harness.repository.seedVerifiedUser(email = "user@example.com", password = "Str0ngPass")
            val passwordService =
                object : PasswordService {
                    override suspend fun hashPassword(password: String): String = "hashed:$password"

                    override suspend fun verifyPassword(
                        password: String,
                        hash: String,
                    ): Boolean {
                        harness.repository.updatePassword(user.id, "hashed:changed")
                        return true
                    }
                }
            val sessionService =
                UserSessionServiceI(
                    userRepository = harness.repository,
                    passwordService = passwordService,
                    vehicleAssignmentSessionTerminator = harness.assignmentTerminator,
                    tx = NoopTransactionalRunner(),
                )

            val result = sessionService.deleteAccount(user.id, "Str0ngPass")

            assertError(AccountDeletionError.INVALID_CREDENTIALS, result)
            assertEquals("hashed:changed", harness.repository.findUserById(user.id)?.passwordHash)
        }
    }
}
