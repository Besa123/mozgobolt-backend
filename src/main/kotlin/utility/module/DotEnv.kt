package com.besa.boardShare.utility.module

import io.github.cdimascio.dotenv.dotenv

object DotEnv {
    val INSTANCE = dotenv()
}