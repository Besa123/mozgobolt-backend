/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.feature.user.service

import com.mozgobolt.core.database.TransactionalRunner
import com.mozgobolt.core.domain.AppResult
import com.mozgobolt.core.domain.security.PasswordService
import com.mozgobolt.feature.user.domain.UserRepository
import com.mozgobolt.feature.user.domain.UserSessionService
import com.mozgobolt.feature.user.domain.model.AccountDeletionError
import com.mozgobolt.feature.vehicleAssignment.domain.VehicleAssignmentSessionTerminator
import org.postgresql.util.PSQLException
import java.sql.SQLException

class UserSessionServiceI(
    private val userRepository: UserRepository,
    private val passwordService: PasswordService,
    private val vehicleAssignmentSessionTerminator: VehicleAssignmentSessionTerminator,
    private val tx: TransactionalRunner,
) : UserSessionService {
    override suspend fun logoutUser(
        userId: Int,
        familyId: String,
    ) {
        val endedAssignment =
            tx.transactional {
                userRepository.findUserByIdForUpdate(userId) ?: return@transactional null
                userRepository.revokeTokenFamily(familyId)
                vehicleAssignmentSessionTerminator.endActiveAssignmentForVendor(userId)
            }
        endedAssignment?.let { vehicleAssignmentSessionTerminator.notifyEndedAssignments(listOf(it)) }
    }

    override suspend fun logoutAllSessions(userId: Int) {
        val endedAssignment =
            tx.transactional {
                userRepository.findUserByIdForUpdate(userId) ?: return@transactional null
                userRepository.revokeAllTokensForUser(userId)
                vehicleAssignmentSessionTerminator.endActiveAssignmentForVendor(userId)
            }
        endedAssignment?.let { vehicleAssignmentSessionTerminator.notifyEndedAssignments(listOf(it)) }
    }

    @Suppress("ReturnCount")
    override suspend fun deleteAccount(
        userId: Int,
        currentPassword: String,
    ): AppResult<Unit, AccountDeletionError> {
        val user =
            tx.transactional { userRepository.findUserById(userId) }
                ?: return AppResult.Error(AccountDeletionError.USER_NOT_FOUND)
        if (!passwordService.verifyPassword(currentPassword, user.passwordHash)) {
            return AppResult.Error(AccountDeletionError.INVALID_CREDENTIALS)
        }

        val outcome =
            try {
                tx.transactional {
                    val currentUser =
                        userRepository.findUserByIdForUpdate(userId)
                            ?: return@transactional AppResult.Error(AccountDeletionError.USER_NOT_FOUND) to null

                    if (currentUser.passwordHash != user.passwordHash) {
                        return@transactional AppResult.Error(AccountDeletionError.INVALID_CREDENTIALS) to null
                    }

                    // The user-row lock prevents a concurrent FK-backed assignment from committing
                    // after this snapshot. Deletion cascades the assignment; clear its live-map
                    // entry only after the account transaction commits.
                    val activeAssignment = vehicleAssignmentSessionTerminator.findActiveForVendor(userId)
                    userRepository.revokeAllTokensForUser(userId)
                    if (!userRepository.deleteUser(userId)) {
                        return@transactional AppResult.Error(AccountDeletionError.USER_NOT_FOUND) to null
                    }

                    AppResult.Success(Unit) to activeAssignment
                }
            } catch (failure: SQLException) {
                if (failure.isLastCompanyAdminDeleteViolation()) {
                    return AppResult.Error(AccountDeletionError.LAST_COMPANY_ADMIN)
                }
                throw failure
            }

        val (result, activeAssignment) = outcome
        if (result is AppResult.Success) {
            activeAssignment?.let {
                vehicleAssignmentSessionTerminator.notifyEndedAssignments(listOf(it))
            }
        }
        return result
    }
}

private fun Throwable.isLastCompanyAdminDeleteViolation(): Boolean =
    generateSequence<Throwable>(this) { it.cause }
        .filterIsInstance<PSQLException>()
        .any {
            it.sqlState == "23514" &&
                it.serverErrorMessage?.constraint == "users_last_company_admin_delete"
        }
