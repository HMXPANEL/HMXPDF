package com.hmx.pdf.data.remote

import android.content.Context
import com.hmx.pdf.BuildConfig
import com.hmx.pdf.data.storage.SharedPreferencesStringKeyValueStore

const val FLAG_PREFS_NAME = "clearpdf_flags"

/**
 * Android wiring for the config client. Constructing it performs no network request; a later
 * approved phase must call [FeatureFlagClient.refresh] from a background coroutine after the
 * privacy flow. Empty BuildConfig endpoints make refresh a no-op with every flag OFF.
 */
object RemoteConfig {
    fun create(context: Context, appVersionCode: Int): FeatureFlagClient {
        val store = SharedPreferencesStringKeyValueStore(
            context.applicationContext.getSharedPreferences(FLAG_PREFS_NAME, Context.MODE_PRIVATE)
        )
        return FeatureFlagClient(
            configEndpoint = BuildConfig.CONFIG_ENDPOINT,
            publishableKey = BuildConfig.SUPABASE_PUBLISHABLE_KEY,
            appVersionCode = appVersionCode,
            cache = FeatureFlagCache(store),
            transport = HttpUrlConfigTransport(),
        )
    }
}
