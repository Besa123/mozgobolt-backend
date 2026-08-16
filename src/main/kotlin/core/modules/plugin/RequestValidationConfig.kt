package com.besa.boardShare.core.modules.plugin

import com.besa.boardShare.core.domain.validation.ValidatedRequest
import io.ktor.server.application.*
import io.ktor.server.plugins.requestvalidation.*

fun Application.configureRequestValidation() {
    install(RequestValidation) {
        validate<ValidatedRequest> { dto ->
            val reasons = dto.validate()
            if (reasons.isEmpty()) ValidationResult.Valid
            else ValidationResult.Invalid(reasons)
        }
    }
}
