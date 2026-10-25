package com.hmx.pdf.data.storage

import android.content.SharedPreferences

/**
 * Small string-only key/value abstraction so privacy, cache, and install-ID logic can be
 * tested on the JVM with an in-memory map instead of Android's SharedPreferences.
 */
interface StringKeyValueStore {
    fun getString(key: String): String?
    fun putString(key: String, value: String)
    fun remove(key: String)
}

class SharedPreferencesStringKeyValueStore(
    private val prefs: SharedPreferences,
) : StringKeyValueStore {
    override fun getString(key: String): String? =
        runCatching { prefs.getString(key, null) }.getOrNull()

    override fun putString(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    override fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }
}
