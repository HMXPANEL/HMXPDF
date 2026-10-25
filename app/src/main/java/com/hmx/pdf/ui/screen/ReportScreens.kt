package com.hmx.pdf.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hmx.pdf.R
import com.hmx.pdf.data.privacy.ConsentState
import com.hmx.pdf.data.privacy.PrivacyStorage
import com.hmx.pdf.data.report.ProblemCategory
import com.hmx.pdf.data.report.ReportAppInfo
import com.hmx.pdf.data.report.ReportEnvelope
import com.hmx.pdf.data.report.ReportKind
import com.hmx.pdf.data.report.ReportPayload
import com.hmx.pdf.data.report.ReportPayloadFactory
import com.hmx.pdf.data.report.ReportQueueFactory
import com.hmx.pdf.data.report.ReportSanitizer
import com.hmx.pdf.data.report.ReportSelfChecks
import com.hmx.pdf.data.report.ReportSender
import com.hmx.pdf.data.report.ReportState
import com.hmx.pdf.data.report.ReportSubmitOutcome
import com.hmx.pdf.data.report.ReportSubmitResult
import com.hmx.pdf.data.report.ReportTimestamps
import com.hmx.pdf.data.report.SelfCheckKey
import com.hmx.pdf.data.report.SelfCheckValue
import com.hmx.pdf.ui.components.GlassChoiceChip
import com.hmx.pdf.ui.components.GlassDialog
import com.hmx.pdf.ui.components.GlassDialogAction
import com.hmx.pdf.ui.components.GlassSectionHeader
import com.hmx.pdf.ui.components.LiquidGlassErrorCard
import com.hmx.pdf.ui.components.ToolPrimaryButton
import com.hmx.pdf.ui.components.ToolScaffold
import com.hmx.pdf.ui.components.liquidGlassPanel
import com.hmx.pdf.ui.theme.LiquidGlassColors
import com.hmx.pdf.ui.theme.LocalIsDarkMode
import com.hmx.pdf.ui.utils.rememberUISensor
import com.kyant.backdrop.backdrops.LayerBackdrop
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Diagnostic report: run the four approved on-device self-checks, show every field that will be
 * sent, and submit only after an explicit Send confirmation. Nothing is scheduled or retried in the
 * background; Retry repeats the same payload and therefore the same client report ID.
 */
