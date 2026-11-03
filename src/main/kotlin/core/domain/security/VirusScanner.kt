/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.core.domain.security

/** Result of scanning content for malware. */
sealed interface VirusScanResult {
    /** The scanned content had no detected malware. */
    data object Clean : VirusScanResult

    /** The scanner detected malware with the supplied signature name. */
    data class Infected(
        /** The malware signature reported by the scanner. */
        val signature: String,
    ) : VirusScanResult

    /** The scanner could not determine whether the content was safe. */
    data object Unavailable : VirusScanResult
}

/** Scans uploaded content for malware before it is stored or served. */
interface VirusScanner {
    /** Scans [bytes] and returns the detection or availability result. */
    suspend fun scan(bytes: ByteArray): VirusScanResult
}
