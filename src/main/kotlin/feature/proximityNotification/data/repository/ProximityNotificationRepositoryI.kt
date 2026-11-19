/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.feature.proximityNotification.data.repository

import com.mozgobolt.feature.proximityNotification.data.database.ProximityNotificationsTable
import com.mozgobolt.feature.proximityNotification.domain.ProximityNotificationRepository
import com.mozgobolt.feature.proximityNotification.domain.model.ProximityNotificationCandidate
import com.mozgobolt.feature.proximityNotification.domain.model.ProximityNotificationRecord
import com.mozgobolt.feature.vehicle.data.database.VehiclesTable
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.inSubQuery
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.updateReturning
import java.time.Instant

private const val MAX_LOCATION_IDS_PER_CLAIM_UPDATE = 500

/**
 * Notification delivery uses single or batched claims so eligibility and cooldown updates remain
 * atomic for each buyer/location/vehicle triple. Batch claims must stay inside the caller's transaction.
 * [recordEntryAttempted] remains an unconditional state write for callers that already own that decision.
 */
class ProximityNotificationRepositoryI : ProximityNotificationRepository {
    override suspend fun find(
        buyerUserId: Int,
        savedLocationId: Int,
        vehicleId: Int,
    ): ProximityNotificationRecord? = findRow(buyerUserId, savedLocationId, vehicleId)?.toProximityNotificationRecord()

    override suspend fun recordEntryAttempted(
        buyerUserId: Int,
        savedLocationId: Int,
        vehicleId: Int,
        attemptedAt: Instant,
    ): ProximityNotificationRecord {
        val insertedCount =
            ProximityNotificationsTable
                .insertIgnore {
                    it[ProximityNotificationsTable.buyerUserId] = buyerUserId
                    it[ProximityNotificationsTable.savedLocationId] = savedLocationId
                    it[ProximityNotificationsTable.vehicleId] = vehicleId
                    it[ProximityNotificationsTable.currentlyInside] = true
                    it[ProximityNotificationsTable.lastAttemptedAt] = attemptedAt
                }.insertedCount
        val existing =
            findRow(buyerUserId, savedLocationId, vehicleId, forUpdate = true)
                ?: error("Notification row disappeared while recording an attempt")

        if (insertedCount == 0) {
            val updated =
                ProximityNotificationsTable.update(
                    where = { ProximityNotificationsTable.id eq existing[ProximityNotificationsTable.id] },
                ) {
                    it[ProximityNotificationsTable.currentlyInside] = true
                    it[ProximityNotificationsTable.lastAttemptedAt] = attemptedAt
                }
            check(updated == 1) { "Locked notification row was not updated" }
        }

        return existing
            .toProximityNotificationRecord()
            .copy(
                currentlyInside = true,
                lastAttemptedAt = attemptedAt,
            )
    }

    override suspend fun claimEntryNotification(
        buyerUserId: Int,
        savedLocationId: Int,
        vehicleId: Int,
        attemptedAt: Instant,
        cooldownCutoff: Instant,
    ): Boolean {
        val insertedCount =
            ProximityNotificationsTable
                .insertIgnore {
                    it[ProximityNotificationsTable.buyerUserId] = buyerUserId
                    it[ProximityNotificationsTable.savedLocationId] = savedLocationId
                    it[ProximityNotificationsTable.vehicleId] = vehicleId
                    it[ProximityNotificationsTable.currentlyInside] = true
                    it[ProximityNotificationsTable.lastAttemptedAt] = attemptedAt
                }.insertedCount
        if (insertedCount > 0) return true

        val existing = findRow(buyerUserId, savedLocationId, vehicleId, forUpdate = true) ?: return false

        return updateEntryIfEligible(existing, attemptedAt, cooldownCutoff)
    }

