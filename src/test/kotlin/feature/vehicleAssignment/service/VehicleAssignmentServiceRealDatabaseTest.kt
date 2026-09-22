package com.mozgobolt.feature.vehicleAssignment.service

import com.mozgobolt.core.domain.AppResult
import com.mozgobolt.core.skipIfNoDocker
import com.mozgobolt.feature.vehicleAssignment.domain.model.VehicleAssignmentError
import com.mozgobolt.feature.vehicleAssignment.withRealVehicleAssignmentDatabase
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.fail

/**
 * The unit tests in [VehicleAssignmentServiceITest] prove the *service* correctly reacts to
 * `startAssignment` returning null; this proves the underlying assumption those tests are built
 * on — that the real partial unique indexes on `vehicle_assignments` actually fire under a
 * genuinely concurrent Postgres workload, and that the resulting `ExposedSQLException` is caught
 * with the right SQL state — rather than trusting that by reasoning alone. This is exactly the
 * class of bug CLAUDE.md calls out: invisible to unit-test fakes, only caught by a real DB.
 */
class VehicleAssignmentServiceRealDatabaseTest {
    @Test
    fun `two concurrent link requests for the same vehicle by different vendors only ever produce one winner`() {
        skipIfNoDocker()

        withRealVehicleAssignmentDatabase { harness ->
            val vehicleId = harness.seedSharedCompanyWithOneVehicle()

            val results =
                coroutineScope {
                    val first = async { harness.service.link(vendorUserId = 1, vehicleId = vehicleId) }
                    val second = async { harness.service.link(vendorUserId = 2, vehicleId = vehicleId) }
                    listOf(first.await(), second.await())
                }

            val successes = results.count { it is AppResult.Success }
            val rejections =
                results.count {
                    it is AppResult.Error && it.errorType == VehicleAssignmentError.VEHICLE_ALREADY_ASSIGNED
                }
            assertEquals(1, successes, "exactly one concurrent link should win: $results")
            assertEquals(1, rejections, "the loser must see a clean VEHICLE_ALREADY_ASSIGNED, not crash: $results")

            val activeAssignment =
                harness.tx.transactional {
                    harness.assignmentRepository.findActiveForVehicle(
                        vehicleId,
                    )
                }
            assertNotNull(activeAssignment, "the winner's assignment must actually be the active one on record")
        }
    }

    @Test
    fun `linking then immediately relinking to the same vehicle concurrently never creates two active rows`() {
        // The vendor calling link() twice for the SAME vehicle at once (e.g. a flaky client
        // double-submitting) should be idempotent, not a race — this exercises the
        // "activeOnVehicle belongs to me already" branch under real concurrency too.
        skipIfNoDocker()

        withRealVehicleAssignmentDatabase { harness ->
            val vehicleId = harness.seedSharedCompanyWithOneVehicle()

            val results =
                coroutineScope {
                    val first = async { harness.service.link(vendorUserId = 1, vehicleId = vehicleId) }
                    val second = async { harness.service.link(vendorUserId = 1, vehicleId = vehicleId) }
                    listOf(first.await(), second.await())
                }

            assertEquals(
                2,
                results.count { it is AppResult.Success },
                "relinking to your own vehicle must never fail: $results",
            )

            val allAssignmentsForVehicle =
                harness.tx.transactional {
                    harness.assignmentRepository.findActiveForVehicle(vehicleId)
                }
            assertNotNull(allAssignmentsForVehicle)
            assertEquals(1, allAssignmentsForVehicle.vendorUserId)
        }
    }

    @Test
    fun `REGRESSION losing a concurrent race for a different vehicle never loses your own current session`() {
        // This is the scenario a fake in-memory repository can never prove: vendor 1 is already
        // actively driving vehicle A when they attempt to switch to vehicle B, racing vendor 2 for
        // it. If vendor 1 loses that race, link()'s own endAssignment(vehicle A) — issued before
        // the losing startAssignment(vehicle B) is even attempted — must be rolled back by the real
        // transaction, not committed. Only a real Postgres transaction can actually demonstrate
        // that rollback; a fake repository has no transactional semantics to roll back in the first
        // place, so this exact regression is invisible to unit tests, per CLAUDE.md.
        skipIfNoDocker()

        withRealVehicleAssignmentDatabase { harness ->
            val (vehicleA, vehicleB) = harness.seedSharedCompanyWithTwoVehicles()

            harness.service
                .link(vendorUserId = 1, vehicleId = vehicleA)
                .fold(onSuccess = { it }, onError = { fail("setup failed: $it") })

            val results =
                coroutineScope {
                    val vendorOneAttempt = async { harness.service.link(vendorUserId = 1, vehicleId = vehicleB) }
                    val vendorTwoAttempt = async { harness.service.link(vendorUserId = 2, vehicleId = vehicleB) }
                    listOf(vendorOneAttempt.await(), vendorTwoAttempt.await())
                }

            val vendorOneWonVehicleB = results[0] is AppResult.Success

            val activeOnVehicleA =
                harness.tx.transactional {
                    harness.assignmentRepository.findActiveForVehicle(
                        vehicleA,
                    )
                }
            if (vendorOneWonVehicleB) {
                // The ordinary case: vendor 1 genuinely switched vehicles, so their old session on
                // A is correctly gone, not a bug.
                assertNull(activeOnVehicleA, "vendor 1 switched to B, so A must now be free")
            } else {
                // The regression this test exists for: vendor 1 lost the race for B, so their
                // EXISTING session on A must have survived — not silently erased by a transaction
                // that itself reports failure.
                assertNotNull(activeOnVehicleA, "vendor 1 lost the race for B — their session on A must survive")
                assertEquals(1, activeOnVehicleA.vendorUserId)
            }

            val activeOnVehicleB =
                harness.tx.transactional {
                    harness.assignmentRepository.findActiveForVehicle(
                        vehicleB,
                    )
                }
            assertNotNull(activeOnVehicleB, "exactly one of vendor 1 or vendor 2 must hold vehicle B")
        }
    }
}
