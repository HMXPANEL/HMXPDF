package com.hmx.pdf.data.privacy

import com.hmx.pdf.data.storage.StringKeyValueStore

const val PRIVACY_PREFS_NAME = "clearpdf_privacy"
const val CONSENT_VERSION = 1

private const val KEY_DIAGNOSTIC = "diagnostic_reports_enabled"
private const val KEY_PROBLEM = "problem_reports_enabled"
private const val KEY_CONSENT_VERSION = "consent_version"
private const val KEY_CONSENT_UPDATED_AT = "consent_updated_at"
const val INSTALL_ID_KEY = "install_id"

/**
 * Local, independent consent. Both values are OFF unless a complete, current decision exists.
 * Missing or malformed data fails closed; it never upgrades an old value to ON.
 */
data class ConsentState(
    val diagnosticReportsEnabled: Boolean = false,
    val problemReportsEnabled: Boolean = false,
    val consentVersion: Int? = null,
    val updatedAtMillis: Long? = null,
) {
    val hasDecision: Boolean get() = consentVersion == CONSENT_VERSION

    companion object {
        val OFF = ConsentState()
    }
}

object ConsentCodec {
    fun decode(
        version: String?,
        diagnostic: String?,
        problem: String?,
        updatedAt: String?,
    ): ConsentState {
        if (version != CONSENT_VERSION.toString()) return ConsentState.OFF

        val diagnosticValue = when (diagnostic) {
            "true" -> true
            "false" -> false
            else -> return ConsentState.OFF
        }
        val problemValue = when (problem) {
            "true" -> true
            "false" -> false
            else -> return ConsentState.OFF
        }
        val updated = updatedAt?.toLongOrNull()?.takeIf { it > 0 } ?: return ConsentState.OFF

        return ConsentState(
            diagnosticReportsEnabled = diagnosticValue,
            problemReportsEnabled = problemValue,
            consentVersion = CONSENT_VERSION,
            updatedAtMillis = updated,
        )
    }
}

class ConsentStore(
    private val store: StringKeyValueStore,
) {
    fun read(): ConsentState = runCatching {
        ConsentCodec.decode(
            version = store.getString(KEY_CONSENT_VERSION),
            diagnostic = store.getString(KEY_DIAGNOSTIC),
            problem = store.getString(KEY_PROBLEM),
            updatedAt = store.getString(KEY_CONSENT_UPDATED_AT),
        )
    }.getOrDefault(ConsentState.OFF)

    /**
     * Records an explicit decision. Saving both OFF is meaningful: it records that the user
     * declined, so later launches do not treat the state as merely missing.
     */
    fun save(
        diagnosticReportsEnabled: Boolean,
        problemReportsEnabled: Boolean,
        nowMillis: Long = System.currentTimeMillis(),
    ) {
        require(nowMillis > 0) { "invalid consent timestamp" }
        // Drop the version first so a crash during the remaining writes reads as OFF instead
        // of combining a new toggle with values from an older decision.
        store.remove(KEY_CONSENT_VERSION)
        store.putString(KEY_DIAGNOSTIC, diagnosticReportsEnabled.toString())
        store.putString(KEY_PROBLEM, problemReportsEnabled.toString())
        store.putString(KEY_CONSENT_UPDATED_AT, nowMillis.toString())
        // Written last: a partial write leaves no version and reads OFF.
        store.putString(KEY_CONSENT_VERSION, CONSENT_VERSION.toString())
    }

    fun clear() {
        store.remove(KEY_DIAGNOSTIC)
        store.remove(KEY_PROBLEM)
        store.remove(KEY_CONSENT_UPDATED_AT)
        store.remove(KEY_CONSENT_VERSION)
    }
}
