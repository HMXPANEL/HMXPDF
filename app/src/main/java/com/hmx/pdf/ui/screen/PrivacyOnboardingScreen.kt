package com.hmx.pdf.ui.screen

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hmx.pdf.R
import com.hmx.pdf.data.privacy.PrivacyStorage
import com.hmx.pdf.data.repository.OnboardingManager
import com.hmx.pdf.ui.components.AccentIconTile
import com.hmx.pdf.ui.components.LiquidButton
import com.hmx.pdf.ui.components.LiquidToggle
import com.hmx.pdf.ui.theme.LiquidGlassColors
import com.hmx.pdf.ui.theme.LocalIsDarkMode
import com.kyant.backdrop.backdrops.LayerBackdrop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val PrivacyStepCount = 2

/**
 * Two-step first-run privacy flow, shown after the existing seven-page tour.
 *
 * Nothing here touches the network. Consent is written only when the user leaves the flow with
 * Next or Not now; both are explicit decisions and both are persisted before navigation.
 */
@Composable
fun PrivacyOnboardingScreen(
    backdrop: LayerBackdrop,
    onComplete: (diagnosticReports: Boolean, problemReports: Boolean) -> Unit,
) {
    val context = LocalContext.current
    val isDark = LocalIsDarkMode.current
    val ink = LiquidGlassColors.text(isDark)
    val sub = LiquidGlassColors.secondary(isDark)
    val scope = rememberCoroutineScope()

    var step by rememberSaveable { mutableIntStateOf(0) }
    var diagnosticReports by rememberSaveable { mutableStateOf(false) }
    var problemReports by rememberSaveable { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var saveFailed by remember { mutableStateOf(false) }

    fun finish(diagnostic: Boolean, problem: Boolean) {
        if (saving) return
        saving = true
        saveFailed = false
        scope.launch {
            val saved = withContext(Dispatchers.IO) {
                runCatching {
                    PrivacyStorage.consentStore(context).save(
                        diagnosticReportsEnabled = diagnostic,
                        problemReportsEnabled = problem,
                    )
                    OnboardingManager.setPrivacyOnboardingComplete(context)
                }.isSuccess
            }
            saving = false
            if (saved) onComplete(diagnostic, problem) else saveFailed = true
        }
    }

    // Back only moves between the two privacy steps. On step one it falls through to the system
    // default, so quitting leaves privacy onboarding pending for the next ordinary launch.
    BackHandler(enabled = step > 0) { step = 0 }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 28.dp),
    ) {
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(top = 28.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            StepHeader(step = step, ink = ink, sub = sub)
            if (step == 0) {
                PrivacyOverview(ink = ink, sub = sub)
            } else {
                PrivacyPreferences(
                    backdrop = backdrop,
                    ink = ink,
                    sub = sub,
                    diagnosticReports = diagnosticReports,
                    problemReports = problemReports,
                    onDiagnosticChanged = { diagnosticReports = it },
                    onProblemChanged = { problemReports = it },
                )
            }
            if (saveFailed) {
                BasicText(
                    stringResource(R.string.privacy_save_failed),
                    style = TextStyle(LiquidGlassColors.danger(isDark), 13.sp, lineHeight = 18.sp),
                )
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(bottom = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LiquidButton(
                onClick = { finish(false, false) },
                backdrop = backdrop,
                isInteractive = !saving,
                surfaceColor = if (isDark) Color.White.copy(0.10f) else Color.Black.copy(0.06f),
                modifier = Modifier.weight(1f),
            ) {
                BasicText(
                    stringResource(R.string.privacy_not_now),
                    style = TextStyle(ink, 15.sp, FontWeight.SemiBold, textAlign = TextAlign.Center),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            LiquidButton(
                onClick = {
                    if (step == 0) {
                        step = 1
                    } else {
                        finish(diagnosticReports, problemReports)
                    }
                },
                backdrop = backdrop,
                isInteractive = !saving,
                tint = LiquidGlassColors.Blue,
                modifier = Modifier.weight(1f),
            ) {
                BasicText(
                    stringResource(R.string.privacy_continue),
                    style = TextStyle(Color.White, 15.sp, FontWeight.Bold, textAlign = TextAlign.Center),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun StepHeader(step: Int, ink: Color, sub: Color) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
            repeat(PrivacyStepCount) { index ->
                Box(
                    Modifier
                        .clip(CircleShape)
                        .background(
                            if (index == step) ink.copy(0.85f) else ink.copy(0.22f)
                        )
                        .height(6.dp)
                        .width(if (index == step) 22.dp else 7.dp)
                )
            }
        }
        BasicText(
            stringResource(R.string.privacy_onboarding_step, step + 1, PrivacyStepCount),
            style = TextStyle(sub, 12.sp, FontWeight.Medium),
        )
    }
}

@Composable
private fun PrivacyOverview(ink: Color, sub: Color) {
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        AccentIconTile(Icons.Rounded.Lock, LiquidGlassColors.Blue, size = 64, iconSize = 30)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            BasicText(
                stringResource(R.string.privacy_security_title),
                style = TextStyle(ink, 28.sp, FontWeight.Bold),
                modifier = Modifier.semantics { heading() },
            )
            BasicText(
                stringResource(R.string.privacy_security_subtitle),
                style = TextStyle(sub, 15.sp, lineHeight = 21.sp),
            )
        }
        PrivacyPoint(
            icon = Icons.Rounded.PhoneAndroid,
            accent = LiquidGlassColors.Teal,
            title = stringResource(R.string.privacy_ondevice_title),
            body = stringResource(R.string.privacy_ondevice_body),
            ink = ink,
            sub = sub,
        )
        PrivacyPoint(
            icon = Icons.Rounded.UploadFile,
            accent = LiquidGlassColors.Purple,
            title = stringResource(R.string.privacy_optional_title),
            body = stringResource(R.string.privacy_optional_body),
            ink = ink,
            sub = sub,
        )
        PrivacyPoint(
            icon = Icons.Rounded.CheckCircle,
            accent = LiquidGlassColors.Green,
            title = stringResource(R.string.privacy_control_title),
            body = stringResource(R.string.privacy_control_body),
            ink = ink,
            sub = sub,
        )
        PrivacyPoint(
            icon = Icons.Rounded.Info,
            accent = LiquidGlassColors.Orange,
            title = stringResource(R.string.privacy_retention_title),
            body = stringResource(R.string.privacy_retention_body),
            ink = ink,
            sub = sub,
        )
    }
}

@Composable
private fun PrivacyPreferences(
    backdrop: LayerBackdrop,
    ink: Color,
    sub: Color,
    diagnosticReports: Boolean,
    problemReports: Boolean,
    onDiagnosticChanged: (Boolean) -> Unit,
    onProblemChanged: (Boolean) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        AccentIconTile(Icons.Rounded.BugReport, LiquidGlassColors.Purple, size = 64, iconSize = 30)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            BasicText(
                stringResource(R.string.privacy_preferences_title),
                style = TextStyle(ink, 28.sp, FontWeight.Bold),
                modifier = Modifier.semantics { heading() },
            )
            BasicText(
                stringResource(R.string.privacy_preferences_subtitle),
                style = TextStyle(sub, 15.sp, lineHeight = 21.sp),
            )
        }
        PrivacyPreferenceRow(
            backdrop = backdrop,
            icon = Icons.Rounded.CheckCircle,
            accent = LiquidGlassColors.Teal,
            title = stringResource(R.string.privacy_diagnostic_toggle_title),
            body = stringResource(R.string.privacy_diagnostic_toggle_desc),
            checked = diagnosticReports,
            onCheckedChange = onDiagnosticChanged,
            ink = ink,
            sub = sub,
        )
        PrivacyPreferenceRow(
            backdrop = backdrop,
            icon = Icons.Rounded.BugReport,
            accent = LiquidGlassColors.Orange,
            title = stringResource(R.string.privacy_problem_toggle_title),
            body = stringResource(R.string.privacy_problem_toggle_desc),
            checked = problemReports,
            onCheckedChange = onProblemChanged,
            ink = ink,
            sub = sub,
        )
        BasicText(
            stringResource(R.string.privacy_preferences_note),
            style = TextStyle(sub, 12.sp, lineHeight = 17.sp),
        )
    }
}

@Composable
private fun PrivacyPoint(
    icon: ImageVector,
    accent: Color,
    title: String,
    body: String,
    ink: Color,
    sub: Color,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        AccentIconTile(icon, accent, size = 38, iconSize = 19)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            BasicText(title, style = TextStyle(ink, 16.sp, FontWeight.SemiBold))
            BasicText(body, style = TextStyle(sub, 13.sp, lineHeight = 19.sp))
        }
    }
}

@Composable
private fun PrivacyPreferenceRow(
    backdrop: LayerBackdrop,
    icon: ImageVector,
    accent: Color,
    title: String,
    body: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    ink: Color,
    sub: Color,
) {
    val onLabel = stringResource(R.string.privacy_accessibility_on)
    val offLabel = stringResource(R.string.privacy_accessibility_off)
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AccentIconTile(icon, accent, size = 40, iconSize = 20)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            BasicText(title, style = TextStyle(ink, 16.sp, FontWeight.SemiBold))
            BasicText(body, style = TextStyle(sub, 12.sp, lineHeight = 17.sp))
        }
        LiquidToggle(
            selected = { checked },
            onSelect = onCheckedChange,
            backdrop = backdrop,
            modifier = Modifier
                .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                .semantics {
                    contentDescription = title
                    stateDescription = if (checked) onLabel else offLabel
                },
        )
    }
}
