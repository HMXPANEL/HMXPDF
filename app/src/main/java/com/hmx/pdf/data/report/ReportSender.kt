package com.hmx.pdf.data.report

import android.content.Context
import com.hmx.pdf.BuildConfig
import com.hmx.pdf.data.privacy.ConsentState
import com.hmx.pdf.data.privacy.PrivacyStorage
import com.hmx.pdf.data.remote.EffectiveFeatures
import com.hmx.pdf.data.remote.RemoteConfig

/**
 * The single explicit-send path. It checks local consent first, then remote availability, then
 * queues and submits the already-previewed payload. There is no scheduler and no automatic retry.
 */
object ReportSender {
    fun sendNew(context: Context, payload: ReportPayload): ReportSubmitResult {
        val consent = PrivacyStorage.consentStore(context).read()
        val availability = checkAvailability(context, consent, payload.kind)
        if (availability.result != null) return availability.result

        val queue = ReportQueueFactory.create(context)
        val envelope = runCatching { queue.enqueue(payload) }.getOrElse {
            return ReportSubmitResult(
                outcome = ReportSubmitOutcome.LOCAL_ERROR,
                errorCode = ReportErrorCode.UNKNOWN,
            )
        }
        if (envelope.isTerminal) {
            return ReportSubmitResult(
                outcome = ReportSubmitOutcome.REJECTED,
                errorCode = ReportErrorCode.REJECTED,
            )
        }
        return runCatching { deliver(queue, payload) }.getOrElse {
            ReportSubmitResult(
                outcome = ReportSubmitOutcome.LOCAL_ERROR,
                errorCode = ReportErrorCode.UNKNOWN,
            )
        }
    }

    fun retry(context: Context, clientReportId: String): ReportSubmitResult {
        val queue = ReportQueueFactory.create(context)
        val envelope = queue.get(clientReportId)
            ?: return ReportSubmitResult(
                outcome = ReportSubmitOutcome.LOCAL_ERROR,
                errorCode = ReportErrorCode.UNKNOWN,
            )
        if (envelope.isTerminal) {
            return ReportSubmitResult(
                outcome = ReportSubmitOutcome.REJECTED,
                errorCode = ReportErrorCode.REJECTED,
            )
        }

        val consent = PrivacyStorage.consentStore(context).read()
        val availability = checkAvailability(context, consent, envelope.payload.kind)
        if (availability.result != null) return availability.result
        return runCatching { deliver(queue, envelope.payload) }.getOrElse {
            ReportSubmitResult(
                outcome = ReportSubmitOutcome.LOCAL_ERROR,
                errorCode = ReportErrorCode.UNKNOWN,
            )
        }
    }

    fun isEnabledFor(consent: ConsentState, kind: ReportKind): Boolean = when (kind) {
        ReportKind.DIAGNOSTIC_TEST -> consent.diagnosticReportsEnabled
        ReportKind.PROBLEM -> consent.problemReportsEnabled
    }

    fun effectiveEnabled(features: EffectiveFeatures, kind: ReportKind): Boolean = when (kind) {
        ReportKind.DIAGNOSTIC_TEST -> features.diagnosticReports
        ReportKind.PROBLEM -> features.problemReports
    }

    private data class Availability(val result: ReportSubmitResult? = null)

    private fun checkAvailability(
        context: Context,
        consent: ConsentState,
        kind: ReportKind,
    ): Availability {
        if (!isEnabledFor(consent, kind)) {
            return Availability(
                ReportSubmitResult(
                    outcome = ReportSubmitOutcome.CONSENT_OFF,
                    errorCode = ReportErrorCode.UNKNOWN,
                )
            )
        }
        if (BuildConfig.REPORT_ENDPOINT.isBlank() ||
            BuildConfig.SUPABASE_PUBLISHABLE_KEY.isBlank()
        ) {
            return Availability(ReportSubmitResult(ReportSubmitOutcome.NOT_CONFIGURED))
        }

        val client = RemoteConfig.create(context, BuildConfig.VERSION_CODE)
        var features = client.effectiveFeatures(consent)
        if (!effectiveEnabled(features, kind)) {
            // Explicit Send may refresh availability once. It still fails closed if the refresh
            // fails or the server keeps the flag disabled.
            val refresh = client.refresh()
            features = client.effectiveFeatures(consent)
            if (!effectiveEnabled(features, kind)) {
                return Availability(
                    ReportSubmitResult(
                        outcome = when (refresh.outcome) {
                            com.hmx.pdf.data.remote.ConfigRefreshOutcome.NOT_CONFIGURED ->
                                ReportSubmitOutcome.NOT_CONFIGURED
                            else -> ReportSubmitOutcome.UNAVAILABLE
                        },
                        errorCode = ReportErrorCode.UNKNOWN,
                    )
                )
            }
        }
        return Availability()
    }

    private fun deliver(
        queue: ReportQueueStore,
        payload: ReportPayload,
    ): ReportSubmitResult {
        queue.recordAttempt(payload.clientReportId)
        val result = ReportSubmissionClient(
            endpoint = BuildConfig.REPORT_ENDPOINT,
            publishableKey = BuildConfig.SUPABASE_PUBLISHABLE_KEY,
        ).submit(payload)

        val id = payload.clientReportId
        when (result.outcome) {
            ReportSubmitOutcome.SUBMITTED -> {
                val ack = result.acknowledgement
                if (ack != null) {
                    queue.transition(id, ReportState.SUBMITTED, serverId = ack.reportId)
                } else {
                    queue.transition(
                        id,
                        ReportState.FAILED,
                        error = ReportErrorCode.INVALID_RESPONSE,
                    )
                }
            }
            ReportSubmitOutcome.REJECTED -> queue.transition(
                id,
                ReportState.REJECTED,
                error = ReportErrorCode.REJECTED,
            )
            else -> queue.transition(
                id,
                ReportState.FAILED,
                error = result.errorCode ?: ReportErrorCode.UNKNOWN,
            )
        }
        return result
    }
}
