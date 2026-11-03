/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.feature.vehicle.data.repository

import com.mozgobolt.core.domain.security.SecureTokenGenerator
import com.mozgobolt.core.skipIfNoDocker
import com.mozgobolt.core.withRealDatabase
import com.mozgobolt.feature.company.data.repository.CompanyRepositoryI
import com.mozgobolt.feature.company.domain.model.CompanyConstraints
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The unit tests in [com.mozgobolt.feature.vehicle.service.VehicleServiceITest] prove the
 * *service* correctly reacts to
 * [com.mozgobolt.feature.vehicle.domain.VehicleRepository.create] returning null; this proves
 * the underlying assumption those tests are built on — that the real `UNIQUE(company_id,
 * license_plate)` index actually fires under a genuinely concurrent Postgres workload — rather
 * than trusting it by reasoning alone. Exposed throws outside a transaction in ways invisible to
 * fakes; this is only caught by a real DB.
 */
class VehicleRepositoryRealDatabaseTest {
    @Test
    fun `two concurrent creates with the same plate in the same company only ever produce one winner`() {
        skipIfNoDocker()

        withRealDatabase { _, tx ->
            val vehicleRepository = VehicleRepositoryI()
            val companyId =
                tx.transactional {
                    CompanyRepositoryI()
                        .create(
                            name = "FamilyFrost",
                            inviteCode = SecureTokenGenerator.generate(CompanyConstraints.INVITE_CODE_BYTE_LENGTH),
                        ).id
                }

            val results =
                coroutineScope {
                    val first =
                        async {
                            tx.transactional {
                                vehicleRepository.create(companyId, "Truck A", "SAME-PLATE")
                            }
                        }
                    val second =
                        async {
                            tx.transactional {
                                vehicleRepository.create(companyId, "Truck B", "SAME-PLATE")
                            }
                        }
                    listOf(first.await(), second.await())
                }

            val successes = results.count { it != null }
            assertEquals(1, successes, "exactly one concurrent create with the same plate should win: $results")

            val survivor = tx.transactional { vehicleRepository.findByCompanyAndPlate(companyId, "SAME-PLATE") }
            assertNotNull(survivor, "the winner's vehicle must actually be findable by its plate")
        }
    }

    @Test
    fun `the same plate is allowed across two different companies against a real database`() {
        skipIfNoDocker()

        withRealDatabase { _, tx ->
            val vehicleRepository = VehicleRepositoryI()
            val (companyOneId, companyTwoId) =
                tx.transactional {
                    val companyRepository = CompanyRepositoryI()
                    val one =
                        companyRepository.create(
                            name = "Company One",
                            inviteCode = SecureTokenGenerator.generate(CompanyConstraints.INVITE_CODE_BYTE_LENGTH),
                        )
                    val two =
                        companyRepository.create(
                            name = "Company Two",
                            inviteCode = SecureTokenGenerator.generate(CompanyConstraints.INVITE_CODE_BYTE_LENGTH),
                        )
                    one.id to two.id
                }

            val firstVehicle =
                tx.transactional { vehicleRepository.create(companyOneId, "Truck A", "SHARED-PLATE") }
            val secondVehicle =
                tx.transactional { vehicleRepository.create(companyTwoId, "Truck B", "SHARED-PLATE") }

            assertNotNull(firstVehicle, "creating the first vehicle must succeed")
            assertNotNull(secondVehicle, "a different company reusing the same plate must succeed too")
        }
    }

    @Test
    fun `database rejects a vehicle without an existing company and remains usable after rollback`() {
        skipIfNoDocker()

        withRealDatabase { _, tx ->
            val vehicleRepository = VehicleRepositoryI()
            val failure =
                assertFails {
                    tx.transactional {
                        vehicleRepository.create(
                            companyId = Int.MAX_VALUE,
                            label = "Orphan vehicle",
                            licensePlate = "ORPHAN-PLATE",
                        )
                    }
                }

            assertTrue(
                generateSequence(failure) { it.cause }
                    .filterIsInstance<SQLException>()
                    .any { it.sqlState == "23503" },
                "expected PostgreSQL foreign-key violation for a missing company",
            )

            val validVehicle =
                tx.transactional {
                    val company =
                        CompanyRepositoryI()
                            .create(
                                name = "Valid Company",
                                inviteCode = SecureTokenGenerator.generate(CompanyConstraints.INVITE_CODE_BYTE_LENGTH),
                            )
                    vehicleRepository.create(company.id, "Valid vehicle", "VALID-PLATE")
                }
            assertNotNull(validVehicle, "the failed transaction must not poison later database work")
        }
    }

