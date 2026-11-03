/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.feature.user.service

import com.mozgobolt.core.domain.security.PasswordService
import com.mozgobolt.core.domain.security.SecureTokenGenerator
import com.mozgobolt.core.skipIfNoDocker
import com.mozgobolt.core.withRealDatabase
import com.mozgobolt.feature.company.data.repository.CompanyMembershipRepositoryI
import com.mozgobolt.feature.company.data.repository.CompanyRepositoryI
import com.mozgobolt.feature.company.domain.model.CompanyConstraints
import com.mozgobolt.feature.company.domain.model.CompanyRole
import com.mozgobolt.feature.sync.routing.FakeSyncService
import com.mozgobolt.feature.user.data.repository.UserRepositoryI
import com.mozgobolt.feature.user.domain.model.AccountDeletionError
import com.mozgobolt.feature.user.domain.model.UserRole
import com.mozgobolt.feature.vehicle.data.repository.VehicleRepositoryI
import com.mozgobolt.feature.vehicleAssignment.data.database.VehicleAssignmentsTable
import com.mozgobolt.feature.vehicleAssignment.data.repository.VehicleAssignmentRepositoryI
import com.mozgobolt.feature.vehicleAssignment.service.VehicleAssignmentServiceI
import com.mozgobolt.feature.vehicleTracking.domain.model.VehicleLocationUpdate
import com.mozgobolt.feature.vehicleTracking.service.H3CellIndexer
import com.mozgobolt.feature.vehicleTracking.service.InMemoryVehicleLocationHub
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.fail

class UserAccountDeletionRealDatabaseTest {
    private val passwordService =
        object : PasswordService {
            override suspend fun hashPassword(password: String): String = "hashed:$password"

            override suspend fun verifyPassword(
                password: String,
                hash: String,
            ): Boolean = hash == hashPassword(password)
        }

    @Test
    fun `account deletion cascades user data and clears active vendor from live map after commit`() {
        skipIfNoDocker()

        withRealDatabase { _, tx ->
            val userRepository = UserRepositoryI()
            val companyRepository = CompanyRepositoryI()
            val membershipRepository = CompanyMembershipRepositoryI()
            val vehicleRepository = VehicleRepositoryI()
            val assignmentRepository = VehicleAssignmentRepositoryI()
            val locationHub = InMemoryVehicleLocationHub(H3CellIndexer())
            val (userId, companyId) =
                tx.transactional {
                    val user =
                        checkNotNull(
                            userRepository.createUser(
                                email = "delete-account@example.test",
                                password = passwordService.hashPassword("current passphrase"),
                                name = "Account Owner",
                                role = UserRole.VENDOR,
                            ),
                        )
                    val company =
                        companyRepository.create(
                            "Account deletion company",
                            SecureTokenGenerator.generate(CompanyConstraints.INVITE_CODE_BYTE_LENGTH),
                        )
                    membershipRepository.addIfAbsent(company.id, user.id, CompanyRole.ADMIN)
                    membershipRepository.addIfAbsent(company.id, 2, CompanyRole.ADMIN)
                    val vehicle =
                        checkNotNull(
                            vehicleRepository.create(
                                companyId = company.id,
                                label = "Active vehicle",
                                licensePlate = "DELETE-ACCOUNT",
                            ),
                        )
                    val assignment =
                        checkNotNull(
                            assignmentRepository.startAssignment(
                                vehicleId = vehicle.id,
                                vendorUserId = user.id,
                                startedAt = Instant.now(),
                            ),
                        )
                    val indexer = H3CellIndexer()
                    locationHub.publish(
                        VehicleLocationUpdate(
                            vehicleId = vehicle.id,
                            vendorUserId = user.id,
                            latitude = 47.4979,
                            longitude = 19.0402,
                            recordedAt = Instant.now(),
                            cellId = indexer.cellFor(47.4979, 19.0402),
                            assignmentId = assignment.id,
                        ),
                    )
                    user.id to company.id
                }
            val assignmentService =
                VehicleAssignmentServiceI(
                    vehicleAssignmentRepository = assignmentRepository,
                    companyRepository = companyRepository,
                    vehicleRepository = vehicleRepository,
                    membershipRepository = membershipRepository,
                    syncService = FakeSyncService(),
                    hub = locationHub,
                    tx = tx,
                )
            val sessionService =
                UserSessionServiceI(
                    userRepository = userRepository,
                    passwordService = passwordService,
                    vehicleAssignmentSessionTerminator = assignmentService,
                    tx = tx,
                )
            tx.transactional {
                userRepository.saveRefreshToken(userId, "stored-refresh-token-hash", "account-family")
            }
            val activeAssignment = tx.transactional { assignmentRepository.findActiveForVendor(userId) }
            val vehicleId = checkNotNull(activeAssignment).vehicleId

            sessionService.deleteAccount(userId, "current passphrase").fold(
                onSuccess = {},
                onError = { fail("expected deletion to succeed but got $it") },
            )

            assertNull(tx.transactional { userRepository.findUserById(userId) })
            assertNull(tx.transactional { assignmentRepository.findActiveForVendor(userId) })
            assertEquals(
                0L,
                tx.transactional {
                    VehicleAssignmentsTable
                        .selectAll()
                        .where { VehicleAssignmentsTable.vendorUserId eq userId }
                        .count()
                },
            )
            assertNull(locationHub.lastKnownLocation(vehicleId))
            assertEquals(
                com.mozgobolt.feature.user.domain.model.TokenValidationResult.NotFound,
                tx.transactional {
                    userRepository.validateAndRevokeRefreshToken(userId, "stored-refresh-token-hash")
                },
            )
        }
    }

    @Test
    fun `account deletion is rejected atomically when the user is a company's last admin`() {
        skipIfNoDocker()

        withRealDatabase { _, tx ->
            val userRepository = UserRepositoryI()
            val companyRepository = CompanyRepositoryI()
            val membershipRepository = CompanyMembershipRepositoryI()
            val (userId, companyId) =
                tx.transactional {
                    val user =
                        checkNotNull(
                            userRepository.createUser(
                                email = "last-admin-delete@example.test",
                                password = passwordService.hashPassword("current passphrase"),
                                name = "Last Admin",
                                role = UserRole.VENDOR,
                            ),
                        )
                    val company =
                        companyRepository.create(
                            "Last admin company",
                            SecureTokenGenerator.generate(CompanyConstraints.INVITE_CODE_BYTE_LENGTH),
                        )
                    membershipRepository.addIfAbsent(company.id, user.id, CompanyRole.ADMIN)
                    user.id to company.id
                }
            val sessionService =
                UserSessionServiceI(
                    userRepository = userRepository,
                    passwordService = passwordService,
                    vehicleAssignmentSessionTerminator =
                        VehicleAssignmentServiceI(
                            vehicleAssignmentRepository = VehicleAssignmentRepositoryI(),
                            companyRepository = companyRepository,
                            vehicleRepository = VehicleRepositoryI(),
                            membershipRepository = membershipRepository,
                            syncService = FakeSyncService(),
                            hub = InMemoryVehicleLocationHub(H3CellIndexer()),
                            tx = tx,
                        ),
                    tx = tx,
                )

            sessionService.deleteAccount(userId, "current passphrase").fold(
                onSuccess = { fail("last company admin must transfer admin first") },
                onError = { assertEquals(AccountDeletionError.LAST_COMPANY_ADMIN, it) },
            )

            assertNotNull(tx.transactional { userRepository.findUserById(userId) })
            assertEquals(1, tx.transactional { membershipRepository.countAdmins(companyId) })
        }
    }
}
