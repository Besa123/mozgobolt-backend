/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.feature.company.routing

import com.mozgobolt.core.data.idempotency.IdempotencyStore
import com.mozgobolt.core.data.idempotency.idempotent
import com.mozgobolt.core.data.idempotency.idempotentResult
import com.mozgobolt.core.modules.plugin.BodyLimit
import com.mozgobolt.core.modules.plugin.RequestTimeout
import com.mozgobolt.core.modules.plugin.limitedDelete
import com.mozgobolt.core.modules.plugin.limitedPost
import com.mozgobolt.core.modules.plugin.validatedPatch
import com.mozgobolt.core.modules.plugin.validatedPost
import com.mozgobolt.core.routing.dto.response.ErrorResponse
import com.mozgobolt.core.utility.functions.currentUserIdOrNull
import com.mozgobolt.core.utility.functions.protectedApi
import com.mozgobolt.feature.company.domain.CompanyService
import com.mozgobolt.feature.company.domain.model.CompanyError
import com.mozgobolt.feature.company.routing.dto.request.CreateCompanyRequestDto
import com.mozgobolt.feature.company.routing.dto.request.JoinCompanyRequestDto
import com.mozgobolt.feature.company.routing.dto.request.RenameCompanyRequestDto
import com.mozgobolt.feature.company.routing.dto.response.InviteCodeResponseDto
import com.mozgobolt.feature.company.routing.dto.response.toMemberResponseDto
import com.mozgobolt.feature.company.routing.dto.response.toMyCompanyResponseDto
import com.mozgobolt.feature.company.routing.dto.response.toResponseDto
import com.mozgobolt.feature.user.domain.model.UserRole
import com.mozgobolt.feature.user.routing.requireRole
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.get
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private const val INVALID_COMPANY_ID = "INVALID_COMPANY_ID"
private const val INVALID_USER_ID = "INVALID_USER_ID"
private const val SELF_MEMBER_PATH_SEGMENT = "me"

fun Route.companyRoutes(
    companyService: CompanyService,
    idempotencyStore: IdempotencyStore,
) {
    protectedApi {
        registerCompanyCrudRoutes(companyService, idempotencyStore)
        registerCompanyMembershipRoutes(companyService)
    }
}

private fun Route.registerCompanyCrudRoutes(
    companyService: CompanyService,
    idempotencyStore: IdempotencyStore,
) {
    registerCompanyReadRoutes(companyService)
    registerCompanyCreateRoute(companyService, idempotencyStore)
    registerCompanyJoinRoute(companyService, idempotencyStore)
    registerCompanyRenameRoute(companyService)
    registerInviteCodeRegenerationRoute(companyService, idempotencyStore)
    registerCompanyDeleteRoute(companyService)
}

private fun Route.registerCompanyReadRoutes(companyService: CompanyService) {
    get("/companies") {
        if (!requireRole(UserRole.VENDOR)) return@get
        val userId = call.currentUserIdOrNull() ?: return@get call.respond(HttpStatusCode.Unauthorized)

        companyService.listCompaniesForUser(userId).fold(
            onSuccess = { companies ->
                call.respond(HttpStatusCode.OK, companies.map { it.toMyCompanyResponseDto() })
            },
            onError = { error -> call.respond(error.toHttpStatusCode(), ErrorResponse(error = error.name)) },
        )
    }

    get("/companies/{id}/invite-code") {
        if (!requireRole(UserRole.VENDOR)) return@get
        val adminUserId = call.currentUserIdOrNull() ?: return@get call.respond(HttpStatusCode.Unauthorized)
        val companyId =
            call.parameters["id"]?.toIntOrNull()
                ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse(error = INVALID_COMPANY_ID))

        companyService.getCurrentInviteCode(adminUserId, companyId).fold(
            onSuccess = { inviteCode ->
                call.respond(HttpStatusCode.OK, InviteCodeResponseDto(inviteCode))
            },
            onError = { error -> call.respond(error.toHttpStatusCode(), ErrorResponse(error = error.name)) },
        )
    }
}

private fun Route.registerCompanyCreateRoute(
    companyService: CompanyService,
    idempotencyStore: IdempotencyStore,
) {
    validatedPost<CreateCompanyRequestDto>(
        "/companies",
        BodyLimit.TINY,
        RequestTimeout.STANDARD,
    ) { request ->
        if (!requireRole(UserRole.VENDOR)) return@validatedPost
        val userId = call.currentUserIdOrNull() ?: return@validatedPost call.respond(HttpStatusCode.Unauthorized)

        // A network retry re-sending the same "create my company" request must not create a
        // second company — same convention as /auth/register.
        idempotent(idempotencyStore, requestFingerprint = "$userId:${Json.encodeToString(request)}") {
            companyService.createCompany(creatorUserId = userId, name = request.name).fold(
                onSuccess = { company -> idempotentResult(HttpStatusCode.Created, company.toResponseDto()) },
                onError = { error -> idempotentResult(error.toHttpStatusCode(), ErrorResponse(error = error.name)) },
            )
        }
    }
}

