package kr.geulbeot.hwp.model

import kr.geulbeot.hwp.record.HwpRecord
import kr.geulbeot.hwp.util.HwpUnit

/**
 * How a control character inside paragraph text is stored.
 *
 * This classification is the single most important detail in reading HWP body text: a paragraph's
 * character-shape table is indexed by position in the raw WCHAR array, so miscounting an eight-unit
 * control by even one slot shifts every subsequent run's formatting.
 */
enum class ControlCharKind(val wcharLength: Int) {
    /** Ordinary text. */
    NONE(1),

    /** Occupies a single WCHAR: line break, paragraph end, fixed-width space and friends. */
    CHAR(1),

    /** Occupies eight WCHARs and belongs to the text flow: tab, and the reserved inline slots. */
    INLINE(8),

    /** Occupies eight WCHARs and refers to a control record that follows the text record. */
    EXTENDED(8),
}

object HwpChar {
    private val KIND = Array(32) { ControlCharKind.CHAR }

    init {
        for (c in intArrayOf(4, 5, 6, 7, 8, 9, 19, 20)) KIND[c] = ControlCharKind.INLINE
        for (c in intArrayOf(1, 2, 3, 11, 12, 14, 15, 16, 17, 18, 21, 22, 23)) KIND[c] = ControlCharKind.EXTENDED
        for (c in intArrayOf(0, 10, 13, 24, 25, 26, 27, 28, 29, 30, 31)) KIND[c] = ControlCharKind.CHAR
    }

    fun kindOf(code: Int): ControlCharKind = if (code < 32) KIND[code] else ControlCharKind.NONE

    const val LINE_BREAK = 10
    const val PARA_BREAK = 13
    const val TAB = 9
    const val EXTENDED_OBJECT = 11
    const val HARD_SPACE = 30
    const val FIXED_WIDTH_SPACE = 31

    /** U+FFFC OBJECT REPLACEMENT CHARACTER - shown in the editor where an anchored object sits. */
    const val OBJECT_PLACEHOLDER = '￼'
}

/** One piece of a paragraph. Paragraph content is an ordered list of these. */
sealed interface ParaItem {
    var charShapeId: Int

    /** How many WCHARs this item occupies in the raw PARA_TEXT array. */
    val wcharLength: Int

    /**
     * How many characters this item contributes to the text a person sees and edits.
     *
     * This is deliberately different from [wcharLength]: a tab is eight code units on disk but one
     * character in the editor, and a section definition is eight code units and no characters at
     * all. Editing works in these coordinates; the file format works in the other.
     */
    val displayLength: Int
}

/** A run of ordinary characters sharing one character shape. */
data class TextSpan(
    var text: String,
    override var charShapeId: Int = 0,
) : ParaItem {
    override val wcharLength: Int get() = text.length
    override val displayLength: Int get() = text.length
}

/** A one-WCHAR control such as a line break or a fixed-width space. */
data class CharControlSpan(
    val code: Int,
    override var charShapeId: Int = 0,
) : ParaItem {
    override val wcharLength: Int get() = 1

    override val displayLength: Int
        get() = when (code) {
            HwpChar.LINE_BREAK, HwpChar.HARD_SPACE, HwpChar.FIXED_WIDTH_SPACE -> 1
            else -> 0
        }
}

/**
 * An eight-WCHAR control. [raw] keeps the original units so anything not modelled here survives a
 * save; [control] is set for extended controls whose record was parsed.
 */
data class ControlSpan(
    val code: Int,
    var raw: String,
    var ctrlId: String = "",
    var control: Control? = null,
    override var charShapeId: Int = 0,
) : ParaItem {
    override val wcharLength: Int get() = 8

    override val displayLength: Int get() = if (code == HwpChar.TAB || occupiesSpace) 1 else 0

    val isExtended: Boolean get() = HwpChar.kindOf(code) == ControlCharKind.EXTENDED

    /** Whether the editor should show an object placeholder here. */
    val occupiesSpace: Boolean get() = isExtended && (control?.occupiesSpace ?: true)

    /** Whether this control must survive the paragraph's text being replaced. */
    val isStructural: Boolean get() = control?.isStructural ?: false
}

/**
 * A paragraph.
 *
 * [sourceRecord] and [dirty] implement the preservation policy: a paragraph that the user never
 * touched is written back from its original records byte for byte, so parts of HWP 5.0 this app
 * does not understand are not quietly discarded on save.
 */
