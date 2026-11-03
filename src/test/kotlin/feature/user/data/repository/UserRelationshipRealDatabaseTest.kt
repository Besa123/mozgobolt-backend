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
import com.mozgobolt.feature.companyFavorite.data.repository.CompanyFavoriteRepositoryI
import com.mozgobolt.feature.deviceInstallation.data.repository.DeviceInstallationRepositoryI
import com.mozgobolt.feature.deviceInstallation.domain.model.DevicePlatform
import com.mozgobolt.feature.proximityNotification.data.repository.ProximityNotificationRepositoryI
import com.mozgobolt.feature.savedLocation.data.repository.SavedLocationRepositoryI
import com.mozgobolt.feature.sync.data.repository.SyncRepositoryI
import com.mozgobolt.feature.sync.domain.model.SyncEntityType
import com.mozgobolt.feature.sync.domain.model.SyncOperation
import com.mozgobolt.feature.user.data.database.UsersTable
import com.mozgobolt.feature.user.domain.model.TokenValidationResult
import com.mozgobolt.feature.vehicle.data.repository.VehicleRepositoryI
import com.mozgobolt.feature.vehicleAssignment.data.database.VehicleAssignmentsTable
import com.mozgobolt.feature.vehicleAssignment.data.repository.VehicleAssignmentRepositoryI
import com.mozgobolt.feature.vehiclePing.data.database.VehiclePingsTable
import com.mozgobolt.feature.vehicleTracking.data.database.VehicleLocationsTable
import com.mozgobolt.feature.vehicleTracking.data.repository.VehicleLocationRepositoryI
import com.mozgobolt.feature.vehicleTracking.domain.model.VehicleLocationUpdate
import com.mozgobolt.feature.vehicleTracking.service.H3CellIndexer
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.sql.SQLException
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UserRelationshipRealDatabaseTest {
    private data class Fixture(
        val companyId: Int,
        val vehicleId: Int,
        val assignmentId: Int,
        val deletedUserSavedLocationId: Int,
        val retainedUserSavedLocationId: Int,
    )

    @Test
    fun `deleting the last company admin is blocked until another member is promoted`() {
        skipIfNoDocker()

        withRealDatabase { _, tx ->
            val fixture = seedRelationships(tx)
            val failure =
                assertFails {
                    tx.transactional {
                        UsersTable.deleteWhere { UsersTable.id eq 1 }
                    }
                }

            assertTrue(
                generateSequence<Throwable>(failure) { it.cause }
                    .filterIsInstance<SQLException>()
                    .any { it.sqlState == "23514" },
                "deleting the last admin must fail with the explicit PostgreSQL invariant error",
            )
            assertNotNull(tx.transactional { UserRepositoryI().findUserById(1) })
            assertEquals(
                CompanyRole.ADMIN,
                tx.transactional { CompanyMembershipRepositoryI().find(fixture.companyId, 1)?.role },
            )
            assertNotNull(
                tx.transactional {
                    SavedLocationRepositoryI().findById(fixture.deletedUserSavedLocationId)
                },
                "a rejected user delete must not partially cascade owned records",
            )
            assertEquals(
                setOf(1 to null, 1 to fixture.assignmentId, 2 to null),
                tx.transactional {
                    VehicleLocationsTable
                        .selectAll()
                        .where { VehicleLocationsTable.vehicleId eq fixture.vehicleId }
                        .map { it[VehicleLocationsTable.vendorUserId] to it[VehicleLocationsTable.assignmentId] }
                        .toSet()
                },
                "a rejected user delete must not clear or cascade telemetry",
            )

            tx.transactional {
                CompanyMembershipRepositoryI().updateRole(fixture.companyId, 2, CompanyRole.ADMIN)
            }
            assertEquals(1, tx.transactional { UsersTable.deleteWhere { UsersTable.id eq 1 } })

            assertUserCascadeEffects(tx, fixture)
        }
    }

    private suspend fun seedRelationships(tx: com.mozgobolt.core.database.TransactionalRunner): Fixture {
        val companyRepository = CompanyRepositoryI()
        val membershipRepository = CompanyMembershipRepositoryI()
        val vehicleRepository = VehicleRepositoryI()
        val assignmentRepository = VehicleAssignmentRepositoryI()
        val userRepository = UserRepositoryI()
        val favoriteRepository = CompanyFavoriteRepositoryI()
        val savedLocationRepository = SavedLocationRepositoryI()
        val deviceInstallationRepository = DeviceInstallationRepositoryI()
        val proximityNotificationRepository = ProximityNotificationRepositoryI()
        val vehicleLocationRepository = VehicleLocationRepositoryI()
        val syncRepository = SyncRepositoryI()
        val cellIndexer = H3CellIndexer()
        val now = Instant.now()

        return tx.transactional {
            val company =
                companyRepository.create(
                    name = "Relationship Company",
                    inviteCode = SecureTokenGenerator.generate(CompanyConstraints.INVITE_CODE_BYTE_LENGTH),
                )
            membershipRepository.addIfAbsent(company.id, 1, CompanyRole.ADMIN)
            membershipRepository.addIfAbsent(company.id, 2, CompanyRole.MEMBER)

            val vehicle = checkNotNull(vehicleRepository.create(company.id, "Relationship Truck", "RELATION-PLATE"))
            val deletedUserSavedLocation =
                savedLocationRepository.create(1, "Deleted user's home", 47.4979, 19.0402, 2.5)
            val retainedUserSavedLocation =
                savedLocationRepository.create(2, "Retained user's home", 47.5, 19.1, 2.5)
            val assignment =
                checkNotNull(assignmentRepository.startAssignment(vehicle.id, vendorUserId = 1, startedAt = now))

            favoriteRepository.addIfAbsent(userId = 1, companyId = company.id)
            favoriteRepository.addIfAbsent(userId = 2, companyId = company.id)
            deviceInstallationRepository.register(1, "deleted-user-installation", DevicePlatform.ANDROID, now)
            deviceInstallationRepository.register(2, "retained-user-installation", DevicePlatform.IOS, now)
            userRepository.saveRefreshToken(1, "deleted-user-refresh-hash", "deleted-user-family")
            userRepository.saveRefreshToken(2, "retained-user-refresh-hash", "retained-user-family")
            userRepository.createVerificationToken(1, "deleted-user-verification", now.plusSeconds(60))
            userRepository.createPasswordResetToken(1, "deleted-user-reset", now.plusSeconds(60))

            vehicleLocationRepository.insertAll(
                listOf(
                    locationUpdate(vehicle.id, 1, cellIndexer, now),
                    locationUpdate(vehicle.id, 1, cellIndexer, now.plusSeconds(1), assignment.id),
                    locationUpdate(vehicle.id, 2, cellIndexer, now.plusSeconds(2)),
                ),
            )
            proximityNotificationRepository.recordEntryAttempted(
                buyerUserId = 1,
                savedLocationId = deletedUserSavedLocation.id,
                vehicleId = vehicle.id,
                attemptedAt = now,
            )
            proximityNotificationRepository.recordEntryAttempted(
                buyerUserId = 2,
                savedLocationId = retainedUserSavedLocation.id,
                vehicleId = vehicle.id,
                attemptedAt = now,
            )

            listOf(1, 2).forEach { buyerUserId ->
                VehiclePingsTable.insert {
                    it[VehiclePingsTable.vehicleId] = vehicle.id
                    it[VehiclePingsTable.buyerUserId] = buyerUserId
                    it[VehiclePingsTable.sentAt] = now
                }
                syncRepository.record(
                    userId = buyerUserId,
                    entityType = SyncEntityType.COMPANY,
                    entityId = company.id,
                    operation = SyncOperation.UPSERT,
                    originDeviceId = null,
                )
            }

            Fixture(
                companyId = company.id,
                vehicleId = vehicle.id,
                assignmentId = assignment.id,
                deletedUserSavedLocationId = deletedUserSavedLocation.id,
                retainedUserSavedLocationId = retainedUserSavedLocation.id,
            )
        }
    }

    private suspend fun assertUserCascadeEffects(
        tx: com.mozgobolt.core.database.TransactionalRunner,
        fixture: Fixture,
    ) {
        val userRepository = UserRepositoryI()
        val companyRepository = CompanyRepositoryI()
        val membershipRepository = CompanyMembershipRepositoryI()
        val vehicleRepository = VehicleRepositoryI()
        val assignmentRepository = VehicleAssignmentRepositoryI()
        val favoriteRepository = CompanyFavoriteRepositoryI()
        val savedLocationRepository = SavedLocationRepositoryI()
        val deviceInstallationRepository = DeviceInstallationRepositoryI()
        val proximityNotificationRepository = ProximityNotificationRepositoryI()
        val syncRepository = SyncRepositoryI()

        assertNull(tx.transactional { userRepository.findUserById(1) })
        assertNotNull(tx.transactional { userRepository.findUserById(2) })
        assertNotNull(tx.transactional { companyRepository.findById(fixture.companyId) })
        assertNotNull(tx.transactional { vehicleRepository.findById(fixture.vehicleId) })

        assertNull(tx.transactional { membershipRepository.find(fixture.companyId, 1) })
        assertEquals(CompanyRole.ADMIN, tx.transactional { membershipRepository.find(fixture.companyId, 2)?.role })
        assertEquals(listOf(2), tx.transactional { favoriteRepository.findUserIdsFavoritingCompany(fixture.companyId) })
        assertTrue(tx.transactional { favoriteRepository.findAllForUser(1) }.isEmpty())

        assertTrue(tx.transactional { deviceInstallationRepository.findAllForUser(1) }.isEmpty())
        assertEquals(1, tx.transactional { deviceInstallationRepository.findAllForUser(2) }.size)
        assertEquals(
            TokenValidationResult.NotFound,
            tx.transactional { userRepository.validateAndRevokeRefreshToken(1, "deleted-user-refresh-hash") },
        )
        val retainedRefreshResult =
            tx.transactional {
                userRepository.validateAndRevokeRefreshToken(2, "retained-user-refresh-hash")
            }
        assertTrue(
            retainedRefreshResult is TokenValidationResult.Valid &&
                retainedRefreshResult.familyId == "retained-user-family",
        )
        assertNull(tx.transactional { userRepository.findVerificationToken("deleted-user-verification") })
        assertNull(tx.transactional { userRepository.findPasswordResetToken("deleted-user-reset") })

        assertNull(tx.transactional { savedLocationRepository.findById(fixture.deletedUserSavedLocationId) })
        assertNotNull(tx.transactional { savedLocationRepository.findById(fixture.retainedUserSavedLocationId) })
        assertNull(
            tx.transactional {
                proximityNotificationRepository.find(
                    buyerUserId = 1,
                    savedLocationId = fixture.deletedUserSavedLocationId,
                    vehicleId = fixture.vehicleId,
                )
            },
            "the deleted user's notification state must cascade with their saved location",
        )
        assertNotNull(
            tx.transactional {
                proximityNotificationRepository.find(
                    buyerUserId = 2,
                    savedLocationId = fixture.retainedUserSavedLocationId,
                    vehicleId = fixture.vehicleId,
                )
            },
        )

        assertEquals(
            0,
            tx.transactional {
                VehicleAssignmentsTable
                    .selectAll()
                    .where { VehicleAssignmentsTable.id eq fixture.assignmentId }
                    .count()
            },
        )
        assertNull(tx.transactional { assignmentRepository.findActiveForVendor(1) })
        assertEquals(
            listOf(2),
            tx.transactional {
                VehiclePingsTable
                    .selectAll()
                    .where { VehiclePingsTable.vehicleId eq fixture.vehicleId }
                    .map { it[VehiclePingsTable.buyerUserId] }
            },
        )
        assertTrue(tx.transactional { syncRepository.findSince(userId = 1, cursor = 0, limit = 10).events }.isEmpty())
        assertEquals(1, tx.transactional { syncRepository.findSince(userId = 2, cursor = 0, limit = 10).events.size })

        val remainingLocationOwners =
            tx.transactional {
                VehicleLocationsTable
                    .selectAll()
                    .where { VehicleLocationsTable.vehicleId eq fixture.vehicleId }
                    .orderBy(VehicleLocationsTable.id to SortOrder.ASC)
                    .map { row -> row[VehicleLocationsTable.vendorUserId] to row[VehicleLocationsTable.assignmentId] }
                    .toSet()
            }
        assertEquals(
            setOf(null to null, 2 to null),
            remainingLocationOwners,
            "unassigned telemetry must survive with the deleted vendor set to null; assigned telemetry cascades",
        )
    }

    private fun locationUpdate(
        vehicleId: Int,
        vendorUserId: Int,
        cellIndexer: H3CellIndexer,
        recordedAt: Instant,
        assignmentId: Int? = null,
    ): VehicleLocationUpdate {
        val latitude = 47.4979
        val longitude = 19.0402
        return VehicleLocationUpdate(
            vehicleId = vehicleId,
            vendorUserId = vendorUserId,
            latitude = latitude,
            longitude = longitude,
            recordedAt = recordedAt,
            cellId = cellIndexer.cellFor(latitude, longitude),
            assignmentId = assignmentId,
        )
    }
}
