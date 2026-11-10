package kr.geulbeot.hwp.hwpx

import kr.geulbeot.hwp.model.BinDataEntry
import kr.geulbeot.hwp.model.BinDataKind
import kr.geulbeot.hwp.model.BorderFill
import kr.geulbeot.hwp.model.CharControlSpan
import kr.geulbeot.hwp.model.CharShape
import kr.geulbeot.hwp.model.Control
import kr.geulbeot.hwp.model.ControlSpan
import kr.geulbeot.hwp.model.DocStyle
import kr.geulbeot.hwp.model.DocumentFormat
import kr.geulbeot.hwp.model.FontFace
import kr.geulbeot.hwp.model.FontLanguage
import kr.geulbeot.hwp.model.HwpChar
import kr.geulbeot.hwp.model.HwpDocument
import kr.geulbeot.hwp.model.LineSpacingKind
import kr.geulbeot.hwp.model.OpaqueControl
import kr.geulbeot.hwp.model.PageDef
import kr.geulbeot.hwp.model.ParaShape
import kr.geulbeot.hwp.model.Paragraph
import kr.geulbeot.hwp.model.PictureControl
import kr.geulbeot.hwp.model.Section
import kr.geulbeot.hwp.model.SectionDefControl
import kr.geulbeot.hwp.model.StrikeOutKind
import kr.geulbeot.hwp.model.TableCell
import kr.geulbeot.hwp.model.TableControl
import kr.geulbeot.hwp.model.TableRow
import kr.geulbeot.hwp.model.TextSpan
import kr.geulbeot.hwp.util.HwpFormatException
import kr.geulbeot.hwp.xml.XmlNode
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/**
 * Reads a `.hwpx` document - the OWPML format standardised as KS X 6101 - into the shared model.
 *
 * A `.hwpx` is a ZIP of XML parts, so unlike the binary format there is no compression layer or
 * record framing to get right; the work is in the vocabulary.
 */
object HwpxReader {

    fun read(bytes: ByteArray): HwpDocument {
        val entries = readZip(bytes)

        val mimetype = entries[Owpml.ENTRY_MIMETYPE]?.toString(Charsets.US_ASCII)?.trim()
        if (mimetype != null && mimetype != Owpml.MIMETYPE) {
            throw HwpFormatException("한글 표준 문서(.hwpx)가 아닙니다. (mimetype: $mimetype)")
        }
        if (!entries.containsKey(Owpml.ENTRY_HEADER)) {
            throw HwpFormatException("문서에 Contents/header.xml이 없습니다. 한글 표준 문서(.hwpx)가 아닙니다.")
        }

        val document = HwpDocument()
        document.format = DocumentFormat.HWPX
        document.sourceStreams = entries

        readHeader(document, XmlNode.parse(entries.getValue(Owpml.ENTRY_HEADER)))
        entries[Owpml.ENTRY_CONTENT]?.let { readPackage(document, XmlNode.parse(it)) }
        readBinData(document, entries)

        val sectionNames = entries.keys
            .filter { it.startsWith(Owpml.PREFIX_SECTION) && it.endsWith(".xml") }
            .sortedBy { it.removePrefix(Owpml.PREFIX_SECTION).removeSuffix(".xml").toIntOrNull() ?: Int.MAX_VALUE }
        for (name in sectionNames) {
            document.sections.add(readSection(XmlNode.parse(entries.getValue(name))))
        }
        if (document.sections.isEmpty()) document.sections.add(Section())

        document.markClean()
        return document
    }

    /** A fast path for document lists: OWPML keeps a plain-text preview alongside the content. */
    fun readPreviewText(bytes: ByteArray): String? = try {
        readZip(bytes)[Owpml.ENTRY_PREVIEW_TEXT]?.toString(Charsets.UTF_8)
    } catch (_: Exception) {
        null
    }

    fun looksLikeHwpx(bytes: ByteArray): Boolean = try {
        val entries = readZip(bytes)
        entries.containsKey(Owpml.ENTRY_HEADER) || entries[Owpml.ENTRY_MIMETYPE]
            ?.toString(Charsets.US_ASCII)?.trim() == Owpml.MIMETYPE
    } catch (_: Exception) {
        false
    }

