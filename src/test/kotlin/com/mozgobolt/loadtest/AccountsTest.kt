/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.loadtest

import java.nio.file.Path
import java.time.Instant
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AccountsTest {
    @Test
    fun `load test accounts can be written to a per-run file under build output`() {
        val output = loadTestAccountsOutputPath("build/load-test/parking-stress-users.csv")

        assertEquals(
            Path.of("build/load-test/parking-stress-users.csv").toAbsolutePath().normalize(),
            output,
        )
    }

    @Test
    fun `load test account output cannot escape ignored build output`() {
        assertFailsWith<IllegalArgumentException> {
            loadTestAccountsOutputPath("../.env")
        }
    }

    private fun tokenExpiringAt(epochSeconds: Long): String {
        val payload = Base64.getUrlEncoder().withoutPadding().encodeToString("""{"exp":$epochSeconds}""".toByteArray())
        return "header.$payload.signature"
    }

    @Test
    fun `synthetic client addresses are valid IPv4 and unique across every block and index`() {
        val addresses =
            AddressBlock.entries.flatMap { block -> (0 until 1_000).map { syntheticClientAddress(block, it) } }

        assertEquals(addresses.size, addresses.toSet().size)
        assertTrue(
            addresses.all { address ->
                address.split('.').let {
                    it.size == 4 &&
                        it.all { octet -> octet.toInt() in 0..255 }
                }
            },
            "every octet must be 0..255",
        )
    }

    @Test
    fun `forwardedFor emulates a single trusted local proxy hop`() {
        assertEquals("10.2.0.1, 127.0.0.1", forwardedFor("10.2.0.1"))
    }

    @Test
    fun `accessTokenExpiry reads the exp claim`() {
        assertEquals(Instant.ofEpochSecond(1_900_000_000), accessTokenExpiry(tokenExpiringAt(1_900_000_000)))
    }

    @Test
    fun `tokens that expire during the run are rejected up front`() {
        val credentials = { exp: Long -> Credentials(1, "a@x.invalid", "pw", tokenExpiringAt(exp)) }
        val soon = Instant.now().plusSeconds(30).epochSecond
        val later = Instant.now().plusSeconds(3_600).epochSecond
        val book = { exp: Long ->
            AccountBook(
                emptyList(),
                listOf(
                    BuyerAccount(
                        credentials(exp),
                        com.mozgobolt.feature.vehicleTracking.domain.model
                            .GeoPoint(0.0, 0.0),
                        regionIndex = 0,
                        clientAddress = "10.2.0.1",
                    ),
                ),
            )
        }

        book(later).requireTokensValidFor(kotlin.time.Duration.parse("5m"))
        assertFailsWith<IllegalArgumentException> { book(soon).requireTokensValidFor(kotlin.time.Duration.parse("5m")) }
    }
}
