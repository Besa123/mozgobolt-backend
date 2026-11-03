/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.core.data.validator

import com.mozgobolt.core.domain.AppResult
import com.mozgobolt.core.domain.security.PasswordBreachChecker
import com.mozgobolt.core.domain.security.PasswordPolicy
import com.mozgobolt.core.domain.validation.PasswordValidationError
import com.mozgobolt.core.domain.validation.PasswordValidator

class StandardPasswordValidator(
    private val passwordBreachChecker: PasswordBreachChecker,
) : PasswordValidator {
    override suspend fun validate(password: String): AppResult<Unit, PasswordValidationError> {
        if (!PasswordPolicy.isValid(password)) return AppResult.Error(PasswordValidationError.INVALID)

        return passwordBreachChecker.isCompromised(password).fold(
            onSuccess = { compromised ->
                if (compromised) {
                    AppResult.Error(PasswordValidationError.INVALID)
                } else {
                    AppResult.Success(Unit)
                }
            },
            onError = { AppResult.Error(PasswordValidationError.BREACH_CHECK_UNAVAILABLE) },
        )
    }
}
