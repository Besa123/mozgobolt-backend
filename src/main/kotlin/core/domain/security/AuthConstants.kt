package com.besa.shelflife.core.domain.security

object AuthConstants {
    const val CLAIM_USER_ID = "userId"
    const val CLAIM_TOKEN_TYPE = "type"
    const val TOKEN_TYPE_ACCESS = "access"
    const val TOKEN_TYPE_REFRESH = "refresh"

    const val ENV_JWT_SECRET = "JWT_SECRET"
    const val ENV_JWT_ISSUER = "JWT_ISSUER"
    const val ENV_JWT_AUDIENCE = "JWT_AUDIENCE"

    const val PROTECT_ENDPOINT_JWT = "auth-jwt"
}