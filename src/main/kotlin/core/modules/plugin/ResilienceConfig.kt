package com.mozgobolt.core.modules.plugin

import com.mozgobolt.core.data.email.TransientEmailDeliveryException
import com.mozgobolt.core.data.push.TransientPushDeliveryException
import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig
import io.github.resilience4j.circuitbreaker.event.CircuitBreakerOnStateTransitionEvent
import io.github.resilience4j.core.IntervalFunction
import io.github.resilience4j.kotlin.circuitbreaker.executeSuspendFunction
import io.github.resilience4j.kotlin.retry.executeSuspendFunction
import io.github.resilience4j.retry.Retry
import io.github.resilience4j.retry.RetryConfig
import kotlinx.coroutines.CancellationException
import software.amazon.awssdk.services.s3.model.NoSuchKeyException
import java.time.Duration

private val logger = KotlinLogging.logger {}

private fun logStateTransition(
    dependencyName: String,
    event: CircuitBreakerOnStateTransitionEvent,
) {
    val message = { "$dependencyName circuit-breaker: ${event.stateTransition}" }
    if (event.stateTransition.toState == CircuitBreaker.State.OPEN) {
        logger.error(message)
    } else {
        logger.warn(message)
    }
}

class ResiliencePolicy(
    val circuitBreaker: CircuitBreaker,
    val retry: Retry? = null,
) {
    suspend fun <T> execute(block: suspend () -> T): T =
        circuitBreaker.executeSuspendFunction {
            retry?.executeSuspendFunction { block() } ?: block()
        }
}

object ResilienceRegistry {
    val emailRetry: Retry =
        Retry
            .of(
                "emailRetry",
                RetryConfig
                    .custom<Any>()
                    .maxAttempts(3)
                    .intervalFunction(IntervalFunction.ofExponentialBackoff(100, 2.0))
                    .retryExceptions(TransientEmailDeliveryException::class.java)
                    .build(),
            ).apply {
                eventPublisher
                    .onRetry { event ->
                        logger.warn { "Email API retry #${event.numberOfRetryAttempts}" }
                    }.onSuccess { event ->
                        if (event.numberOfRetryAttempts > 0) {
                            logger.info { "Email API succeeded after ${event.numberOfRetryAttempts} retries" }
                        }
                    }.onError { event ->
                        logger.error(event.lastThrowable) {
                            "Email API failed after ${event.numberOfRetryAttempts} retries"
                        }
                    }
            }

    val emailCircuitBreaker: CircuitBreaker =
        CircuitBreaker
            .of(
                "emailCircuitBreaker",
                CircuitBreakerConfig
                    .custom()
                    .failureRateThreshold(50f)
                    .slowCallRateThreshold(50f)
                    .slowCallDurationThreshold(Duration.ofSeconds(5))
                    .waitDurationInOpenState(Duration.ofSeconds(30))
                    .minimumNumberOfCalls(10)
                    .slidingWindowSize(20)
                    .build(),
            ).apply {
                eventPublisher.onStateTransition { event -> logStateTransition("Email", event) }
            }

    val email: ResiliencePolicy = ResiliencePolicy(emailCircuitBreaker, emailRetry)

    val clamAvRetry: Retry =
        Retry
            .of(
                "clamAvRetry",
                RetryConfig
                    .custom<Any>()
                    .maxAttempts(2)
                    .intervalFunction(IntervalFunction.ofExponentialBackoff(50, 2.0))
                    .retryOnException { it !is CancellationException }
                    .build(),
            ).apply {
                eventPublisher.onRetry { event ->
                    logger.warn { "ClamAV retry #${event.numberOfRetryAttempts}" }
                }
            }

    val clamAvCircuitBreaker: CircuitBreaker =
        CircuitBreaker
            .of(
                "clamAvCircuitBreaker",
                CircuitBreakerConfig
                    .custom()
                    .failureRateThreshold(50f)
                    .slowCallRateThreshold(50f)
                    .slowCallDurationThreshold(Duration.ofSeconds(3))
                    .waitDurationInOpenState(Duration.ofSeconds(30))
                    .minimumNumberOfCalls(5)
                    .slidingWindowSize(10)
                    .build(),
            ).apply {
                eventPublisher.onStateTransition { event -> logStateTransition("ClamAV", event) }
            }

    val clamAv: ResiliencePolicy = ResiliencePolicy(clamAvCircuitBreaker, clamAvRetry)

    val s3CircuitBreaker: CircuitBreaker =
        CircuitBreaker
            .of(
                "s3CircuitBreaker",
                CircuitBreakerConfig
                    .custom()
                    .failureRateThreshold(50f)
                    .slowCallRateThreshold(50f)
                    .slowCallDurationThreshold(Duration.ofSeconds(5))
                    .waitDurationInOpenState(Duration.ofSeconds(30))
                    .minimumNumberOfCalls(10)
                    .slidingWindowSize(20)
                    .ignoreExceptions(NoSuchKeyException::class.java)
                    .build(),
            ).apply {
                eventPublisher.onStateTransition { event -> logStateTransition("S3", event) }
            }

    val s3: ResiliencePolicy = ResiliencePolicy(s3CircuitBreaker)

    val pushRetry: Retry =
        Retry
            .of(
                "pushRetry",
                RetryConfig
                    .custom<Any>()
                    .maxAttempts(3)
                    .intervalFunction(IntervalFunction.ofExponentialBackoff(100, 2.0))
                    .retryExceptions(TransientPushDeliveryException::class.java)
                    .build(),
            ).apply {
                eventPublisher.onRetry { event ->
                    logger.warn { "Push API retry #${event.numberOfRetryAttempts}" }
                }
            }

    val pushCircuitBreaker: CircuitBreaker =
        CircuitBreaker
            .of(
                "pushCircuitBreaker",
                CircuitBreakerConfig
                    .custom()
                    .failureRateThreshold(50f)
                    .slowCallRateThreshold(50f)
                    .slowCallDurationThreshold(Duration.ofSeconds(5))
                    .waitDurationInOpenState(Duration.ofSeconds(30))
                    .minimumNumberOfCalls(10)
                    .slidingWindowSize(20)
                    .build(),
            ).apply {
                eventPublisher.onStateTransition { event -> logStateTransition("Push", event) }
            }

    val push: ResiliencePolicy = ResiliencePolicy(pushCircuitBreaker, pushRetry)
}

suspend fun <T> withEmailResilience(
    policy: ResiliencePolicy = ResilienceRegistry.email,
    block: suspend () -> T,
): T = policy.execute(block)

suspend fun <T> withClamAvResilience(
    policy: ResiliencePolicy = ResilienceRegistry.clamAv,
    block: suspend () -> T,
): T = policy.execute(block)

suspend fun <T> withS3Resilience(
    policy: ResiliencePolicy = ResilienceRegistry.s3,
    block: suspend () -> T,
): T = policy.execute(block)

suspend fun <T> withPushResilience(
    policy: ResiliencePolicy = ResilienceRegistry.push,
    block: suspend () -> T,
): T = policy.execute(block)
