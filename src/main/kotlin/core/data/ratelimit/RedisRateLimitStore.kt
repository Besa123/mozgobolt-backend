/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.core.data.ratelimit

import com.mozgobolt.core.utility.functions.closeAllResources
import com.mozgobolt.core.utility.functions.runSuspendCatching
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.server.plugins.ratelimit.RateLimiter
import io.lettuce.core.ExperimentalLettuceCoroutinesApi
import io.lettuce.core.RedisClient
import io.lettuce.core.RedisException
import io.lettuce.core.RedisURI
import io.lettuce.core.ScriptOutputType
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.api.coroutines
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.charset.StandardCharsets
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import java.time.Duration as JavaDuration

private val logger = KotlinLogging.logger {}
private val REDIS_COMMAND_TIMEOUT = 2.seconds
private const val RATE_LIMIT_KEY_PREFIX = "mozgobolt:rate-limits:v1:"
private const val RATE_LIMIT_KEY_CONTEXT = "mozgobolt:rate-limits:v1"

@Suppress("MultilineRawStringIndentation") // Lua ignores leading whitespace; keep the script as a constant.
private const val RATE_LIMIT_SCRIPT =
    """
    local limit = tonumber(ARGV[1])
    local period = tonumber(ARGV[2])
    local weight = tonumber(ARGV[3])
    local time = redis.call('TIME')
    local now = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)
    local tokens = tonumber(redis.call('HGET', KEYS[1], 'tokens'))
    local lastRefill = tonumber(redis.call('HGET', KEYS[1], 'lastRefill'))

    if tokens == nil or lastRefill == nil then
        tokens = limit
        lastRefill = now
    elseif period - (now - lastRefill) <= 0 then
        tokens = limit
        lastRefill = now
    end

    local allowed = 0
    local remaining = tokens
    if weight <= limit and tokens - weight >= 0 then
        remaining = tokens - weight
        allowed = 1
    end

    local refillAt = lastRefill + period
    redis.call('HSET', KEYS[1], 'tokens', remaining, 'lastRefill', lastRefill)
    redis.call('PEXPIRE', KEYS[1], period * 2)

    return {allowed, math.floor(remaining), refillAt, math.max(0, refillAt - now)}
    """

/**
 * Shares Ktor's fixed-window refill semantics across application instances using one atomic
 * Valkey/Redis script. Request identifiers use keyed hashes before they become Redis keys.
 */
