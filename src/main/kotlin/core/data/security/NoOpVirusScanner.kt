package com.mozgobolt.core.data.security

import com.mozgobolt.core.domain.security.VirusScanResult
import com.mozgobolt.core.domain.security.VirusScanner

class NoOpVirusScanner : VirusScanner {
    override suspend fun scan(bytes: ByteArray): VirusScanResult = VirusScanResult.Clean
}