@Suppress("SqlInjection")
private fun Route.registerCompanyJoinRoute(
    companyService: CompanyService,
    idempotencyStore: IdempotencyStore,
) {
    validatedPost<JoinCompanyRequestDto>("/companies/join", BodyLimit.TINY, RequestTimeout.STANDARD) { request ->
        if (!requireRole(UserRole.VENDOR)) return@validatedPost
        val userId = call.currentUserIdOrNull() ?: return@validatedPost call.respond(HttpStatusCode.Unauthorized)

        // This is an idempotency namespace, not an SQL query.
        idempotent(idempotencyStore, requestFingerprint = "join-company:$userId:${Json.encodeToString(request)}") {
            companyService.joinCompany(userId = userId, inviteCode = request.inviteCode).fold(
                onSuccess = { company -> idempotentResult(HttpStatusCode.OK, company.toMemberResponseDto()) },
                onError = { error -> idempotentResult(error.toHttpStatusCode(), ErrorResponse(error = error.name)) },
            )
        }
    }
}

private fun Route.registerCompanyRenameRoute(companyService: CompanyService) {
    validatedPatch<RenameCompanyRequestDto>("/companies/{id}", BodyLimit.TINY, RequestTimeout.STANDARD) { request ->
        if (!requireRole(UserRole.VENDOR)) return@validatedPatch
        val userId = call.currentUserIdOrNull() ?: return@validatedPatch call.respond(HttpStatusCode.Unauthorized)
        val companyId =
            call.parameters["id"]?.toIntOrNull()
                ?: return@validatedPatch call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse(error = INVALID_COMPANY_ID),
                )

        companyService.renameCompany(adminUserId = userId, companyId = companyId, newName = request.name).fold(
            onSuccess = { company -> call.respond(HttpStatusCode.OK, company.toResponseDto()) },
            onError = { error -> call.respond(error.toHttpStatusCode(), ErrorResponse(error = error.name)) },
        )
    }
}

private fun Route.registerInviteCodeRegenerationRoute(
    companyService: CompanyService,
    idempotencyStore: IdempotencyStore,
) {
    limitedPost("/companies/{id}/invite-code/regenerate", timeout = RequestTimeout.STANDARD) {
        if (!requireRole(UserRole.VENDOR)) return@limitedPost
        val userId = call.currentUserIdOrNull() ?: return@limitedPost call.respond(HttpStatusCode.Unauthorized)
        val companyId =
            call.parameters["id"]?.toIntOrNull()
                ?: return@limitedPost call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse(error = INVALID_COMPANY_ID),
                )

        // A network retry re-sending the same "regenerate my invite code" request must replay the
        // one new code already generated, not silently mint a second one the caller never saw.
        idempotent(idempotencyStore, requestFingerprint = "$userId:$companyId:regenerate-invite-code") {
            companyService.regenerateInviteCode(adminUserId = userId, companyId = companyId).fold(
                onSuccess = { company -> idempotentResult(HttpStatusCode.OK, company.toResponseDto()) },
                onError = { error -> idempotentResult(error.toHttpStatusCode(), ErrorResponse(error = error.name)) },
            )
        }
    }
}

private fun Route.registerCompanyDeleteRoute(companyService: CompanyService) {
    limitedDelete("/companies/{id}", timeout = RequestTimeout.STANDARD) {
        if (!requireRole(UserRole.VENDOR)) return@limitedDelete
        val userId = call.currentUserIdOrNull() ?: return@limitedDelete call.respond(HttpStatusCode.Unauthorized)
        val companyId =
            call.parameters["id"]?.toIntOrNull()
                ?: return@limitedDelete call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse(error = INVALID_COMPANY_ID),
                )

        companyService.deleteCompany(adminUserId = userId, companyId = companyId).fold(
            onSuccess = { call.respond(HttpStatusCode.NoContent) },
            onError = { error -> call.respond(error.toHttpStatusCode(), ErrorResponse(error = error.name)) },
        )
    }
}

