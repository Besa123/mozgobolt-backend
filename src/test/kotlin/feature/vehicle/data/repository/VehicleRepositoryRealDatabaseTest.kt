package com.mozgobolt.feature.vehicle.data.repository

import com.mozgobolt.core.domain.security.SecureTokenGenerator
import com.mozgobolt.core.skipIfNoDocker
import com.mozgobolt.core.withRealDatabase
import com.mozgobolt.feature.company.data.repository.CompanyRepositoryI
import com.mozgobolt.feature.company.domain.model.CompanyConstraints
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

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
}
