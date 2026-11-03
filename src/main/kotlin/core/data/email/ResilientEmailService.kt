/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.core.data.email

import com.mozgobolt.core.domain.email.EmailService
import com.mozgobolt.core.resilience.ResiliencePolicy

class ResilientEmailService(
    private val delegate: EmailService,
    private val resilience: ResiliencePolicy,
) : EmailService {
    override suspend fun sendVerificationEmail(
        to: String,
        token: String,
    ) {
        resilience.execute {
            delegate.sendVerificationEmail(to, token)
        }
    }

    override suspend fun sendPasswordResetEmail(
        to: String,
        token: String,
    ) {
        resilience.execute {
            delegate.sendPasswordResetEmail(to, token)
        }
    }
}
