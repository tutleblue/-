package kr.geulbeot.hwp.model

import kr.geulbeot.hwp.record.HwpRecord
import kr.geulbeot.hwp.util.HwpUnit

/**
 * A whole document, independent of the format it came from.
 *
 * Both the `.hwp` and `.hwpx` readers produce this, and both writers consume it, which is what
 * makes "open as one, save as the other" work without a conversion step of its own.
 *
 * The `source*` fields carry everything the readers did not interpret. They are the mechanism
 * behind this app's preservation policy: saving back to the same format reuses the original bytes
 * for every part of the document the user did not touch.
 */
class HwpDocument {

    var format: DocumentFormat = DocumentFormat.HWPX
    var properties: DocumentProperties = DocumentProperties()
    var summary: DocumentSummary = DocumentSummary()

    /** Version of the source file, e.g. 5.1.1.0 written as 0x05010100. */
    var version: Int = DEFAULT_VERSION
    var compressed: Boolean = true

    /**
     * HWP keeps seven parallel font tables, one per script (Hangul, Latin, Hanja, Japanese, other,
     * symbol, user), and a character shape names a font id *per script*. 한글 itself writes the same
     * list into all seven, and this app keeps them in step, but they are stored separately because a
     * document produced elsewhere is entitled to differ.
     */
    val fontTables: List<MutableList<FontFace>> = List(FontLanguage.COUNT) { ArrayList<FontFace>() }

    /** The Hangul font table, which is the one the editor's font picker works with. */
    val fontFaces: MutableList<FontFace> get() = fontTables[FontLanguage.HANGUL.index]
    val charShapes: MutableList<CharShape> = ArrayList()
    val paraShapes: MutableList<ParaShape> = ArrayList()
    val styles: MutableList<DocStyle> = ArrayList()
    val borderFills: MutableList<BorderFill> = ArrayList()
    val binData: MutableList<BinDataEntry> = ArrayList()
    val sections: MutableList<Section> = ArrayList()

    /** Parsed DocInfo record tree, kept verbatim for save-back. */
    var docInfoRecords: List<HwpRecord>? = null

    /** Raw container streams (`.hwp`) or zip entries (`.hwpx`) from the file that was opened. */
    var sourceStreams: Map<String, ByteArray>? = null

    /** Set when any shape table changed, so the writer knows DocInfo must be regenerated. */
    var docInfoDirty: Boolean = false

    val isDirty: Boolean
        get() = docInfoDirty || sections.any { section ->
            section.dirty || section.paragraphs.any { it.dirty }
        }

    fun markClean() {
        docInfoDirty = false
        for (s in sections) {
            s.dirty = false
            for (p in s.paragraphs) p.dirty = false
        }
    }

    // ---- text ------------------------------------------------------------------------------

    /** Every paragraph of body text, including the text inside table cells. */
    fun plainText(): String = buildString {
        for ((si, section) in sections.withIndex()) {
            if (si > 0) append('\n')
            for (p in section.paragraphs) {
                append(paragraphText(p))
                append('\n')
            }
        }
    }.trimEnd('\n')

    private fun paragraphText(p: Paragraph): String {
        val nested = p.controls().filterIsInstance<TableControl>()
        if (nested.isEmpty()) return p.text
        // A table's own text is not in the paragraph's WCHAR array, so splice it in for search,
        // word counts and plain-text export.
        return buildString {
            append(p.text)
            for (t in nested) {
                for (row in t.rows) {
                    append('\n')
                    append(row.cells.joinToString("\t") { it.text.replace('\n', ' ') })
                }
            }
        }
    }

    fun statistics(): DocumentStatistics {
        var paragraphs = 0
        var characters = 0
        var charactersNoSpace = 0
        var words = 0
        for (section in sections) {
            for (p in section.paragraphs) {
                paragraphs++
                val t = paragraphText(p)
                characters += t.length
                charactersNoSpace += t.count { !it.isWhitespace() }
                words += t.split(Regex("\\s+")).count { it.isNotBlank() }
            }
        }
        return DocumentStatistics(
            sections = sections.size,
            paragraphs = paragraphs,
            characters = characters,
            charactersWithoutSpaces = charactersNoSpace,
            words = words,
            tables = sections.sumOf { s -> s.paragraphs.sumOf { p -> p.controls().count { it is TableControl } } },
            pictures = sections.sumOf { s -> s.paragraphs.sumOf { p -> p.controls().count { it is PictureControl } } },
        )
    }