class Paragraph(
    var paraShapeId: Int = 0,
    var styleId: Int = 0,
) {
    val items: MutableList<ParaItem> = ArrayList()

    /** PARA_HEADER control mask/flags, preserved as read. */
    var controlMask: Long = 0
    var breakType: Int = 0
    var sourceRecord: HwpRecord? = null
    var dirty: Boolean = false

    /** Total WCHAR count, which is what PARA_CHAR_SHAPE positions are measured in. */
    val wcharLength: Int get() = items.sumOf { it.wcharLength }

    /** Length of [text], i.e. the editor's coordinate space. */
    val displayLength: Int get() = items.sumOf { it.displayLength }

    /** The text a person sees and edits. Controls become tabs, newlines or an object placeholder. */
    val text: String
        get() = buildString {
            for (item in items) {
                when (item) {
                    is TextSpan -> append(item.text)
                    is CharControlSpan -> when (item.code) {
                        HwpChar.LINE_BREAK -> append('\n')
                        HwpChar.HARD_SPACE, HwpChar.FIXED_WIDTH_SPACE -> append(' ')
                        else -> Unit
                    }
                    is ControlSpan -> when {
                        item.code == HwpChar.TAB -> append('\t')
                        item.occupiesSpace -> append(HwpChar.OBJECT_PLACEHOLDER)
                        else -> Unit
                    }
                }
            }
        }

    val isEmpty: Boolean
        get() = items.none { it is TextSpan && it.text.isNotEmpty() } &&
            controls().none { it.occupiesSpace }

    fun controls(): List<Control> = items.filterIsInstance<ControlSpan>().mapNotNull { it.control }

    fun charShapeIdAt(offset: Int): Int {
        var pos = 0
        for (item in items) {
            val end = pos + item.wcharLength
            if (offset < end) return item.charShapeId
            pos = end
        }
        return items.lastOrNull()?.charShapeId ?: 0
    }

    /**
     * Replaces all content with a single run of text.
     *
     * Structural controls stay: a section definition or a header is anchored in the first paragraph
     * of a section but is not part of what the user is typing over.
     */
    fun setPlainText(value: String, charShapeId: Int = this.items.firstOrNull()?.charShapeId ?: 0) {
        val structural = items.filterIsInstance<ControlSpan>().filter { it.isStructural }
        items.clear()
        items.addAll(structural)
        appendText(value, charShapeId)
        dirty = true
    }

    fun appendText(value: String, charShapeId: Int) {
        if (value.isEmpty()) return
        // Split on the characters that HWP stores as controls rather than as text.
        var start = 0
        for (i in value.indices) {
            when (value[i]) {
                '\n' -> {
                    if (i > start) items.add(TextSpan(value.substring(start, i), charShapeId))
                    items.add(CharControlSpan(HwpChar.LINE_BREAK, charShapeId))
                    start = i + 1
                }
                '\t' -> {
                    if (i > start) items.add(TextSpan(value.substring(start, i), charShapeId))
                    items.add(ControlSpan(HwpChar.TAB, tabRawUnits(), charShapeId = charShapeId))
                    start = i + 1
                }
            }
        }
        if (start < value.length) items.add(TextSpan(value.substring(start), charShapeId))
        dirty = true
    }

    /** Merges neighbouring text spans that share a character shape. Keeps saved files tidy. */
    fun normalise() {
        var i = 0
        while (i < items.size - 1) {
            val a = items[i]
            val b = items[i + 1]
            if (a is TextSpan && b is TextSpan && a.charShapeId == b.charShapeId) {
                a.text += b.text
                items.removeAt(i + 1)
            } else {
                i++
            }
        }
        items.removeAll { it is TextSpan && it.text.isEmpty() }
    }

    /**
     * A faithful copy, including the source record and dirty flag.
     *
     * Undo depends on this being faithful: restoring a snapshot must also restore whether the
     * paragraph had been edited, or an undone edit would still force its records to be regenerated.
     * Anchored objects are copied deeply so editing a table after a snapshot cannot reach back and
     * change what the snapshot holds.
     */
    fun deepCopy(): Paragraph {
        val p = Paragraph(paraShapeId, styleId)
        p.controlMask = controlMask
        p.breakType = breakType
        p.sourceRecord = sourceRecord
        p.dirty = dirty
        for (item in items) {
            p.items.add(
                when (item) {
                    is TextSpan -> item.copy()
                    is CharControlSpan -> item.copy()
                    is ControlSpan -> item.copy(control = item.control?.deepCopyControl())
                },
            )
        }
        return p
    }

    companion object {
        /**
         * A tab is stored as eight WCHARs: the control id, four information units, then the id
         * again. The information units are zero for a plain tab.
         */
        fun tabRawUnits(): String =
            HwpChar.TAB.toChar() + String(CharArray(6)) + HwpChar.TAB.toChar()
    }
}

