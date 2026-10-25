package com.hmx.pdf.data.report

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportSanitizerTest {

    @Test
    fun controlCharactersAndExcessWhitespaceAreNormalized() {
        val result = ReportSanitizer.description("  hello\u0000\n\n\nworld\t  ")
        assertEquals("hello\n\nworld", result.text)
        assertFalse(result.truncated)
        assertTrue(result.safeToSubmit)
    }

    @Test
    fun pathsAndUrisAreFlaggedButNotSilentlyRewritten() {
        val result = ReportSanitizer.description("open content://docs/secret.pdf please")
        assertEquals("open content://docs/secret.pdf please", result.text)
        assertTrue(result.issues.contains(SanitizeIssue.PATH_OR_URI))
        assertFalse(result.safeToSubmit)
        assertFalse(result.redacted)
    }

    @Test
    fun documentAndMediaFilenamesAreFlagged() {
        for (text in listOf(
            "secret.pdf",
            "my report.docx",
            "photo.jpg",
            "recording.mp4",
        )) {
            val result = ReportSanitizer.description(text)
            assertFalse(text, result.safeToSubmit)
            assertTrue(text, result.issues.contains(SanitizeIssue.PATH_OR_URI))
        }
    }

    @Test
    fun sensitivePatternsAreFlagged() {
        for (text in listOf(
            "token=abc123",
            "password: hunter2",
            "Bearer abc.def.ghi",
            "api_key=deadbeef",
            "0123456789abcdef0123456789abcdef",
            "https://example.test/private",
            "mailto:someone@example.test",
            "someone@example.test",
            "+1 (555) 123-4567",
        )) {
            val result = ReportSanitizer.description(text)
            assertFalse(text, result.safeToSubmit)
            assertTrue(text, result.issues.isNotEmpty())
        }
    }

    @Test
    fun errorDetailsAreRedactedAutomatically() {
        val result = ReportSanitizer.errorDetail(
            "failed at /storage/emulated/0/secret.pdf token=abc123"
        )
        assertTrue(result.redacted)
        assertTrue(result.safeToSubmit)
        assertFalse(result.text.contains("/storage/"))
        assertFalse(result.text.contains("abc123"))
        assertTrue(result.text.contains("[redacted]"))
    }

    @Test
    fun truncationIsBoundedAndReported() {
        val result = ReportSanitizer.description("a".repeat(REPORT_MAX_DESCRIPTION_CHARS + 1))
        assertEquals(REPORT_MAX_DESCRIPTION_CHARS, result.text.length)
        assertTrue(result.truncated)
    }

    @Test
    fun blankTextIsNotSubmittable() {
        assertFalse(ReportSanitizer.description("   \n\t").safeToSubmit)
        assertFalse(ReportSanitizer.errorSummary("").safeToSubmit)
    }

    @Test
    fun sanitizerIsDeterministic() {
        val input = "line one\r\nline two\n\n\nline three"
        assertEquals(ReportSanitizer.description(input), ReportSanitizer.description(input))
        assertEquals(ReportSanitizer.errorDetail(input), ReportSanitizer.errorDetail(input))
    }

    @Test
    fun longWordsAndOrdinaryProseAreNotFlagged() {
        val result = ReportSanitizer.description("The export button did nothing after I rotated the screen.")
        assertTrue(result.safeToSubmit)
        assertTrue(result.issues.isEmpty())
    }
}
