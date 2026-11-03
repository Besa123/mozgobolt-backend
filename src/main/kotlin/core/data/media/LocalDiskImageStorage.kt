/*
 * SPDX-License-Identifier: Apache-2.0
 */

@file:Suppress("PathTraversal")

package com.mozgobolt.core.data.media

import com.mozgobolt.core.domain.media.IMAGE_STORAGE_KEY_MAX_LENGTH
import com.mozgobolt.core.domain.media.ImageStorage
import com.mozgobolt.core.utility.functions.runSuspendCatching
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption

private val logger = KotlinLogging.logger {}

class LocalDiskImageStorage(
    rootDirectory: String,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ImageStorage {
    private val root: Path = Path.of(rootDirectory).toAbsolutePath().normalize()

    init {
        Files.createDirectories(root)
    }

    override suspend fun store(
        key: String,
        bytes: ByteArray,
    ) {
        val target = resolveWithinRoot(key)
        withContext(ioDispatcher) {
            Files.createDirectories(target.parent)
            val tempFile = Files.createTempFile(root, "upload-", ".tmp")
            try {
                Files.write(tempFile, bytes)
                Files.move(tempFile, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } finally {
                Files.deleteIfExists(tempFile)
            }
        }
    }

    override suspend fun read(key: String): ByteArray? {
        val target = resolveWithinRoot(key)
        return withContext(ioDispatcher) {
            try {
                Files.readAllBytes(target)
            } catch (e: NoSuchFileException) {
                logger.debug(e) { "No image for key=$key — treating as absent" }
                null
            }
        }
    }

    // Each key's delete is fully independent (its own file, its own try/catch, its own log line
    // on failure) — no shared state or ordering between them, so they run concurrently rather
    // than paying for N sequential IO round-trips.
    override suspend fun deleteBestEffort(keys: List<String>) {
        coroutineScope {
            keys.map { key -> async { deleteOne(key) } }.awaitAll()
        }
    }

    @Suppress("RedundantSuspendModifier") // withContext suspends inside the inline Result wrapper.
    private suspend fun deleteOne(key: String) {
        runSuspendCatching {
            withContext(ioDispatcher) {
                Files.deleteIfExists(resolveWithinRoot(key))
            }
        }.onFailure { error ->
            logger.warn(error) { "Failed to remove orphaned image file for key=$key — leaving it in place" }
        }
    }

    private fun resolveWithinRoot(key: String): Path {
        require(key.length <= IMAGE_STORAGE_KEY_MAX_LENGTH && key.isSafeStorageKey()) {
            "Refusing to resolve an unsafe storage key"
        }
        val resolved = root.resolve(key).normalize()
        check(resolved.startsWith(root)) { "Resolved storage path escaped the storage root" }
        return resolved
    }

    private fun String.isSafeStorageKey(): Boolean =
        isNotEmpty() &&
            split('/').all { segment ->
                segment.isNotEmpty() && segment.all(Char::isSafeStorageKeyCharacter)
            }
}

private fun Char.isSafeStorageKeyCharacter(): Boolean =
    this in 'a'..'z' ||
        this in 'A'..'Z' ||
        this in '0'..'9' ||
        this == '_' ||
        this == '-'
