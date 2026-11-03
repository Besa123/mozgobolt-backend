/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.core.data.security

import com.mozgobolt.core.domain.security.VirusScanResult
import com.mozgobolt.core.modules.AppConfig
import com.mozgobolt.core.resilience.ExternalServiceResiliencePolicies
import com.mozgobolt.core.resilience.ResiliencePolicy
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.github.resilience4j.retry.Retry
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.system.measureTimeMillis
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ClamAvVirusScannerTest {
    private fun freshResilience(): ResiliencePolicy {
        val profile = ExternalServiceResiliencePolicies.create().clamAv
        return ResiliencePolicy(
            circuitBreaker =
                CircuitBreaker.of(
                    "test-clamav-breaker",
                    profile.circuitBreaker.circuitBreakerConfig,
                ),
            retry = Retry.of("test-clamav-retry", profile.retry!!.retryConfig),
        )
    }

    @Test
    fun `a daemon that accepts the connection but never replies degrades to Unavailable near the configured timeout`() {
        val serverSocket = ServerSocket(0)
        val acceptThread =
            Thread {
                runCatching { serverSocket.accept() }
            }.apply {
                isDaemon = true
                start()
            }

        try {
            val scanner =
                ClamAvVirusScanner(
                    AppConfig.ClamAv(
                        enabled = true,
                        host = "127.0.0.1",
                        port = serverSocket.localPort,
                        timeoutMs = 300,
                    ),
                    resilience = freshResilience(),
                )

            var result: VirusScanResult? = null
            val elapsedMs = measureTimeMillis { result = runBlocking { scanner.scan(byteArrayOf(1, 2, 3)) } }

            assertEquals(VirusScanResult.Unavailable, result)
            assertTrue(
                elapsedMs < 5_000,
                "expected the scan to give up near the configured timeout, took ${elapsedMs}ms",
            )
        } finally {
            serverSocket.close()
            acceptThread.join(1_000)
        }
    }

    @Test
    fun `a refused daemon connection degrades to Unavailable`() {
        val unusedPort = ServerSocket(0).use { it.localPort }
        val scanner =
            ClamAvVirusScanner(
                AppConfig.ClamAv(enabled = true, host = "127.0.0.1", port = unusedPort, timeoutMs = 2_000),
                resilience = freshResilience(),
            )

        assertEquals(VirusScanResult.Unavailable, runBlocking { scanner.scan(byteArrayOf(1, 2, 3)) })
    }

    @Test
    fun `caller cancellation is propagated instead of becoming Unavailable`() {
        val serverSocket = ServerSocket(0)
        val accepted = CountDownLatch(1)
        val releaseServer = CountDownLatch(1)
        val acceptedSocket = AtomicReference<Socket?>()
        val serverThread =
            Thread {
                try {
                    serverSocket.accept().use { socket ->
                        acceptedSocket.set(socket)
                        accepted.countDown()
                        releaseServer.await()
                    }
                } catch (_: Exception) {
                    // Closing the test socket releases this fixture thread during cleanup.
                }
            }.apply {
                isDaemon = true
                start()
            }

        try {
            val scanner =
                ClamAvVirusScanner(
                    AppConfig.ClamAv(
                        enabled = true,
                        host = "127.0.0.1",
                        port = serverSocket.localPort,
                        timeoutMs = 10_000,
                    ),
                    resilience = freshResilience(),
                )

            runBlocking {
                val scan = async { scanner.scan(byteArrayOf(1, 2, 3)) }
                assertTrue(withContext(kotlinx.coroutines.Dispatchers.IO) { accepted.await(2, TimeUnit.SECONDS) })

                scan.cancelAndJoin()

                assertTrue(scan.isCancelled)
            }
        } finally {
            releaseServer.countDown()
            acceptedSocket.get()?.close()
            serverSocket.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `clean and infected ClamAV protocol responses map to domain scan results`() {
        assertEquals(VirusScanResult.Clean, scanAgainstMockDaemon("stream: OK"))
        assertEquals(
            VirusScanResult.Infected("Eicar-Test-Signature"),
            scanAgainstMockDaemon("stream: /tmp/upload: Eicar-Test-Signature FOUND"),
        )
    }

    private fun scanAgainstMockDaemon(response: String): VirusScanResult =
        MockClamAvDaemon(response).use { daemon ->
            val scanner =
                ClamAvVirusScanner(
                    AppConfig.ClamAv(
                        enabled = true,
                        host = "127.0.0.1",
                        port = daemon.port,
                        timeoutMs = 3_000,
                    ),
                    resilience = freshResilience(),
                )
            val result = runBlocking { scanner.scan(byteArrayOf(1, 2, 3)) }
            daemon.throwIfFailed()
            result
        }
}

private class MockClamAvDaemon(
    private val scanResponse: String,
) : AutoCloseable {
    private val serverSocket = ServerSocket(0)
    private val serverFailure = AtomicReference<Throwable?>()
    private val serverThread =
        Thread(::serve).apply {
            isDaemon = true
            start()
        }

    val port: Int
        get() = serverSocket.localPort

    private fun serve() {
        try {
            serveVersionCommand()
            serveScanCommand()
        } catch (failure: Throwable) {
            serverFailure.set(failure)
        }
    }

    private fun serveVersionCommand() {
        serverSocket.accept().use { socket ->
            val command = readCommand(DataInputStream(socket.getInputStream()), '\n'.code)
            check(command == "nVERSIONCOMMANDS")
            socket.getOutputStream().apply {
                write("ClamAV 1.0 | COMMANDS: INSTREAM".toByteArray(StandardCharsets.US_ASCII))
                write('\n'.code)
                flush()
            }
        }
    }

    private fun serveScanCommand() {
        serverSocket.accept().use { socket ->
            val input = DataInputStream(socket.getInputStream())
            check(readCommand(input, 0) == "zINSTREAM")
            discardStreamChunks(input)
            socket.getOutputStream().apply {
                write(scanResponse.toByteArray(StandardCharsets.US_ASCII))
                write(0)
                flush()
            }
        }
    }

    private fun discardStreamChunks(input: DataInputStream) {
        while (true) {
            val chunkSize = input.readInt()
            if (chunkSize == 0) return
            input.skipNBytes(chunkSize.toLong())
        }
    }

    private fun readCommand(
        input: DataInputStream,
        terminator: Int,
    ): String =
        buildString {
            while (true) {
                val value = input.readUnsignedByte()
                if (value == terminator) return@buildString
                append(value.toChar())
            }
        }

    fun throwIfFailed() {
        serverThread.join(1_000)
        serverFailure.get()?.let { throw AssertionError("mock ClamAV daemon failed", it) }
    }

    override fun close() {
        serverSocket.close()
        serverThread.join(1_000)
    }
}
