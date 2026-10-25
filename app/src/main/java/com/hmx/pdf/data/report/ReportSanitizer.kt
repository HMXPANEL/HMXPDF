package com.hmx.pdf.data.report

enum class SanitizeIssue {
    PATH_OR_URI,
    SECRET_OR_TOKEN,
    CONTACT_INFO,
    LONG_OPAQUE_VALUE,
}

data class SanitizeResult(
    val text: String,
    val issues: Set<SanitizeIssue>,
    val truncated: Boolean,
    val redacted: Boolean,
) {
    /** Description text with issues is blocked; redacted error text can be submitted. */
    val safeToSubmit: Boolean get() = issues.isEmpty() && text.isNotBlank()
}

/**
 * Deterministic, side-effect-free text cleanup. It never reads PDFs, documents, logs, or
 * storage. User-authored descriptions are flagged rather than silently rewritten when they
 * look like they contain paths, URIs, or secrets; the preview can then require an edit.
 * Error summaries/details are not user prose, so they are redacted automatically.
 */
object ReportSanitizer {
    private data class SensitivePattern(val regex: Regex, val issue: SanitizeIssue)

    private val PATTERNS = listOf(
        SensitivePattern(Regex("(?i)\\b(?:content|file)://\\S+"), SanitizeIssue.PATH_OR_URI),
        SensitivePattern(Regex("(?i)\\bfile:\\S+"), SanitizeIssue.PATH_OR_URI),
        SensitivePattern(Regex("(?i)\\b(?:https?|ftp)://\\S+"), SanitizeIssue.PATH_OR_URI),
        SensitivePattern(Regex("(?i)\\b(?:mailto|tel):\\S+"), SanitizeIssue.PATH_OR_URI),
        SensitivePattern(
            Regex("(?i)(?:/storage/|/sdcard/|/mnt/|/data/)\\S*"),
            SanitizeIssue.PATH_OR_URI,
        ),
        SensitivePattern(
            Regex(
                "(?i)\\b[\\w .-]+\\.(?:pdf|docx?|xlsx?|pptx?|odt|ods|odp|txt|rtf|csv|" +
                    "png|jpe?g|gif|webp|heic|mp4|mov|zip)\\b"
            ),
            SanitizeIssue.PATH_OR_URI,
        ),
        SensitivePattern(Regex("(?i)android/(?:data|media)\\S*"), SanitizeIssue.PATH_OR_URI),
        SensitivePattern(Regex("\\b[A-Za-z]:\\\\[^\\s]+"), SanitizeIssue.PATH_OR_URI),
        SensitivePattern(
            Regex("(?i)\\bbearer\\s+[A-Za-z0-9._~+/=-]+"),
            SanitizeIssue.SECRET_OR_TOKEN,
        ),
        SensitivePattern(
            Regex("(?i)\\b(?:api[_-]?key|access[_-]?token|token|password|passwd|secret)\\s*[:=]\\s*\\S+"),
            SanitizeIssue.SECRET_OR_TOKEN,
        ),
        SensitivePattern(
            Regex("(?i)\\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}\\b"),
            SanitizeIssue.CONTACT_INFO,
        ),
        SensitivePattern(
            Regex("\\b\\+?\\d(?:[\\d .()-]*\\d){8,}\\b"),
            SanitizeIssue.CONTACT_INFO,
        ),
        SensitivePattern(Regex("\\b[0-9a-fA-F]{32,}\\b"), SanitizeIssue.LONG_OPAQUE_VALUE),
        SensitivePattern(
            Regex("\\b[A-Za-z0-9+/]{40,}={0,2}\\b"),
            SanitizeIssue.LONG_OPAQUE_VALUE,
        ),
    )

    fun description(input: String): SanitizeResult {
        val cleaned = clean(input, REPORT_MAX_DESCRIPTION_CHARS)
        return SanitizeResult(
            text = cleaned.text,
            issues = detect(cleaned.text),
            truncated = cleaned.truncated,
            redacted = false,
        )
    }

    fun errorSummary(input: String): SanitizeResult =
        redact(input, REPORT_MAX_ERROR_SUMMARY_CHARS)

    fun errorDetail(input: String): SanitizeResult =
        redact(input, REPORT_MAX_ERROR_DETAIL_CHARS)

    private data class Cleaned(val text: String, val truncated: Boolean)

    private fun clean(input: String, maxChars: Int): Cleaned {
        val withoutControls = input.filter { char ->
            char == '\n' || char == '\r' || char == '\t' || (char >= ' ' && char != '\u007F')
        }
        val collapsed = withoutControls
            .replace(Regex("\\r\\n?"), "\n")
            .replace(Regex("[\\t ]+"), " ")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
        val truncated = collapsed.length > maxChars
        return Cleaned(
            text = if (truncated) collapsed.take(maxChars).trimEnd() else collapsed,
            truncated = truncated,
        )
    }

    private fun detect(text: String): Set<SanitizeIssue> {
        val found = LinkedHashSet<SanitizeIssue>()
        for (pattern in PATTERNS) {
            if (pattern.regex.containsMatchIn(text)) found.add(pattern.issue)
        }
        return found
    }

    private fun redact(input: String, maxChars: Int): SanitizeResult {
        val cleaned = clean(input, maxChars)
        var text = cleaned.text
        for (pattern in PATTERNS) {
            text = pattern.regex.replace(text, "[redacted]")
        }
        val remainingIssues = detect(text)
        return SanitizeResult(
            text = text,
            issues = remainingIssues,
            truncated = cleaned.truncated,
            redacted = text != cleaned.text,
        )
    }
}
