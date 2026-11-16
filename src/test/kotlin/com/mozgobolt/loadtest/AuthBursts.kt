/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.loadtest

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

private const val HTTP_OK = 200
private const val HTTP_TOO_MANY_REQUESTS = 429

/**
 * Logins arriving while telemetry and live maps are busy: the password-hashing path competes with
 * them for CPU, and the per-IP auth rate limit is checked from both sides of a NAT.
 */
internal class AuthBursts(
    private val api: LiveApi,
    private val sink: ObservationSink,
    private val accounts: List<BuyerAccount>,
    private val authLimitPerMinute: Int,
) {
    /** Fires both bursts at once and reports any deviation from the rate-limit contract. */
    suspend fun run() {
        val (distinct, shared) =
            coroutineScope {
                val distinct = async { burst(AddressBlock.LOGIN_BURST, "login.unique", sharedAddress = false) }
                val shared = async { burst(AddressBlock.SHARED_NAT, "login.shared", sharedAddress = true) }
                distinct.await() to shared.await()
            }

        if (distinct.count { it == HTTP_OK } != accounts.size) {
            sink.record(Observation.FunctionalFailure("distinct-IP login burst: not every login succeeded"))
        }
        val sharedAllowed = shared.count { it == HTTP_OK }
        val sharedLimited = shared.count { it == HTTP_TOO_MANY_REQUESTS }
        if (sharedAllowed != authLimitPerMinute || sharedLimited != accounts.size - authLimitPerMinute) {
            sink.record(
                Observation.FunctionalFailure(
                    "shared-NAT login burst: expected $authLimitPerMinute successes and the rest rate-limited, " +
                        "got $sharedAllowed/$sharedLimited",
                ),
            )
        }
    }

    private suspend fun burst(
        block: AddressBlock,
        metricName: String,
        sharedAddress: Boolean,
    ): List<Int> =
        coroutineScope {
            accounts
                .mapIndexed { index, buyer ->
                    async {
                        val address = syntheticClientAddress(block, if (sharedAddress) 0 else index)
                        api.login(buyer.credentials, address, metricName)
                    }
                }.awaitAll()
        }
}
