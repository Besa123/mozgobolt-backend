package com.shelflife.core.domain.validation

import java.time.LocalDate
import java.time.format.DateTimeFormatter

fun isValidIsoDate(value: String): Boolean =
    runCatching { LocalDate.parse(value, DateTimeFormatter.ISO_DATE) }.isSuccess
