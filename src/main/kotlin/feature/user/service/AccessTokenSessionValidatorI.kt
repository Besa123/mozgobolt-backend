/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.feature.user.service

import com.mozgobolt.core.database.TransactionalRunner
import com.mozgobolt.core.domain.security.AccessTokenSessionValidator
import com.mozgobolt.feature.user.domain.UserRepository
import java.time.Instant

class AccessTokenSessionValidatorI(
    private val userRepository: UserRepository,
    private val tx: TransactionalRunner,
) : AccessTokenSessionValidator {
    override suspend fun isActive(
        userId: Int,
        familyId: String,
    ): Boolean =
        tx.transactional {
            userRepository.isTokenFamilyActive(userId, familyId, Instant.now())
        }
}
