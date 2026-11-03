/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.core.resilience

/**
 * Per-application policies for outbound dependencies.
 *
 * Create this once when wiring the application so stateful breakers and bulkheads are shared by
 * that application's services, while separate application instances keep independent state.
 */
data class ExternalServiceResiliencePolicies(
    /** Resilience policy shared by outbound email delivery. */
    val email: ResiliencePolicy,
    /** Resilience policy shared by password-breach checks. */
    val passwordBreach: ResiliencePolicy,
    /** Resilience policy shared by malware scanning. */
    val clamAv: ResiliencePolicy,
    /** Resilience policy shared by object storage operations. */
    val s3: ResiliencePolicy,
    /** Resilience policy shared by push delivery. */
    val push: ResiliencePolicy,
) {
    /** Factory methods for the application's default outbound-service policies. */
    companion object {
        /** Creates one default policy for each configured outbound dependency. */
        fun create(): ExternalServiceResiliencePolicies =
            ExternalServiceResiliencePolicies(
                email = createEmailResiliencePolicy(),
                passwordBreach = createPasswordBreachResiliencePolicy(),
                clamAv = createClamAvResiliencePolicy(),
                s3 = createS3ResiliencePolicy(),
                push = createPushResiliencePolicy(),
            )
    }
}
