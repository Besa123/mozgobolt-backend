/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.securityprobe

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess

private val LOOPBACK_HOSTS = setOf("127.0.0.1", "localhost", "::1")
private val REPORT_JSON = Json { prettyPrint = true }

private fun requireLoopback(baseUrl: String) {
    require(URI.create(baseUrl).host in LOOPBACK_HOSTS) {
        "This suite registers disposable throwaway accounts and fires rate-limit/auth bursts: it only targets loopback"
    }
}

/**
 * Runs every probe category against a real running instance of the app and reports the result as
 * an OWASP API Security Top 10-shaped checklist. Every probe's expectation was written down before
 * it was run (see each file's own doc comment for the documented contract it checks: the OpenAPI
 * spec, CLAUDE.md's non-negotiable invariants, or the handler's own committed error vocabulary) —
 * this runner only wires those probes to a live app and a disposable account factory, it does not
 * decide what "correct" means.
 */
internal suspend fun runSecurityProbes(baseUrl: String): ProbeReport {
    requireLoopback(baseUrl)

    return newProbeHttpClient().use { http ->
        val api = ProbeApi(http, baseUrl)
        check(api.get("/ready").status == HTTP_OK) { "The app at $baseUrl is not ready" }

        val accounts = ProbeAccountFactory(api)
        val outcomes =
            authenticationProbes(api, http, baseUrl, accounts) +
                authorizationProbes(api, accounts) +
                inputValidationProbes(api, accounts) +
                rateLimitProbes(api, accounts) +
                transportProbes(api, http, baseUrl, accounts) +
                botPatternProbes(api, accounts)
        outcomes.toReport(baseUrl)
    }
}

private const val HTTP_OK = 200

fun main(args: Array<String>) {
    val baseUrl = (args.getOrNull(0) ?: "http://127.0.0.1:18080").trimEnd('/')
    val reportPath = Path.of(args.getOrNull(1) ?: "build/security-probe/security-probe-report.json")

    val report = runBlocking { runSecurityProbes(baseUrl) }

    Files.createDirectories(reportPath.parent)
    val json = REPORT_JSON.encodeToString(report)
    Files.writeString(reportPath, json + "\n")
    println(json)
    println("\n${report.passed}/${report.total} passed, ${report.failed} failed, ${report.errored} errored.")
    println("Report written to $reportPath")
    exitProcess(if (report.allPassed) 0 else 1)
}
