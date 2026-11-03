/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.feature.proximityNotification.data.repository

import com.mozgobolt.core.domain.security.SecureTokenGenerator
import com.mozgobolt.core.skipIfNoDocker
import com.mozgobolt.core.withRealDatabase
import com.mozgobolt.feature.company.data.repository.CompanyRepositoryI
import com.mozgobolt.feature.company.domain.model.CompanyConstraints
import com.mozgobolt.feature.savedLocation.data.repository.SavedLocationRepositoryI
import com.mozgobolt.feature.user.data.database.UsersTable
import com.mozgobolt.feature.vehicle.data.repository.VehicleRepositoryI
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ProximityNotificationUserCascadeRealDatabaseTest {
    @Test
    fun `deleting a buyer cascades only their saved locations and proximity state`() {
        skipIfNoDocker()

        withRealDatabase { _, tx ->
            val savedLocationRepository = SavedLocationRepositoryI()
            val proximityRepository = ProximityNotificationRepositoryI()
            val (deletedLocation, retainedLocation, vehicleId) =
                tx.transactional {
                    val company =
                        CompanyRepositoryI().create(
                            name = "User Cascade Company",
                            inviteCode = SecureTokenGenerator.generate(CompanyConstraints.INVITE_CODE_BYTE_LENGTH),
                        )
                    val vehicle =
                        VehicleRepositoryI().create(
                            companyId = company.id,
                            label = "User Cascade Truck",
                            licensePlate = "PROX-USER-CASCADE",
                        )!!
                    val firstLocation =
                        savedLocationRepository.create(
                            userId = 1,
                            label = "Deleted buyer location",
                            latitude = 47.4979,
                            longitude = 19.0402,
                            radiusKm = 1.0,
                        )
                    val secondLocation =
                        savedLocationRepository.create(
                            userId = 2,
                            label = "Retained buyer location",
                            latitude = 47.5,
                            longitude = 19.1,
                            radiusKm = 1.0,
                        )
                    val attemptedAt = Instant.parse("2026-01-01T00:00:00Z")
                    proximityRepository.recordEntryAttempted(1, firstLocation.id, vehicle.id, attemptedAt)
                    proximityRepository.recordEntryAttempted(2, secondLocation.id, vehicle.id, attemptedAt)
                    Triple(firstLocation, secondLocation, vehicle.id)
                }

            assertEquals(1, tx.transactional { UsersTable.deleteWhere { UsersTable.id eq 1 } })

            assertNull(tx.transactional { savedLocationRepository.findById(deletedLocation.id) })
            assertNotNull(tx.transactional { savedLocationRepository.findById(retainedLocation.id) })
            assertNull(tx.transactional { proximityRepository.find(1, deletedLocation.id, vehicleId) })
            assertNotNull(tx.transactional { proximityRepository.find(2, retainedLocation.id, vehicleId) })
            assertNotNull(tx.transactional { VehicleRepositoryI().findById(vehicleId) })
        }
    }
}
