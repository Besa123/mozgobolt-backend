/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.feature.user.data.repository

import com.mozgobolt.core.domain.security.SecureTokenGenerator
import com.mozgobolt.core.skipIfNoDocker
import com.mozgobolt.core.withRealDatabase
import com.mozgobolt.feature.company.data.repository.CompanyMembershipRepositoryI
import com.mozgobolt.feature.company.data.repository.CompanyRepositoryI
import com.mozgobolt.feature.company.domain.model.CompanyConstraints
import com.mozgobolt.feature.company.domain.model.CompanyRole
import com.mozgobolt.feature.user.data.database.UsersTable
import com.mozgobolt.feature.user.domain.model.UserRole
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import java.sql.SQLException
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UserCompanyAdminDeleteRealDatabaseTest {
    @Test
    fun `concurrent deletion of two admins cannot leave a company without an admin`() {
        skipIfNoDocker()

        withRealDatabase { _, tx ->
            val companyRepository = CompanyRepositoryI()
            val membershipRepository = CompanyMembershipRepositoryI()
            val company =
                tx.transactional {
                    companyRepository.create("Concurrent Admin Company", newInviteCode()).also { created ->
                        membershipRepository.addIfAbsent(created.id, 1, CompanyRole.ADMIN)
                        membershipRepository.addIfAbsent(created.id, 2, CompanyRole.ADMIN)
                    }
                }

            val deletionResults = concurrentlyDeleteUsers(tx, listOf(1, 2))

            assertEquals(1, deletionResults.count { it }, "one admin delete should succeed")
            assertEquals(1, deletionResults.count { !it }, "the competing delete must be rejected")
            assertEquals(1, tx.transactional { membershipRepository.countAdmins(company.id) })

            val userRepository = UserRepositoryI()
            val remainingUserIds =
                listOf(1, 2).filter { userId ->
                    tx.transactional { userRepository.findUserById(userId) != null }
                }
            assertEquals(1, remainingUserIds.size)
            assertEquals(
                CompanyRole.ADMIN,
                tx.transactional { membershipRepository.find(company.id, remainingUserIds.single())?.role },
            )
        }
    }

    @Test
    fun `deleting a user is blocked if any of their companies would lose its last admin`() {
        skipIfNoDocker()

        withRealDatabase { _, tx ->
            val companyRepository = CompanyRepositoryI()
            val membershipRepository = CompanyMembershipRepositoryI()
            val userRepository = UserRepositoryI()
            val (firstCompany, secondCompany, thirdUserId) =
                tx.transactional {
                    val thirdUser =
                        checkNotNull(
                            userRepository.createUser(
                                email = "third-admin-delete-test@example.test",
                                password = "test-hash",
                                name = "Third User",
                                role = UserRole.BUYER,
                            ),
                        )
                    val first =
                        companyRepository.create("First Admin Delete Company", newInviteCode())
                    val second =
                        companyRepository.create("Second Admin Delete Company", newInviteCode())
                    membershipRepository.addIfAbsent(first.id, 1, CompanyRole.ADMIN)
                    membershipRepository.addIfAbsent(first.id, 2, CompanyRole.ADMIN)
                    membershipRepository.addIfAbsent(second.id, 1, CompanyRole.ADMIN)
                    membershipRepository.addIfAbsent(second.id, thirdUser.id, CompanyRole.MEMBER)
                    Triple(first, second, thirdUser.id)
                }

            assertFalse(deleteUser(tx, userId = 1))
            assertNotNull(tx.transactional { userRepository.findUserById(1) })
            assertEquals(2, tx.transactional { membershipRepository.countAdmins(firstCompany.id) })
            assertEquals(1, tx.transactional { membershipRepository.countAdmins(secondCompany.id) })

            tx.transactional {
                membershipRepository.updateRole(secondCompany.id, userId = thirdUserId, role = CompanyRole.ADMIN)
            }

            assertTrue(deleteUser(tx, userId = 1))
            assertNull(tx.transactional { membershipRepository.find(firstCompany.id, 1) })
            assertEquals(CompanyRole.ADMIN, tx.transactional { membershipRepository.find(firstCompany.id, 2)?.role })
            assertNull(tx.transactional { membershipRepository.find(secondCompany.id, 1) })
            assertEquals(
                CompanyRole.ADMIN,
                tx.transactional { membershipRepository.find(secondCompany.id, thirdUserId)?.role },
            )
        }
    }

    private suspend fun concurrentlyDeleteUsers(
        tx: com.mozgobolt.core.database.TransactionalRunner,
        userIds: List<Int>,
    ): List<Boolean> {
        val start = CyclicBarrier(userIds.size)
        return coroutineScope {
            userIds
                .map { userId ->
                    async(Dispatchers.IO) {
                        start.await(10, TimeUnit.SECONDS)
                        deleteUser(tx, userId)
                    }
                }.map { it.await() }
        }
    }

    private suspend fun deleteUser(
        tx: com.mozgobolt.core.database.TransactionalRunner,
        userId: Int,
    ): Boolean =
        try {
            tx.transactional {
                UsersTable.deleteWhere { UsersTable.id eq userId } == 1
            }
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            val isLastAdminViolation =
                generateSequence<Throwable>(failure) { it.cause }
                    .filterIsInstance<SQLException>()
                    .any { it.sqlState == "23514" }
            if (isLastAdminViolation) {
                false
            } else {
                throw failure
            }
        }

    private fun newInviteCode() = SecureTokenGenerator.generate(CompanyConstraints.INVITE_CODE_BYTE_LENGTH)
}