    @Test
    fun `archiving a vehicle sets archivedAt and excludes it from the company's fleet listing`() {
        skipIfNoDocker()

        withRealDatabase { _, tx ->
            val vehicleRepository = VehicleRepositoryI()
            val companyId =
                tx.transactional {
                    CompanyRepositoryI()
                        .create(
                            name = "FamilyFrost",
                            inviteCode = SecureTokenGenerator.generate(CompanyConstraints.INVITE_CODE_BYTE_LENGTH),
                        ).id
                }
            val vehicleId =
                tx.transactional { vehicleRepository.create(companyId, "Truck", "ARCHIVE-PLATE")!!.id }

            val archived = tx.transactional { vehicleRepository.archive(vehicleId) }

            assertEquals(true, archived)
            val reloaded = tx.transactional { vehicleRepository.findById(vehicleId) }
            assertNotNull(reloaded?.archivedAt, "archivedAt must actually be persisted")
            val fleet = tx.transactional { vehicleRepository.findAllByCompany(companyId) }
            assertEquals(emptyList(), fleet, "an archived vehicle must not appear in the company's fleet listing")
        }
    }

    @Test
    fun `archiving an already-archived vehicle is a no-op that never overwrites the original instant`() {
        skipIfNoDocker()

        withRealDatabase { _, tx ->
            val vehicleRepository = VehicleRepositoryI()
            val companyId =
                tx.transactional {
                    CompanyRepositoryI()
                        .create(
                            name = "FamilyFrost",
                            inviteCode = SecureTokenGenerator.generate(CompanyConstraints.INVITE_CODE_BYTE_LENGTH),
                        ).id
                }
            val vehicleId =
                tx.transactional { vehicleRepository.create(companyId, "Truck", "RE-ARCHIVE-PLATE")!!.id }
            tx.transactional { vehicleRepository.archive(vehicleId) }
            val firstArchivedAt = tx.transactional { vehicleRepository.findById(vehicleId)!!.archivedAt }

            val secondAttempt = tx.transactional { vehicleRepository.archive(vehicleId) }

            assertEquals(false, secondAttempt, "re-archiving must report nothing changed")
            val secondArchivedAt = tx.transactional { vehicleRepository.findById(vehicleId)!!.archivedAt }
            assertEquals(firstArchivedAt, secondArchivedAt, "the original archive instant must survive untouched")
        }
    }

    @Test
    fun `vehicle lookups and detail and picture updates return the persisted row or null for missing ids`() {
        skipIfNoDocker()

        withRealDatabase { _, tx ->
            val repository = VehicleRepositoryI()
            val companyId =
                tx.transactional {
                    CompanyRepositoryI()
                        .create(
                            name = "FamilyFrost",
                            inviteCode = SecureTokenGenerator.generate(CompanyConstraints.INVITE_CODE_BYTE_LENGTH),
                        ).id
                }
            val vehicleId =
                tx.transactional {
                    repository.create(companyId, "Truck", "ORIGINAL-PLATE")!!.id
                }

            assertEquals(
                vehicleId,
                tx.transactional { repository.findByCompanyAndPlate(companyId, "ORIGINAL-PLATE")?.id },
            )
            assertEquals(null, tx.transactional { repository.findByCompanyAndPlate(companyId, "MISSING-PLATE") })
            assertEquals(null, tx.transactional { repository.findByCompanyAndPlate(companyId + 1, "ORIGINAL-PLATE") })
            assertEquals(null, tx.transactional { repository.updateDetails(-1, "MISSING", null) })
            assertEquals(null, tx.transactional { repository.updatePictureUrl(-1, "https://example.test/missing.jpg") })
            assertEquals(false, tx.transactional { repository.archive(-1) })

            val updated =
                tx.transactional {
                    repository.updateDetails(vehicleId, "UPDATED-PLATE", "https://example.test/vehicle.jpg")
                }
            assertEquals("UPDATED-PLATE", updated?.licensePlate)
            assertEquals("https://example.test/vehicle.jpg", updated?.pictureUrl)

            val storageKey = "vehicles/$vehicleId/8f8a6c38-4538-44c8-8ac3-6d3ea2a0ef5d"
            val pictureAdded =
                tx.transactional {
                    repository.updatePictureUrl(
                        vehicleId,
                        "/vehicles/$vehicleId/picture",
                        storageKey,
                    )
                }
            assertEquals(storageKey, pictureAdded?.pictureStorageKey)

            val pictureRemoved = tx.transactional { repository.updatePictureUrl(vehicleId, null) }
            assertEquals(null, pictureRemoved?.pictureUrl)
            assertEquals(null, pictureRemoved?.pictureStorageKey)
            assertEquals(
                null,
                tx.transactional { repository.findByCompanyAndPlate(companyId + 1, "UPDATED-PLATE") },
            )
        }
    }
}
