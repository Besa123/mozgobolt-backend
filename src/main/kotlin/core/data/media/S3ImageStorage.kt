package com.shelflife.core.data.media

import com.shelflife.core.domain.media.ImageStorage
import com.shelflife.core.modules.AppConfig
import com.shelflife.core.modules.plugin.ResiliencePolicy
import com.shelflife.core.modules.plugin.ResilienceRegistry
import com.shelflife.core.modules.plugin.withS3Resilience
import com.shelflife.core.utility.functions.runSuspendCatching
import io.github.oshai.kotlinlogging.KotlinLogging
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
    private val resilience: ResiliencePolicy = ResilienceRegistry.s3,
) : ImageStorage {
    override suspend fun store(
        key: String,
        bytes: ByteArray,
    ) {
        withS3Resilience(resilience) {
            withContext(Dispatchers.IO) {
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
            withS3Resilience(resilience) {
                withContext(Dispatchers.IO) {
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
            withS3Resilience(resilience) {
                withContext(Dispatchers.IO) {
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
                }
            }
        }.onFailure { error ->
            logger.warn(error) { "Failed to delete objects from S3: $keys — leaving them in place" }
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
