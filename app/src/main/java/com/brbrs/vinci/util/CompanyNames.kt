package com.brbrs.vinci.util

import java.util.Locale

// Trailing legal-form words ignored when deciding whether two organization names are the
// same company: "Acme", "Acme B.V." and "ACME Inc." all collapse to one company page.
private val LEGAL_SUFFIXES = setOf(
    "bv", "nv", "bvba", "vof", "cv", "ltd", "limited", "inc", "incorporated", "llc",
    "gmbh", "ag", "sa", "sas", "srl", "spa", "plc", "corp", "corporation", "co",
    "company", "oy", "ab", "as", "aps", "kft",
)

/** Stable key for an organization name, used as the identity of its company page. */
fun companyKey(name: String): String {
    val tokens = name.lowercase(Locale.ROOT)
        .replace(".", "")
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()
        .split(" ")
        .filter { it.isNotEmpty() }
        .toMutableList()
    // Only strip legal-form words from the end, and never strip a name down to nothing
    while (tokens.size > 1 && tokens.last() in LEGAL_SUFFIXES) tokens.removeAt(tokens.lastIndex)
    return tokens.joinToString(" ").ifBlank { name.trim().lowercase(Locale.ROOT) }
}
