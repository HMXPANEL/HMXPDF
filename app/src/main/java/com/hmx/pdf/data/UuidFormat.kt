package com.hmx.pdf.data

private val UUID_PATTERN =
    Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

fun isUuid(value: String): Boolean = UUID_PATTERN.matches(value)

/** Lowercase UUID or null. Lowercasing keeps retry/idempotency keys stable across producers. */
fun normalizeUuid(value: String): String? =
    if (isUuid(value)) value.lowercase() else null