    /**
     * Total uncompressed size a `.hwpx` may expand to.
     *
     * A ZIP can claim to be a few kilobytes and expand to gigabytes. Opening a document should not
     * be able to take the app down, so the archive is read under a ceiling no real document reaches.
     */
    const val MAX_ARCHIVE_BYTES: Long = 512L * 1024 * 1024

    fun readZip(bytes: ByteArray): LinkedHashMap<String, ByteArray> {
        val entries = LinkedHashMap<String, ByteArray>()
        var total = 0L
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                // Reject paths that would escape the archive if anything ever writes them out.
                val name = entry.name
                if (name.startsWith("/") || name.startsWith("\\") || name.contains("..")) continue
                val data = zip.readBytes()
                total += data.size
                if (total > MAX_ARCHIVE_BYTES) {
                    throw HwpFormatException(
                        "문서의 압축을 푸는 중 크기가 비정상적으로 커졌습니다. 손상되었거나 안전하지 않은 파일일 수 있습니다.",
                    )
                }
                entries[name] = data
            }
        }
        return entries
    }

    // ---- header.xml ----------------------------------------------------------------------------

    private fun readHeader(document: HwpDocument, head: XmlNode) {
        head.child("beginNum")?.let { begin ->
            document.properties = document.properties.copy(
                startingPageNumber = begin.int("page", 1),
                startingFootnoteNumber = begin.int("footnote", 1),
                startingEndnoteNumber = begin.int("endnote", 1),
                startingPictureNumber = begin.int("pic", 1),
                startingTableNumber = begin.int("tbl", 1),
                startingEquationNumber = begin.int("equation", 1),
            )
        }

        val refList = head.child("refList") ?: return

        refList.child("fontfaces")?.childrenNamed("fontface")?.forEach { faceGroup ->
            val language = Owpml.languageOf(faceGroup.attr("lang"))
            val table = document.fontTables[language.index]
            for (font in faceGroup.childrenNamed("font")) {
                val id = font.int("id", table.size)
                while (table.size <= id) table.add(FontFace(""))
                table[id] = FontFace(
                    name = font.attr("face").orEmpty(),
                    type = when (font.attr("type")?.uppercase()) {
                        "TTF" -> 1
                        "HFT" -> 2
                        else -> 0
                    },
                    substituteName = font.child("substFont")?.attr("face"),
                )
            }
        }

        refList.child("borderFills")?.childrenNamed("borderFill")?.forEach { fill ->
            // OWPML numbers border fills from 1; the model indexes from 0 like the binary format.
            val id = fill.int("id", document.borderFills.size + 1) - 1
            while (document.borderFills.size <= id) document.borderFills.add(BorderFill(document.borderFills.size))
            val background = fill.child("fillBrush")?.child("winBrush")?.attr("faceColor")
            document.borderFills[id] = BorderFill(
                id = id,
                backgroundColor = background?.takeIf { !it.equals("none", true) }?.let { Owpml.colorOf(it, 0xFFFFFF) },
            )
        }

        refList.child("charProperties")?.childrenNamed("charPr")?.forEach { node ->
            val id = node.int("id", document.charShapes.size)
            while (document.charShapes.size <= id) document.charShapes.add(CharShape())
            document.charShapes[id] = readCharPr(node)
        }

        refList.child("paraProperties")?.childrenNamed("paraPr")?.forEach { node ->
            val id = node.int("id", document.paraShapes.size)
            while (document.paraShapes.size <= id) document.paraShapes.add(ParaShape())
            document.paraShapes[id] = readParaPr(node)
        }

        refList.child("styles")?.childrenNamed("style")?.forEach { node ->
            val id = node.int("id", document.styles.size)
            while (document.styles.size <= id) document.styles.add(DocStyle(name = ""))
            document.styles[id] = DocStyle(
                name = node.attr("name").orEmpty(),
                englishName = node.attr("engName").orEmpty(),
                paraShapeId = node.int("paraPrIDRef"),
                charShapeId = node.int("charPrIDRef"),
                type = if (node.attr("type").equals("CHAR", true)) 1 else 0,
                nextStyleId = node.int("nextStyleIDRef"),
                languageId = node.int("langID", 0x0412),
                lockForm = node.int("lockForm"),
            )
        }
    }

    private fun readCharPr(node: XmlNode): CharShape {
        val shape = CharShape()
        shape.height = node.int("height", 1000)
        shape.textColor = Owpml.colorOf(node.attr("textColor"), 0x000000)
        shape.shadeColor = Owpml.colorOf(node.attr("shadeColor"), 0xFFFFFF)
        shape.borderFillId = node.int("borderFillIDRef")

        node.child("fontRef")?.let { ref ->
            for (language in FontLanguage.entries) {
                shape.fontIds[language.index] = ref.int(Owpml.languageAttribute(language))
            }
        }
        node.child("ratio")?.let { r ->
            for (language in FontLanguage.entries) {
                shape.widthRatios[language.index] = r.int(Owpml.languageAttribute(language), 100)
            }
        }
        node.child("spacing")?.let { r ->
            for (language in FontLanguage.entries) {
                shape.spacings[language.index] = r.int(Owpml.languageAttribute(language))
            }
        }
        node.child("relSz")?.let { r ->
            for (language in FontLanguage.entries) {
                shape.relativeSizes[language.index] = r.int(Owpml.languageAttribute(language), 100)
            }
        }
        node.child("offset")?.let { r ->
            for (language in FontLanguage.entries) {
                shape.charOffsets[language.index] = r.int(Owpml.languageAttribute(language))
            }
        }

        shape.bold = node.child("bold") != null
        shape.italic = node.child("italic") != null
        shape.emboss = node.child("emboss") != null
        shape.engrave = node.child("engrave") != null
        shape.superscript = node.child("supscript") != null
        shape.subscript = node.child("subscript") != null
        node.child("outline")?.let { shape.outline = if (it.attr("type").equals("NONE", true)) 0 else 1 }
        node.child("shadow")?.let { shape.shadow = if (it.attr("type").equals("NONE", true)) 0 else 1 }
        node.child("underline")?.let { u ->
            shape.underline = Owpml.underlineOf(u.attr("type"))
            shape.underlineStyle = Owpml.lineStyleOf(u.attr("shape"))
            shape.underlineColor = Owpml.colorOf(u.attr("color"), 0x000000)
        }
        node.child("strikeout")?.let { s ->
            val shapeName = s.attr("shape")
            shape.strikeOut = if (shapeName == null || shapeName.equals("NONE", true)) {
                StrikeOutKind.NONE
            } else {
                StrikeOutKind.SINGLE
            }
            shape.strikeOutStyle = Owpml.lineStyleOf(shapeName)
            shape.strikeOutColor = Owpml.colorOf(s.attr("color"), 0x000000)
        }
        return shape
    }

    private fun readParaPr(node: XmlNode): ParaShape {
        val shape = ParaShape()
        shape.tabDefId = node.int("tabPrIDRef")
        shape.condense = node.int("condense")
        node.child("align")?.let { shape.align = Owpml.alignOf(it.attr("horizontal")) }
        node.child("margin")?.let { margin ->
            shape.indent = margin.child("intent")?.int("value") ?: 0
            shape.marginLeft = margin.child("left")?.int("value") ?: 0
            shape.marginRight = margin.child("right")?.int("value") ?: 0
            shape.spaceBefore = margin.child("prev")?.int("value") ?: 0
            shape.spaceAfter = margin.child("next")?.int("value") ?: 0
        }
        node.child("lineSpacing")?.let { spacing ->
            shape.lineSpacingKind = Owpml.lineSpacingOf(spacing.attr("type"))
            shape.lineSpacing = spacing.int("value", 160)
        }
        node.child("border")?.let { border ->
            shape.borderFillId = border.int("borderFillIDRef")
            shape.borderOffsetLeft = border.int("offsetLeft")
            shape.borderOffsetRight = border.int("offsetRight")
            shape.borderOffsetTop = border.int("offsetTop")
            shape.borderOffsetBottom = border.int("offsetBottom")
        }
        node.child("heading")?.let { shape.numberingId = it.int("idRef") }
        return shape
    }

    // ---- content.hpf ---------------------------------------------------------------------------

    private fun readPackage(document: HwpDocument, pkg: XmlNode) {
        pkg.child("metadata")?.let { meta ->
            meta.child("title")?.let { document.summary.title = it.deepText().trim() }
            meta.child("creator")?.let { document.summary.author = it.deepText().trim() }
            meta.child("subject")?.let { document.summary.subject = it.deepText().trim() }
            meta.child("description")?.let { document.summary.comments = it.deepText().trim() }
            for (m in meta.childrenNamed("meta")) {
                when (m.attr("name")?.lowercase()) {
                    "keywords" -> document.summary.keywords = m.attr("content").orEmpty()
                    "lastsaveby" -> document.summary.lastSavedBy = m.attr("content").orEmpty()
                }
            }
        }
    }

    private fun readBinData(document: HwpDocument, entries: Map<String, ByteArray>) {
        var nextId = 1
        for ((name, data) in entries) {
            if (!name.startsWith(Owpml.PREFIX_BINDATA)) continue
            val fileName = name.removePrefix(Owpml.PREFIX_BINDATA)
            document.binData.add(
                BinDataEntry(
                    id = nextId++,
                    kind = BinDataKind.EMBEDDING,
                    extension = fileName.substringAfterLast('.', "png"),
                    streamName = fileName,
                    data = data,
                ).also { it.itemId = fileName.substringBeforeLast('.') },
            )
        }
    }

    // ---- sectionN.xml --------------------------------------------------------------------------

    fun readSection(sec: XmlNode): Section {
        val section = Section()
        for (p in sec.childrenNamed("p")) {
            section.paragraphs.add(readParagraph(p))
        }
        section.paragraphs.firstOrNull()
            ?.controls()
            ?.filterIsInstance<SectionDefControl>()
            ?.firstOrNull()
            ?.let { section.pageDef = it.pageDef }
        return section
    }

    private fun readParagraph(node: XmlNode): Paragraph {
        val paragraph = Paragraph(
            paraShapeId = node.int("paraPrIDRef"),
            styleId = node.int("styleIDRef"),
        )
        paragraph.breakType = if (node.bool("pageBreak")) 1 else 0
        for (run in node.childrenNamed("run")) {
            val charShapeId = run.int("charPrIDRef")
            readRun(run, charShapeId, paragraph)
        }
        if (paragraph.items.isEmpty()) paragraph.items.add(TextSpan("", 0))
        return paragraph
    }

    private fun readRun(run: XmlNode, charShapeId: Int, paragraph: Paragraph) {
        for (item in run.content) {
            if (item !is XmlNode) continue
            when (item.localName) {
                "t" -> readTextElement(item, charShapeId, paragraph)
                "tab" -> paragraph.items.add(
                    ControlSpan(HwpChar.TAB, Paragraph.tabRawUnits(), charShapeId = charShapeId),
                )
                "lineBreak" -> paragraph.items.add(CharControlSpan(HwpChar.LINE_BREAK, charShapeId))
                "tbl" -> paragraph.items.add(controlSpan(readTable(item), charShapeId))
                "pic" -> paragraph.items.add(controlSpan(readPicture(item), charShapeId))
                "secPr" -> paragraph.items.add(
                    controlSpan(SectionDefControl().apply { pageDef = readPageDef(item) }, charShapeId),
                )
                "ctrl" -> for (child in item.children) {
                    paragraph.items.add(controlSpan(OpaqueControl(ctrlIdFor(child.localName)), charShapeId))
                }
                in DRAWING_ELEMENTS -> paragraph.items.add(
                    controlSpan(OpaqueControl("gso "), charShapeId),
                )
            }
        }
    }

    private fun readTextElement(node: XmlNode, charShapeId: Int, paragraph: Paragraph) {
        for (item in node.content) {
            when (item) {
                is String -> if (item.isNotEmpty()) paragraph.items.add(TextSpan(item, charShapeId))
                is XmlNode -> when (item.localName) {
                    "tab" -> paragraph.items.add(
                        ControlSpan(HwpChar.TAB, Paragraph.tabRawUnits(), charShapeId = charShapeId),
                    )
                    "lineBreak" -> paragraph.items.add(CharControlSpan(HwpChar.LINE_BREAK, charShapeId))
                    "nbSpace" -> paragraph.items.add(CharControlSpan(HwpChar.HARD_SPACE, charShapeId))
                    "fwSpace" -> paragraph.items.add(CharControlSpan(HwpChar.FIXED_WIDTH_SPACE, charShapeId))
                    else -> Unit
                }
            }
        }
    }

    private fun controlSpan(control: Control, charShapeId: Int): ControlSpan = ControlSpan(
        code = HwpChar.EXTENDED_OBJECT,
        raw = "",
        ctrlId = control.ctrlId,
        control = control,
        charShapeId = charShapeId,
    )

    private fun ctrlIdFor(localName: String): String = when (localName) {
        "colPr" -> "cold"
        "header" -> "head"
        "footer" -> "foot"
        "footNote" -> "fn  "
        "endNote" -> "en  "
        "pageNumCtrl" -> "pgct"
        "pageHiding" -> "pghd"
        "bookmark" -> "bokm"
        "indexmark" -> "idxm"
        "autoNum" -> "atno"
        "newNum" -> "nwno"
        else -> localName.padEnd(4, ' ').take(4)
    }

    private val DRAWING_ELEMENTS = setOf(
        "container", "rect", "ellipse", "line", "arc", "polygon", "curve",
        "connectLine", "textart", "ole", "equation", "chart", "video", "compose", "dutmal",
    )

    private fun readTable(node: XmlNode): TableControl {
        val table = TableControl()
        table.rowCount = node.int("rowCnt")
        table.columnCount = node.int("colCnt")
        table.cellSpacing = node.int("cellSpacing")
        table.borderFillId = node.int("borderFillIDRef")
        node.child("sz")?.let {
            table.width = it.int("width")
            table.height = it.int("height")
        }
        node.child("inMargin")?.let {
            table.insideMarginLeft = it.int("left")
            table.insideMarginRight = it.int("right")
            table.insideMarginTop = it.int("top")
            table.insideMarginBottom = it.int("bottom")
        }
        for (tr in node.childrenNamed("tr")) {
            val row = TableRow()
            for (tc in tr.childrenNamed("tc")) row.cells.add(readCell(tc))
            table.rows.add(row)
        }
        if (table.rowCount == 0) table.rowCount = table.rows.size
        if (table.columnCount == 0) table.columnCount = table.rows.firstOrNull()?.cells?.size ?: 0
        return table
    }

    private fun readCell(node: XmlNode): TableCell {
        val cell = TableCell()
        cell.borderFillId = node.int("borderFillIDRef")
        node.child("cellAddr")?.let {
            cell.columnIndex = it.int("colAddr")
            cell.rowIndex = it.int("rowAddr")
        }
        node.child("cellSpan")?.let {
            cell.columnSpan = it.int("colSpan", 1).coerceAtLeast(1)
            cell.rowSpan = it.int("rowSpan", 1).coerceAtLeast(1)
        }
        node.child("cellSz")?.let {
            cell.width = it.int("width")
            cell.height = it.int("height")
        }
        node.child("cellMargin")?.let {
            cell.marginLeft = it.int("left")
            cell.marginRight = it.int("right")
            cell.marginTop = it.int("top")
            cell.marginBottom = it.int("bottom")
        }
        node.child("subList")?.childrenNamed("p")?.forEach { cell.paragraphs.add(readParagraph(it)) }
        return cell
    }

    private fun readPicture(node: XmlNode): PictureControl {
        val picture = PictureControl()
        node.child("sz")?.let {
            picture.width = it.int("width", picture.width)
            picture.height = it.int("height", picture.height)
        }
        node.child("img")?.let { picture.binDataRef = it.attr("binaryItemIDRef") }
        node.child("imgRect")?.let { rect ->
            // pt0..pt3 are the corners; the width of the rectangle is the drawn size.
            val x = rect.child("pt1")?.int("x") ?: 0
            val y = rect.child("pt2")?.int("y") ?: 0
            if (x > 0) picture.width = x
            if (y > 0) picture.height = y
        }
        picture.description = node.attr("desc").orEmpty()
        return picture
    }

    fun readPageDef(secPr: XmlNode): PageDef {
        val pagePr = secPr.child("pagePr") ?: return PageDef()
        val margin = pagePr.child("margin")
        return PageDef(
            width = pagePr.int("width", PageDef().width),
            height = pagePr.int("height", PageDef().height),
            marginLeft = margin?.int("left") ?: 0,
            marginRight = margin?.int("right") ?: 0,
            marginTop = margin?.int("top") ?: 0,
            marginBottom = margin?.int("bottom") ?: 0,
            marginHeader = margin?.int("header") ?: 0,
            marginFooter = margin?.int("footer") ?: 0,
            marginGutter = margin?.int("gutter") ?: 0,
            landscape = pagePr.attr("landscape").equals("WIDELY", ignoreCase = true),
        )
    }
}
