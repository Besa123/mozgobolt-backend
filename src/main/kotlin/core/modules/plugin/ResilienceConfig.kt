package com.shelflife.core.modules.plugin

import com.shelflife.core.data.email.TransientEmailDeliveryException
import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig
import io.github.resilience4j.core.IntervalFunction
import io.github.resilience4j.kotlin.circuitbreaker.executeSuspendFunction
import io.github.resilience4j.kotlin.retry.executeSuspendFunction
import io.github.resilience4j.retry.Retry
import io.github.resilience4j.retry.RetryConfig
import java.time.Duration

private val logger = KotlinLogging.logger {}

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
                eventPublisher.onStateTransition { event ->
                    logger.warn { "Email circuit-breaker: ${event.stateTransition}" }
                }
            }
}

suspend fun <T> withEmailResilience(block: suspend () -> T): T =
    ResilienceRegistry.emailCircuitBreaker.executeSuspendFunction {
        ResilienceRegistry.emailRetry.executeSuspendFunction {
            block()
        }
    }
