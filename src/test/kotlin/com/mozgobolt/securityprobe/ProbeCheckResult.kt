/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.securityprobe

import com.mozgobolt.core.utility.functions.runSuspendCatching

/** What one probe's body concluded, before it is wrapped into a timestamped-and-categorized [ProbeOutcome]. */
internal sealed interface ProbeCheckResult {
    data object Pass : ProbeCheckResult

    data class Fail(
        val detail: String,
    ) : ProbeCheckResult
}

internal fun pass(): ProbeCheckResult = ProbeCheckResult.Pass

internal fun fail(detail: String): ProbeCheckResult = ProbeCheckResult.Fail(detail)

/**
 * Runs one probe and turns its outcome into a [ProbeOutcome]. [expectation] is written down as the
 * call site's own description of the documented contract being checked — it is fixed before
 * [block] runs, never derived from what [block] finds, so a probe cannot be quietly re-aimed at
 * whatever the app happens to do. An exception from [block] (a connection refusal, a decode
 * failure) is its own [Verdict.ERROR], distinct from a deliberate [Verdict.FAIL]: a probe that
 * cannot run proves nothing about the contract either way.
 */
internal suspend fun probe(
    category: ProbeCategory,
    name: String,
    expectation: String,
    block: suspend () -> ProbeCheckResult,
): ProbeOutcome =
    runSuspendCatching { block() }.fold(
        onSuccess = { result ->
            when (result) {
                is ProbeCheckResult.Pass -> ProbeOutcome(category, name, expectation, Verdict.PASS)
                is ProbeCheckResult.Fail -> ProbeOutcome(category, name, expectation, Verdict.FAIL, result.detail)
            }
        },
        onError = { failure ->
            ProbeOutcome(
                category,
                name,
                expectation,
                Verdict.ERROR,
                "${failure.javaClass.simpleName}: ${failure.message}",
            )
        },
    )
