package com.mozgobolt.feature.savedLocation.data.database

import com.mozgobolt.core.domain.validation.DISPLAY_NAME_MAX_LENGTH
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.dao.IntEntity
import org.jetbrains.exposed.v1.dao.IntEntityClass
import org.jetbrains.exposed.v1.javatime.timestamp

private const val LATITUDE_LONGITUDE_PRECISION = 9
private const val LATITUDE_LONGITUDE_SCALE = 6
private const val RADIUS_PRECISION = 5
private const val RADIUS_SCALE = 2

data object UserSavedLocationsTable : IntIdTable("user_saved_locations") {
    val userId = integer("user_id")
    val label = varchar("label", DISPLAY_NAME_MAX_LENGTH)
    val latitude = decimal("latitude", LATITUDE_LONGITUDE_PRECISION, LATITUDE_LONGITUDE_SCALE)
    val longitude = decimal("longitude", LATITUDE_LONGITUDE_PRECISION, LATITUDE_LONGITUDE_SCALE)
    val radiusKm = decimal("radius_km", RADIUS_PRECISION, RADIUS_SCALE)
    val createdAt = timestamp("created_at")
}

class UserSavedLocationEntity(
    id: EntityID<Int>,
) : IntEntity(id) {
    var userId by UserSavedLocationsTable.userId
    var label by UserSavedLocationsTable.label
    var latitude by UserSavedLocationsTable.latitude
    var longitude by UserSavedLocationsTable.longitude
    var radiusKm by UserSavedLocationsTable.radiusKm
    var createdAt by UserSavedLocationsTable.createdAt

    companion object : IntEntityClass<UserSavedLocationEntity>(UserSavedLocationsTable)
}
