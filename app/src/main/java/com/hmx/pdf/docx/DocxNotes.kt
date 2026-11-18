package com.hmx.pdf.docx

/**
 * `word/footnotes.xml` / `word/endnotes.xml`.
 *
 * Notes are immutable snapshots: every edit produces a new [DocxNotes], which is what lets the
 * editor's session snapshots undo a note together with its body reference. A part is only rewritten
 * when a note in it was added, edited or removed; untouched notes (and the reserved separator
 * entries) are spliced out of the original XML verbatim, so imported namespaces, unknown children
 * and numbering settings survive a save.
 *
 * Reserved separator ids (Word's -1/0 entries) are never allocated, edited or removed. New ids are
 * independent per part and start above the highest existing id.
 */
class DocxNote(
    val id: Int,
    val kind: DocxNotes.Kind,
    /** `separator`, `continuationSeparator` or `continuationNotice`: structural, never edited. */
    val separator: Boolean,
    val blocks: List<DocBlock>,
    /** The original start tag (attributes/prefixes preserved) or the generated one. */
    val head: String,
    /** The original element, for splicing; null for a note added this session. */
    val raw: String? = null,
    val edited: Boolean = false,
    val removed: Boolean = false,
    val added: Boolean = false
) {
    fun withBlocks(blocks: List<DocBlock>) = DocxNote(
        id, kind, separator, blocks, head, raw, edited = true, removed = false, added = added
    )

    fun markedRemoved() = DocxNote(id, kind, separator, blocks, head, raw, edited, true, added)
}

