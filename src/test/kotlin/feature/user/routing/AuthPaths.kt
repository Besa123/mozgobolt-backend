package com.besa.shelflife.feature.user.routing

object AuthPaths {
    private const val BASE = "/api/v1/auth"
    const val REGISTER = "$BASE/register"
    const val LOGIN = "$BASE/login"
    const val REFRESH = "$BASE/refresh"
    const val VERIFY_EMAIL = "$BASE/verify-email"
    const val LOGOUT = "$BASE/logout"
    const val LOGOUT_ALL = "$BASE/logout-all"
    const val RESEND_VERIFICATION = "$BASE/resend-verification"
}
