package com.shelflife.core.data.media

import com.shelflife.core.modules.AppConfig
import com.shelflife.core.modules.plugin.ResiliencePolicy
import com.shelflife.core.modules.plugin.ResilienceRegistry
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import kotlinx.coroutines.runBlocking
import software.amazon.awssdk.core.ResponseInputStream
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest
import software.amazon.awssdk.services.s3.model.DeleteObjectsResponse
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.GetObjectResponse
import software.amazon.awssdk.services.s3.model.NoSuchKeyException
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.model.PutObjectResponse
import java.io.ByteArrayInputStream
import java.net.ServerSocket
import kotlin.system.measureTimeMillis
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class S3ImageStorageTest {
    private fun freshResilience(): ResiliencePolicy =
        ResiliencePolicy(CircuitBreaker.of("test-s3-breaker", ResilienceRegistry.s3CircuitBreaker.circuitBreakerConfig))

    private class FakeS3Client : S3Client {
        val stored = mutableMapOf<String, ByteArray>()
        var lastPutBucket: String? = null
        var lastDeleteObjectKeys: List<String>? = null

        override fun serviceName(): String = "s3"

        override fun close() = Unit

        override fun putObject(
            putObjectRequest: PutObjectRequest,
            requestBody: RequestBody,
        ): PutObjectResponse {
            lastPutBucket = putObjectRequest.bucket()
            stored[putObjectRequest.key()] = requestBody.contentStreamProvider().newStream().readAllBytes()
            return PutObjectResponse.builder().build()
        }

        override fun getObject(getObjectRequest: GetObjectRequest): ResponseInputStream<GetObjectResponse> {
            val bytes = stored[getObjectRequest.key()] ?: throw NoSuchKeyException.builder().build()
            return ResponseInputStream(GetObjectResponse.builder().build(), ByteArrayInputStream(bytes))
        }

        override fun deleteObjects(deleteObjectsRequest: DeleteObjectsRequest): DeleteObjectsResponse {
            val keys = deleteObjectsRequest.delete().objects().map { it.key() }
            lastDeleteObjectKeys = keys
            keys.forEach { stored.remove(it) }
            return DeleteObjectsResponse.builder().build()
        }
    }

    @Test
    fun `store then read round-trips the exact bytes`() {
        runBlocking {
            val client = FakeS3Client()
            val storage = S3ImageStorage(client, "test-bucket", freshResilience())

            storage.store("key-1", byteArrayOf(1, 2, 3))

            assertEquals(listOf<Byte>(1, 2, 3), storage.read("key-1")?.toList())
            assertEquals("test-bucket", client.lastPutBucket)
        }
    }

    @Test
    fun `reading a key that was never stored returns null, not an exception`() {
        runBlocking {
            val storage = S3ImageStorage(FakeS3Client(), "test-bucket", freshResilience())

            assertNull(storage.read("never-stored"))
        }
    }

    @Test
    fun `deleteBestEffort removes every requested key`() {
        runBlocking {
            val client = FakeS3Client()
            val storage = S3ImageStorage(client, "test-bucket", freshResilience())
            storage.store("key-1", byteArrayOf(1))
            storage.store("key-2", byteArrayOf(2))

            storage.deleteBestEffort(listOf("key-1", "key-2"))

            assertNull(storage.read("key-1"))
            assertNull(storage.read("key-2"))
            assertEquals(listOf("key-1", "key-2"), client.lastDeleteObjectKeys)
        }
    }

    @Test
    fun `deleteBestEffort with an empty list never calls S3 at all`() {
        runBlocking {
            val client = FakeS3Client()
            val storage = S3ImageStorage(client, "test-bucket", freshResilience())

            storage.deleteBestEffort(emptyList())

            assertEquals(null, client.lastDeleteObjectKeys)
        }
    }

    @Test
    fun `a client that throws on delete is swallowed, not propagated`() {
        runBlocking {
            val client =
                object : S3Client by FakeS3Client() {
                    override fun deleteObjects(deleteObjectsRequest: DeleteObjectsRequest): DeleteObjectsResponse =
                        throw SimulatedS3Failure()
                }
            val storage = S3ImageStorage(client, "test-bucket", freshResilience())

            // Must not throw — deleteBestEffort's whole contract is "log and move on."
            storage.deleteBestEffort(listOf("key-1"))
            assertTrue(true)
        }
    }

    private class SimulatedS3Failure : RuntimeException("S3 is down")

    @Test
    fun `a wedged endpoint fails within the configured api call timeout, not indefinitely`() {
        val serverSocket = ServerSocket(0)
        val acceptThread =
            Thread {
                // Accept and hold the connection open forever — never write a response, never close.
                runCatching { serverSocket.accept() }
            }.apply {
                isDaemon = true
                start()
            }

        try {
            val client =
                S3ImageStorage.buildClient(
                    AppConfig.ObjectStorage(
                        enabled = true,
                        endpoint = "http://127.0.0.1:${serverSocket.localPort}",
                        bucket = "test-bucket",
                        accessKeyId = "test-access-key",
                        secretAccessKey = "test-secret-key",
                        region = "auto",
                        apiCallTimeoutMs = 500,
                        apiCallAttemptTimeoutMs = 500,
                    ),
                )
            val storage = S3ImageStorage(client, "test-bucket", freshResilience())

            var threw = false
            val elapsedMs =
                measureTimeMillis {
                    runBlocking {
                        runCatching { storage.store("key-1", byteArrayOf(1)) }.onFailure { threw = true }
                    }
                }

            assertTrue(threw, "expected the call to fail against a wedged endpoint, not silently succeed")
            // Generous upper bound — this asserts "doesn't hang indefinitely" (an unbounded client
            // would hang until the OS-level TCP timeout, which can be minutes), not an exact number.
            assertTrue(
                elapsedMs < 5_000,
                "expected the call to give up near the configured timeout, took ${elapsedMs}ms",
            )
        } finally {
            serverSocket.close()
            acceptThread.join(1_000)
        }
    }
}