@Composable
fun DiagnosticReportScreen(
    backdrop: LayerBackdrop,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val isDark = LocalIsDarkMode.current
    val ink = LiquidGlassColors.text(isDark)
    val sub = LiquidGlassColors.secondary(isDark)
    val uiSensor = rememberUISensor()
    val scope = rememberCoroutineScope()

    var consent by remember { mutableStateOf<ConsentState?>(null) }
    var payload by remember { mutableStateOf<ReportPayload?>(null) }
    var preparing by remember { mutableStateOf(true) }
    var prepareFailed by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<ReportSubmitResult?>(null) }
    var confirmSend by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val savedConsent = withContext(Dispatchers.IO) {
            runCatching { PrivacyStorage.consentStore(context).read() }.getOrDefault(ConsentState.OFF)
        }
        consent = savedConsent
        if (!savedConsent.diagnosticReportsEnabled) {
            preparing = false
            return@LaunchedEffect
        }
        val prepared = withContext(Dispatchers.IO) {
            runCatching {
                ReportPayloadFactory.diagnostic(
                    clientReportId = UUID.randomUUID().toString(),
                    installId = PrivacyStorage.installIdProvider(context).getOrCreate(),
                    clientCreatedAt = ReportTimestamps.nowIso8601(),
                    app = ReportAppInfo.fromBuild(),
                    selfChecks = ReportSelfChecks.run(context),
                )
            }.getOrNull()
        }
        payload = prepared
        prepareFailed = prepared == null
        preparing = false
    }

    fun send() {
        val current = payload ?: return
        if (sending) return
        confirmSend = false
        sending = true
        scope.launch {
            result = withContext(Dispatchers.IO) { ReportSender.sendNew(context, current) }
            sending = false
        }
    }

    ToolScaffold(
        title = stringResource(R.string.report_diagnostic_title),
        backdrop = backdrop,
        onBack = onBack,
        overlay = { glass ->
            GlassDialog(
                visible = confirmSend,
                onDismiss = { confirmSend = false },
                backdrop = glass,
                title = stringResource(R.string.report_send_confirm_title),
                actions = {
                    GlassDialogAction(
                        text = stringResource(R.string.report_cancel),
                        onClick = { confirmSend = false },
                    )
                    GlassDialogAction(
                        text = stringResource(R.string.report_send),
                        onClick = { send() },
                        primary = true,
                    )
                },
            ) {
                BasicText(
                    stringResource(R.string.report_send_confirm_body),
                    style = TextStyle(ink, 14.sp, lineHeight = 20.sp),
                )
            }
        },
    ) {
        when {
            preparing -> LoadingReportPanel(
                backdrop = backdrop,
                uiSensor = uiSensor,
                ink = ink,
                message = stringResource(R.string.report_running_checks),
            )
            consent?.diagnosticReportsEnabled != true -> LiquidGlassErrorCard(
                message = stringResource(R.string.report_consent_off),
                backdrop = backdrop,
                uiSensor = uiSensor,
            )
            prepareFailed || payload == null -> LiquidGlassErrorCard(
                message = stringResource(R.string.report_prepare_failed),
                backdrop = backdrop,
                uiSensor = uiSensor,
            )
            else -> {
                val current = payload ?: return@ToolScaffold
                ReportPreviewCard(payload = current, backdrop = backdrop, uiSensor = uiSensor, ink = ink, sub = sub)
                val currentResult = result
                if (currentResult != null) {
                    ReportResultCard(
                        result = currentResult,
                        backdrop = backdrop,
                        uiSensor = uiSensor,
                        ink = ink,
                        sub = sub,
                    )
                }
                if (currentResult?.outcome != ReportSubmitOutcome.SUBMITTED) {
                    ToolPrimaryButton(
                        text = stringResource(
                            if (currentResult == null) R.string.report_send else R.string.report_retry
                        ),
                        onClick = { confirmSend = true },
                        backdrop = backdrop,
                        accent = LiquidGlassColors.Blue,
                        icon = Icons.Rounded.Send,
                        busy = sending,
                        enabled = !sending,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

/**
 * User-authored problem report. The description is validated and flagged client-side before the
 * preview exists; the preview is the exact payload, and Send always asks for confirmation.
 */
@Composable
fun ProblemReportScreen(
    backdrop: LayerBackdrop,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val isDark = LocalIsDarkMode.current
    val ink = LiquidGlassColors.text(isDark)
    val sub = LiquidGlassColors.secondary(isDark)
    val uiSensor = rememberUISensor()
    val scope = rememberCoroutineScope()

    var consent by remember { mutableStateOf<ConsentState?>(null) }
    var description by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf<ProblemCategory?>(null) }
    var previewPayload by remember { mutableStateOf<ReportPayload?>(null) }
    var preparing by remember { mutableStateOf(false) }
    var inputError by remember { mutableStateOf<ProblemInputError?>(null) }
    var sending by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<ReportSubmitResult?>(null) }
    var confirmSend by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        consent = withContext(Dispatchers.IO) {
            runCatching { PrivacyStorage.consentStore(context).read() }.getOrDefault(ConsentState.OFF)
        }
    }

    fun preview() {
        if (preparing) return
        val sanitized = ReportSanitizer.description(description)
        inputError = when {
            sanitized.text.isBlank() -> ProblemInputError.REQUIRED
            sanitized.issues.isNotEmpty() -> ProblemInputError.UNSAFE
            else -> null
        }
        if (inputError != null) return

        preparing = true
        result = null
        scope.launch {
            val prepared = withContext(Dispatchers.IO) {
                runCatching {
                    ReportPayloadFactory.problem(
                        clientReportId = UUID.randomUUID().toString(),
                        installId = PrivacyStorage.installIdProvider(context).getOrCreate(),
                        clientCreatedAt = ReportTimestamps.nowIso8601(),
                        app = ReportAppInfo.fromBuild(),
                        description = sanitized,
                        category = category,
                    )
                }.getOrNull()
            }
            previewPayload = prepared
            if (prepared == null) inputError = ProblemInputError.PREPARE_FAILED
            preparing = false
        }
    }

    fun send() {
        val current = previewPayload ?: return
        if (sending) return
        confirmSend = false
        sending = true
        scope.launch {
            result = withContext(Dispatchers.IO) { ReportSender.sendNew(context, current) }
            sending = false
        }
    }

    ToolScaffold(
        title = stringResource(R.string.report_problem_title),
        backdrop = backdrop,
        onBack = onBack,
        overlay = { glass ->
            GlassDialog(
                visible = confirmSend,
                onDismiss = { confirmSend = false },
                backdrop = glass,
                title = stringResource(R.string.report_send_confirm_title),
                actions = {
                    GlassDialogAction(
                        text = stringResource(R.string.report_cancel),
                        onClick = { confirmSend = false },
                    )
                    GlassDialogAction(
                        text = stringResource(R.string.report_send),
                        onClick = { send() },
                        primary = true,
                    )
                },
            ) {
                BasicText(
                    stringResource(R.string.report_send_confirm_body),
                    style = TextStyle(ink, 14.sp, lineHeight = 20.sp),
                )
            }
        },
    ) {
        when {
            consent == null -> LoadingReportPanel(
                backdrop = backdrop,
                uiSensor = uiSensor,
                ink = ink,
                message = stringResource(R.string.report_checking),
            )
            consent?.problemReportsEnabled != true -> LiquidGlassErrorCard(
                message = stringResource(R.string.report_consent_off),
                backdrop = backdrop,
                uiSensor = uiSensor,
            )
            previewPayload == null -> {
                Column(
                    Modifier.fillMaxWidth().liquidGlassPanel(backdrop, uiSensor).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    GlassSectionHeader(
                        title = stringResource(R.string.report_problem_title),
                        icon = Icons.Rounded.BugReport,
                        iconTint = LiquidGlassColors.Orange,
                        titleColor = ink,
                    )
                    BasicText(
                        stringResource(R.string.report_problem_intro),
                        style = TextStyle(sub, 13.sp, lineHeight = 19.sp),
                    )
                    BasicText(
                        stringResource(R.string.report_problem_description_label),
                        style = TextStyle(ink, 14.sp, FontWeight.SemiBold),
                    )
                    BasicTextField(
                        value = description,
                        onValueChange = {
                            if (it.length <= com.hmx.pdf.data.report.REPORT_MAX_DESCRIPTION_CHARS) {
                                description = it
                                inputError = null
                            }
                        },
                        textStyle = TextStyle(ink, 14.sp, lineHeight = 20.sp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 140.dp, max = 280.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(if (isDark) Color.White.copy(0.08f) else Color.Black.copy(0.05f))
                            .padding(12.dp),
                        decorationBox = { inner ->
                            if (description.isEmpty()) {
                                BasicText(
                                    stringResource(R.string.report_problem_description_hint),
                                    style = TextStyle(sub, 14.sp),
                                )
                            }
                            inner()
                        },
                    )
                    BasicText(
                        stringResource(
                            R.string.report_problem_chars,
                            description.length,
                            com.hmx.pdf.data.report.REPORT_MAX_DESCRIPTION_CHARS,
                        ),
                        style = TextStyle(sub, 11.sp),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    BasicText(
                        stringResource(R.string.report_problem_category_label),
                        style = TextStyle(ink, 14.sp, FontWeight.SemiBold),
                    )
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        ProblemCategory.entries.forEach { option ->
                            GlassChoiceChip(
                                label = problemCategoryLabel(option),
                                selected = category == option,
                                onClick = { category = if (category == option) null else option },
                                backdrop = backdrop,
                                accent = LiquidGlassColors.Orange,
                            )
                        }
                    }
                    BasicText(
                        stringResource(R.string.report_problem_sanitized_note),
                        style = TextStyle(sub, 11.sp, lineHeight = 16.sp),
                    )
                    when (inputError) {
                        ProblemInputError.REQUIRED -> InputErrorText(
                            text = stringResource(R.string.report_problem_required),
                            isDark = isDark,
                        )
                        ProblemInputError.UNSAFE -> InputErrorText(
                            text = stringResource(R.string.report_problem_unsafe),
                            isDark = isDark,
                        )
                        ProblemInputError.PREPARE_FAILED -> InputErrorText(
                            text = stringResource(R.string.report_prepare_failed),
                            isDark = isDark,
                        )
                        null -> Unit
                    }
                }
                ToolPrimaryButton(
                    text = stringResource(R.string.report_problem_preview),
                    onClick = { preview() },
                    backdrop = backdrop,
                    accent = LiquidGlassColors.Orange,
                    icon = Icons.Rounded.Description,
                    busy = preparing,
                    enabled = !preparing,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            else -> {
                val current = previewPayload ?: return@ToolScaffold
                ReportPreviewCard(payload = current, backdrop = backdrop, uiSensor = uiSensor, ink = ink, sub = sub)
                val currentResult = result
                if (currentResult != null) {
                    ReportResultCard(
                        result = currentResult,
                        backdrop = backdrop,
                        uiSensor = uiSensor,
                        ink = ink,
                        sub = sub,
                    )
                }
                if (currentResult?.outcome != ReportSubmitOutcome.SUBMITTED) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        ToolPrimaryButton(
                            text = stringResource(R.string.report_edit),
                            onClick = {
                                previewPayload = null
                                result = null
                            },
                            backdrop = backdrop,
                            accent = LiquidGlassColors.Indigo,
                            icon = Icons.Rounded.Description,
                            modifier = Modifier.weight(1f),
                        )
                        ToolPrimaryButton(
                            text = stringResource(
                                if (currentResult == null) R.string.report_send else R.string.report_retry
                            ),
                            onClick = { confirmSend = true },
                            backdrop = backdrop,
                            accent = LiquidGlassColors.Blue,
                            icon = Icons.Rounded.Send,
                            busy = sending,
                            enabled = !sending,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

/** Local history of saved reports with explicit Retry/Discard. No background worker is involved. */
@Composable
fun PendingReportsScreen(
    backdrop: LayerBackdrop,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val isDark = LocalIsDarkMode.current
    val ink = LiquidGlassColors.text(isDark)
    val sub = LiquidGlassColors.secondary(isDark)
    val uiSensor = rememberUISensor()
    val scope = rememberCoroutineScope()

    var consent by remember { mutableStateOf(ConsentState.OFF) }
    var reports by remember { mutableStateOf<List<ReportEnvelope>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var expanded by remember { mutableStateOf<Set<String>>(emptySet()) }
    var retryingId by remember { mutableStateOf<String?>(null) }
    var lastResult by remember { mutableStateOf<ReportSubmitResult?>(null) }
    var discardId by remember { mutableStateOf<String?>(null) }

    fun reload() {
        scope.launch {
            reports = withContext(Dispatchers.IO) {
                runCatching { ReportQueueFactory.create(context).list() }.getOrDefault(emptyList())
            }
            loading = false
        }
    }

    LaunchedEffect(Unit) {
        consent = withContext(Dispatchers.IO) {
            runCatching { PrivacyStorage.consentStore(context).read() }.getOrDefault(ConsentState.OFF)
        }
        reload()
    }

    fun retry(id: String) {
        if (retryingId != null) return
        retryingId = id
        scope.launch {
            lastResult = withContext(Dispatchers.IO) { ReportSender.retry(context, id) }
            retryingId = null
            reload()
        }
    }

    fun discard(id: String) {
        discardId = null
        scope.launch {
            withContext(Dispatchers.IO) {
                runCatching { ReportQueueFactory.create(context).delete(id) }
            }
            reload()
        }
    }

    ToolScaffold(
        title = stringResource(R.string.report_pending_title),
        backdrop = backdrop,
        onBack = onBack,
        overlay = { glass ->
            GlassDialog(
                visible = discardId != null,
                onDismiss = { discardId = null },
                backdrop = glass,
                title = stringResource(R.string.report_pending_discard_title),
                actions = {
                    GlassDialogAction(
                        text = stringResource(R.string.report_cancel),
                        onClick = { discardId = null },
                    )
                    GlassDialogAction(
                        text = stringResource(R.string.report_pending_discard),
                        onClick = { discardId?.let(::discard) },
                        primary = true,
                        destructive = true,
                    )
                },
            ) {
                BasicText(
                    stringResource(R.string.report_pending_discard_body),
                    style = TextStyle(ink, 14.sp, lineHeight = 20.sp),
                )
            }
        },
    ) {
        BasicText(
            stringResource(R.string.report_pending_intro),
            style = TextStyle(sub, 13.sp, lineHeight = 19.sp),
        )
        lastResult?.let { result ->
            ReportResultCard(
                result = result,
                backdrop = backdrop,
                uiSensor = uiSensor,
                ink = ink,
                sub = sub,
            )
        }
        when {
            loading -> LoadingReportPanel(
                backdrop = backdrop,
                uiSensor = uiSensor,
                ink = ink,
                message = stringResource(R.string.report_checking),
            )
            reports.isEmpty() -> Column(
                Modifier.fillMaxWidth().liquidGlassPanel(backdrop, uiSensor).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                BasicText(
                    stringResource(R.string.report_pending_empty),
                    style = TextStyle(ink, 14.sp),
                )
            }
            else -> reports.forEach { envelope ->
                val kindEnabled = ReportSender.isEnabledFor(consent, envelope.payload.kind)
                PendingReportCard(
                    envelope = envelope,
                    expanded = envelope.payload.clientReportId in expanded,
                    kindEnabled = kindEnabled,
                    retrying = retryingId == envelope.payload.clientReportId,
                    onTogglePayload = {
                        val id = envelope.payload.clientReportId
                        expanded = if (id in expanded) expanded - id else expanded + id
                    },
                    onRetry = { retry(envelope.payload.clientReportId) },
                    onDiscard = { discardId = envelope.payload.clientReportId },
                    backdrop = backdrop,
                    ink = ink,
                    sub = sub,
                )
            }
        }
    }
}

@Composable
private fun PendingReportCard(
    envelope: ReportEnvelope,
    expanded: Boolean,
    kindEnabled: Boolean,
    retrying: Boolean,
    onTogglePayload: () -> Unit,
    onRetry: () -> Unit,
    onDiscard: () -> Unit,
    backdrop: LayerBackdrop,
    ink: Color,
    sub: Color,
) {
    val uiSensor = rememberUISensor()
    Column(
        Modifier.fillMaxWidth().liquidGlassPanel(backdrop, uiSensor).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (envelope.isTerminal) Icons.Rounded.CheckCircle else Icons.Rounded.BugReport,
                null,
                Modifier.size(20.dp),
                if (envelope.isTerminal) LiquidGlassColors.success(LocalIsDarkMode.current)
                else LiquidGlassColors.Orange,
            )
            Column(Modifier.weight(1f)) {
                BasicText(
                    reportKindLabel(envelope.payload.kind),
                    style = TextStyle(ink, 15.sp, FontWeight.SemiBold),
                )
                BasicText(
                    ReportTimestamps.iso8601Utc(envelope.createdAtMillis),
                    style = TextStyle(sub, 11.sp),
                )
            }
            BasicText(
                reportStateLabel(envelope.state),
                style = TextStyle(
                    when (envelope.state) {
                        ReportState.SUBMITTED -> LiquidGlassColors.success(LocalIsDarkMode.current)
                        ReportState.REJECTED, ReportState.CANCELLED -> LiquidGlassColors.danger(LocalIsDarkMode.current)
                        else -> LiquidGlassColors.warning(LocalIsDarkMode.current)
                    },
                    12.sp,
                    FontWeight.Medium,
                ),
            )
        }
        BasicText(
            stringResource(R.string.report_pending_attempts, envelope.attempts),
            style = TextStyle(sub, 12.sp),
        )
        envelope.serverId?.let {
            BasicText(
                stringResource(R.string.report_server_id) + ": " + it,
                style = TextStyle(sub, 11.sp),
            )
        }
        if (!envelope.isTerminal && !kindEnabled) {
            BasicText(
                stringResource(R.string.report_pending_retry_unavailable),
                style = TextStyle(LiquidGlassColors.warning(LocalIsDarkMode.current), 11.sp, lineHeight = 16.sp),
            )
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ToolPrimaryButton(
                text = stringResource(
                    if (expanded) R.string.report_pending_hide else R.string.report_pending_show
                ),
                onClick = onTogglePayload,
                backdrop = backdrop,
                accent = LiquidGlassColors.Indigo,
                icon = Icons.Rounded.Description,
                modifier = Modifier.weight(1f),
            )
            if (!envelope.isTerminal) {
                ToolPrimaryButton(
                    text = stringResource(R.string.report_retry),
                    onClick = onRetry,
                    backdrop = backdrop,
                    accent = LiquidGlassColors.Blue,
                    icon = Icons.Rounded.Refresh,
                    busy = retrying,
                    enabled = kindEnabled && !retrying,
                    modifier = Modifier.weight(1f),
                )
            }
            ToolPrimaryButton(
                text = stringResource(R.string.report_pending_discard),
                onClick = onDiscard,
                backdrop = backdrop,
                accent = LiquidGlassColors.Red,
                icon = Icons.Rounded.DeleteOutline,
                modifier = Modifier.weight(1f),
            )
        }
        if (expanded) {
            ReportPayloadFields(payload = envelope.payload, ink = ink, sub = sub)
        }
    }
}

/** Every field of the exact payload, with no derived or hidden extras. */
@Composable
private fun ReportPreviewCard(
    payload: ReportPayload,
    backdrop: LayerBackdrop,
    uiSensor: com.hmx.pdf.ui.utils.UISensor,
    ink: Color,
    sub: Color,
) {
    Column(
        Modifier.fillMaxWidth().liquidGlassPanel(backdrop, uiSensor).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        GlassSectionHeader(
            title = stringResource(R.string.report_preview_title),
            icon = Icons.Rounded.Description,
            iconTint = LiquidGlassColors.Blue,
            titleColor = ink,
        )
        BasicText(
            stringResource(R.string.report_preview_body),
            style = TextStyle(sub, 12.sp, lineHeight = 18.sp),
        )
        Spacer(Modifier.height(2.dp))
        ReportPayloadFields(payload = payload, ink = ink, sub = sub)
    }
}

@Composable
private fun ReportPayloadFields(payload: ReportPayload, ink: Color, sub: Color) {
    ReportField(stringResource(R.string.report_field_type), reportKindLabel(payload.kind), ink, sub)
    ReportField(stringResource(R.string.report_field_id), payload.clientReportId, ink, sub)
    ReportField(stringResource(R.string.report_field_created), payload.clientCreatedAt, ink, sub)
    ReportField(stringResource(R.string.report_field_install_id), payload.installId, ink, sub)
    ReportField(
        stringResource(R.string.report_field_app),
        payload.app.versionName + " (" + payload.app.versionCode + ")",
        ink,
        sub,
    )
    ReportField(
        stringResource(R.string.report_field_build),
        payload.app.flavor.name.lowercase() + " / " + payload.app.buildType.name.lowercase(),
        ink,
        sub,
    )
    payload.app.androidApi?.let {
        ReportField(stringResource(R.string.report_field_api), it.toString(), ink, sub)
    }
    payload.app.locale?.let {
        ReportField(stringResource(R.string.report_field_locale), it, ink, sub)
    }
    payload.category?.let {
        ReportField(stringResource(R.string.report_field_category), problemCategoryLabel(it), ink, sub)
    }
    payload.description?.let {
        ReportField(stringResource(R.string.report_field_description), it, ink, sub)
    }
    payload.attachedError?.let { error ->
        ReportField(stringResource(R.string.report_field_attached_error), error.summary, ink, sub)
        error.detail?.let {
            ReportField(stringResource(R.string.report_field_error_detail), it, ink, sub)
        }
    }
    payload.selfChecks?.let { checks ->
        Spacer(Modifier.height(2.dp))
        BasicText(
            stringResource(R.string.report_field_self_checks),
            style = TextStyle(ink, 13.sp, FontWeight.SemiBold),
        )
        checks.forEach { (key, value) ->
            ReportField(selfCheckLabel(key), selfCheckValueLabel(value), ink, sub)
        }
    }
}

@Composable
private fun ReportField(label: String, value: String, ink: Color, sub: Color) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        BasicText(
            label,
            style = TextStyle(sub, 12.sp),
            modifier = Modifier.width(116.dp),
        )
        BasicText(
            value,
            style = TextStyle(ink, 13.sp, lineHeight = 19.sp),
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun ReportResultCard(
    result: ReportSubmitResult,
    backdrop: LayerBackdrop,
    uiSensor: com.hmx.pdf.ui.utils.UISensor,
    ink: Color,
    sub: Color,
) {
    if (result.outcome == ReportSubmitOutcome.SUBMITTED) {
        val acknowledgement = result.acknowledgement
        Column(
            Modifier.fillMaxWidth().liquidGlassPanel(backdrop, uiSensor).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Rounded.CheckCircle,
                    null,
                    Modifier.size(22.dp),
                    LiquidGlassColors.success(LocalIsDarkMode.current),
                )
                BasicText(
                    stringResource(R.string.report_sent),
                    style = TextStyle(ink, 16.sp, FontWeight.SemiBold),
                )
            }
            BasicText(
                stringResource(R.string.report_sent_body),
                style = TextStyle(sub, 13.sp, lineHeight = 19.sp),
            )
            if (acknowledgement != null) {
                ReportField(stringResource(R.string.report_server_id), acknowledgement.reportId, ink, sub)
            }
        }
    } else {
        LiquidGlassErrorCard(
            message = reportErrorText(result),
            backdrop = backdrop,
            uiSensor = uiSensor,
        )
    }
}

@Composable
private fun LoadingReportPanel(
    backdrop: LayerBackdrop,
    uiSensor: com.hmx.pdf.ui.utils.UISensor,
    ink: Color,
    message: String,
) {
    Row(
        Modifier.fillMaxWidth().liquidGlassPanel(backdrop, uiSensor).padding(20.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(Modifier.size(20.dp), LiquidGlassColors.Blue, strokeWidth = 2.dp)
        BasicText(message, style = TextStyle(ink, 14.sp))
    }
}

@Composable
private fun InputErrorText(text: String, isDark: Boolean) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            Icons.Rounded.Warning,
            null,
            Modifier.size(16.dp),
            LiquidGlassColors.danger(isDark),
        )
        BasicText(
            text,
            style = TextStyle(LiquidGlassColors.danger(isDark), 12.sp, lineHeight = 17.sp),
            modifier = Modifier.weight(1f),
        )
    }
}

private enum class ProblemInputError {
    REQUIRED,
    UNSAFE,
    PREPARE_FAILED,
}

@Composable
private fun reportKindLabel(kind: ReportKind): String = stringResource(
    when (kind) {
        ReportKind.DIAGNOSTIC_TEST -> R.string.report_type_diagnostic
        ReportKind.PROBLEM -> R.string.report_type_problem
    }
)

@Composable
private fun problemCategoryLabel(category: ProblemCategory): String = stringResource(
    when (category) {
        ProblemCategory.BUG -> R.string.report_category_bug
        ProblemCategory.CRASH -> R.string.report_category_crash
        ProblemCategory.PERFORMANCE -> R.string.report_category_performance
        ProblemCategory.OTHER -> R.string.report_category_other
    }
)

@Composable
private fun reportStateLabel(state: ReportState): String = stringResource(
    when (state) {
        ReportState.PENDING -> R.string.report_state_pending
        ReportState.FAILED -> R.string.report_state_failed
        ReportState.SUBMITTED -> R.string.report_state_submitted
        ReportState.REJECTED -> R.string.report_state_rejected
        ReportState.CANCELLED -> R.string.report_state_cancelled
    }
)

@Composable
private fun selfCheckLabel(key: SelfCheckKey): String = stringResource(
    when (key) {
        SelfCheckKey.STORAGE_WRITE -> R.string.report_self_check_storage_write
        SelfCheckKey.CACHE_WRITE -> R.string.report_self_check_cache_write
        SelfCheckKey.PDF_ENGINE_INIT -> R.string.report_self_check_pdf_engine_init
        SelfCheckKey.REPORT_QUEUE_INIT -> R.string.report_self_check_report_queue_init
    }
)

@Composable
private fun selfCheckValueLabel(value: SelfCheckValue): String = stringResource(
    when (value) {
        SelfCheckValue.OK -> R.string.report_self_check_ok
        SelfCheckValue.FAILED -> R.string.report_self_check_failed
        SelfCheckValue.SKIPPED -> R.string.report_self_check_skipped
    }
)

@Composable
private fun reportErrorText(result: ReportSubmitResult): String = stringResource(
    when (result.outcome) {
        ReportSubmitOutcome.TIMEOUT -> R.string.report_timeout
        ReportSubmitOutcome.OFFLINE -> R.string.report_offline
        ReportSubmitOutcome.RATE_LIMITED -> R.string.report_rate_limited
        ReportSubmitOutcome.UNAUTHORIZED -> R.string.report_unauthorized
        ReportSubmitOutcome.SERVER_ERROR -> R.string.report_server_error
        ReportSubmitOutcome.INVALID_RESPONSE -> R.string.report_invalid_response
        ReportSubmitOutcome.NOT_CONFIGURED -> R.string.report_not_configured
        ReportSubmitOutcome.CONSENT_OFF -> R.string.report_consent_off
        ReportSubmitOutcome.UNAVAILABLE -> R.string.report_unavailable
        ReportSubmitOutcome.LOCAL_ERROR -> R.string.report_local_error
        ReportSubmitOutcome.REJECTED -> R.string.report_rejected_body
        ReportSubmitOutcome.SUBMITTED -> R.string.report_sent_body
    }
)
