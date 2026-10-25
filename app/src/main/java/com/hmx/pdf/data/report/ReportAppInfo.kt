package com.hmx.pdf.data.report

import android.os.Build
import com.hmx.pdf.BuildConfig
import java.util.Locale

private val AppLocalePattern = Regex("^[A-Za-z]{2,3}(?:-[A-Za-z0-9]{2,8})*$")

/**
 * Minimal app/build metadata already approved by the report schema. It contains no device model,
 * advertising ID, hardware identifier, account, or document information.
 */
object ReportAppInfo {
    fun fromBuild(): ReportApp {
        val versionName = BuildConfig.VERSION_NAME.trim().take(64).ifBlank { "unknown" }
        val flavor = when (BuildConfig.FLAVOR.lowercase(Locale.US)) {
            "foss" -> BuildFlavor.FOSS
            "fdroid" -> BuildFlavor.FDROID
            else -> BuildFlavor.PLAY
        }
        val locale = runCatching { Locale.getDefault().toLanguageTag() }
            .getOrNull()
            ?.takeIf { it.length in 1..16 && AppLocalePattern.matches(it) }
        return ReportApp(
            versionCode = BuildConfig.VERSION_CODE,
            versionName = versionName,
            flavor = flavor,
            buildType = if (BuildConfig.DEBUG) BuildType.DEBUG else BuildType.RELEASE,
            androidApi = Build.VERSION.SDK_INT.takeIf { it in 1..100 },
            locale = locale,
        )
    }
}
