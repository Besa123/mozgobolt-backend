/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.loadtest

import com.mozgobolt.feature.vehicleTracking.domain.model.GeoPoint
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path

private val WORKLOAD_JSON = Json { ignoreUnknownKeys = false }

/**
 * The single source of truth shared by the fixture seeder and the load runner, deserialized from
 * `load-tests/workload.json`. Everything that must agree between the two (actor counts, regions,
 * placement skew, what buyers favorite) lives here so they cannot drift apart.
 */
@Serializable
internal data class WorkloadSpec(
    val trucks: Int,
    val buyers: Int,
    val loginBurst: Int,
    val authLimitPerMinute: Int,
    val maxRadiusKm: Double,
    val maxPointsPerBatch: Int,
    /** Zipf exponent over [regions] (in listed order): 0 is uniform, larger concentrates on the first regions. */
    val regionSkew: Double,
    /** Scatter (Rayleigh sigma) of buyers around their region centre. */
    val cityRadiusKm: Double,
    /** Radius of the star-shaped driving loop each truck repeats around its region centre. */
    val routeRadiusKm: Double,
    val buyerBehavior: BuyerBehavior,
    val defaults: Defaults,
    val profiles: Map<String, Profile>,
    val regions: List<Region>,
) {
    @Serializable
    data class BuyerBehavior(
        val favoritesPerBuyer: Int,
        /** Zipf exponent over companies: how strongly a few popular companies attract most favorites. */
        val favoriteSkew: Double,
        val savedLocationsPerBuyer: Int,
        val savedLocationRadiusKm: Double,
    )

    @Serializable
    data class Defaults(
        val durationSeconds: Int,
        val truckIntervalSeconds: Double,
        val pointsPerBatch: Int,
        val radiusKm: Double,
        /** Mean time a buyer keeps one live map open before closing and reopening it; 0 disables churn. */
        val viewerSessionSeconds: Int,
    )

    @Serializable
    data class Profile(
        val viewers: Int,
        val placement: Placement,
    )

    @Serializable
    enum class Placement {
        /** Trucks and buyers stay where the seeder put them (skewed across regions). */
        @SerialName("seeded")
        SEEDED,

        /** Every truck and buyer is moved into the first region, the worst case for fan-out. */
        @SerialName("single-region")
        SINGLE_REGION,
    }

    @Serializable
    data class Region(
        val name: String,
        val latitude: Double,
        val longitude: Double,
    ) {
        val center: GeoPoint get() = GeoPoint(latitude, longitude)
    }

    companion object {
        const val DEFAULT_PATH = "load-tests/workload.json"

        fun load(path: Path = Path.of(DEFAULT_PATH)): WorkloadSpec =
            WORKLOAD_JSON.decodeFromString(serializer(), Files.readString(path))
    }
}