    // ---- shape tables ------------------------------------------------------------------------

    /** Returns the id of an existing identical character shape, or appends a new one. */
    fun ensureCharShape(shape: CharShape): Int {
        val signature = shape.signature()
        val existing = charShapes.indexOfFirst { it.signature() == signature }
        if (existing >= 0) return existing
        charShapes.add(shape.copyShape())
        docInfoDirty = true
        return charShapes.size - 1
    }

    fun ensureParaShape(shape: ParaShape): Int {
        val signature = shape.signature()
        val existing = paraShapes.indexOfFirst { it.signature() == signature }
        if (existing >= 0) return existing
        paraShapes.add(shape.copy())
        docInfoDirty = true
        return paraShapes.size - 1
    }

    /**
     * Returns the id of a font by name, adding it to every language table if it is new.
     *
     * The font is appended at the same index in all seven tables so a single id is valid whichever
     * script a run of text turns out to be.
     */
    fun ensureFont(name: String): Int {
        val existing = fontFaces.indexOfFirst { it.name == name }
        if (existing >= 0) return existing
        val target = fontFaces.size
        for (table in fontTables) {
            while (table.size < target) table.add(FontFace(fontFaces.getOrNull(table.size)?.name ?: name))
            table.add(FontFace(name))
        }
        docInfoDirty = true
        return target
    }

    fun charShapeOrDefault(id: Int): CharShape =
        charShapes.getOrNull(id) ?: charShapes.firstOrNull() ?: CharShape()

    fun paraShapeOrDefault(id: Int): ParaShape =
        paraShapes.getOrNull(id) ?: paraShapes.firstOrNull() ?: ParaShape()

    fun fontName(id: Int, language: FontLanguage = FontLanguage.HANGUL): String =
        fontTables[language.index].getOrNull(id)?.name
            ?: fontFaces.getOrNull(id)?.name
            ?: DEFAULT_BODY_FONT

    fun nextBinDataId(): Int = (binData.maxOfOrNull { it.id } ?: 0) + 1

    fun addPicture(bytes: ByteArray, extension: String): BinDataEntry {
        val entry = BinDataEntry(
            id = nextBinDataId(),
            kind = BinDataKind.EMBEDDING,
            extension = extension.lowercase().removePrefix("."),
            data = bytes,
        )
        binData.add(entry)
        docInfoDirty = true
        return entry
    }

    companion object {
        /** HWP 5.1.1.0, the version 한글 2014 and later write. */
        const val DEFAULT_VERSION = 0x05010100

        const val DEFAULT_BODY_FONT = "함초롬바탕"
        const val DEFAULT_HEADING_FONT = "함초롬돋움"

        /** Fonts 한글 ships with, offered in the font picker. */
        val COMMON_FONTS: List<String> = listOf(
            "함초롬바탕", "함초롬돋움", "바탕", "돋움", "굴림", "궁서",
            "맑은 고딕", "나눔고딕", "나눔명조", "HY헤드라인M", "HY견고딕", "휴먼명조",
        )

        /**
         * A new, empty document: one A4 section holding one empty paragraph, with the default
         * styles 한글 itself creates. No author, no title - see [DocumentSummary].
         */
        fun blank(): HwpDocument = HwpDocument().apply {
            format = DocumentFormat.HWPX
            val bodyFont = ensureFont(DEFAULT_BODY_FONT)
            ensureFont(DEFAULT_HEADING_FONT)

            borderFills.add(BorderFill(id = 0))

            val bodyChar = CharShape().apply {
                setFontForAllLanguages(bodyFont)
                height = HwpUnit.fontHeightFromPoint(10.0)
            }
            charShapes.add(bodyChar)

            val bodyPara = ParaShape(
                align = ParaAlign.JUSTIFY,
                lineSpacingKind = LineSpacingKind.PERCENT,
                lineSpacing = 160,
            )
            paraShapes.add(bodyPara)

            styles.add(DocStyle(name = "바탕글", englishName = "Normal", paraShapeId = 0, charShapeId = 0))

            val section = Section()
            section.paragraphs.add(Paragraph(paraShapeId = 0, styleId = 0).apply { items.add(TextSpan("", 0)) })
            sections.add(section)
            docInfoDirty = true
        }
    }
}

data class DocumentStatistics(
    val sections: Int,
    val paragraphs: Int,
    val characters: Int,
    val charactersWithoutSpaces: Int,
    val words: Int,
    val tables: Int,
    val pictures: Int,
)
