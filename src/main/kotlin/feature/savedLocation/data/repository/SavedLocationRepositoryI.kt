package com.mozgobolt.feature.savedLocation.data.repository

import com.mozgobolt.feature.savedLocation.data.database.UserSavedLocationEntity
import com.mozgobolt.feature.savedLocation.data.database.UserSavedLocationsTable
import com.mozgobolt.feature.savedLocation.data.mapper.toUserSavedLocation
import com.mozgobolt.feature.savedLocation.domain.SavedLocationRepository
import com.mozgobolt.feature.savedLocation.domain.model.UserSavedLocation
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import java.math.BigDecimal
import java.time.Instant

class SavedLocationRepositoryI : SavedLocationRepository {
    override suspend fun create(
        userId: Int,
        label: String,
        latitude: Double,
        longitude: Double,
        radiusKm: Double,
    ): UserSavedLocation {
        val entity =
            UserSavedLocationEntity.new {
                this.userId = userId
                this.label = label
                this.latitude = BigDecimal.valueOf(latitude)
                this.longitude = BigDecimal.valueOf(longitude)
                this.radiusKm = BigDecimal.valueOf(radiusKm)
                this.createdAt = Instant.now()
            }
        return entity.toUserSavedLocation()
    }

    override suspend fun findById(id: Int): UserSavedLocation? =
        UserSavedLocationEntity
            .find { UserSavedLocationsTable.id eq id }
            .firstOrNull()
            ?.toUserSavedLocation()

    override suspend fun findAllForUser(userId: Int): List<UserSavedLocation> =
        UserSavedLocationEntity
            .find { UserSavedLocationsTable.userId eq userId }
            .map { it.toUserSavedLocation() }

    override suspend fun delete(id: Int) {
        UserSavedLocationEntity.findById(id)?.delete()
    }

    override suspend fun findAllForUsers(userIds: Collection<Int>): List<UserSavedLocation> {
        if (userIds.isEmpty()) return emptyList()
        return UserSavedLocationEntity
            .find { UserSavedLocationsTable.userId inList userIds }
            .map { it.toUserSavedLocation() }
    }
}
