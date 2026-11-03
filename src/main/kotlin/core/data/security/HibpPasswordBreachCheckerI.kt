/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.core.data.security

import com.mozgobolt.core.domain.AppResult
import com.mozgobolt.core.domain.security.PasswordBreachCheckError
import com.mozgobolt.core.domain.security.PasswordBreachChecker
import com.mozgobolt.core.resilience.ResiliencePolicy
import com.mozgobolt.core.utility.functions.runSuspendCatching
import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.resilience4j.bulkhead.BulkheadFullException
import io.github.resilience4j.circuitbreaker.CallNotPermittedException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Duration
import java.util.concurrent.TimeoutException

private val logger = KotlinLogging.logger {}

private const val HIBP_RANGE_ENDPOINT = "https://api.pwnedpasswords.com/range/"
private const val SHA1_PREFIX_LENGTH = 5
private const val SHA1_HASH_LENGTH = 40
private const val SHA1_SUFFIX_LENGTH = SHA1_HASH_LENGTH - SHA1_PREFIX_LENGTH
private const val UNSIGNED_BYTE_MASK = 0xFF
private const val HEX_DIGIT_SHIFT = 4
private const val HEX_NIBBLE_MASK = 0x0F
private const val USER_AGENT = "MozgoBolt Password Validation"
private const val REQUEST_TIMEOUT_SECONDS = 1L
private const val HTTP_REQUEST_TIMEOUT_STATUS = 408
private const val HEX_DIGITS = "0123456789ABCDEF"

private val HTTP_REQUEST_TIMEOUT = Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS)
private val DEFAULT_HTTP_CLIENT =
    HttpClient
        .newBuilder()
        .connectTimeout(HTTP_REQUEST_TIMEOUT)
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()

class HibpPasswordBreachCheckerI internal constructor(
    private val resilience: ResiliencePolicy,
    private val httpClient: HttpClient = DEFAULT_HTTP_CLIENT,
    private val rangeEndpoint: URI = URI.create(HIBP_RANGE_ENDPOINT),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : PasswordBreachChecker {
    override suspend fun isCompromised(password: String): AppResult<Boolean, PasswordBreachCheckError> {
        val passwordHash = password.sha1Hex()
        val prefix = passwordHash.take(SHA1_PREFIX_LENGTH)
        val suffix = passwordHash.drop(SHA1_PREFIX_LENGTH)

        return runSuspendCatching {
            resilience.execute {
                fetchHashRange(prefix).containsPositiveSuffix(suffix)
            }
        }.fold(
            onSuccess = { AppResult.Success(it) },
            onFailure = { failure ->
                if (failure.isExpectedUnavailableFailure()) unavailable() else throw failure
            },
        )
    }

    private suspend fun fetchHashRange(prefix: String): String {
        val request =
            HttpRequest
                .newBuilder(rangeEndpoint.resolve(prefix))
                .timeout(HTTP_REQUEST_TIMEOUT)
                .header("User-Agent", USER_AGENT)
                .header("Add-Padding", "true")
                .GET()
                .build()

        return send(request).requireSuccessfulBody()
    }

    @Suppress("RedundantSuspendModifier") // runInterruptible suspends while performing the blocking HTTP call.
    private suspend fun send(request: HttpRequest): HttpResponse<String> =
        try {
            runInterruptible(ioDispatcher) {
                httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.US_ASCII))
            }
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw CancellationException("Password breach lookup interrupted").apply {
                initCause(interrupted)
            }
        } catch (failure: IOException) {
            throw TransientPasswordBreachException(failure)
        }

    private fun unavailable(): AppResult.Error<PasswordBreachCheckError> {
        logger.warn { "Password breach check unavailable" }
        return AppResult.Error(PasswordBreachCheckError.UNAVAILABLE)
    }

    private fun Throwable.isExpectedUnavailableFailure(): Boolean =
        this is TransientPasswordBreachException ||
            this is PasswordBreachServiceException ||
            this is TimeoutException ||
            this is BulkheadFullException ||
            this is CallNotPermittedException

    private fun HttpResponse<String>.requireSuccessfulBody(): String =
        when {
            statusCode() == HTTP_OK -> {
                body()
            }

            statusCode() == HTTP_TOO_MANY_REQUESTS ||
                statusCode() == HTTP_REQUEST_TIMEOUT_STATUS ||
                statusCode() in HTTP_SERVER_ERROR_RANGE
            -> {
                throw TransientPasswordBreachException()
            }

            else -> {
                throw PasswordBreachServiceException()
            }
        }
}

private fun String.containsPositiveSuffix(expectedSuffix: String): Boolean =
    lineSequence()
        .map(String::trim)
        .filter(String::isNotEmpty)
        .map { line -> line.parsePwnedSuffix() ?: throw PasswordBreachServiceException() }
        .any { (suffix, count) -> count > 0 && suffix.equals(expectedSuffix, ignoreCase = true) }

private fun String.parsePwnedSuffix(): Pair<String, Long>? =
    indexOf(':')
        .takeIf { it == SHA1_SUFFIX_LENGTH }
        ?.let { separator ->
            val suffix =
                substring(0, separator)
                    .takeIf { value -> value.all { it in HEX_DIGITS } }
                    ?: return@let null
            val count =
                substring(separator + 1)
                    .toLongOrNull()
                    ?.takeIf { it >= 0 }
                    ?: return@let null
            suffix to count
        }

// HIBP's range-query protocol requires SHA-1; this hash is not used to store or verify passwords.
@Suppress("WeakHashAlgorithm")
private fun String.sha1Hex(): String {
    val digest = MessageDigest.getInstance("SHA-1").digest(toByteArray(StandardCharsets.UTF_8))
    return buildString(SHA1_HASH_LENGTH) {
        digest.forEach { byte ->
            val unsigned = byte.toInt() and UNSIGNED_BYTE_MASK
            append(HEX_DIGITS[unsigned ushr HEX_DIGIT_SHIFT])
            append(HEX_DIGITS[unsigned and HEX_NIBBLE_MASK])
        }
    }
}

private class PasswordBreachServiceException : RuntimeException()

private const val HTTP_OK = 200
private const val HTTP_TOO_MANY_REQUESTS = 429
private val HTTP_SERVER_ERROR_RANGE = 500..599
