package com.shelflife.core.data.media

import com.shelflife.core.domain.media.ImageStorage
import com.shelflife.core.utility.functions.runSuspendCatching
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption

private val logger = KotlinLogging.logger {}

private val SAFE_KEY_PATTERN = Regex("^[a-zA-Z0-9_-]{1,128}$")

class LocalDiskImageStorage(
    rootDirectory: String,
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
        withContext(Dispatchers.IO) {
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
        return withContext(Dispatchers.IO) {
            try {
                Files.readAllBytes(target)
            } catch (e: NoSuchFileException) {
                logger.debug(e) { "No file for key=$key — treating as absent" }
                null
            }
        }
    }

    override suspend fun deleteBestEffort(keys: List<String>) {
        for (key in keys) {
            runSuspendCatching {
                withContext(Dispatchers.IO) {
                    Files.deleteIfExists(resolveWithinRoot(key))
                }
            }.onFailure { error ->
                logger.warn(error) { "Failed to delete orphaned image file for key=$key — leaving it in place" }
            }
        }
    }

    private fun resolveWithinRoot(key: String): Path {
        require(SAFE_KEY_PATTERN.matches(key)) { "Refusing to resolve an unsafe storage key" }
        val resolved = root.resolve(key).normalize()
        check(resolved.startsWith(root)) { "Resolved storage path escaped the storage root" }
        return resolved
    }
}
