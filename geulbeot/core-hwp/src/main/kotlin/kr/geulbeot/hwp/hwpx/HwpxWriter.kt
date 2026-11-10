package kr.geulbeot.hwp.hwpx

import kr.geulbeot.hwp.model.BinDataEntry
import kr.geulbeot.hwp.model.CharControlSpan
import kr.geulbeot.hwp.model.CharShape
import kr.geulbeot.hwp.model.ControlSpan
import kr.geulbeot.hwp.model.DocStyle
import kr.geulbeot.hwp.model.FontFace
import kr.geulbeot.hwp.model.FontLanguage
import kr.geulbeot.hwp.model.HwpChar
import kr.geulbeot.hwp.model.HwpDocument
import kr.geulbeot.hwp.model.PageDef
import kr.geulbeot.hwp.model.ParaShape
import kr.geulbeot.hwp.model.Paragraph
import kr.geulbeot.hwp.model.PictureControl
import kr.geulbeot.hwp.model.Section
import kr.geulbeot.hwp.model.SectionDefControl
import kr.geulbeot.hwp.model.StrikeOutKind
import kr.geulbeot.hwp.model.TableCell
import kr.geulbeot.hwp.model.TableControl
import kr.geulbeot.hwp.model.TextSpan
import kr.geulbeot.hwp.model.UnderlineKind
import kr.geulbeot.hwp.util.HwpUnit
import kr.geulbeot.hwp.xml.XmlWriter
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Writes the document model out as a `.hwpx` file.
 *
 * This is the format new documents default to. OWPML is a published standard with no undocumented
 * regions, so a document written here is reproducible from the model alone - there is no equivalent
 * of the binary writer's dependence on records copied from a source file.
 *
 * Parts the source document carried and this app does not regenerate (settings, master pages,
 * histories) are copied straight across.
 */
object HwpxWriter {

    /** Parts rebuilt on every save. Everything else in the source archive is passed through. */
    private val REGENERATED = setOf(
        Owpml.ENTRY_MIMETYPE,
        Owpml.ENTRY_VERSION,
        Owpml.ENTRY_CONTAINER,
        Owpml.ENTRY_MANIFEST,
        Owpml.ENTRY_CONTENT,
        Owpml.ENTRY_HEADER,
        Owpml.ENTRY_PREVIEW_TEXT,
    )

    fun write(document: HwpDocument): ByteArray {
        val source = document.sourceStreams.orEmpty()
        val parts = LinkedHashMap<String, ByteArray>()

        val sectionNames = document.sections.indices.map { "${Owpml.PREFIX_SECTION}$it.xml" }
        val binaryParts = LinkedHashMap<String, ByteArray>()
        for (entry in document.binData) {
            val name = binaryEntryName(entry)
            entry.data?.let { binaryParts[name] = it }
        }

        parts[Owpml.ENTRY_VERSION] = versionXml()
        parts[Owpml.ENTRY_HEADER] = headerXml(document)
        for ((index, section) in document.sections.withIndex()) {
            parts[sectionNames[index]] = sectionXml(document, section, index)
        }
        parts.putAll(binaryParts)
        parts[Owpml.ENTRY_PREVIEW_TEXT] = previewText(document).toByteArray(Charsets.UTF_8)

        // Anything the source had that we do not generate: settings, master pages, scripts.
        for ((name, data) in source) {
            if (name in REGENERATED) continue
            if (name.startsWith(Owpml.PREFIX_SECTION)) continue
            if (name.startsWith(Owpml.PREFIX_BINDATA)) continue
            if (name == Owpml.ENTRY_PREVIEW_IMAGE) parts[name] = data
            if (!parts.containsKey(name)) parts[name] = data
        }

        val manifestNames = buildList {
            add(Owpml.ENTRY_VERSION)
            add(Owpml.ENTRY_HEADER)
            addAll(sectionNames)
            addAll(binaryParts.keys)
            if (parts.containsKey(Owpml.ENTRY_SETTINGS)) add(Owpml.ENTRY_SETTINGS)
        }
        parts[Owpml.ENTRY_CONTAINER] = containerXml()
        parts[Owpml.ENTRY_MANIFEST] = manifestXml(manifestNames)
        parts[Owpml.ENTRY_CONTENT] = contentHpf(document, sectionNames, binaryParts.keys.toList())

        return zip(parts)
    }

