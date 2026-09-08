package com.shelflife.core.domain.media

interface ImageStorage {
    suspend fun store(
        key: String,
        bytes: ByteArray,
    )

    suspend fun read(key: String): ByteArray?

    suspend fun deleteBestEffort(keys: List<String>)
}
