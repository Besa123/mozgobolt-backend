package com.shelflife.feature.user.routing

object AuthPaths {
    private const val BASE = "/api/v1/auth"
    const val REGISTER = "$BASE/register"
    const val LOGIN = "$BASE/login"
    const val REFRESH = "$BASE/refresh"
    const val VERIFY_EMAIL = "$BASE/verify-email"
    const val LOGOUT = "$BASE/logout"
    const val LOGOUT_ALL = "$BASE/logout-all"
    const val RESEND_VERIFICATION = "$BASE/resend-verification"
    const val PASSWORD_RESET_REQUEST = "$BASE/password-reset/request"
    const val PASSWORD_RESET_VALIDATE = "$BASE/password-reset/validate"
    const val PASSWORD_RESET_CONFIRM = "$BASE/password-reset/confirm"
}