    private fun binaryEntryName(entry: BinDataEntry): String {
        val name = entry.streamName ?: "${entry.itemId ?: "image${entry.id}"}.${entry.extension}"
        return Owpml.PREFIX_BINDATA + name
    }

    /**
     * The `mimetype` entry has to be first in the archive and stored uncompressed - that is what
     * lets a reader identify the format from the first bytes of the file without inflating anything.
     */
    private fun zip(parts: Map<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream(64 * 1024)
        ZipOutputStream(out).use { zip ->
            val mimetype = Owpml.MIMETYPE.toByteArray(Charsets.US_ASCII)
            val stored = ZipEntry(Owpml.ENTRY_MIMETYPE).apply {
                method = ZipEntry.STORED
                size = mimetype.size.toLong()
                compressedSize = mimetype.size.toLong()
                crc = CRC32().apply { update(mimetype) }.value
                time = FIXED_TIME
            }
            zip.putNextEntry(stored)
            zip.write(mimetype)
            zip.closeEntry()

            for ((name, data) in parts) {
                if (name == Owpml.ENTRY_MIMETYPE) continue
                // A fixed timestamp keeps saves reproducible and keeps the clock out of the file.
                zip.putNextEntry(ZipEntry(name).apply { time = FIXED_TIME })
                zip.write(data)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    /** 2000-01-01T00:00:00 local, chosen so saved files carry no information about when or where. */
    private val FIXED_TIME: Long = run {
        val calendar = java.util.Calendar.getInstance()
        calendar.clear()
        calendar.set(2000, 0, 1, 0, 0, 0)
        calendar.timeInMillis
    }

    private fun previewText(document: HwpDocument): String {
        val text = document.plainText()
        return if (text.length <= 2000) text else text.substring(0, 2000)
    }

    // ---- package parts ---------------------------------------------------------------------

    private fun versionXml(): ByteArray = XmlWriter()
        .start(
            "hv:HCFVersion",
            "xmlns:hv" to Owpml.NS_VERSION,
            "tagetApplication" to "WORDPROCESSOR",
            "major" to 5,
            "minor" to 1,
            "micro" to 1,
            "buildNumber" to 0,
            "os" to 1,
            "xmlVersion" to Owpml.VERSION_XML,
            "application" to "Geulbeot",
            "appVersion" to "1.0",
        )
        .end()
        .toBytes()

    private fun containerXml(): ByteArray = XmlWriter()
        .start("ocf:container", "xmlns:ocf" to Owpml.NS_OCF)
        .start("ocf:rootfiles")
        .element(
            "ocf:rootfile",
            "full-path" to Owpml.ENTRY_CONTENT,
            "media-type" to "application/hwpml-package+xml",
        )
        .end()
        .end()
        .toBytes()

    private fun manifestXml(names: List<String>): ByteArray {
        val w = XmlWriter()
        w.start("odf:manifest", "xmlns:odf" to Owpml.NS_ODF_MANIFEST, "odf:version" to "1.2")
        w.element("odf:file-entry", "odf:full-path" to "/", "odf:media-type" to Owpml.MIMETYPE)
        for (name in names.distinct()) {
            w.element("odf:file-entry", "odf:full-path" to name, "odf:media-type" to mediaType(name))
        }
        w.end()
        return w.toBytes()
    }

    private fun mediaType(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "xml", "hpf" -> "application/xml"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "bmp" -> "image/bmp"
        "txt" -> "text/plain"
        else -> "application/octet-stream"
    }

    private fun contentHpf(document: HwpDocument, sectionNames: List<String>, binaryNames: List<String>): ByteArray {
        val w = XmlWriter()
        w.start(
            "opf:package",
            "xmlns:opf" to Owpml.NS_OPF,
            "xmlns:ha" to Owpml.NS_APP,
            "xmlns:dc" to Owpml.NS_DC,
            "version" to "",
            "unique-identifier" to "",
            "id" to "",
        )
        w.start("opf:metadata")
        w.textElement("opf:title", document.summary.title)
        w.textElement("opf:language", "ko")
        if (document.summary.author.isNotEmpty()) w.textElement("opf:creator", document.summary.author)
        if (document.summary.subject.isNotEmpty()) w.textElement("opf:subject", document.summary.subject)
        if (document.summary.comments.isNotEmpty()) w.textElement("opf:description", document.summary.comments)
        if (document.summary.keywords.isNotEmpty()) {
            w.element("opf:meta", "name" to "keywords", "content" to document.summary.keywords)
        }
        if (document.summary.lastSavedBy.isNotEmpty()) {
            w.element("opf:meta", "name" to "lastsaveby", "content" to document.summary.lastSavedBy)
        }
        w.end()

        w.start("opf:manifest")
        w.element("opf:item", "id" to "header", "href" to Owpml.ENTRY_HEADER, "media-type" to "application/xml")
        for ((index, name) in sectionNames.withIndex()) {
            w.element("opf:item", "id" to "section$index", "href" to name, "media-type" to "application/xml")
        }
        for (name in binaryNames) {
            val itemId = name.removePrefix(Owpml.PREFIX_BINDATA).substringBeforeLast('.')
            w.element("opf:item", "id" to itemId, "href" to name, "media-type" to mediaType(name))
        }
        w.end()

        w.start("opf:spine")
        w.element("opf:itemref", "idref" to "header", "linear" to "yes")
        for (index in sectionNames.indices) {
            w.element("opf:itemref", "idref" to "section$index", "linear" to "yes")
        }
        w.end()
        w.end()
        return w.toBytes()
    }

    // ---- header.xml ----------------------------------------------------------------------------

    private fun headerXml(document: HwpDocument): ByteArray {
        val w = XmlWriter()
        w.start(
            "hh:head",
            "xmlns:hh" to Owpml.NS_HEAD,
            "xmlns:hc" to Owpml.NS_CORE,
            "version" to Owpml.VERSION_XML,
            "secCnt" to document.sections.size,
        )
        val p = document.properties
        w.element(
            "hh:beginNum",
            "page" to p.startingPageNumber,
            "footnote" to p.startingFootnoteNumber,
            "endnote" to p.startingEndnoteNumber,
            "pic" to p.startingPictureNumber,
            "tbl" to p.startingTableNumber,
            "equation" to p.startingEquationNumber,
        )

        w.start("hh:refList")

        w.start("hh:fontfaces", "itemCnt" to FontLanguage.COUNT)
        for (language in FontLanguage.entries) {
            val table = document.fontTables[language.index].ifEmpty { document.fontFaces }
            w.start("hh:fontface", "lang" to Owpml.languageName(language), "fontCnt" to table.size)
            for ((id, face) in table.withIndex()) writeFont(w, id, face)
            w.end()
        }
        w.end()

        val fills = document.borderFills.ifEmpty { listOf(kr.geulbeot.hwp.model.BorderFill(0)) }
        w.start("hh:borderFills", "itemCnt" to fills.size)
        for ((index, fill) in fills.withIndex()) writeBorderFill(w, index + 1, fill.backgroundColor)
        w.end()

        w.start("hh:charProperties", "itemCnt" to document.charShapes.size)
        for ((id, shape) in document.charShapes.withIndex()) writeCharPr(w, id, shape)
        w.end()

        w.start("hh:tabProperties", "itemCnt" to 1)
        w.start("hh:tabPr", "id" to 0, "autoTabLeft" to 0, "autoTabRight" to 0).end()
        w.end()

        w.start("hh:numberings", "itemCnt" to 0).end()

        w.start("hh:paraProperties", "itemCnt" to document.paraShapes.size)
        for ((id, shape) in document.paraShapes.withIndex()) writeParaPr(w, id, shape)
        w.end()

        w.start("hh:styles", "itemCnt" to document.styles.size)
        for ((id, style) in document.styles.withIndex()) writeStyle(w, id, style)
        w.end()

        w.end() // refList

        w.start("hh:compatibleDocument", "targetProgram" to "HWP201X")
        w.start("hh:layoutCompatibility").end()
        w.end()

        w.end()
        return w.toBytes()
    }

    private fun writeFont(w: XmlWriter, id: Int, face: FontFace) {
        w.start(
            "hh:font",
            "id" to id,
            "face" to face.name,
            "type" to if (face.type == 2) "HFT" else "TTF",
            "isEmbedded" to 0,
        )
        face.substituteName?.let {
            w.element("hh:substFont", "face" to it, "type" to "TTF", "isEmbedded" to 0)
        }
        w.end()
    }

    private fun writeBorderFill(w: XmlWriter, id: Int, backgroundColor: Int?) {
        w.start(
            "hh:borderFill",
            "id" to id,
            "threeD" to 0,
            "shadow" to 0,
            "centerLine" to "NONE",
            "breakCellSeparateLine" to 0,
        )
        w.element("hh:slash", "type" to "NONE", "Crooked" to 0, "isCounter" to 0)
        w.element("hh:backSlash", "type" to "NONE", "Crooked" to 0, "isCounter" to 0)
        for (side in listOf("leftBorder", "rightBorder", "topBorder", "bottomBorder")) {
            w.element("hh:$side", "type" to "NONE", "width" to "0.1 mm", "color" to "#000000")
        }
        w.element("hh:diagonal", "type" to "SOLID", "width" to "0.1 mm", "color" to "#000000")
        if (backgroundColor != null) {
            w.start("hc:fillBrush")
            w.start("hc:winBrush", "faceColor" to Owpml.colorName(backgroundColor), "hatchColor" to "#999999", "alpha" to "0").end()
            w.end()
        }
        w.end()
    }

    private fun writeCharPr(w: XmlWriter, id: Int, shape: CharShape) {
        w.start(
            "hh:charPr",
            "id" to id,
            "height" to shape.height,
            "textColor" to Owpml.colorName(shape.textColor),
            "shadeColor" to if (shape.shadeColor == 0xFFFFFF) "none" else Owpml.colorName(shape.shadeColor),
            "useFontSpace" to 0,
            "useKerning" to 0,
            "symMark" to "NONE",
            "borderFillIDRef" to (shape.borderFillId + 1),
        )
        writePerLanguage(w, "hh:fontRef") { shape.fontIds.getOrElse(it) { 0 } }
        writePerLanguage(w, "hh:ratio") { shape.widthRatios.getOrElse(it) { 100 } }
        writePerLanguage(w, "hh:spacing") { shape.spacings.getOrElse(it) { 0 } }
        writePerLanguage(w, "hh:relSz") { shape.relativeSizes.getOrElse(it) { 100 } }
        writePerLanguage(w, "hh:offset") { shape.charOffsets.getOrElse(it) { 0 } }
        if (shape.bold) w.start("hh:bold").end()
        if (shape.italic) w.start("hh:italic").end()
        if (shape.underline != UnderlineKind.NONE) {
            w.element(
                "hh:underline",
                "type" to Owpml.underlineName(shape.underline),
                "shape" to Owpml.lineStyleName(shape.underlineStyle),
                "color" to Owpml.colorName(shape.underlineColor),
            )
        }
        if (shape.strikeOut != StrikeOutKind.NONE) {
            w.element(
                "hh:strikeout",
                "shape" to Owpml.lineStyleName(shape.strikeOutStyle),
                "color" to Owpml.colorName(shape.strikeOutColor),
            )
        }
        if (shape.outline != 0) w.element("hh:outline", "type" to "SOLID")
        if (shape.shadow != 0) {
            w.element("hh:shadow", "type" to "DROP", "color" to Owpml.colorName(shape.shadowColor), "offsetX" to shape.shadowOffsetX, "offsetY" to shape.shadowOffsetY)
        }
        if (shape.emboss) w.start("hh:emboss").end()
        if (shape.engrave) w.start("hh:engrave").end()
        if (shape.superscript) w.start("hh:supscript").end()
        if (shape.subscript) w.start("hh:subscript").end()
        w.end()
    }

    private inline fun writePerLanguage(w: XmlWriter, element: String, value: (Int) -> Int) {
        w.start(element)
        for (language in FontLanguage.entries) {
            w.attribute(Owpml.languageAttribute(language), value(language.index))
        }
        w.end()
    }

    private fun writeParaPr(w: XmlWriter, id: Int, shape: ParaShape) {
        w.start(
            "hh:paraPr",
            "id" to id,
            "tabPrIDRef" to shape.tabDefId,
            "condense" to shape.condense,
            "fontLineHeight" to 0,
            "snapToGrid" to 1,
            "suppressLineNumbers" to 0,
            "checked" to 0,
        )
        w.element("hh:align", "horizontal" to Owpml.alignName(shape.align), "vertical" to "BASELINE")
        w.element("hh:heading", "type" to "NONE", "idRef" to shape.numberingId, "level" to 0)
        w.element(
            "hh:breakSetting",
            "breakLatinWord" to "KEEP_WORD",
            "breakNonLatinWord" to "KEEP_WORD",
            "widowOrphan" to 0,
            "keepWithNext" to 0,
            "keepLines" to 0,
            "pageBreakBefore" to 0,
            "lineWrap" to "BREAK",
        )
        w.element("hh:autoSpacing", "eAsianEng" to 0, "eAsianNum" to 0)
        w.start("hh:margin")
        w.element("hc:intent", "value" to shape.indent, "unit" to "HWPUNIT")
        w.element("hc:left", "value" to shape.marginLeft, "unit" to "HWPUNIT")
        w.element("hc:right", "value" to shape.marginRight, "unit" to "HWPUNIT")
        w.element("hc:prev", "value" to shape.spaceBefore, "unit" to "HWPUNIT")
        w.element("hc:next", "value" to shape.spaceAfter, "unit" to "HWPUNIT")
        w.end()
        w.element(
            "hh:lineSpacing",
            "type" to Owpml.lineSpacingName(shape.lineSpacingKind),
            "value" to shape.lineSpacing,
            "unit" to "HWPUNIT",
        )
        w.element(
            "hh:border",
            "borderFillIDRef" to (shape.borderFillId + 1),
            "offsetLeft" to shape.borderOffsetLeft,
            "offsetRight" to shape.borderOffsetRight,
            "offsetTop" to shape.borderOffsetTop,
            "offsetBottom" to shape.borderOffsetBottom,
            "connect" to 0,
            "ignoreMargin" to 0,
        )
        w.end()
    }

    private fun writeStyle(w: XmlWriter, id: Int, style: DocStyle) {
        w.element(
            "hh:style",
            "id" to id,
            "type" to if (style.type == 1) "CHAR" else "PARA",
            "name" to style.name,
            "engName" to style.englishName,
            "paraPrIDRef" to style.paraShapeId,
            "charPrIDRef" to style.charShapeId,
            "nextStyleIDRef" to style.nextStyleId,
            "langID" to style.languageId,
            "lockForm" to style.lockForm,
        )
    }

    // ---- sectionN.xml --------------------------------------------------------------------------

    private fun sectionXml(document: HwpDocument, section: Section, index: Int): ByteArray {
        val w = XmlWriter()
        w.start(
            "hs:sec",
            "xmlns:hs" to Owpml.NS_SECTION,
            "xmlns:hp" to Owpml.NS_PARAGRAPH,
            "xmlns:hc" to Owpml.NS_CORE,
        )
        for ((paragraphIndex, paragraph) in section.paragraphs.withIndex()) {
            val isFirst = paragraphIndex == 0
            writeParagraph(w, document, paragraph, paragraphIndex, if (isFirst) section.pageDef else null)
        }
        w.end()
        return w.toBytes()
    }

    /**
     * Writes one paragraph.
     *
     * Runs are grouped by character shape: OWPML puts the shape on the run, so a paragraph whose
     * formatting changes mid-sentence becomes several runs.
     */
    private fun writeParagraph(
        w: XmlWriter,
        document: HwpDocument,
        paragraph: Paragraph,
        id: Int,
        pageDef: PageDef?,
    ) {
        paragraph.normalise()
        w.start(
            "hp:p",
            "id" to id,
            "paraPrIDRef" to paragraph.paraShapeId,
            "styleIDRef" to paragraph.styleId,
            "pageBreak" to if (paragraph.breakType == 1) 1 else 0,
            "columnBreak" to 0,
            "merged" to 0,
        )

        var runOpen = false
        var runShape = -1
        var textOpen = false

        fun closeText() {
            if (textOpen) {
                w.end()
                textOpen = false
            }
        }

        fun closeRun() {
            closeText()
            if (runOpen) {
                w.end()
                runOpen = false
            }
        }

        fun openRun(shapeId: Int) {
            if (runOpen && runShape == shapeId) return
            closeRun()
            w.start("hp:run", "charPrIDRef" to shapeId)
            runOpen = true
            runShape = shapeId
        }

        fun openText() {
            if (!textOpen) {
                w.start("hp:t")
                textOpen = true
            }
        }

        if (pageDef != null) {
            openRun(paragraph.items.firstOrNull()?.charShapeId ?: 0)
            writeSectionProperties(w, pageDef)
            w.start("hp:ctrl")
            w.element("hp:colPr", "id" to "", "type" to "NEWSPAPER", "layout" to "LEFT", "colCount" to 1, "sameSz" to 1, "sameGap" to 0)
            w.end()
        }

        for (item in paragraph.items) {
            when (item) {
                is TextSpan -> {
                    if (item.text.isEmpty()) continue
                    openRun(item.charShapeId)
                    openText()
                    w.text(XmlWriter.sanitize(item.text))
                }
                is CharControlSpan -> {
                    openRun(item.charShapeId)
                    when (item.code) {
                        HwpChar.LINE_BREAK -> {
                            openText()
                            w.element("hp:lineBreak")
                        }
                        HwpChar.HARD_SPACE -> {
                            openText()
                            w.element("hp:nbSpace")
                        }
                        HwpChar.FIXED_WIDTH_SPACE -> {
                            openText()
                            w.element("hp:fwSpace")
                        }
                        else -> Unit
                    }
                }
                is ControlSpan -> {
                    openRun(item.charShapeId)
                    when {
                        item.code == HwpChar.TAB -> {
                            openText()
                            w.element("hp:tab")
                        }
                        item.control is TableControl -> {
                            closeText()
                            writeTable(w, document, item.control as TableControl)
                        }
                        item.control is PictureControl -> {
                            closeText()
                            writePicture(w, item.control as PictureControl)
                        }
                        item.control is SectionDefControl -> Unit // written above, once per section
                        else -> Unit
                    }
                }
            }
        }

        if (!runOpen) {
            // An empty paragraph still needs a run so its character shape is recorded.
            w.start("hp:run", "charPrIDRef" to (paragraph.items.firstOrNull()?.charShapeId ?: 0))
            w.start("hp:t").end()
            w.end()
        } else {
            closeRun()
        }
        w.end()
    }

    private fun writeSectionProperties(w: XmlWriter, page: PageDef) {
        w.start(
            "hp:secPr",
            "id" to "",
            "textDirection" to "HORIZONTAL",
            "spaceColumns" to HwpUnit.fromMm(8.0),
            "tabStop" to HwpUnit.fromPoint(40.0),
            "tabStopVal" to HwpUnit.fromPoint(40.0),
            "tabStopUnit" to "HWPUNIT",
            "outlineShapeIDRef" to 1,
            "memoShapeIDRef" to 0,
            "textVerticalWidthHead" to 0,
            "masterPageCnt" to 0,
        )
        w.element("hp:grid", "lineGrid" to 0, "charGrid" to 0, "wonggojiFormat" to 0, "strtnum" to 0)
        w.element("hp:startNum", "pageStartsOn" to "BOTH", "page" to 0, "pic" to 0, "tbl" to 0, "equation" to 0)
        w.element(
            "hp:visibility",
            "hideFirstHeader" to 0,
            "hideFirstFooter" to 0,
            "hideFirstMasterPage" to 0,
            "border" to "SHOW_ALL",
            "fill" to "SHOW_ALL",
            "hideFirstPageNum" to 0,
            "hideFirstEmptyLine" to 0,
            "showLineNumber" to 0,
        )
        w.start(
            "hp:pagePr",
            "landscape" to if (page.landscape) "WIDELY" else "NARROWLY",
            "width" to page.width,
            "height" to page.height,
            "gutterType" to "LEFT_ONLY",
        )
        w.element(
            "hp:margin",
            "header" to page.marginHeader,
            "footer" to page.marginFooter,
            "gutter" to page.marginGutter,
            "left" to page.marginLeft,
            "right" to page.marginRight,
            "top" to page.marginTop,
            "bottom" to page.marginBottom,
        )
        w.end()
        w.end()
    }

    private fun writeTable(w: XmlWriter, document: HwpDocument, table: TableControl) {
        w.start(
            "hp:tbl",
            "id" to "",
            "zOrder" to 0,
            "numberingType" to "TABLE",
            "textWrap" to "TOP_AND_BOTTOM",
            "textFlow" to "BOTH_SIDES",
            "lock" to 0,
            "dropcapstyle" to "None",
            "pageBreak" to "CELL",
            "repeatHeader" to 1,
            "rowCnt" to table.rows.size,
            "colCnt" to (table.rows.firstOrNull()?.cells?.size ?: table.columnCount),
            "cellSpacing" to table.cellSpacing,
            "borderFillIDRef" to (table.borderFillId + 1),
            "noAdjust" to 0,
        )
        w.element(
            "hp:sz",
            "width" to table.width,
            "widthRelTo" to "ABSOLUTE",
            "height" to table.height,
            "heightRelTo" to "ABSOLUTE",
            "protect" to 0,
        )
        w.element(
            "hp:pos",
            "treatAsChar" to 1,
            "affectLSpacing" to 0,
            "flowWithText" to 1,
            "allowOverlap" to 0,
            "holdAnchorAndSO" to 0,
            "vertRelTo" to "PARA",
            "horzRelTo" to "COLUMN",
            "vertAlign" to "TOP",
            "horzAlign" to "LEFT",
            "vertOffset" to 0,
            "horzOffset" to 0,
        )
        w.element("hp:outMargin", "left" to 0, "right" to 0, "top" to 0, "bottom" to 0)
        w.element(
            "hp:inMargin",
            "left" to table.insideMarginLeft,
            "right" to table.insideMarginRight,
            "top" to table.insideMarginTop,
            "bottom" to table.insideMarginBottom,
        )
        for (row in table.rows) {
            w.start("hp:tr")
            for (cell in row.cells) writeCell(w, document, cell, table.borderFillId)
            w.end()
        }
        w.end()
    }

    private fun writeCell(w: XmlWriter, document: HwpDocument, cell: TableCell, tableBorderFillId: Int) {
        w.start(
            "hp:tc",
            "name" to "",
            "header" to 0,
            "hasMargin" to 0,
            "protect" to 0,
            "editable" to 0,
            "dirty" to 0,
            "borderFillIDRef" to ((if (cell.borderFillId > 0) cell.borderFillId else tableBorderFillId) + 1),
        )
        w.start(
            "hp:subList",
            "id" to "",
            "textDirection" to "HORIZONTAL",
            "lineWrap" to "BREAK",
            "vertAlign" to "CENTER",
            "linkListIDRef" to 0,
            "linkListNextIDRef" to 0,
            "textWidth" to 0,
            "textHeight" to 0,
            "hasTextRef" to 0,
            "hasNumRef" to 0,
        )
        val paragraphs = cell.paragraphs.ifEmpty { listOf(Paragraph()) }
        for ((index, paragraph) in paragraphs.withIndex()) {
            writeParagraph(w, document, paragraph, index, null)
        }
        w.end()
        w.element("hp:cellAddr", "colAddr" to cell.columnIndex, "rowAddr" to cell.rowIndex)
        w.element("hp:cellSpan", "colSpan" to cell.columnSpan, "rowSpan" to cell.rowSpan)
        w.element("hp:cellSz", "width" to cell.width, "height" to cell.height)
        w.element(
            "hp:cellMargin",
            "left" to cell.marginLeft,
            "right" to cell.marginRight,
            "top" to cell.marginTop,
            "bottom" to cell.marginBottom,
        )
        w.end()
    }

    private fun writePicture(w: XmlWriter, picture: PictureControl) {
        w.start(
            "hp:pic",
            "id" to "",
            "zOrder" to 0,
            "numberingType" to "PICTURE",
            "textWrap" to "TOP_AND_BOTTOM",
            "textFlow" to "BOTH_SIDES",
            "lock" to 0,
            "dropcapstyle" to "None",
            "href" to "",
            "groupLevel" to 0,
            "instid" to 0,
            "reverse" to 0,
            "desc" to picture.description,
        )
        w.element(
            "hp:sz",
            "width" to picture.width,
            "widthRelTo" to "ABSOLUTE",
            "height" to picture.height,
            "heightRelTo" to "ABSOLUTE",
            "protect" to 0,
        )
        w.element(
            "hp:pos",
            "treatAsChar" to 1,
            "affectLSpacing" to 0,
            "flowWithText" to 1,
            "allowOverlap" to 0,
            "holdAnchorAndSO" to 0,
            "vertRelTo" to "PARA",
            "horzRelTo" to "COLUMN",
            "vertAlign" to "TOP",
            "horzAlign" to "LEFT",
            "vertOffset" to 0,
            "horzOffset" to 0,
        )
        w.element("hp:outMargin", "left" to 0, "right" to 0, "top" to 0, "bottom" to 0)
        w.element(
            "hp:img",
            "binaryItemIDRef" to (picture.binDataRef ?: "image${picture.binDataId}"),
            "bright" to 0,
            "contrast" to 0,
            "effect" to "REAL_PIC",
            "alpha" to "0",
        )
        w.start("hp:imgRect")
        w.element("hc:pt0", "x" to 0, "y" to 0)
        w.element("hc:pt1", "x" to picture.width, "y" to 0)
        w.element("hc:pt2", "x" to picture.width, "y" to picture.height)
        w.element("hc:pt3", "x" to 0, "y" to picture.height)
        w.end()
        w.element("hp:imgClip", "left" to 0, "right" to picture.width, "top" to 0, "bottom" to picture.height)
        w.element("hp:inMargin", "left" to 0, "right" to 0, "top" to 0, "bottom" to 0)
        w.element("hp:imgDim", "dimwidth" to picture.width, "dimheight" to picture.height)
        w.end()
    }
}