class DocxNotes(
    val footnotePath: String?,
    val footnoteXml: String?,
    val endnotePath: String?,
    val endnoteXml: String?,
    val footnotes: List<DocxNote>,
    val endnotes: List<DocxNote>
) {
    enum class Kind { FOOTNOTE, ENDNOTE }

    fun pathOf(kind: Kind): String? = if (kind == Kind.FOOTNOTE) footnotePath else endnotePath

    fun xmlOf(kind: Kind): String? = if (kind == Kind.FOOTNOTE) footnoteXml else endnoteXml

    fun notes(kind: Kind): List<DocxNote> = if (kind == Kind.FOOTNOTE) footnotes else endnotes

    fun withNotes(kind: Kind, list: List<DocxNote>): DocxNotes =
        if (kind == Kind.FOOTNOTE) DocxNotes(footnotePath, footnoteXml, endnotePath, endnoteXml, list, endnotes)
        else DocxNotes(footnotePath, footnoteXml, endnotePath, endnoteXml, footnotes, list)

    /** Content notes (separators excluded) in document order. */
    fun visible(kind: Kind): List<DocxNote> = notes(kind).filter { !it.separator && !it.removed }

    /** True when this kind's part has an unsaved addition, edit or removal. */
    fun touched(kind: Kind): Boolean = notes(kind).any { it.added || it.edited || it.removed }

    val modified: Boolean get() = touched(Kind.FOOTNOTE) || touched(Kind.ENDNOTE)

    /**
     * The part's XML after this snapshot's edits, or null when the part is untouched (a null result
     * means "leave the original zip entry alone").
     */
    fun document(kind: Kind): String? {
        if (!touched(kind)) return null
        val base = xmlOf(kind)
        val list = notes(kind)
        val removed = list.filter { it.removed && it.raw != null }
        val edited = list.filter { it.edited && !it.added && !it.removed && it.raw != null }
        val added = list.filter { it.added && !it.removed }
        val head = base ?: run {
            if (added.isEmpty()) return null
            return newPart(kind, added)
        }
        val root = XmlScan.scan(head).children.firstOrNull() ?: return null
        // Original spans, so splices never disturb each other. Descending order keeps offsets valid.
        val splices = ArrayList<Pair<XNode, DocxNote>>()
        for (n in root.children) {
            val id = n.attr(head, "id")?.toIntOrNull() ?: continue
            val note = removed.firstOrNull { it.id == id } ?: edited.firstOrNull { it.id == id }
            if (note != null) splices.add(n to note)
        }
        var out: String = head
        for ((node, note) in splices.sortedByDescending { it.first.start }) {
            val text = if (note.removed) "" else serialize(note)
            out = out.substring(0, node.start) + text + out.substring(node.end)
        }
        if (added.isNotEmpty()) {
            val at = out.lastIndexOf("</")
            if (at > 0) out = out.substring(0, at) + added.joinToString("") { serialize(it) } + out.substring(at)
        }
        return out
    }

    companion object {
        private const val W_NS = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"

        val EMPTY = DocxNotes(null, null, null, null, emptyList(), emptyList())

        fun parse(
            footnotePath: String?,
            footnoteXml: String?,
            endnotePath: String?,
            endnoteXml: String?
        ): DocxNotes = DocxNotes(
            footnotePath, footnoteXml, endnotePath, endnoteXml,
            parsePart(footnoteXml, Kind.FOOTNOTE), parsePart(endnoteXml, Kind.ENDNOTE)
        )

        private fun parsePart(xml: String?, kind: Kind): List<DocxNote> {
            if (xml == null) return emptyList()
            val root = XmlScan.scan(xml).children.firstOrNull() ?: return emptyList()
            val element = if (kind == Kind.FOOTNOTE) "footnote" else "endnote"
            val out = ArrayList<DocxNote>()
            for (n in root.childrenNamed(element)) {
                val id = n.attr(xml, "id")?.toIntOrNull() ?: continue
                val type = n.attr(xml, "type")
                val separator = type != null || id < 0
                val blocks = if (n.end > n.startTagEnd) DocxReader.Ctx(xml).container(n.children, readOnly = false)
                else emptyList()
                out.add(DocxNote(id, kind, separator, blocks, n.startTag(xml), n.text(xml)))
            }
            return out
        }

        /** The default path for a part that does not exist yet. */
        fun defaultPath(kind: Kind, mainDir: String): String {
            val name = if (kind == Kind.FOOTNOTE) "footnotes.xml" else "endnotes.xml"
            return if (mainDir.isEmpty()) name else "$mainDir/$name"
        }

        /** Marks the run that lives in the body and points at [id]. */
        fun reference(kind: Kind, id: Int): CharFmt {
            val ref = if (kind == Kind.FOOTNOTE) "footnoteReference" else "endnoteReference"
            return CharFmt(
                "", null,
                "<w:r><w:rPr><w:vertAlign w:val=\"superscript\"/></w:rPr><w:$ref w:id=\"$id\"/></w:r>"
            )
        }

        /** Appends the reference run to [p] (the VM has no caret range for notes, like comments). */
        fun anchorPara(p: DocPara, kind: Kind, id: Int): DocPara =
            p.edited(text = p.text + OBJ_CHAR, fmts = p.fmts + reference(kind, id))

        /** The next free id in this part: above every existing one, never below 1. */
        fun nextId(notes: DocxNotes, kind: Kind): Int =
            (notes.notes(kind).filter { !it.removed }.maxOfOrNull { it.id } ?: 0).coerceAtLeast(0) + 1

        /**
         * Adds a note with one paragraph. The number mark is intrinsic (`w:footnoteRef`), not a
         * style reference, so the note renders even when the document has no note styles.
         */
        fun add(notes: DocxNotes, kind: Kind, text: String): Pair<DocxNotes, Int> {
            val id = nextId(notes, kind)
            val element = if (kind == Kind.FOOTNOTE) "footnote" else "endnote"
            val mark = if (kind == Kind.FOOTNOTE) "footnoteRef" else "endnoteRef"
            val markFmt = CharFmt(
                "", null,
                "<w:r><w:rPr><w:vertAlign w:val=\"superscript\"/></w:rPr><w:$mark/></w:r>"
            )
            // The number mark is a non-editable object char, so typing never splits or drops it.
            val para = DocPara(
                id = DocIds.next(), origin = null, openTag = "<w:p>", pPr = "", extras = "",
                text = OBJ_CHAR + text, fmts = listOf(markFmt) + List(text.length) { CharFmt.Plain },
                editable = true
            )
            val blocks = listOf(para, DocPara(
                id = DocIds.next(), origin = null, openTag = "<w:p>", pPr = "", extras = "",
                text = "", fmts = emptyList(), editable = true
            ))
            val note = DocxNote(
                id = id, kind = kind, separator = false, blocks = blocks,
                head = "<w:$element w:id=\"$id\">", added = true
            )
            return notes.withNotes(kind, notes.notes(kind) + note) to id
        }

        /** Flags a note for deletion; null when it is missing or a reserved separator. */
        fun remove(notes: DocxNotes, kind: Kind, id: Int): DocxNotes? {
            val list = notes.notes(kind)
            val i = list.indexOfFirst { it.id == id }
            if (i < 0 || list[i].separator) return null
            val next = if (list[i].added) list.filterIndexed { j, _ -> j != i }
            else list.toMutableList().also { it[i] = it[i].markedRemoved() }
            return notes.withNotes(kind, next)
        }

        /** Replaces one note's blocks; null when it is missing or a reserved separator. */
        fun edit(notes: DocxNotes, kind: Kind, id: Int, blocks: List<DocBlock>): DocxNotes? {
            val list = notes.notes(kind)
            val i = list.indexOfFirst { it.id == id }
            if (i < 0 || list[i].separator || list[i].removed) return null
            return notes.withNotes(kind, list.toMutableList().also { it[i] = it[i].withBlocks(blocks) })
        }

        /** Removes every reference run of [id] from editable paragraphs, or null when it isn't there. */
        fun stripReferences(blocks: List<DocBlock>, kind: Kind, id: Int): List<DocBlock>? {
            val hit = Regex("w:${if (kind == Kind.FOOTNOTE) "footnote" else "endnote"}Reference w:id=\"$id\"")
            val ids = ArrayList<Long>()
            // Only containers replacePara can rebuild: DocRaw content is never edited in place.
            fun walk(list: List<DocBlock>) {
                for (b in list) when (b) {
                    is DocPara -> if (b.editable && b.fmts.any { f -> f.obj?.let { hit.containsMatchIn(it) } == true }) ids.add(b.id)
                    is DocTable -> for (row in b.rows) for (cell in row.cells) walk(cell.blocks)
                    is DocRaw -> {}
                }
            }
            walk(blocks)
            if (ids.isEmpty()) return null
            var out = blocks
            for (paraId in ids) {
                val p = findPara(out, paraId) ?: continue
                val drop = (0 until p.text.length)
                    .filter { p.fmts[it].obj?.let { o -> hit.containsMatchIn(o) } == true }
                    .toSet()
                if (drop.isEmpty()) continue
                val keepText = StringBuilder(p.text.length)
                val keepFmts = ArrayList<CharFmt>(p.text.length)
                for (i in 0 until p.text.length) {
                    if (i in drop) continue
                    keepText.append(p.text[i]); keepFmts.add(p.fmts[i])
                }
                val marks = p.marks.map { m -> m - drop.count { it < m } }.distinct()
                val np = p.edited(text = keepText.toString(), fmts = keepFmts, marks = marks)
                out = replacePara(out, np) ?: out
            }
            return out
        }

        /** True when any paragraph or raw block still carries a reference to [id]. */
        fun hasReference(blocks: List<DocBlock>, kind: Kind, id: Int): Boolean {
            val needle = "w:${if (kind == Kind.FOOTNOTE) "footnote" else "endnote"}Reference w:id=\"$id\""
            var found = false
            forEachPara(blocks) { p -> if (p.fmts.any { f -> f.obj?.contains(needle) == true }) found = true }
            fun raw(list: List<DocBlock>) {
                for (b in list) when (b) {
                    is DocPara -> {}
                    is DocTable -> for (row in b.rows) for (cell in row.cells) raw(cell.blocks)
                    is DocRaw -> if (b.xml.contains(needle)) found = true
                }
            }
            raw(blocks)
            return found
        }

        private fun newPart(kind: Kind, notes: List<DocxNote>): String {
            val element = if (kind == Kind.FOOTNOTE) "footnote" else "endnote"
            val body = StringBuilder()
            if (kind == Kind.FOOTNOTE) {
                body.append("<w:footnote w:type=\"separator\" w:id=\"-1\"><w:p><w:r><w:separator/></w:r></w:p></w:footnote>")
                body.append("<w:footnote w:type=\"continuationSeparator\" w:id=\"0\"><w:p><w:r><w:continuationSeparator/></w:r></w:p></w:footnote>")
            } else {
                body.append("<w:endnote w:type=\"separator\" w:id=\"-1\"><w:p><w:r><w:separator/></w:r></w:p></w:endnote>")
                body.append("<w:endnote w:type=\"continuationSeparator\" w:id=\"0\"><w:p><w:r><w:continuationSeparator/></w:r></w:p></w:endnote>")
            }
            for (n in notes) body.append(serialize(n))
            return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
                "<w:${element}s xmlns:w=\"$W_NS\">$body</w:${element}s>"
        }

        private fun serialize(note: DocxNote): String {
            val sb = StringBuilder()
            val open = if (note.head.endsWith("/>")) note.head.dropLast(2).trimEnd() + ">" else note.head
            sb.append(open)
            DocxWriter.blocks(note.blocks, sb)
            sb.append("</").append(tagName(open)).append('>')
            return sb.toString()
        }

        private fun tagName(openTag: String): String =
            openTag.drop(1).takeWhile { !it.isWhitespace() && it != '>' && it != '/' }
    }
}