// ---- controls ----------------------------------------------------------------------------------

/** Anything anchored in the text that is not text: tables, pictures, headers, footnotes, fields. */
sealed class Control {
    abstract val ctrlId: String

    /** The original record, kept so unmodelled controls survive a save unchanged. */
    var sourceRecord: HwpRecord? = null

    /**
     * True for objects that take up room on the page, which is what the editor marks with a
     * placeholder. Section definitions, headers, footnotes and fields are anchored in the text but
     * draw nothing where they sit, exactly as in 한글.
     */
    open val occupiesSpace: Boolean get() = false

    /**
     * True for controls that describe the document rather than its content. Clearing a paragraph's
     * text must not take these with it - losing a section definition would leave the document with
     * no page setup at all.
     */
    open val isStructural: Boolean get() = false

    /**
     * A copy deep enough that editing the original cannot change the copy.
     *
     * Only controls whose contents the editor can change need real copying; the rest are immutable
     * as far as this app is concerned and are shared.
     */
    open fun deepCopyControl(): Control = this
}

class TableControl : Control() {
    override val ctrlId: String get() = "tbl "
    override val occupiesSpace: Boolean get() = true
    var rowCount: Int = 0
    var columnCount: Int = 0
    var cellSpacing: Int = 0
    var insideMarginLeft: Int = HwpUnit.fromMm(1.0)
    var insideMarginRight: Int = HwpUnit.fromMm(1.0)
    var insideMarginTop: Int = 0
    var insideMarginBottom: Int = 0
    var borderFillId: Int = 0
    var width: Int = 0
    var height: Int = 0
    val rows: MutableList<TableRow> = ArrayList()

    fun cellAt(row: Int, column: Int): TableCell? =
        rows.getOrNull(row)?.cells?.firstOrNull { it.columnIndex == column }

    override fun deepCopyControl(): Control {
        val copy = TableControl()
        copy.sourceRecord = sourceRecord
        copy.rowCount = rowCount
        copy.columnCount = columnCount
        copy.cellSpacing = cellSpacing
        copy.insideMarginLeft = insideMarginLeft
        copy.insideMarginRight = insideMarginRight
        copy.insideMarginTop = insideMarginTop
        copy.insideMarginBottom = insideMarginBottom
        copy.borderFillId = borderFillId
        copy.width = width
        copy.height = height
        for (row in rows) {
            val rowCopy = TableRow()
            for (cell in row.cells) rowCopy.cells.add(cell.deepCopy())
            copy.rows.add(rowCopy)
        }
        return copy
    }
}

class TableRow {
    val cells: MutableList<TableCell> = ArrayList()
}

class TableCell {
    var columnIndex: Int = 0
    var rowIndex: Int = 0
    var columnSpan: Int = 1
    var rowSpan: Int = 1
    var width: Int = 0
    var height: Int = 0
    var marginLeft: Int = 0
    var marginRight: Int = 0
    var marginTop: Int = 0
    var marginBottom: Int = 0
    var borderFillId: Int = 0
    val paragraphs: MutableList<Paragraph> = ArrayList()

    val text: String get() = paragraphs.joinToString("\n") { it.text }

    fun deepCopy(): TableCell {
        val copy = TableCell()
        copy.columnIndex = columnIndex
        copy.rowIndex = rowIndex
        copy.columnSpan = columnSpan
        copy.rowSpan = rowSpan
        copy.width = width
        copy.height = height
        copy.marginLeft = marginLeft
        copy.marginRight = marginRight
        copy.marginTop = marginTop
        copy.marginBottom = marginBottom
        copy.borderFillId = borderFillId
        for (p in paragraphs) copy.paragraphs.add(p.deepCopy())
        return copy
    }
}

class PictureControl : Control() {
    override val ctrlId: String get() = "gso "
    override val occupiesSpace: Boolean get() = true
    var binDataId: Int = 0

    /**
     * The manifest item id an OWPML document uses to point at its picture data. The binary format
     * numbers binaries instead, so a document converted between the two carries whichever it has.
     */
    var binDataRef: String? = null
    var width: Int = HwpUnit.fromMm(50.0)
    var height: Int = HwpUnit.fromMm(50.0)
    var cropLeft: Int = 0
    var cropTop: Int = 0
    var cropRight: Int = 0
    var cropBottom: Int = 0
    var description: String = ""
}

