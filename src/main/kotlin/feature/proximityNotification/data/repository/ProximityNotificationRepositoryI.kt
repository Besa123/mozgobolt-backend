package com.mozgobolt.feature.proximityNotification.data.repository

import com.mozgobolt.feature.proximityNotification.data.database.ProximityNotificationEntity
import com.mozgobolt.feature.proximityNotification.data.database.ProximityNotificationsTable
import com.mozgobolt.feature.proximityNotification.data.mapper.toProximityNotificationRecord
import com.mozgobolt.feature.proximityNotification.domain.ProximityNotificationRepository
import com.mozgobolt.feature.proximityNotification.domain.model.ProximityNotificationRecord
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import java.time.Instant

/**
 * Find-then-write, not DB-atomic — a rare, genuinely concurrent evaluation of the exact same
 * (buyer, saved location, vehicle) triple (e.g. a buggy client double-firing telemetry for the
 * same vehicle at the same instant) could race here. This is debounce state for a notification
 * feature, not a correctness invariant like `vehicle_assignments`' partial unique indexes — the
 * same risk tolerance already accepted for the company's last-admin guard and
 * `VehiclePingServiceI`'s cooldown check, both documented as low-stakes races rather than bugs to
 * eliminate with locking machinery this app's realistic scale doesn't call for.
 */
class ProximityNotificationRepositoryI : ProximityNotificationRepository {
    override suspend fun find(
        buyerUserId: Int,
        savedLocationId: Int,
        vehicleId: Int,
    ): ProximityNotificationRecord? =
        findEntity(buyerUserId, savedLocationId, vehicleId)?.toProximityNotificationRecord()

    override suspend fun recordEntryNotified(
        buyerUserId: Int,
        savedLocationId: Int,
        vehicleId: Int,
        notifiedAt: Instant,
    ): ProximityNotificationRecord {
        val existing = findEntity(buyerUserId, savedLocationId, vehicleId)
        val entity =
            if (existing != null) {
                existing.apply {
                    currentlyInside = true
                    lastNotifiedAt = notifiedAt
                }
            } else {
                ProximityNotificationEntity.new {
                    this.buyerUserId = buyerUserId
                    this.savedLocationId = savedLocationId
                    this.vehicleId = vehicleId
                    this.currentlyInside = true
                    this.lastNotifiedAt = notifiedAt
                }
            }
        return entity.toProximityNotificationRecord()
    }

    override suspend fun markOutside(
        buyerUserId: Int,
        savedLocationId: Int,
        vehicleId: Int,
    ) {
        findEntity(buyerUserId, savedLocationId, vehicleId)?.currentlyInside = false
    }

    private suspend fun findEntity(
        buyerUserId: Int,
        savedLocationId: Int,
        vehicleId: Int,
    ): ProximityNotificationEntity? =
        ProximityNotificationEntity
            .find {
                (ProximityNotificationsTable.buyerUserId eq buyerUserId) and
                    (ProximityNotificationsTable.savedLocationId eq savedLocationId) and
                    (ProximityNotificationsTable.vehicleId eq vehicleId)
            }.firstOrNull()
}
