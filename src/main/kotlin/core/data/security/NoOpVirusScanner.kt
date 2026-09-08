package com.shelflife.core.data.security

import com.shelflife.core.domain.security.VirusScanResult
import com.shelflife.core.domain.security.VirusScanner

class NoOpVirusScanner : VirusScanner {
    override suspend fun scan(bytes: ByteArray): VirusScanResult = VirusScanResult.Clean
}
