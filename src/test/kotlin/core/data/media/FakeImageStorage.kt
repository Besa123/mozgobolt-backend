package com.shelflife.core.data.media

import com.shelflife.core.domain.media.ImageStorage

class FakeImageStorage : ImageStorage {
    val stored = mutableMapOf<String, ByteArray>()
    val deletedKeys = mutableListOf<String>()
    var failNextStore: Boolean = false
    var failNextRead: Boolean = false

    override suspend fun store(
        key: String,
        bytes: ByteArray,
    ) {
        if (failNextStore) {
            failNextStore = false
            throw SimulatedStorageFailure()
        }
        stored[key] = bytes
    }

    override suspend fun read(key: String): ByteArray? {
        if (failNextRead) {
            failNextRead = false
            throw SimulatedStorageFailure()
        }
        return stored[key]
    }

    override suspend fun deleteBestEffort(keys: List<String>) {
        deletedKeys += keys
        keys.forEach { stored.remove(it) }
    }

    class SimulatedStorageFailure : RuntimeException("storage backend unreachable")
}