class RedisRateLimitStore(
    redisUri: String,
    namespace: String,
    keySecret: String,
) {
    private val redisUri =
        RedisURI
            .create(redisUri)
            .apply { timeout = JavaDuration.ofMillis(REDIS_COMMAND_TIMEOUT.inWholeMilliseconds) }
    private val namespace = namespace.takeIf(String::isNotBlank) ?: error("Rate-limit namespace must not be blank")
    private val key =
        keySecret.takeIf(String::isNotBlank)?.let {
            SecretKeySpec(it.toByteArray(StandardCharsets.UTF_8), "HmacSHA256")
        } ?: error("Rate-limit key secret must not be blank")

    private var client: RedisClient? = null
    private var connection: StatefulRedisConnection<String, String>? = null

    @Synchronized
    fun start() {
        check(client == null) { "RedisRateLimitStore.start() may only be called once" }
        val newClient = RedisClient.create(redisUri)
        try {
            val newConnection = newClient.connect()
            client = newClient
            connection = newConnection
        } catch (failure: RedisException) {
            newClient.shutdown()
            throw failure
        }
    }

    @Synchronized
    fun stop() {
        val redisConnection = connection
        val redisClient = client
        connection = null
        client = null
        closeAllResources(
            { redisConnection?.close() },
            { redisClient?.shutdown() },
        )
    }

    fun rateLimiter(
        bucket: String,
        requestKey: Any,
        limit: Int,
        refillPeriod: Duration,
    ): RateLimiter {
        require(bucket.isNotBlank()) { "Rate-limit bucket must not be blank" }
        return RedisBackedRateLimiter(
            store = this,
            redisKey = redisKey(bucket, requestKey.toString()),
            limit = limit,
            refillPeriod = refillPeriod,
        )
    }

    @OptIn(ExperimentalLettuceCoroutinesApi::class)
    suspend fun ping() {
        val redisConnection =
            checkNotNull(connection) {
                "RedisRateLimitStore.start() must be called before ping()"
            }
        executeRedisCommand { redisConnection.coroutines().ping() }
    }

    @Suppress("GroovyScriptInjection") // The script is fixed; request-specific values are bound as EVAL args.
    @OptIn(ExperimentalLettuceCoroutinesApi::class)
    private suspend fun tryConsume(
        redisKey: String,
        limit: Int,
        refillPeriod: Duration,
        weight: Int,
    ): RateLimiter.State {
        require(limit > 0) { "Rate limit must be greater than zero" }
        require(weight > 0) { "Rate-limit request weight must be greater than zero" }
        val refillPeriodMillis = refillPeriod.inWholeMilliseconds
        require(refillPeriodMillis > 0) { "Rate-limit refill period must be at least one millisecond" }

        val redisConnection =
            checkNotNull(connection) {
                "RedisRateLimitStore.start() must be called before rate limiting"
            }
        val values =
            executeRedisCommand {
                redisConnection
                    .coroutines()
                    .eval<List<Long>>(
                        RATE_LIMIT_SCRIPT,
                        ScriptOutputType.MULTI,
                        arrayOf(redisKey),
                        limit.toString(),
                        refillPeriodMillis.toString(),
                        weight.toString(),
                    )
            }

        check(values.size == SCRIPT_RESULT_SIZE) { "Redis rate-limit script returned an invalid result" }
        val isAllowed = values[ALLOWED_RESULT] == 1L
        val remaining = values[REMAINING_RESULT].toInt()
        val refillAt = values[REFILL_AT_RESULT]
        val toWait = values[WAIT_RESULT]
        return if (isAllowed) {
            RateLimiter.State.Available(remaining, limit, refillAt)
        } else {
            RateLimiter.State.Exhausted(toWait.milliseconds)
        }
    }

    private fun redisKey(
        bucket: String,
        requestKey: String,
    ): String {
        val identity =
            "$RATE_LIMIT_KEY_CONTEXT\u0000$namespace\u0000$bucket\u0000$requestKey"
                .toByteArray(StandardCharsets.UTF_8)
        val mac =
            Mac.getInstance("HmacSHA256").apply {
                init(key)
            }
        val digest = mac.doFinal(identity)
        return RATE_LIMIT_KEY_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }

    private suspend fun <T : Any> executeRedisCommand(command: suspend () -> T?): T =
        runSuspendCatching {
            withTimeoutOrNull(REDIS_COMMAND_TIMEOUT) { command() } ?: throw RateLimitStoreUnavailableException()
        }.getOrElse { failure ->
            if (failure is RedisException) {
                logger.warn {
                    "Shared rate-limit storage failed (${failure.javaClass.simpleName}); rejecting the request"
                }
            }
            throw failure.toRateLimitStoreFailure()
        }

    private fun Throwable.toRateLimitStoreFailure() =
        when (this) {
            is RedisException,
            is RateLimitStoreUnavailableException,
            -> RateLimitStoreUnavailableException()

            else -> this
        }

    private class RedisBackedRateLimiter(
        private val store: RedisRateLimitStore,
        private val redisKey: String,
        private val limit: Int,
        private val refillPeriod: Duration,
    ) : RateLimiter {
        override suspend fun tryConsume(tokens: Int): RateLimiter.State =
            store.tryConsume(redisKey = redisKey, limit = limit, refillPeriod = refillPeriod, weight = tokens)
    }

    companion object {
        private const val SCRIPT_RESULT_SIZE = 4
        private const val ALLOWED_RESULT = 0
        private const val REMAINING_RESULT = 1
        private const val REFILL_AT_RESULT = 2
        private const val WAIT_RESULT = 3
    }
}

class RateLimitStoreUnavailableException : RuntimeException("Shared rate-limit storage is unavailable")
