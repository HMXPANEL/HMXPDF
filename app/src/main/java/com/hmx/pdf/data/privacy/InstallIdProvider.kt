package com.hmx.pdf.data.privacy

import com.hmx.pdf.data.normalizeUuid
import com.hmx.pdf.data.storage.StringKeyValueStore
import java.util.UUID

object InstallIdGenerator {
    /** UUID.randomUUID() is a version-4 UUID backed by SecureRandom. */
    fun newId(): String = UUID.randomUUID().toString()
}

/**
 * Report-only install identity. The config client has no reference to this class and no
 * method here accepts or returns feature flags. The value may reset on uninstall/reinstall.
 */
class InstallIdProvider(
    private val store: StringKeyValueStore,
    private val generate: () -> String = InstallIdGenerator::newId,
) {
    fun getOrCreate(): String {
        peek()?.let { return it }
        val generated = normalizeUuid(generate()) ?: error("install ID generator returned a non-UUID")
        store.putString(INSTALL_ID_KEY, generated)
        return generated
    }

    /** Existing valid ID without creating one; used only for an explicit report submission. */
    fun peek(): String? = runCatching {
        store.getString(INSTALL_ID_KEY)?.let(::normalizeUuid)
    }.getOrNull()
}
