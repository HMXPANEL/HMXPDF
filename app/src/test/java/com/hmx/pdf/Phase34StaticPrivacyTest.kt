package com.hmx.pdf

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Static regression checks for the Phase 3 privacy gate and explicit-send boundary. These do not
 * replace the Android build; they catch ordering and consent-UI regressions in review.
 */
class Phase34StaticPrivacyTest {

    private val moduleDir: File by lazy {
        val candidates = listOf(File("."), File("app"), File("../app"))
        candidates.firstOrNull { File(it, "src/main/AndroidManifest.xml").isFile }
            ?: error("cannot locate the app module from ${File(".").absolutePath}")
    }

    private fun source(path: String): String = File(moduleDir, path).readText()

    @Test
    fun startDestinationRunsTourThenPrivacyThenWhatsNew() {
        val app = source("src/main/java/com/hmx/pdf/ui/DocsApp.kt")
        val tour = app.indexOf("needsOnboarding -> ROUTE_ONBOARDING")
        val privacy = app.indexOf("needsPrivacyOnboarding -> ROUTE_PRIVACY_ONBOARDING")
        val whatsNew = app.indexOf("showWhatsNew -> com.hmx.pdf.ui.navigation.ROUTE_WHATS_NEW")
        assertTrue("tour start destination missing", tour >= 0)
        assertTrue("privacy start destination missing", privacy > tour)
        assertTrue("What's New must come after privacy", whatsNew > privacy)
    }

    @Test
    fun tourReplayNeverReentersPrivacy() {
        val nav = source("src/main/java/com/hmx/pdf/ui/navigation/DocsNavGraph.kt")
        val onboarding = nav.substringAfter("composable(ROUTE_ONBOARDING)")
            .substringBefore("composable(ROUTE_PRIVACY_ONBOARDING)")
        assertTrue(onboarding.contains("previousBackStackEntry != null"))
        assertTrue(onboarding.contains("popBackStack()"))
        assertTrue(onboarding.contains("hasCompletedPrivacyOnboarding"))
    }

    @Test
    fun settingsPrivacyPageIsInformationalOnly() {
        val settings = source("src/main/java/com/hmx/pdf/ui/screen/SettingsScreen.kt")
        val page = settings.substringAfter("private fun PrivacySecuritySettingsPage(")
            .substringBefore("private fun PrivacyStatusRow(")
        assertFalse("consent toggles must not live in Settings", page.contains("LiquidToggle"))
        assertFalse("Settings must not write consent", page.contains("consentStore(context).save"))
        assertTrue(page.contains("privacy_settings_choice_fixed"))
        assertTrue(page.contains("privacy_settings_retention_body"))
    }

    @Test
    fun privacySettingsEntrySitsBelowOfficeEngine() {
        val settings = source("src/main/java/com/hmx/pdf/ui/screen/SettingsScreen.kt")
        val officeRow = settings.indexOf(
            "SettingsNavRow(Icons.Rounded.Description, LiquidGlassColors.Green, stringResource(R.string.office_engine_title)"
        )
        val privacyRow = settings.indexOf(
            "SettingsNavRow(Icons.Rounded.Lock, LiquidGlassColors.Blue, stringResource(R.string.settings_privacy_security)"
        )
        assertTrue(officeRow >= 0)
        assertTrue(privacyRow > officeRow)
    }

    @Test
    fun firstRunFlowUsesNoNetworkAndPersistsBeforeContinuing() {
        val screen = source("src/main/java/com/hmx/pdf/ui/screen/PrivacyOnboardingScreen.kt")
        assertFalse(screen.contains("HttpURLConnection"))
        assertFalse(screen.contains("FeatureFlagClient"))
        assertFalse(screen.contains("RemoteConfig"))
        assertFalse(screen.contains("ReportSender"))
        assertTrue(screen.contains("consentStore(context).save"))
        assertTrue(screen.contains("setPrivacyOnboardingComplete"))
    }

    @Test
    fun reportsAreExplicitSendsWithoutABackgroundUploader() {
        val screens = source("src/main/java/com/hmx/pdf/ui/screen/ReportScreens.kt")
        val sender = source("src/main/java/com/hmx/pdf/data/report/ReportSender.kt")
        assertTrue(screens.contains("ReportSender.sendNew"))
        assertTrue(screens.contains("ReportSender.retry"))
        assertTrue(sender.contains("consentStore(context).read()"))
        assertFalse(sender.contains("WorkManager"))
        assertFalse(sender.contains("ReportUploadScheduler"))
    }

    @Test
    fun startupRefreshRequiresACompletedPrivacyDecision() {
        val app = source("src/main/java/com/hmx/pdf/ui/DocsApp.kt")
        val refresh = app.substringAfter("One non-blocking config refresh")
            .substringBefore("val openRepo")
        assertTrue(refresh.contains("hasCompletedPrivacyOnboarding(context)"))
        assertTrue(refresh.contains("!needsPrivacyOnboarding"))
    }
}