private fun Route.registerCompanyMembershipRoutes(companyService: CompanyService) {
    get("/companies/{id}/members") {
        if (!requireRole(UserRole.VENDOR)) return@get
        val userId = call.currentUserIdOrNull() ?: return@get call.respond(HttpStatusCode.Unauthorized)
        val companyId =
            call.parameters["id"]?.toIntOrNull()
                ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse(error = INVALID_COMPANY_ID))

        companyService.listMembers(userId = userId, companyId = companyId).fold(
            onSuccess = { members -> call.respond(HttpStatusCode.OK, members.map { it.toResponseDto() }) },
            onError = { error -> call.respond(error.toHttpStatusCode(), ErrorResponse(error = error.name)) },
        )
    }

    limitedPost("/companies/{id}/members/{userId}/promote", timeout = RequestTimeout.STANDARD) {
        handleRoleChange(companyService, RoleChange.PROMOTE)
    }

    limitedPost("/companies/{id}/members/{userId}/demote", timeout = RequestTimeout.STANDARD) {
        handleRoleChange(companyService, RoleChange.DEMOTE)
    }

    limitedDelete("/companies/{id}/members/{userId}", timeout = RequestTimeout.STANDARD) {
        if (!requireRole(UserRole.VENDOR)) return@limitedDelete
        val adminUserId = call.currentUserIdOrNull() ?: return@limitedDelete call.respond(HttpStatusCode.Unauthorized)
        val companyId =
            call.parameters["id"]?.toIntOrNull()
                ?: return@limitedDelete call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse(error = INVALID_COMPANY_ID),
                )
        val targetUserId =
            call.parameters["userId"]?.toIntOrNull()
                ?: return@limitedDelete call.respond(HttpStatusCode.BadRequest, ErrorResponse(error = INVALID_USER_ID))

        companyService.removeMember(adminUserId = adminUserId, companyId = companyId, targetUserId = targetUserId).fold(
            onSuccess = { call.respond(HttpStatusCode.NoContent) },
            onError = { error -> call.respond(error.toHttpStatusCode(), ErrorResponse(error = error.name)) },
        )
    }

    limitedDelete("/companies/{id}/members/$SELF_MEMBER_PATH_SEGMENT", timeout = RequestTimeout.STANDARD) {
        if (!requireRole(UserRole.VENDOR)) return@limitedDelete
        val userId = call.currentUserIdOrNull() ?: return@limitedDelete call.respond(HttpStatusCode.Unauthorized)
        val companyId =
            call.parameters["id"]?.toIntOrNull()
                ?: return@limitedDelete call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse(error = INVALID_COMPANY_ID),
                )

        companyService.leaveCompany(userId = userId, companyId = companyId).fold(
            onSuccess = { call.respond(HttpStatusCode.NoContent) },
            onError = { error -> call.respond(error.toHttpStatusCode(), ErrorResponse(error = error.name)) },
        )
    }
}

private enum class RoleChange { PROMOTE, DEMOTE }

private suspend fun RoutingContext.handleRoleChange(
    companyService: CompanyService,
    change: RoleChange,
) {
    if (!requireRole(UserRole.VENDOR)) return
    val adminUserId = call.currentUserIdOrNull() ?: return call.respond(HttpStatusCode.Unauthorized)
    val companyId =
        call.parameters["id"]?.toIntOrNull()
            ?: return call.respond(HttpStatusCode.BadRequest, ErrorResponse(error = INVALID_COMPANY_ID))
    val targetUserId =
        call.parameters["userId"]?.toIntOrNull()
            ?: return call.respond(HttpStatusCode.BadRequest, ErrorResponse(error = INVALID_USER_ID))

    val result =
        when (change) {
            RoleChange.PROMOTE -> companyService.promoteMember(adminUserId, companyId, targetUserId)
            RoleChange.DEMOTE -> companyService.demoteMember(adminUserId, companyId, targetUserId)
        }
    result.fold(
        onSuccess = { call.respond(HttpStatusCode.OK) },
        onError = { error -> call.respond(error.toHttpStatusCode(), ErrorResponse(error = error.name)) },
    )
}

private fun CompanyError.toHttpStatusCode() =
    when (this) {
        CompanyError.COMPANY_NOT_FOUND -> HttpStatusCode.NotFound
        CompanyError.INVALID_INVITE_CODE -> HttpStatusCode.BadRequest
        CompanyError.ALREADY_MEMBER -> HttpStatusCode.Conflict
        CompanyError.NOT_A_MEMBER -> HttpStatusCode.Forbidden
        CompanyError.NOT_ADMIN -> HttpStatusCode.Forbidden
        CompanyError.TARGET_NOT_A_MEMBER -> HttpStatusCode.NotFound
        CompanyError.LAST_ADMIN -> HttpStatusCode.Conflict
    }
