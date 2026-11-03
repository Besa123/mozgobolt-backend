/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.core.domain.media

/** Maximum accepted length for an image storage key. */
const val IMAGE_STORAGE_KEY_MAX_LENGTH = 128

/** Stores private image bytes under application-generated storage keys. */
interface ImageStorage {
    /** Stores [bytes] under [key], replacing any existing object with that key. */
    suspend fun store(
        key: String,
        bytes: ByteArray,
    )

    /** Reads the object at [key], or returns `null` when it does not exist. */
    suspend fun read(key: String): ByteArray?

    /** Attempts to delete each supplied key without failing the caller on cleanup errors. */
    suspend fun deleteBestEffort(keys: List<String>)
}
