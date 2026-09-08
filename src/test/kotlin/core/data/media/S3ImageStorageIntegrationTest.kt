package com.shelflife.core.data.media

import com.adobe.testing.s3mock.testcontainers.S3MockContainer
import com.shelflife.core.modules.AppConfig
import com.shelflife.core.skipIfNoDocker
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class S3ImageStorageIntegrationTest {
    private val bucket = "test-bucket"

    @Test
    fun `store, read, and delete round-trip against a real S3-protocol server`() {
        skipIfNoDocker()
        val mock = S3MockContainer("4.9.1").withInitialBuckets(bucket)
        mock.start()
        try {
            val storage =
                S3ImageStorage(
                    S3ImageStorage.buildClient(
                        AppConfig.ObjectStorage(
                            enabled = true,
                            endpoint = mock.httpEndpoint,
                            bucket = bucket,
                            accessKeyId = "test-access-key",
                            secretAccessKey = "test-secret-key",
                            region = "auto",
                        ),
                    ),
                    bucket,
                )

            runBlocking {
                storage.store("key-1", byteArrayOf(1, 2, 3, 4))
                assertEquals(listOf<Byte>(1, 2, 3, 4), storage.read("key-1")?.toList())

                storage.deleteBestEffort(listOf("key-1"))
                assertNull(storage.read("key-1"))
            }
        } finally {
            mock.stop()
        }
    }

    @Test
    fun `reading a key that was never stored returns null against the real server too`() {
        skipIfNoDocker()

        val mock = S3MockContainer("4.9.1").withInitialBuckets(bucket)
        mock.start()
        try {
            val storage =
                S3ImageStorage(
                    S3ImageStorage.buildClient(
                        AppConfig.ObjectStorage(
                            enabled = true,
                            endpoint = mock.httpEndpoint,
                            bucket = bucket,
                            accessKeyId = "test-access-key",
                            secretAccessKey = "test-secret-key",
                            region = "auto",
                        ),
                    ),
                    bucket,
                )

            runBlocking {
                assertNull(storage.read("never-stored"))
            }
        } finally {
            mock.stop()
        }
    }
}
