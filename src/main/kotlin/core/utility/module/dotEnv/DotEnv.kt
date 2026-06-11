package com.besa.boardShare.core.utility.module.dotEnv

import io.github.cdimascio.dotenv.dotenv

object DotEnv {
    val INSTANCE = dotenv()
}