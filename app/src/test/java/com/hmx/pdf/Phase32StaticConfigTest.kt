package com.hmx.pdf

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Static checks for the Phase 3.2 manifest/backup/network boundary. These do not replace an
 * Android build or a device restore test; they catch schema and permission regressions here.
 */
class Phase32StaticConfigTest {

    private val moduleDir: File by lazy {
        val candidates = listOf(File("."), File("app"), File("../app"))
        candidates.firstOrNull { File(it, "src/main/AndroidManifest.xml").isFile }
            ?: error("cannot locate the app module from ${File(".").absolutePath}")
    }

    private fun source(path: String): String = File(moduleDir, path).readText()

    @Test
    fun manifestDeclaresInternetAndHttpsOnly() {
        val manifest = source("src/main/AndroidManifest.xml")
        assertTrue(manifest.contains("""<uses-permission android:name="android.permission.INTERNET" />"""))
        assertTrue(manifest.contains("""android:usesCleartextTraffic="false""""))
        assertFalse(manifest.contains("""android:usesCleartextTraffic="true""""))
    }

    @Test
    fun manifestUsesTheCorrectBackupSchemas() {
        val manifest = source("src/main/AndroidManifest.xml")
        assertTrue(manifest.contains("""android:dataExtractionRules="@xml/data_extraction_rules""""))
        assertTrue(manifest.contains("""android:fullBackupContent="@xml/backup_rules""""))
        assertTrue(manifest.contains("""android:allowBackup="true""""))
    }

    @Test
    fun legacyBackupRulesExcludePrivatePhase32State() {
        val rules = source("src/main/res/xml/backup_rules.xml")
        assertTrue(rules.contains("""<full-backup-content>"""))
        assertTrue(rules.contains("""<exclude domain="sharedpref" path="clearpdf_privacy.xml" />"""))
        assertTrue(rules.contains("""<exclude domain="sharedpref" path="clearpdf_flags.xml" />"""))
        assertTrue(rules.contains("""<exclude domain="file" path="save_recovery/" />"""))
        assertFalse("cache is not a valid full-backup-content domain", rules.contains("""domain="cache""""))
    }

    @Test
    fun dataExtractionRulesCoverCloudBackupAndDeviceTransfer() {
        val rules = source("src/main/res/xml/data_extraction_rules.xml")
        assertTrue(rules.contains("<data-extraction-rules>"))
        assertTrue(rules.contains("<cloud-backup>"))
        assertTrue(rules.contains("<device-transfer>"))
        assertTrue(rules.contains("""<exclude domain="sharedpref" path="clearpdf_privacy.xml" />"""))
        assertTrue(rules.contains("""<exclude domain="sharedpref" path="clearpdf_flags.xml" />"""))
        assertFalse(rules.contains("""domain="cache""""))
    }

    @Test
    fun buildConfigIsEnabledWithEmptySafeEndpointDefaults() {
        val gradle = source("build.gradle.kts")
        assertTrue(gradle.contains("buildConfig = true"))
        assertTrue(gradle.contains("""buildConfigField("String", "CONFIG_ENDPOINT""""))
        assertTrue(gradle.contains("""buildConfigField("String", "REPORT_ENDPOINT""""))
        assertTrue(gradle.contains("""buildConfigField("String", "SUPABASE_PUBLISHABLE_KEY""""))
        assertFalse(gradle.contains("SERVICE_ROLE", ignoreCase = true))
    }

    @Test
    fun remoteConfigHasNoInstallIdentityDependency() {
        val remoteDir = File(moduleDir, "src/main/java/com/hmx/pdf/data/remote")
        val text = remoteDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .joinToString("\n") { it.readText() }
        assertFalse(text.contains("InstallIdProvider"))
        assertFalse(text.contains("install_id"))
        assertTrue(text.contains("app_version_code"))
    }

    @Test
    fun reportQueueUsesNoBackupStorage() {
        val factory = source("src/main/java/com/hmx/pdf/data/report/ReportQueueFactory.kt")
        assertTrue(factory.contains("noBackupFilesDir"))
        assertTrue(factory.contains("ReportQueueStore"))
    }
}
