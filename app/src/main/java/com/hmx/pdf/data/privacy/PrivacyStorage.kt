package com.hmx.pdf.data.privacy

import android.content.Context
import com.hmx.pdf.data.storage.SharedPreferencesStringKeyValueStore

/** Android wiring for the privacy stores. The preferences file is excluded from backup. */
object PrivacyStorage {
    private fun store(context: Context) = SharedPreferencesStringKeyValueStore(
        context.applicationContext.getSharedPreferences(PRIVACY_PREFS_NAME, Context.MODE_PRIVATE)
    )

    fun consentStore(context: Context): ConsentStore = ConsentStore(store(context))

    fun installIdProvider(context: Context): InstallIdProvider = InstallIdProvider(store(context))
}
