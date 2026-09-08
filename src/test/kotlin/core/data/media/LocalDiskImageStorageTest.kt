package com.shelflife.core.data.media

import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LocalDiskImageStorageTest {
    private fun newStorage(): LocalDiskImageStorage {
        val directory = Files.createTempDirectory("local-disk-image-storage-test")
        return LocalDiskImageStorage(directory.toString())
    }

    @Test
    fun `a stored key round-trips back the exact bytes`() {
        runBlocking {
            val storage = newStorage()
            val key = UUID.randomUUID().toString()
            val bytes = byteArrayOf(1, 2, 3, 4, 5)

            storage.store(key, bytes)

            assertEquals(bytes.toList(), storage.read(key)?.toList())
        }
    }

    @Test
    fun `reading a key that was never stored returns null, not an exception`() {
        runBlocking {
            val storage = newStorage()

            assertNull(storage.read(UUID.randomUUID().toString()))
        }
    }

    @Test
    fun `reading a key whose file was deleted after the fact returns null, not an exception`() {
        runBlocking {
            val directory = Files.createTempDirectory("local-disk-image-storage-test")
            val storage = LocalDiskImageStorage(directory.toString())
            val key = UUID.randomUUID().toString()
            storage.store(key, byteArrayOf(1))

            Files.delete(directory.resolve(key))

            assertNull(storage.read(key))
        }
    }

    @Test
    fun `deleteBestEffort removes a stored file`() {
        runBlocking {
            val storage = newStorage()
            val key = UUID.randomUUID().toString()
            storage.store(key, byteArrayOf(1))

            storage.deleteBestEffort(listOf(key))

            assertNull(storage.read(key))
        }
    }

    @Test
    fun `deleteBestEffort tolerates a key that was never stored, without throwing`() {
        runBlocking {
            val storage = newStorage()

            storage.deleteBestEffort(listOf(UUID.randomUUID().toString()))
        }
    }

    @Test
    fun `replacing an existing key's content is visible on the next read`() {
        runBlocking {
            val storage = newStorage()
            val key = UUID.randomUUID().toString()
            storage.store(key, byteArrayOf(1))

            storage.store(key, byteArrayOf(2, 2))

            assertEquals(listOf<Byte>(2, 2), storage.read(key)?.toList())
        }
    }

    @Test
    fun `a key containing a path separator is rejected rather than resolved`() {
        runBlocking {
            val storage = newStorage()

            assertFailsWith<IllegalArgumentException> { storage.read("../escape") }
        }
    }

    @Test
    fun `a key that is otherwise safe-looking but still escapes the root is rejected`() {
        runBlocking {
            val storage = newStorage()

            assertFailsWith<IllegalArgumentException> { storage.read("a/../../b") }
        }
    }

    @Test
    fun `storing under an unsafe key is rejected before any file is written`() {
        runBlocking {
            val storage = newStorage()

            assertFailsWith<IllegalArgumentException> { storage.store("not safe!", byteArrayOf(1)) }
        }
    }

    @Test
    fun `two different storage instances over the same directory don't see each other's uncommitted temp files`() {
        runBlocking {
            val directory = Files.createTempDirectory("local-disk-image-storage-test")
            val storage = LocalDiskImageStorage(directory.toString())
            val key = UUID.randomUUID().toString()

            storage.store(key, byteArrayOf(9))

            val entries = Files.list(directory).use { it.toList() }
            assertTrue(entries.none { it.fileName.toString().startsWith("upload-") }, "leftover temp file: $entries")
        }
    }
}
