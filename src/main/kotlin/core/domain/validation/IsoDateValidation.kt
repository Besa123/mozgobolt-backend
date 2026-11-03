/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.mozgobolt.core.domain.validation

import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** Reports whether [value] is a valid ISO-8601 calendar date. */
fun isValidIsoDate(value: String): Boolean =
    runCatching { LocalDate.parse(value, DateTimeFormatter.ISO_LOCAL_DATE) }.isSuccess