    override suspend fun claimEntryNotifications(
        vehicleId: Int,
        candidates: List<ProximityNotificationCandidate>,
        attemptedAt: Instant,
        cooldownCutoff: Instant,
    ): Set<Int> {
        if (candidates.isEmpty()) return emptySet()

        val claimedLocationIds = HashSet<Int>()
        // Candidate query order is unspecified; use a stable lock order for overlapping batches.
        val orderedCandidates = candidates.sortedBy(ProximityNotificationCandidate::savedLocationId)
        orderedCandidates.chunked(MAX_LOCATION_IDS_PER_CLAIM_UPDATE).forEach { chunk ->
            val inserted =
                ProximityNotificationsTable.batchInsert(
                    data = chunk,
                    ignore = true,
                    shouldReturnGeneratedValues = false,
                ) { candidate ->
                    this[ProximityNotificationsTable.buyerUserId] = candidate.buyerUserId
                    this[ProximityNotificationsTable.savedLocationId] = candidate.savedLocationId
                    this[ProximityNotificationsTable.vehicleId] = vehicleId
                    this[ProximityNotificationsTable.currentlyInside] = true
                    this[ProximityNotificationsTable.lastAttemptedAt] = attemptedAt
                }
            val insertedLocationIds =
                inserted.mapTo(HashSet()) { it[ProximityNotificationsTable.savedLocationId] }
            claimedLocationIds.addAll(insertedLocationIds)

            val existingLocationIds =
                chunk
                    .map(ProximityNotificationCandidate::savedLocationId)
                    .filterNot(insertedLocationIds::contains)
            if (existingLocationIds.isNotEmpty()) {
                val updatedLocationIds =
                    ProximityNotificationsTable
                        .updateReturning(
                            returning = listOf(ProximityNotificationsTable.savedLocationId),
                            where = {
                                (ProximityNotificationsTable.vehicleId eq vehicleId) and
                                    (ProximityNotificationsTable.savedLocationId inList existingLocationIds) and
                                    (
                                        (ProximityNotificationsTable.currentlyInside eq false) or
                                            (ProximityNotificationsTable.lastAttemptedAt lessEq cooldownCutoff)
                                    )
                            },
                        ) {
                            it[ProximityNotificationsTable.currentlyInside] = true
                            it[ProximityNotificationsTable.lastAttemptedAt] = attemptedAt
                        }.map { row -> row[ProximityNotificationsTable.savedLocationId] }
                claimedLocationIds.addAll(updatedLocationIds)
            }
        }
        return claimedLocationIds
    }

    private fun updateEntryIfEligible(
        existing: ResultRow,
        attemptedAt: Instant,
        cooldownCutoff: Instant,
    ): Boolean {
        val cooldownElapsed = !existing[ProximityNotificationsTable.lastAttemptedAt].isAfter(cooldownCutoff)
        if (existing[ProximityNotificationsTable.currentlyInside] && !cooldownElapsed) return false

        val updated =
            ProximityNotificationsTable.update(
                where = { ProximityNotificationsTable.id eq existing[ProximityNotificationsTable.id] },
            ) {
                it[ProximityNotificationsTable.currentlyInside] = true
                it[ProximityNotificationsTable.lastAttemptedAt] = attemptedAt
            }
        check(updated == 1) { "Locked notification row was not updated" }
        return true
    }

    override suspend fun markOutside(
        vehicleId: Int,
        savedLocationIds: Collection<Int>,
    ) {
        if (savedLocationIds.isEmpty()) return

        ProximityNotificationsTable.update(
            where = {
                (ProximityNotificationsTable.vehicleId eq vehicleId) and
                    (ProximityNotificationsTable.savedLocationId inList savedLocationIds) and
                    (ProximityNotificationsTable.currentlyInside eq true)
            },
        ) {
            it[ProximityNotificationsTable.currentlyInside] = false
        }
    }

    override suspend fun deleteForUserAndCompany(
        buyerUserId: Int,
        companyId: Int,
    ): Int {
        val companyVehicleIds =
            VehiclesTable
                .select(VehiclesTable.id)
                .where { VehiclesTable.companyId eq companyId }
        return ProximityNotificationsTable.deleteWhere {
            (ProximityNotificationsTable.buyerUserId eq buyerUserId) and
                (ProximityNotificationsTable.vehicleId inSubQuery companyVehicleIds)
        }
    }

    private fun findRow(
        buyerUserId: Int,
        savedLocationId: Int,
        vehicleId: Int,
        forUpdate: Boolean = false,
    ): ResultRow? =
        ProximityNotificationsTable
            .selectAll()
            .where {
                (ProximityNotificationsTable.buyerUserId eq buyerUserId) and
                    (ProximityNotificationsTable.savedLocationId eq savedLocationId) and
                    (ProximityNotificationsTable.vehicleId eq vehicleId)
            }.let { query ->
                if (forUpdate) query.forUpdate().singleOrNull() else query.singleOrNull()
            }

    private fun ResultRow.toProximityNotificationRecord() =
        ProximityNotificationRecord(
            id = this[ProximityNotificationsTable.id].value,
            buyerUserId = this[ProximityNotificationsTable.buyerUserId],
            savedLocationId = this[ProximityNotificationsTable.savedLocationId],
            vehicleId = this[ProximityNotificationsTable.vehicleId],
            currentlyInside = this[ProximityNotificationsTable.currentlyInside],
            lastAttemptedAt = this[ProximityNotificationsTable.lastAttemptedAt],
        )
}
