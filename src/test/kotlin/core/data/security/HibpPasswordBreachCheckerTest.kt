/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.core.data.security

import com.mozgobolt.core.domain.AppResult
import com.mozgobolt.core.domain.security.PasswordBreachCheckError
import com.mozgobolt.core.resilience.ResiliencePolicy
import com.sun.net.httpserver.HttpServer
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Duration
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class HibpPasswordBreachCheckerTest {
    @Test
    fun `checks only the hash prefix and ignores zero-count padding entries`() {
        runBlocking {
            val password = "correct horse battery staple 2026"
            val hash = password.sha1Hex()
            val prefix = hash.take(5)
            val suffix = hash.drop(5)
            val requestPath = AtomicReference<String>()
            val userAgent = AtomicReference<String>()
            val paddingHeader = AtomicReference<String>()
            val responseBody = AtomicReference("$suffix:0\r\n${"0".repeat(35)}:2\r\n")
            val server =
                HttpServer
                    .create(InetSocketAddress("127.0.0.1", 0), 0)
                    .apply {
                        createContext("/range/") { exchange ->
                            requestPath.set(exchange.requestURI.rawPath)
                            userAgent.set(exchange.requestHeaders.getFirst("User-Agent"))
                            paddingHeader.set(exchange.requestHeaders.getFirst("Add-Padding"))
                            val bytes = responseBody.get().toByteArray(StandardCharsets.US_ASCII)
                            exchange.sendResponseHeaders(200, bytes.size.toLong())
                            exchange.responseBody.use { it.write(bytes) }
                        }
                        start()
                    }

            try {
                val checker = checker(server)

                assertEquals(AppResult.Success(false), checker.isCompromised(password))
                assertEquals("/range/$prefix", requestPath.get())
                assertFalse(requestPath.get().contains(hash))
                assertEquals("MozgoBolt Password Validation", userAgent.get())
                assertEquals("true", paddingHeader.get())

                responseBody.set("$suffix:4\r\n")

                assertEquals(AppResult.Success(true), checker.isCompromised(password))
            } finally {
                server.stop(0)
            }
        }
    }

    @Test
    fun `returns unavailable when the breach service does not respond successfully`() {
        runBlocking {
            val requestCount = AtomicInteger()
            val server =
                HttpServer
                    .create(InetSocketAddress("127.0.0.1", 0), 0)
                    .apply {
                        createContext("/range/") { exchange ->
                            requestCount.incrementAndGet()
                            exchange.sendResponseHeaders(503, -1)
                            exchange.close()
                        }
                        start()
                    }

            try {
                val result = checker(server).isCompromised("a sufficiently long passphrase")

                assertEquals(
                    AppResult.Error(PasswordBreachCheckError.UNAVAILABLE),
                    result,
                )
                assertEquals(1, requestCount.get())
            } finally {
                server.stop(0)
            }
        }
    }

    @Test
    fun `fails closed when the breach service returns malformed data`() {
        runBlocking {
            val server =
                HttpServer
                    .create(InetSocketAddress("127.0.0.1", 0), 0)
                    .apply {
                        createContext("/range/") { exchange ->
                            val bytes = "not-a-hash-range-response".toByteArray(StandardCharsets.US_ASCII)
                            exchange.sendResponseHeaders(200, bytes.size.toLong())
                            exchange.responseBody.use { it.write(bytes) }
                        }
                        start()
                    }

            try {
                val result = checker(server).isCompromised("a sufficiently long passphrase")

                assertEquals(AppResult.Error(PasswordBreachCheckError.UNAVAILABLE), result)
            } finally {
                server.stop(0)
            }
        }
    }

    private fun checker(server: HttpServer) =
        HibpPasswordBreachCheckerI(
            resilience =
                ResiliencePolicy(
                    circuitBreaker =
                        CircuitBreaker.of(
                            "hibp-test-${UUID.randomUUID()}",
                            CircuitBreakerConfig.ofDefaults(),
                        ),
                ),
            httpClient =
                HttpClient
                    .newBuilder()
                    .connectTimeout(Duration.ofSeconds(1))
                    .build(),
            rangeEndpoint = URI.create("http://127.0.0.1:${server.address.port}/range/"),
        )

    private fun String.sha1Hex(): String =
        MessageDigest
            .getInstance("SHA-1")
            .digest(toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { byte -> "%02X".format(byte) }
}
