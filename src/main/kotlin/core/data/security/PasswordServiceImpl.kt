package com.mozgobolt.core.data.security

import com.mozgobolt.core.domain.security.PasswordPolicy
import com.mozgobolt.core.domain.security.PasswordService
import com.password4j.Argon2Function
import com.password4j.Password
import com.password4j.types.Argon2
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

class PasswordServiceImpl(
    private val pepper: String,
) : PasswordService {
    private val argon2 =
        Argon2Function.getInstance(
            ARGON2_MEMORY_KB,
            ARGON2_ITERATIONS,
            ARGON2_PARALLELISM,
            ARGON2_LENGTH,
            Argon2.ID,
        )

    override fun hashPassword(password: String): String {
        require(password.length <= PasswordPolicy.MAX_LENGTH) { "Password too long" }

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
        password.length <= PasswordPolicy.MAX_LENGTH &&
            runCatching {
                Password
                    .check(password, hash)
                    .addPepper(pepper)
                    .with(argon2)
            }.onFailure { error ->
                logger.warn(error) { "Password hash could not be parsed during verification" }
            }.getOrDefault(false)

    companion object {
        private const val ARGON2_MEMORY_KB = 65_536
        private const val ARGON2_ITERATIONS = 3
        private const val ARGON2_PARALLELISM = 2
        private const val ARGON2_LENGTH = 64
    }
}
