package com.shelflife.core.data.security

import com.shelflife.core.domain.security.VirusScanResult
import com.shelflife.core.modules.AppConfig
import com.shelflife.core.modules.plugin.ResiliencePolicy
import com.shelflife.core.modules.plugin.ResilienceRegistry
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.github.resilience4j.retry.Retry
import kotlinx.coroutines.runBlocking
import java.net.ServerSocket
import kotlin.system.measureTimeMillis
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ClamAvVirusScannerTest {
    private fun freshResilience(): ResiliencePolicy =
        ResiliencePolicy(
            circuitBreaker =
                CircuitBreaker.of(
                    "test-clamav-breaker",
                    ResilienceRegistry.clamAvCircuitBreaker.circuitBreakerConfig,
                ),
            retry = Retry.of("test-clamav-retry", ResilienceRegistry.clamAvRetry.retryConfig),
        )

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
}
