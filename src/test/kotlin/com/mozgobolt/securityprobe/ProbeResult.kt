/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.securityprobe

import kotlinx.serialization.Serializable

/**
 * One checkable expectation about the running app's public contract — an authorization rule, an
 * input-validation guarantee, a rate limit, a documented behavior. [category] groups checks the
 * same way OWASP's API Security Top 10 does, so a report reads as a checklist against a published
 * standard, not an arbitrary pile of assertions.
 */
internal enum class ProbeCategory {
    BROKEN_OBJECT_LEVEL_AUTHORIZATION,
    BROKEN_AUTHENTICATION,
    BROKEN_OBJECT_PROPERTY_LEVEL_AUTHORIZATION,
    UNRESTRICTED_RESOURCE_CONSUMPTION,
    BROKEN_FUNCTION_LEVEL_AUTHORIZATION,
    SERVER_SIDE_REQUEST_FORGERY,
    SECURITY_MISCONFIGURATION,
    INJECTION,
    IMPROPER_INVENTORY_MANAGEMENT,
}

/** The outcome of one probe: [PASS] means the documented contract held, [FAIL] means it did not. */
internal enum class Verdict { PASS, FAIL, ERROR }

@Serializable
internal data class ProbeFinding(
    val category: String,
    val name: String,
    /** The contract being checked, stated before the probe ran — never adjusted after seeing the result. */
    val expectation: String,
    val verdict: String,
    /** What actually happened, present only for a [Verdict.FAIL] or [Verdict.ERROR]. */
    val detail: String? = null,
)

internal data class ProbeOutcome(
    val category: ProbeCategory,
    val name: String,
    val expectation: String,
    val verdict: Verdict,
    val detail: String? = null,
)

@Serializable
internal data class ProbeReport(
    val baseUrl: String,
    val total: Int,
    val passed: Int,
    val failed: Int,
    val errored: Int,
    val findings: List<ProbeFinding>,
) {
    val allPassed: Boolean get() = failed == 0 && errored == 0
}

internal fun List<ProbeOutcome>.toReport(baseUrl: String): ProbeReport =
    ProbeReport(
        baseUrl = baseUrl,
        total = size,
        passed = count { it.verdict == Verdict.PASS },
        failed = count { it.verdict == Verdict.FAIL },
        errored = count { it.verdict == Verdict.ERROR },
        findings =
            sortedBy { it.verdict == Verdict.PASS }.map {
                ProbeFinding(it.category.name, it.name, it.expectation, it.verdict.name, it.detail)
            },
    )
