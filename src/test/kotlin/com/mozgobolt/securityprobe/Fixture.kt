/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.securityprobe

import com.mozgobolt.loadtest.AddressBlock
import com.mozgobolt.loadtest.forwardedFor
import com.mozgobolt.loadtest.syntheticClientAddress
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/**
 * A distinct `X-Forwarded-For` client address for one unauthenticated auth call that is not
 * going through [ProbeAccountFactory]. Every such call across the whole suite must get its own
 * address, since `/auth/login` and `/auth/register` share one 5/min-per-source-IP rate limit
 * (`AUTH_LIMIT`): two probes calling either endpoint from the same address within a minute would
 * make the second see a 429 that has nothing to do with the contract it is actually checking.
 */
internal fun nextOneOffAuthClientAddress(): String =
    syntheticClientAddress(AddressBlock.SHARED_NAT, oneOffAuthCallCounter.getAndIncrement())

private val oneOffAuthCallCounter = AtomicInteger(0)

/** A freshly registered, logged-in throwaway account this suite owns end to end. */
internal data class ProbeAccount(
    val userId: Int,
    val email: String,
    val password: String,
    val accessToken: String,
    val refreshToken: String,
    val clientAddress: String,
)

/**
 * Registers and logs in disposable accounts for the probe suite, each its own forwarded client
 * address (see [com.mozgobolt.loadtest.Accounts]) so one probe account's login never shares a
 * rate-limit bucket with another's.
 */
internal class ProbeAccountFactory(
    private val api: ProbeApi,
) {
    private var nextIndex = 0

    suspend fun register(role: String): ProbeAccount {
        val index = nextIndex++
        val clientAddress = syntheticClientAddress(AddressBlock.LOGIN_BURST, index)
        val email = "security-probe-$index-${Random.nextInt(Int.MAX_VALUE)}@loadtest.invalid"
        val password = "Security-Probe-Only-${Random.nextLong().toString(RADIX_36)}-Passphrase"
        val headers = mapOf("X-Forwarded-For" to forwardedFor(clientAddress))

        val registerResponse =
            api.postJson(
                "/api/v1/auth/register",
                json = registrationBody(email, password, role),
                headers = headers,
            )
        check(registerResponse.status == HttpStatusCode.Created.value) {
            "Could not register a probe fixture account: HTTP ${registerResponse.status} ${registerResponse.body}"
        }

        val loginResponse =
            api.postJson(
                "/api/v1/auth/login",
                json = credentialsBody(email, password),
                headers = headers,
            )
        check(loginResponse.status == HttpStatusCode.OK.value) {
            "Could not log in a just-registered probe fixture account: " +
                "HTTP ${loginResponse.status} ${loginResponse.body}"
        }
        val tokens = Json.parseToJsonElement(loginResponse.body).jsonObject

        return ProbeAccount(
            userId = userIdFromAccessToken(tokens.getValue("accessToken").jsonPrimitive.content),
            email = email,
            password = password,
            accessToken = tokens.getValue("accessToken").jsonPrimitive.content,
            refreshToken = tokens.getValue("refreshToken").jsonPrimitive.content,
            clientAddress = clientAddress,
        )
    }

    private fun registrationBody(
        email: String,
        password: String,
        role: String,
    ): String =
        JsonObject(
            mapOf(
                "email" to JsonPrimitive(email),
                "password" to JsonPrimitive(password),
                "name" to JsonPrimitive("Security Probe"),
                "role" to JsonPrimitive(role),
            ),
        ).toString()

    private fun credentialsBody(
        email: String,
        password: String,
    ): String = JsonObject(mapOf("email" to JsonPrimitive(email), "password" to JsonPrimitive(password))).toString()

    private companion object {
        const val RADIX_36 = 36
    }
}

/** The app's own access tokens are JWTs carrying `userId` as an unsigned claim this suite can read back. */
internal fun userIdFromAccessToken(token: String): Int {
    val payload =
        java.util.Base64
            .getUrlDecoder()
            .decode(token.split('.')[1].padBase64())
    return Json
        .parseToJsonElement(payload.decodeToString())
        .jsonObject
        .getValue("userId")
        .jsonPrimitive.content
        .toInt()
}

private fun String.padBase64(): String = this + "=".repeat((4 - length % 4) % 4)
