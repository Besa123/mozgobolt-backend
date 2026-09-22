package com.mozgobolt.feature.vehicleTracking.data.repository

import com.mozgobolt.core.skipIfNoDocker
import com.mozgobolt.feature.vehicleTracking.VehicleTrackingTestHarness
import com.mozgobolt.feature.vehicleTracking.service.LOCATION_RETENTION_PERIOD
import com.mozgobolt.feature.vehicleTracking.withRealVehicleTrackingDatabase
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.days
import kotlin.time.toJavaDuration

/**
 * [VehicleLocationRetentionPurgerTest] proves the *purger*'s loop/scope behavior against a fake
 * repository; this proves the actual join/delete query behaves correctly against a real Postgres
 * — the exact class of bug CLAUDE.md calls out: invisible to unit-test fakes, only caught by a
 * real DB (a wrong join direction, a boundary comparison off by one, `inList` on an empty
 * collection generating invalid SQL, etc. would all pass a fake-repository test trivially).
 */
class VehicleLocationRetentionRealDatabaseTest {
    private fun VehicleTrackingTestHarness.now() = Instant.now()

    @Test
    fun `a session that ended well past the retention window has its points purged`() {
        skipIfNoDocker()

        withRealVehicleTrackingDatabase { harness ->
            val vehicleId = harness.seedVehicle()
            val endedAt = harness.now().minus((LOCATION_RETENTION_PERIOD + 1.days).toJavaDuration())
            val assignmentId = harness.seedEndedAssignment(vehicleId, endedAt)
            harness.seedLocation(vehicleId, assignmentId, endedAt)

            val cutoff = harness.now().minus(LOCATION_RETENTION_PERIOD.toJavaDuration())
            val deleted = harness.tx.transactional { harness.locationRepository.purgeOlderThan(cutoff) }

            assertEquals(1, deleted)
            assertEquals(0, harness.countLocationsForAssignment(assignmentId))
        }
    }

    @Test
    fun `a session that ended just within the retention window survives`() {
        skipIfNoDocker()

        withRealVehicleTrackingDatabase { harness ->
            val vehicleId = harness.seedVehicle()
            val endedAt = harness.now().minus((LOCATION_RETENTION_PERIOD - 1.days).toJavaDuration())
            val assignmentId = harness.seedEndedAssignment(vehicleId, endedAt)
            harness.seedLocation(vehicleId, assignmentId, endedAt)

            val cutoff = harness.now().minus(LOCATION_RETENTION_PERIOD.toJavaDuration())
            val deleted = harness.tx.transactional { harness.locationRepository.purgeOlderThan(cutoff) }

            assertEquals(0, deleted)
            assertEquals(1, harness.countLocationsForAssignment(assignmentId))
        }
    }

    @Test
    fun `a still-active session's points are never purged, no matter how old`() {
        skipIfNoDocker()

        withRealVehicleTrackingDatabase { harness ->
            val vehicleId = harness.seedVehicle()
            val veryOldStart = harness.now().minus((LOCATION_RETENTION_PERIOD * 10).toJavaDuration())
            val assignmentId = harness.seedActiveAssignment(vehicleId, veryOldStart)
            harness.seedLocation(vehicleId, assignmentId, veryOldStart)

            val cutoff = harness.now().minus(LOCATION_RETENTION_PERIOD.toJavaDuration())
            val deleted = harness.tx.transactional { harness.locationRepository.purgeOlderThan(cutoff) }

            assertEquals(0, deleted, "an active session (ended_at IS NULL) must never be purged")
            assertEquals(1, harness.countLocationsForAssignment(assignmentId))
        }
    }

    @Test
    fun `a location row with no assignment_id falls back to its own recordedAt against the same cutoff`() {
        skipIfNoDocker()

        withRealVehicleTrackingDatabase { harness ->
            val vehicleId = harness.seedVehicle()
            val staleRecordedAt = harness.now().minus((LOCATION_RETENTION_PERIOD + 1.days).toJavaDuration())
            harness.seedLocation(vehicleId, assignmentId = null, recordedAt = staleRecordedAt)

            val cutoff = harness.now().minus(LOCATION_RETENTION_PERIOD.toJavaDuration())
            val deleted = harness.tx.transactional { harness.locationRepository.purgeOlderThan(cutoff) }

            assertEquals(1, deleted, "a defensive assignment_id IS NULL row must still fall back to recordedAt")
            assertEquals(0, harness.countLocationsForAssignment(null))
        }
    }

    @Test
    fun `purging with nothing stale deletes nothing and does not error on an empty candidate set`() {
        skipIfNoDocker()

        withRealVehicleTrackingDatabase { harness ->
            val cutoff = harness.now().minus(LOCATION_RETENTION_PERIOD.toJavaDuration())

            val deleted = harness.tx.transactional { harness.locationRepository.purgeOlderThan(cutoff) }

            assertEquals(0, deleted)
        }
    }
}
