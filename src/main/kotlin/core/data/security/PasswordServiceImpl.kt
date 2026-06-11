package com.besa.boardShare.core.data.security

import com.besa.boardShare.core.domain.security.PasswordService
import com.password4j.Password

class PasswordServiceImpl(
    private val pepper: String,
) : PasswordService {
    override fun hashPassword(password: String): String {
        return Password.hash(password)
            .addRandomSalt()
            .addPepper(pepper)
            .withArgon2()
            .result
    }

    override fun verifyPassword(
        password: String,
        hash: String,
    ): Boolean {
        return Password.check(password, hash)
            .addPepper(pepper)
            .withArgon2()

    }
}