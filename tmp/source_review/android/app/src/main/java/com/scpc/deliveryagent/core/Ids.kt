package com.scpc.deliveryagent.core

/**
 * Identifier hygiene for state, evidence and receipt IDs.
 *
 * Probe role values are opaque synthetic tokens chosen by the caller, so every
 * derived identifier is normalised instead of interpolated. Evidence and receipt
 * IDs additionally become submission file names and must match
 * `^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$`.
 */
object Ids {

    private const val MAX_SEGMENT = 72
    private const val MAX_FILE_ID = 128

    /** Normalises one opaque token into an identifier segment. */
    fun segment(value: String?): String {
        val cleaned = (value ?: "").replace(Regex("[^A-Za-z0-9._-]"), "_").trim('_')
        if (cleaned.isEmpty()) return "unset"
        val trimmed = if (cleaned.length > MAX_SEGMENT) {
            // Keep the identifier short but still injective for long tokens.
            cleaned.take(MAX_SEGMENT - 9) + "." + Digest.utf8(cleaned).take(8)
        } else {
            cleaned
        }
        return if (trimmed.first().isLetterOrDigit()) trimmed else "x$trimmed"
    }

    /** Builds a dotted state identifier such as `fact.STABLE.scope_keep`. */
    fun state(vararg parts: String): String =
        parts.filter { it.isNotEmpty() }.joinToString(".") { segment(it) }

    /**
     * Builds an identifier that is also used as a submission evidence file name.
     * The result starts with an alphanumeric character and never carries an
     * extension.
     */
    fun file(vararg parts: String): String {
        val joined = state(*parts)
        return if (joined.length <= MAX_FILE_ID) {
            joined
        } else {
            joined.take(MAX_FILE_ID - 9) + "." + Digest.utf8(joined).take(8)
        }
    }
}
