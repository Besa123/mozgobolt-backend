package com.mozgobolt.core.data.security

import com.mozgobolt.core.domain.security.VirusScanResult
import com.mozgobolt.core.domain.security.VirusScanner
import com.mozgobolt.core.modules.AppConfig
import com.mozgobolt.core.modules.plugin.ResiliencePolicy
import com.mozgobolt.core.modules.plugin.ResilienceRegistry
import com.mozgobolt.core.modules.plugin.withClamAvResilience
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeout
import xyz.capybara.clamav.ClamavClient
import xyz.capybara.clamav.commands.scan.result.ScanResult
import java.io.ByteArrayInputStream
import kotlin.time.Duration.Companion.milliseconds

private val logger = KotlinLogging.logger {}

@Suppress("TooGenericExceptionCaught")
class ClamAvVirusScanner(
    private val config: AppConfig.ClamAv,
    private val resilience: ResiliencePolicy = ResilienceRegistry.clamAv,
) : VirusScanner {
    private val client = ClamavClient(config.host, config.port)

    override suspend fun scan(bytes: ByteArray): VirusScanResult =
        try {
            withTimeout(config.timeoutMs.milliseconds) {
                withClamAvResilience(resilience) {
                    runInterruptible(Dispatchers.IO) { client.scan(ByteArrayInputStream(bytes)) }
                }
            }.toVirusScanResult()
        } catch (e: TimeoutCancellationException) {
            logger.warn { "ClamAV scan timed out after ${config.timeoutMs}ms" }
            VirusScanResult.Unavailable
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(e) { "ClamAV scan unavailable" }
            VirusScanResult.Unavailable
        }

    private fun ScanResult.toVirusScanResult(): VirusScanResult =
        when (this) {
            is ScanResult.OK -> VirusScanResult.Clean
            is ScanResult.VirusFound ->
                VirusScanResult.Infected(foundViruses.values.flatten().firstOrNull() ?: "unknown")
        }
}
