/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.core.data.media

import com.mozgobolt.core.domain.media.ImageStorage
import com.mozgobolt.core.modules.AppConfig
import com.mozgobolt.core.resilience.ResiliencePolicy
import com.mozgobolt.core.utility.functions.runSuspendCatching
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration
import software.amazon.awssdk.core.retry.RetryMode
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.Delete
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.NoSuchKeyException
import software.amazon.awssdk.services.s3.model.ObjectIdentifier
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import java.net.URI
import java.time.Duration

private val logger = KotlinLogging.logger {}

class S3ImageStorage(
    private val client: S3Client,
    private val bucket: String,
    private val resilience: ResiliencePolicy,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ImageStorage {
    override suspend fun store(
        key: String,
        bytes: ByteArray,
    ) {
        resilience.execute {
            withContext(ioDispatcher) {
                client.putObject(
                    PutObjectRequest
                        .builder()
                        .bucket(bucket)
                        .key(key)
                        .build(),
                    RequestBody.fromBytes(bytes),
                )
            }
        }
    }

    override suspend fun read(key: String): ByteArray? =
        try {
            resilience.execute {
                withContext(ioDispatcher) {
                    client
                        .getObject(
                            GetObjectRequest
                                .builder()
                                .bucket(bucket)
                                .key(key)
                                .build(),
                        ).use { it.readAllBytes() }
                }
            }
        } catch (e: NoSuchKeyException) {
            logger.debug(e) { "No object for key=$key — treating as absent" }
            null
        }

    override suspend fun deleteBestEffort(keys: List<String>) {
        if (keys.isEmpty()) return

        runSuspendCatching {
            resilience.execute {
                withContext(ioDispatcher) {
                    val response =
                        client.deleteObjects(
                            DeleteObjectsRequest
                                .builder()
                                .bucket(bucket)
                                .delete(
                                    Delete
                                        .builder()
                                        .objects(keys.map { ObjectIdentifier.builder().key(it).build() })
                                        .build(),
                                ).build(),
                        )
                    val errors = response.errors()
                    if (errors.isNotEmpty()) {
                        logger.warn { "S3 reported ${errors.size} image object deletion failure(s)" }
                    }
                }
            }
        }.onFailure { error ->
            logger.warn(error) { "S3 image-object cleanup failed; requested objects may remain" }
        }
    }

    companion object {
        fun buildClient(config: AppConfig.ObjectStorage): S3Client =
            S3Client
                .builder()
                .endpointOverride(URI.create(config.endpoint))
                .region(Region.of(config.region))
                .credentialsProvider(
                    StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(config.accessKeyId, config.secretAccessKey),
                    ),
                ).forcePathStyle(true)
                .overrideConfiguration(
                    ClientOverrideConfiguration
                        .builder()
                        .retryStrategy(RetryMode.STANDARD)
                        .apiCallTimeout(Duration.ofMillis(config.apiCallTimeoutMs))
                        .apiCallAttemptTimeout(Duration.ofMillis(config.apiCallAttemptTimeoutMs))
                        .build(),
                ).build()
    }
}
