package com.hmx.pdf.docx

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Shared minimal DOCX fixtures for JVM tests: a valid package with a customizable body,
 * extra relationships/content-type overrides and arbitrary extra parts. Phase 4.0 introduced
 * this so integrity tests describe *what* they need instead of re-copying the zip scaffolding.
 */
internal object DocxFixtures {

    const val W_NS = "xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\""
    const val R_NS = "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\""
    const val REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    const val CT_MAIN = "application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"

    /** A minimal, valid DOCX in memory. [rels]/[contentTypes] entries are appended verbatim. */
    fun docx(
        body: String,
        rels: String = "",
        contentTypes: String = "",
        parts: Map<String, String> = emptyMap()
    ): ByteArray {
        val all = linkedMapOf(
            "[Content_Types].xml" to "<?xml version=\"1.0\"?>" +
                "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
                "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
                "<Override PartName=\"/word/document.xml\" ContentType=\"$CT_MAIN\"/>" + contentTypes + "</Types>",
            "_rels/.rels" to "<?xml version=\"1.0\"?>" +
                "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                "<Relationship Id=\"rId1\" Type=\"$REL/officeDocument\" Target=\"word/document.xml\"/></Relationships>",
            "word/_rels/document.xml.rels" to "<?xml version=\"1.0\"?>" +
                "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                rels + "</Relationships>",
            "word/document.xml" to "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
                "<w:document $W_NS><w:body>$body</w:body></w:document>"
        )
        for ((n, c) in parts) all[n] = c
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            for ((n, c) in all) { z.putNextEntry(ZipEntry(n)); z.write(c.toByteArray()); z.closeEntry() }
        }
        return bos.toByteArray()
    }

    fun read(bytes: ByteArray): Pair<DocxPackage, DocState> = DocxReader.read(bytes.inputStream())!!

    /** The text of one zip entry, or null when absent. */
    fun entry(bytes: ByteArray, name: String): String? {
        var found: String? = null
        java.util.zip.ZipInputStream(bytes.inputStream()).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                if (e.name == name) { found = z.readBytes().toString(Charsets.UTF_8); break }
            }
        }
        return found
    }

    /** Round-trips [state] through the sole writer against a temp source built from [bytes]. */
    fun save(bytes: ByteArray, pkg: DocxPackage, state: DocState, extras: DocxWriter.Extras = DocxWriter.Extras()): ByteArray {
        val src = java.io.File.createTempFile("src", ".docx").apply { writeBytes(bytes); deleteOnExit() }
        val out = java.io.File.createTempFile("out", ".docx").apply { deleteOnExit() }
        DocxWriter.write(pkg, state, src, out, extras)
        return out.readBytes()
    }
}
