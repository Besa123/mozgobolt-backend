/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.core.data.media

import com.adobe.testing.s3mock.testcontainers.S3MockContainer
import com.mozgobolt.core.modules.AppConfig
import com.mozgobolt.core.resilience.ExternalServiceResiliencePolicies
import com.mozgobolt.core.skipIfNoDocker
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class S3ImageStorageIntegrationTest {
    private val bucket = "test-bucket"
    private val vehiclePictureKey = "vehicles/42/123e4567-e89b-12d3-a456-426614174000"

    @Test
    fun `store, read, and delete round-trip against a real S3-protocol server`() {
        skipIfNoDocker()
        val mock = S3MockContainer("4.9.1").withInitialBuckets(bucket)
        mock.start()
        try {
            val storage =
                S3ImageStorage(
                    client =
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
                    bucket = bucket,
                    resilience = ExternalServiceResiliencePolicies.create().s3,
                )

            runBlocking {
                storage.store(vehiclePictureKey, byteArrayOf(1, 2, 3, 4))
                assertEquals(listOf<Byte>(1, 2, 3, 4), storage.read(vehiclePictureKey)?.toList())

                storage.deleteBestEffort(listOf(vehiclePictureKey))
                assertNull(storage.read(vehiclePictureKey))
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
                    client =
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
                    bucket = bucket,
                    resilience = ExternalServiceResiliencePolicies.create().s3,
                )

            runBlocking {
                assertNull(storage.read("never-stored"))
            }
        } finally {
            mock.stop()
        }
    }
}