class HeaderFooterControl(val isHeader: Boolean, var applyPage: Int = 0) : Control() {
    override val ctrlId: String get() = if (isHeader) "head" else "foot"
    override val isStructural: Boolean get() = true
    val paragraphs: MutableList<Paragraph> = ArrayList()
}

class NoteControl(val isFootnote: Boolean) : Control() {
    override val ctrlId: String get() = if (isFootnote) "fn  " else "en  "
    val paragraphs: MutableList<Paragraph> = ArrayList()
}

class HyperlinkControl(var target: String = "", var display: String = "") : Control() {
    override val ctrlId: String get() = "%hlk"
}

class SectionDefControl : Control() {
    override val ctrlId: String get() = "secd"
    override val isStructural: Boolean get() = true
    var pageDef: PageDef = PageDef()
}

/** A control whose record was read but whose contents this app does not interpret. */
class OpaqueControl(override val ctrlId: String) : Control() {
    override val occupiesSpace: Boolean get() = ctrlId in DRAWN_CTRL_IDS
    override val isStructural: Boolean get() = ctrlId in STRUCTURAL_CTRL_IDS

    companion object {
        /** Controls that draw something where they are anchored. */
        private val DRAWN_CTRL_IDS = setOf("gso ", "tbl ", "eqed", "\$rec", "\$ole")

        /** Controls that configure the section rather than sit in its text. */
        private val STRUCTURAL_CTRL_IDS = setOf("secd", "cold", "head", "foot")
    }
}

// ---- page and section ---------------------------------------------------------------------------

/** Page setup, i.e. 쪽 설정. All values are HWPUNIT. */
data class PageDef(
    var width: Int = HwpUnit.A4_WIDTH,
    var height: Int = HwpUnit.A4_HEIGHT,
    var marginLeft: Int = HwpUnit.fromMm(30.0),
    var marginRight: Int = HwpUnit.fromMm(30.0),
    var marginTop: Int = HwpUnit.fromMm(20.0),
    var marginBottom: Int = HwpUnit.fromMm(15.0),
    var marginHeader: Int = HwpUnit.fromMm(15.0),
    var marginFooter: Int = HwpUnit.fromMm(15.0),
    var marginGutter: Int = 0,
    var landscape: Boolean = false,
) {
    val contentWidth: Int get() = width - marginLeft - marginRight - marginGutter
    val contentHeight: Int get() = height - marginTop - marginBottom - marginHeader - marginFooter
}

class Section {
    var pageDef: PageDef = PageDef()
    val paragraphs: MutableList<Paragraph> = ArrayList()

    /** A copy of every paragraph, for the undo stack. */
    fun snapshotParagraphs(): List<Paragraph> = paragraphs.map { it.deepCopy() }

    /** Puts a snapshot back. The section itself keeps its identity so references stay valid. */
    fun restoreParagraphs(snapshot: List<Paragraph>) {
        paragraphs.clear()
        paragraphs.addAll(snapshot.map { it.deepCopy() })
    }

    /** Records read from this section's stream, used to write back what was not modified. */
    var sourceRecords: List<HwpRecord>? = null
    var dirty: Boolean = false
}

/**
 * Document metadata.
 *
 * Nothing here is filled in automatically from the device. Author and company are exactly what the
 * user typed or what the source file already carried - an editor that stamps a phone's owner name
 * into every file leaks something the user did not ask to share.
 */
data class DocumentSummary(
    var title: String = "",
    var subject: String = "",
    var author: String = "",
    var company: String = "",
    var keywords: String = "",
    var comments: String = "",
    var lastSavedBy: String = "",
)

data class DocumentProperties(
    var sectionCount: Int = 1,
    var startingPageNumber: Int = 1,
    var startingFootnoteNumber: Int = 1,
    var startingEndnoteNumber: Int = 1,
    var startingPictureNumber: Int = 1,
    var startingTableNumber: Int = 1,
    var startingEquationNumber: Int = 1,
    var caretListId: Int = 0,
    var caretParagraphId: Int = 0,
    var caretPosition: Int = 0,
)

/** Why a document could not be opened, when the reason is worth telling the user precisely. */
enum class DocumentRestriction(val message: String) {
    PASSWORD_PROTECTED(
        "암호가 설정된 문서입니다. 한글 PC판에서 암호를 해제한 뒤 다시 열어 주세요.",
    ),
    DISTRIBUTION(
        "배포용 문서입니다. 배포용 문서는 별도의 보호가 걸려 있어 이 앱에서 열 수 없습니다.",
    ),
}

class RestrictedDocumentException(val restriction: DocumentRestriction) : Exception(restriction.message)
