/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.core.data.security

import com.mozgobolt.core.domain.security.VirusScanResult
import com.mozgobolt.core.domain.security.VirusScanner
import com.mozgobolt.core.modules.AppConfig
import com.mozgobolt.core.resilience.ResiliencePolicy
import com.mozgobolt.core.utility.functions.runSuspendCatching
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import xyz.capybara.clamav.ClamavClient
import xyz.capybara.clamav.commands.scan.result.ScanResult
import java.io.ByteArrayInputStream
import java.io.IOException
import java.time.Duration
import java.util.concurrent.TimeoutException

private val logger = KotlinLogging.logger {}

@Suppress("TooGenericExceptionCaught")
class ClamAvVirusScanner(
    private val config: AppConfig.ClamAv,
    private val resilience: ResiliencePolicy,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : VirusScanner {
    private val client = ClamavClient(config.host, config.port)
    private val scanResilience = resilience.withTimeLimiter("clamAvScan", Duration.ofMillis(config.timeoutMs))

    override suspend fun scan(bytes: ByteArray): VirusScanResult =
        try {
            scanResilience.execute { scanInterruptibly(bytes) }.toVirusScanResult()
        } catch (failure: Exception) {
            when (failure) {
                is CancellationException -> throw failure
                is TimeoutException -> logger.warn(failure) { "ClamAV scan timed out after ${config.timeoutMs}ms" }
                else -> logger.warn(failure) { "ClamAV scan unavailable" }
            }
            VirusScanResult.Unavailable
        }

    private fun ScanResult.toVirusScanResult(): VirusScanResult =
        when (this) {
            is ScanResult.OK -> {
                VirusScanResult.Clean
            }

            is ScanResult.VirusFound -> {
                VirusScanResult.Infected(foundViruses.values.flatten().firstOrNull() ?: "unknown")
            }
        }

    @Suppress("RedundantSuspendModifier") // runInterruptible suspends inside the inline Result block.
    private suspend fun scanInterruptibly(bytes: ByteArray): ScanResult =
        runSuspendCatching {
            runInterruptible(ioDispatcher) { client.scan(ByteArrayInputStream(bytes)) }
        }.getOrElse { failure ->
            if (failure.hasIOExceptionCause()) throw TransientClamAvException(failure)
            throw failure
        }

    private fun Throwable.hasIOExceptionCause(): Boolean =
        generateSequence(this) { current -> current.cause }.any { cause -> cause is IOException }
}

internal class TransientClamAvException(
    cause: Throwable,
) : RuntimeException(cause)
