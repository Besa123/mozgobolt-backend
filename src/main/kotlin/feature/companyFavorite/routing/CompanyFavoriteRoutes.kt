package com.mozgobolt.feature.companyFavorite.routing

import com.mozgobolt.core.modules.plugin.RequestTimeout
import com.mozgobolt.core.modules.plugin.limitedDelete
import com.mozgobolt.core.modules.plugin.limitedPost
import com.mozgobolt.core.routing.dto.response.ErrorResponse
import com.mozgobolt.core.utility.functions.currentUserIdOrNull
import com.mozgobolt.core.utility.functions.protectedApi
import com.mozgobolt.feature.companyFavorite.domain.CompanyFavoriteService
import com.mozgobolt.feature.companyFavorite.domain.model.CompanyFavoriteError
import com.mozgobolt.feature.companyFavorite.routing.dto.response.toResponseDto
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

private const val INVALID_COMPANY_ID = "INVALID_COMPANY_ID"

/**
 * Company-level favorites only, deliberately — a buyer favoriting a specific vehicle wouldn't
 * make sense (a different vehicle from the same company being nearby is just as relevant to
 * them), a decision made explicitly during design, not an oversight. No role restriction beyond
 * authentication: a vendor favoriting a company isn't harmful, just not the primary use case.
 */
fun Route.companyFavoriteRoutes(companyFavoriteService: CompanyFavoriteService) {
    protectedApi {
        limitedPost("/companies/{id}/favorite", timeout = RequestTimeout.STANDARD) {
            val userId = call.currentUserIdOrNull() ?: return@limitedPost call.respond(HttpStatusCode.Unauthorized)
            val companyId =
                call.parameters["id"]?.toIntOrNull()
                    ?: return@limitedPost call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse(error = INVALID_COMPANY_ID),
                    )

            companyFavoriteService.favoriteCompany(userId, companyId).fold(
                onSuccess = { call.respond(HttpStatusCode.NoContent) },
                onError = { error -> call.respond(error.toHttpStatusCode(), ErrorResponse(error = error.name)) },
            )
        }

        limitedDelete("/companies/{id}/favorite", timeout = RequestTimeout.FAST) {
            val userId = call.currentUserIdOrNull() ?: return@limitedDelete call.respond(HttpStatusCode.Unauthorized)
            val companyId =
                call.parameters["id"]?.toIntOrNull()
                    ?: return@limitedDelete call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse(error = INVALID_COMPANY_ID),
                    )

            companyFavoriteService.unfavoriteCompany(userId, companyId).fold(
                onSuccess = { call.respond(HttpStatusCode.NoContent) },
                onError = { error -> call.respond(error.toHttpStatusCode(), ErrorResponse(error = error.name)) },
            )
        }

        get("/favorites") {
            val userId = call.currentUserIdOrNull() ?: return@get call.respond(HttpStatusCode.Unauthorized)

            call.respond(HttpStatusCode.OK, companyFavoriteService.listFavorites(userId).map { it.toResponseDto() })
        }
    }
}

private fun CompanyFavoriteError.toHttpStatusCode() =
    when (this) {
        CompanyFavoriteError.COMPANY_NOT_FOUND -> HttpStatusCode.NotFound
    }
