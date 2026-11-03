/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.feature.savedLocation.data.database

import com.mozgobolt.core.domain.validation.DISPLAY_NAME_MAX_LENGTH
import com.mozgobolt.feature.savedLocation.domain.model.SavedLocationConstraints
import com.mozgobolt.feature.user.data.database.UsersTable
import com.mozgobolt.feature.vehicleTracking.domain.model.GeoBounds
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.dao.IntEntity
import org.jetbrains.exposed.v1.dao.IntEntityClass
import org.jetbrains.exposed.v1.javatime.timestamp
import java.math.BigDecimal

private const val LATITUDE_LONGITUDE_PRECISION = 9
private const val LATITUDE_LONGITUDE_SCALE = 6
private const val RADIUS_PRECISION = 5
private const val RADIUS_SCALE = 2

data object UserSavedLocationsTable : IntIdTable("user_saved_locations") {
    val userId =
        integer("user_id")
            .references(
                UsersTable.id,
                onDelete = ReferenceOption.CASCADE,
                onUpdate = ReferenceOption.RESTRICT,
            ).index()
    val label = varchar("label", DISPLAY_NAME_MAX_LENGTH)
    val latitude = decimal("latitude", LATITUDE_LONGITUDE_PRECISION, LATITUDE_LONGITUDE_SCALE)
    val longitude = decimal("longitude", LATITUDE_LONGITUDE_PRECISION, LATITUDE_LONGITUDE_SCALE)
    val radiusKm = decimal("radius_km", RADIUS_PRECISION, RADIUS_SCALE)
    val createdAt = timestamp("created_at")

    init {
        check("user_saved_locations_latitude_range_check") {
            (latitude greaterEq BigDecimal.valueOf(GeoBounds.MIN_LATITUDE)) and
                (latitude lessEq BigDecimal.valueOf(GeoBounds.MAX_LATITUDE))
        }
        check("user_saved_locations_longitude_range_check") {
            (longitude greaterEq BigDecimal.valueOf(GeoBounds.MIN_LONGITUDE)) and
                (longitude lessEq BigDecimal.valueOf(GeoBounds.MAX_LONGITUDE))
        }
        check("user_saved_locations_radius_range_check") {
            (radiusKm greaterEq BigDecimal.valueOf(SavedLocationConstraints.MIN_RADIUS_KM)) and
                (radiusKm lessEq BigDecimal.valueOf(SavedLocationConstraints.MAX_RADIUS_KM))
        }
        uniqueIndex(id, userId)
    }
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
