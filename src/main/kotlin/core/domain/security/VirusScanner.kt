package com.shelflife.core.domain.security

sealed interface VirusScanResult {
    data object Clean : VirusScanResult

    data class Infected(
        val signature: String,
    ) : VirusScanResult

    data object Unavailable : VirusScanResult
}

interface VirusScanner {
    suspend fun scan(bytes: ByteArray): VirusScanResult
}
