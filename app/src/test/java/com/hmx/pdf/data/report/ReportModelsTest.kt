package com.hmx.pdf.data.report

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportModelsTest {

    private fun json(payload: ReportPayload) =
        Json.parseToJsonElement(ReportJson.encodeToString(payload)).jsonObject

    @Test
    fun diagnosticPayloadUsesOnlyBackendFields() {
        val payload = testDiagnostic()
        val objectJson = json(payload)

        assertEquals(
            setOf(
                "schema_version",
                "kind",
                "client_report_id",
                "install_id",
                "client_created_at",
                "app",
                "self_checks",
            ),
            objectJson.keys,
        )
        assertFalse(objectJson.containsKey("description"))
        assertFalse(objectJson.containsKey("category"))
        assertFalse(objectJson.containsKey("attached_error"))
        assertEquals("diagnostic_test", objectJson["kind"]!!.toString().trim('"'))
        assertEquals(
            setOf("version_code", "version_name", "flavor", "build_type", "android_api", "locale"),
            objectJson["app"]!!.jsonObject.keys,
        )
        assertNull(payload.validationError(TEST_NOW))
    }

    @Test
    fun problemPayloadUsesOnlyBackendFieldsAndOmitsNullOptionals() {
        val objectJson = json(testProblem())
        assertEquals(
            setOf(
                "schema_version",
                "kind",
                "client_report_id",
                "install_id",
                "client_created_at",
                "app",
                "description",
            ),
            objectJson.keys,
        )
        assertFalse(objectJson.containsKey("self_checks"))
        assertEquals("problem", objectJson["kind"]!!.toString().trim('"'))
    }

    @Test
    fun serializedPayloadNeverContainsForbiddenFields() {
        val text = ReportJson.encodeToString(testProblem())
        for (field in listOf(
            "device_model",
            "document_id",
            "file_name",
            "file_path",
            "uri",
            "annotation",
            "signature",
            "password",
            "contact_email",
            "phone",
            "location",
            "screenshot",
            "raw_log",
            "logcat",
            "android_id",
            "imei",
            "advertising_id",
            "serial",
            "token",
        )) {
            assertFalse("forbidden field $field", text.contains("\"$field\"", ignoreCase = true))
        }
    }

    @Test
    fun diagnosticRejectsProblemOnlyFields() {
        val payload = testDiagnostic().copy(description = "not allowed")
        assertEquals("invalid_kind", payload.validationError(TEST_NOW))
    }

    @Test
    fun problemRejectsDiagnosticOnlyFields() {
        val payload = testProblem().copy(
            selfChecks = mapOf(SelfCheckKey.STORAGE_WRITE to SelfCheckValue.OK)
        )
        assertEquals("invalid_kind", payload.validationError(TEST_NOW))
    }

    @Test
    fun problemRequiresANonBlankDescription() {
        val payload = testProblem().copy(description = "   ")
        assertEquals("invalid_description", payload.validationError(TEST_NOW))
    }

    @Test
    fun problemRejectsControlCharactersAndOversizedText() {
        assertEquals(
            "invalid_description",
            testProblem().copy(description = "bad\u0000text").validationError(TEST_NOW),
        )
        assertEquals(
            "invalid_description",
            testProblem().copy(description = "a".repeat(4_001)).validationError(TEST_NOW),
        )
    }

    @Test
    fun attachedErrorLimitsAreEnforced() {
        val tooLongSummary = testProblem().copy(
            attachedError = AttachedError("a".repeat(201), null)
        )
        assertEquals("invalid_attached_error", tooLongSummary.validationError(TEST_NOW))

        val tooLongDetail = testProblem().copy(
            attachedError = AttachedError("summary", "a".repeat(4_001))
        )
        assertEquals("invalid_attached_error", tooLongDetail.validationError(TEST_NOW))

        val valid = testProblem().copy(
            attachedError = AttachedError("Export failed", "NullPointerException")
        )
        assertNull(valid.validationError(TEST_NOW))
    }

    @Test
    fun factorySanitizesAttachedErrorText() {
        val payload = ReportPayloadFactory.problem(
            clientReportId = TEST_CLIENT_ID,
            installId = TEST_INSTALL_ID,
            clientCreatedAt = ReportTimestamps.iso8601Utc(TEST_NOW),
            app = testApp(),
            description = ReportSanitizer.description("Export failed."),
            attachedError = AttachedError(
                "failed at /storage/emulated/0/secret.pdf token=abc123",
                "see https://example.test/private",
            ),
            nowMillis = TEST_NOW,
        )

        val error = payload.attachedError!!
        assertFalse(error.summary.contains("/storage/"))
        assertFalse(error.summary.contains("abc123"))
        assertTrue(error.summary.contains("[redacted]"))
        assertFalse(error.detail!!.contains("https://"))
        assertTrue(error.detail!!.contains("[redacted]"))
        assertNull(payload.validationError(TEST_NOW))
    }

    @Test
    fun factoryRejectsABlankAttachedErrorSummary() {
        try {
            ReportPayloadFactory.problem(
                clientReportId = TEST_CLIENT_ID,
                installId = TEST_INSTALL_ID,
                clientCreatedAt = ReportTimestamps.iso8601Utc(TEST_NOW),
                app = testApp(),
                description = ReportSanitizer.description("Export failed."),
                attachedError = AttachedError("   ", null),
                nowMillis = TEST_NOW,
            )
            throw AssertionError("expected rejection")
        } catch (expected: ReportValidationException) {
            assertEquals("unsafe_attached_error", expected.code)
        }
    }

    @Test
    fun appMetadataIsValidated() {
        assertEquals(
            "invalid_app",
            testDiagnostic().copy(app = testApp(versionCode = -1)).validationError(TEST_NOW),
        )
        assertEquals(
            "invalid_app",
            testDiagnostic().copy(app = testApp(androidApi = 0)).validationError(TEST_NOW),
        )
        assertEquals(
            "invalid_app",
            testDiagnostic().copy(app = testApp(locale = "not a locale")).validationError(TEST_NOW),
        )
    }

    @Test
    fun timestampsAreBounded() {
        val old = testDiagnostic().copy(
            clientCreatedAt = ReportTimestamps.iso8601Utc(
                TEST_NOW - REPORT_MAX_AGE_MILLIS - 1_000L
            )
        )
        assertEquals("invalid_client_created_at", old.validationError(TEST_NOW))

        val future = testDiagnostic().copy(
            clientCreatedAt = ReportTimestamps.iso8601Utc(
                TEST_NOW + REPORT_FUTURE_TOLERANCE_MILLIS + 1_000L
            )
        )
        assertEquals("invalid_client_created_at", future.validationError(TEST_NOW))
    }

    @Test
    fun identifiersAreNormalizedToLowercase() {
        val payload = testDiagnostic(
            clientReportId = TEST_CLIENT_ID.uppercase(),
            installId = TEST_INSTALL_ID.uppercase(),
        )
        assertEquals(TEST_CLIENT_ID, payload.clientReportId)
        assertEquals(TEST_INSTALL_ID, payload.installId)
        assertNull(payload.validationError(TEST_NOW))
    }

    @Test
    fun factoryRejectsAnUnsafeDescription() {
        try {
            ReportPayloadFactory.problem(
                clientReportId = TEST_CLIENT_ID,
                installId = TEST_INSTALL_ID,
                clientCreatedAt = ReportTimestamps.iso8601Utc(TEST_NOW),
                app = testApp(),
                description = ReportSanitizer.description("open /storage/emulated/0/secret.pdf"),
                nowMillis = TEST_NOW,
            )
            throw AssertionError("expected rejection")
        } catch (expected: ReportValidationException) {
            assertEquals("unsafe_description", expected.code)
        }
    }

    @Test
    fun timestampFormattingIsUtcAndStable() {
        assertEquals("2023-11-14T22:13:20.000Z", ReportTimestamps.iso8601Utc(TEST_NOW))
        assertEquals(TEST_NOW, ReportTimestamps.parseIso8601Utc("2023-11-14T22:13:20.000Z"))
        assertNull(ReportTimestamps.parseIso8601Utc("2023-11-14T22:13:20Z"))
        assertNull(ReportTimestamps.parseIso8601Utc("not-a-date"))
    }

    @Test
    fun validationErrorIsNullForAValidPayload() {
        assertNull(testProblem().validationError(TEST_NOW))
        assertNotNull(testDiagnostic().selfChecks)
        assertTrue(testDiagnostic().kind == ReportKind.DIAGNOSTIC_TEST)
    }
}
