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
    /** Zipf exponent for vendor/vehicle placement over [regions]. */
    val truckRegionSkew: Double,
    /** Zipf exponent for buyer placement over [regions]. */
    val buyerRegionSkew: Double,
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
        val viewerConnectRampUpSeconds: Int,
    )

    @Serializable
    data class Profile(
        val trucks: Int,
        val viewers: Int,
        val placement: Placement,
        val synchronizeTrucks: Boolean = false,
        val routeRadiusKm: Double? = null,
        val cityRadiusKm: Double? = null,
    )

    @Serializable
    enum class Placement {
        /** Actors retain their seeded positions and account ordering. */
        @SerialName("seeded")
        SEEDED,

        /** Select trucks and buyers round-robin by seeded region, retaining their seeded locations. */
        @SerialName("uniform-regions")
        UNIFORM_REGIONS,

        /** Every truck and buyer is moved into the first region, the worst case for fan-out. */
        @SerialName("single-region")
        SINGLE_REGION,

        /** Every route and map centre is placed inside one H3 cell at the app's resolution. */
        @SerialName("single-cell")
        SINGLE_CELL,
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
