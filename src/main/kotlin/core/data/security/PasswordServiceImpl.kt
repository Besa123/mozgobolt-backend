package com.besa.shelflife.core.data.security

import com.besa.shelflife.core.domain.security.PasswordService
import com.password4j.Argon2Function
import com.password4j.Password
import com.password4j.types.Argon2

class PasswordServiceImpl(
    private val pepper: String,
) : PasswordService {
    private val argon2 =
        Argon2Function.getInstance(
            65536,
            3,
            2,
            64,
            Argon2.ID,
        )

    override fun hashPassword(password: String): String {
        require(password.length <= MAX_PASSWORD_LENGTH) { "Password too long" }

        return Password
            .hash(password)
            .addRandomSalt()
            .addPepper(pepper)
            .with(argon2)
            .result
    }

    override fun verifyPassword(
        password: String,
        hash: String,
    ): Boolean =
        password.length <= MAX_PASSWORD_LENGTH &&
            Password
                .check(password, hash)
                .addPepper(pepper)
                .with(argon2)

    companion object {
        const val MAX_PASSWORD_LENGTH = 128
    }
}
