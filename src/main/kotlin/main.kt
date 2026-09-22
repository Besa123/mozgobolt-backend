package com.mozgobolt

import io.github.cdimascio.dotenv.dotenv
import io.ktor.server.netty.EngineMain

fun main(args: Array<String>) {
    dotenv {
        ignoreIfMissing = true
    }.entries().forEach { entry ->
        if (System.getenv(entry.key) == null) {
            System.setProperty(entry.key, entry.value)
        }
    }

    EngineMain.main(args)
}
