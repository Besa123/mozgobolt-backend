/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.loadtest

import com.mozgobolt.feature.vehicleTracking.domain.model.GeoPoint
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.Base64
import kotlin.time.Duration

private const val JWT_PAYLOAD_PART = 1
private const val LOCAL_PROXY_ADDRESS = "127.0.0.1"
private const val ADDRESSES_PER_BLOCK = 250

// Column positions in the seeder's CSV (see AccountBook.CSV_HEADER).
private const val ROLE = 0
private const val EMAIL = 1
private const val PASSWORD = 2
private const val ACCESS_TOKEN = 3
private const val USER_ID = 4
private const val VEHICLE_ID = 5
private const val REGION_INDEX = 6
private const val HOME_LATITUDE = 7
private const val HOME_LONGITUDE = 8

/** Which synthetic client-address block an actor lives in, so unrelated actors never share a rate-limit key. */
internal enum class AddressBlock(
    val octet: Int,
) {
    VENDORS(1),
    BUYERS(2),
    LOGIN_BURST(3),
    SHARED_NAT(4),
}

/** A synthetic client address in 10.<block>.x.y: unique per (block, index) and always a valid IPv4 address. */
internal fun syntheticClientAddress(
    block: AddressBlock,
    index: Int,
): String = "10.${block.octet}.${index / ADDRESSES_PER_BLOCK}.${index % ADDRESSES_PER_BLOCK + 1}"

/**
 * The `X-Forwarded-For` value emulating one trusted local proxy hop in front of the app, which is
 * how the server derives the rate-limit key.
 */
internal fun forwardedFor(clientAddress: String): String = "$clientAddress, $LOCAL_PROXY_ADDRESS"

internal data class Credentials(
    val userId: Int,
    val email: String,
    val password: String,
    val accessToken: String,
)

internal data class VendorAccount(
    val credentials: Credentials,
    val vehicleId: Int,
    val regionIndex: Int,
    val clientAddress: String,
)

internal data class BuyerAccount(
    val credentials: Credentials,
    val home: GeoPoint,
    val regionIndex: Int,
    val clientAddress: String,
)

/** The disposable accounts written by the seeder to `build/load-test/users.csv`. */
internal data class AccountBook(
    val vendors: List<VendorAccount>,
    val buyers: List<BuyerAccount>,
) {
    /** Fails fast if any seeded access token would expire during a run of [runLength]. */
    fun requireTokensValidFor(runLength: Duration) {
        val deadline = Instant.now().plusMillis(runLength.inWholeMilliseconds)
        val expired =
            (vendors.map { it.credentials } + buyers.map { it.credentials })
                .count { accessTokenExpiry(it.accessToken) <= deadline }
        require(expired == 0) {
            "$expired seeded access tokens expire within the ${runLength.inWholeSeconds}s run; " +
                "recreate the disposable database, reseed, restart the app, and start the workload promptly"
        }
    }

    companion object {
        const val DEFAULT_PATH = "build/load-test/users.csv"
        const val CSV_HEADER =
            "role,email,password,access_token,user_id,vehicle_id,region_index,home_latitude,home_longitude"

        fun load(path: Path = Path.of(DEFAULT_PATH)): AccountBook {
            val rows =
                Files
                    .readAllLines(path)
                    .drop(1)
                    .filter(String::isNotBlank)
                    .map { it.split(',') }
            val vendors = rows.filter { it[ROLE] == "VENDOR" }
            val buyers = rows.filter { it[ROLE] == "BUYER" }
            return AccountBook(
                vendors =
                    vendors.mapIndexed { index, row ->
                        VendorAccount(
                            credentials = row.credentials(),
                            vehicleId = row[VEHICLE_ID].toInt(),
                            regionIndex = row[REGION_INDEX].toInt(),
                            clientAddress = syntheticClientAddress(AddressBlock.VENDORS, index),
                        )
                    },
                buyers =
                    buyers.mapIndexed { index, row ->
                        BuyerAccount(
                            credentials = row.credentials(),
                            home = GeoPoint(row[HOME_LATITUDE].toDouble(), row[HOME_LONGITUDE].toDouble()),
                            regionIndex = row[REGION_INDEX].toInt(),
                            clientAddress = syntheticClientAddress(AddressBlock.BUYERS, index),
                        )
                    },
            )
        }

        private fun List<String>.credentials() =
            Credentials(
                userId = this[USER_ID].toInt(),
                email = this[EMAIL],
                password = this[PASSWORD],
                accessToken = this[ACCESS_TOKEN],
            )
    }
}

internal fun accessTokenExpiry(token: String): Instant {
    val payload = Base64.getUrlDecoder().decode(token.split('.')[JWT_PAYLOAD_PART])
    val expiresAtSeconds =
        Json
            .parseToJsonElement(payload.decodeToString())
            .jsonObject
            .getValue("exp")
            .jsonPrimitive.longOrNull
    return Instant.ofEpochSecond(requireNotNull(expiresAtSeconds) { "A seeded access token has no exp claim" })
}
