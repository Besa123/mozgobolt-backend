package com.mozgobolt.core.domain.security

object AuthConstants {
    const val CLAIM_USER_ID = "userId"
    const val CLAIM_USER_ROLE = "role"
    const val CLAIM_TOKEN_TYPE = "type"
    const val TOKEN_TYPE_ACCESS = "access"
    const val TOKEN_TYPE_REFRESH = "refresh"

    const val PROTECT_ENDPOINT_JWT = "auth-jwt"
}
