package kr.geulbeot.hwp.hwp5

import kr.geulbeot.hwp.model.BinDataEntry
import kr.geulbeot.hwp.model.BinDataKind
import kr.geulbeot.hwp.model.BorderFill
import kr.geulbeot.hwp.model.CharShape
import kr.geulbeot.hwp.model.DocStyle
import kr.geulbeot.hwp.model.DocumentProperties
import kr.geulbeot.hwp.model.FontFace
import kr.geulbeot.hwp.model.FontLanguage
import kr.geulbeot.hwp.model.HwpDocument
import kr.geulbeot.hwp.model.LineSpacingKind
import kr.geulbeot.hwp.model.LineStyle
import kr.geulbeot.hwp.model.ParaAlign
import kr.geulbeot.hwp.model.ParaShape
import kr.geulbeot.hwp.model.StrikeOutKind
import kr.geulbeot.hwp.model.UnderlineKind
import kr.geulbeot.hwp.record.HwpRecord
import kr.geulbeot.hwp.util.ByteReader
import kr.geulbeot.hwp.util.ByteWriter

/**
 * Reads and writes the `DocInfo` stream: the document's shared tables of fonts, character shapes,
 * paragraph shapes, styles, borders and embedded binaries.
 *
 * Writing follows a deliberate rule. Record types this app models completely are regenerated from
 * the document model; every other record - numbering, bullets, tab definitions, compatibility
 * settings, track-change data - is copied across from the source file untouched. That keeps a saved
 * document faithful in the parts HWP 5.0 documents but this app has no reason to interpret.
 */
object DocInfoCodec {

    /** Index order of [HwpTag.ID_MAPPINGS], as published in the format specification. */
    private const val MAP_BIN_DATA = 0
    private const val MAP_FONT_FIRST = 1 // seven entries, one per script
    private const val MAP_BORDER_FILL = 8
    private const val MAP_CHAR_SHAPE = 9
    private const val MAP_TAB_DEF = 10
    private const val MAP_NUMBERING = 11
    private const val MAP_BULLET = 12
    private const val MAP_PARA_SHAPE = 13
    private const val MAP_STYLE = 14
    private const val MAP_COUNT_MIN = 15
    private const val MAP_COUNT_MAX = 18

    // ---- reading ---------------------------------------------------------------------------

    fun read(document: HwpDocument, records: List<HwpRecord>, header: FileHeader) {
        document.docInfoRecords = records

        val fontCounts = IntArray(FontLanguage.COUNT)
        for (record in records) {
            when (record.tagId) {
                HwpTag.DOCUMENT_PROPERTIES -> document.properties = readDocumentProperties(record)
                HwpTag.ID_MAPPINGS -> {
                    val r = record.reader()
                    val counts = ArrayList<Int>()
                    while (r.remaining >= 4) counts.add(r.i32())
                    for (i in 0 until FontLanguage.COUNT) {
                        fontCounts[i] = counts.getOrElse(MAP_FONT_FIRST + i) { 0 }
                    }
                }
            }
        }

        // FACE_NAME records arrive as seven consecutive blocks, sized by the id mapping above.
        var faceIndex = 0
        var language = 0
        var seenInLanguage = 0
        for (record in records) {
            when (record.tagId) {
                HwpTag.FACE_NAME -> {
                    while (language < FontLanguage.COUNT - 1 && seenInLanguage >= fontCounts[language]) {
                        language++
                        seenInLanguage = 0
                    }
                    val face = readFaceName(record)
                    if (language < FontLanguage.COUNT) document.fontTables[language].add(face)
                    seenInLanguage++
                    faceIndex++
                }
                HwpTag.BORDER_FILL -> document.borderFills.add(readBorderFill(record, document.borderFills.size))
                HwpTag.CHAR_SHAPE -> document.charShapes.add(readCharShape(record))
                HwpTag.PARA_SHAPE -> document.paraShapes.add(readParaShape(record, header))
                HwpTag.STYLE -> document.styles.add(readStyle(record))
                HwpTag.BIN_DATA -> document.binData.add(readBinData(record))
            }
        }
        if (faceIndex > 0 && document.fontTables[0].isEmpty()) {
            // A producer that wrote no id mapping still wrote faces; treat them all as Hangul.
            for (record in records.filter { it.tagId == HwpTag.FACE_NAME }) {
                document.fontTables[0].add(readFaceName(record))
            }
        }
    }

    private fun readDocumentProperties(record: HwpRecord): DocumentProperties {
        val r = record.reader()
        return DocumentProperties(
            sectionCount = if (r.remaining >= 2) r.u16() else 1,
            startingPageNumber = if (r.remaining >= 2) r.u16() else 1,
            startingFootnoteNumber = if (r.remaining >= 2) r.u16() else 1,
            startingEndnoteNumber = if (r.remaining >= 2) r.u16() else 1,
            startingPictureNumber = if (r.remaining >= 2) r.u16() else 1,
            startingTableNumber = if (r.remaining >= 2) r.u16() else 1,
            startingEquationNumber = if (r.remaining >= 2) r.u16() else 1,
            caretListId = if (r.remaining >= 4) r.i32() else 0,
            caretParagraphId = if (r.remaining >= 4) r.i32() else 0,
            caretPosition = if (r.remaining >= 4) r.i32() else 0,
        )
    }

    private const val FACE_HAS_SUBSTITUTE = 0x80
    private const val FACE_HAS_TYPE_INFO = 0x40
    private const val FACE_HAS_BASE_FONT = 0x20

    private fun readFaceName(record: HwpRecord): FontFace {
        val r = record.reader()
        val attribute = r.u8()
        val name = r.hwpString()
        val face = FontFace(name)
        if (attribute and FACE_HAS_SUBSTITUTE != 0 && r.remaining >= 1) {
            face.substituteType = r.u8()
            face.substituteName = r.hwpString()
        }
        if (attribute and FACE_HAS_TYPE_INFO != 0 && r.remaining >= 10) {
            face.typeInfo = r.bytes(10)
        }
        if (attribute and FACE_HAS_BASE_FONT != 0 && r.remaining >= 2) {
            face.baseFontName = r.hwpString()
        }
        return face
    }

    private fun readBorderFill(record: HwpRecord, id: Int): BorderFill {
        val fill = BorderFill(id = id, raw = record.payload.copyOf())
        try {
            val r = record.reader()
            r.u16()            // attributes
            r.skip(4 * 6)      // four borders: kind, width, colour
            r.skip(6)          // diagonal
            if (r.remaining >= 4) {
                val fillKind = r.i32()
                // bit 0 means a plain colour fill, which is the only kind we need to know about.
                if (fillKind and 0x1 != 0 && r.remaining >= 4) fill.backgroundColor = r.i32()
            }
        } catch (_: Exception) {
            // Border definitions vary between versions; the raw payload is what gets written back.
        }
        return fill
    }

    // Character shape attribute bits, per the format specification.
    private const val CS_ITALIC = 0
    private const val CS_BOLD = 1
    private const val CS_UNDERLINE = 2 // 2 bits
    private const val CS_UNDERLINE_STYLE = 4 // 4 bits
    private const val CS_OUTLINE = 8 // 3 bits
    private const val CS_SHADOW = 11 // 2 bits
    private const val CS_EMBOSS = 13
    private const val CS_ENGRAVE = 14
    private const val CS_SUPERSCRIPT = 15
    private const val CS_SUBSCRIPT = 16
    private const val CS_STRIKEOUT = 18 // 3 bits
    private const val CS_STRIKEOUT_STYLE = 26 // 3 bits

    /**
     * Every attribute bit this app interprets. What is left over is kept verbatim in
     * [CharShape.reservedAttributes] so emphasis marks, italic degree and the control-character
     * protection flag survive a save - and so two shapes that differ only in bits we set are not
     * mistaken for different shapes.
     */
    private const val CS_MODELLED_MASK =
        0x0001_FFFF or // italic, bold, underline, outline, shadow, emboss, engrave, scripts
            0x001C_0000 or // strike out
            0x1C00_0000 // strike out style

    private fun bits(value: Int, offset: Int, count: Int): Int = (value ushr offset) and ((1 shl count) - 1)

    private fun withBits(value: Int, offset: Int, count: Int, field: Int): Int {
        val mask = ((1 shl count) - 1) shl offset
        return (value and mask.inv()) or ((field shl offset) and mask)
    }

    fun readCharShape(record: HwpRecord): CharShape {
        val r = record.reader()
        val shape = CharShape()
        for (i in 0 until FontLanguage.COUNT) shape.fontIds[i] = r.u16()
        for (i in 0 until FontLanguage.COUNT) shape.widthRatios[i] = r.u8()
        for (i in 0 until FontLanguage.COUNT) shape.spacings[i] = r.i8()
        for (i in 0 until FontLanguage.COUNT) shape.relativeSizes[i] = r.u8()
        for (i in 0 until FontLanguage.COUNT) shape.charOffsets[i] = r.i8()
        shape.height = r.i32()
        val attribute = r.i32()
        shape.italic = bits(attribute, CS_ITALIC, 1) != 0
        shape.bold = bits(attribute, CS_BOLD, 1) != 0
        shape.underline = UnderlineKind.of(bits(attribute, CS_UNDERLINE, 2))
        shape.underlineStyle = LineStyle.of(bits(attribute, CS_UNDERLINE_STYLE, 4))
        shape.outline = bits(attribute, CS_OUTLINE, 3)
        shape.shadow = bits(attribute, CS_SHADOW, 2)
        shape.emboss = bits(attribute, CS_EMBOSS, 1) != 0
        shape.engrave = bits(attribute, CS_ENGRAVE, 1) != 0
        shape.superscript = bits(attribute, CS_SUPERSCRIPT, 1) != 0
        shape.subscript = bits(attribute, CS_SUBSCRIPT, 1) != 0
        shape.strikeOut = StrikeOutKind.of(bits(attribute, CS_STRIKEOUT, 3))
        shape.strikeOutStyle = LineStyle.of(bits(attribute, CS_STRIKEOUT_STYLE, 3))
        shape.reservedAttributes = attribute and CS_MODELLED_MASK.inv()
        if (r.remaining >= 2) {
            shape.shadowOffsetX = r.i8()
            shape.shadowOffsetY = r.i8()
        }
        if (r.remaining >= 4) shape.textColor = r.i32()
        if (r.remaining >= 4) shape.underlineColor = r.i32()
        if (r.remaining >= 4) shape.shadeColor = r.i32()
        if (r.remaining >= 4) shape.shadowColor = r.i32()
        if (r.remaining >= 2) shape.borderFillId = r.u16()
        if (r.remaining >= 4) shape.strikeOutColor = r.i32()
        return shape
    }

    fun writeCharShape(shape: CharShape): ByteArray {
        val w = ByteWriter(74)
        for (i in 0 until FontLanguage.COUNT) w.u16(shape.fontIds.getOrElse(i) { 0 })
        for (i in 0 until FontLanguage.COUNT) w.u8(shape.widthRatios.getOrElse(i) { 100 })
        for (i in 0 until FontLanguage.COUNT) w.u8(shape.spacings.getOrElse(i) { 0 })
        for (i in 0 until FontLanguage.COUNT) w.u8(shape.relativeSizes.getOrElse(i) { 100 })
        for (i in 0 until FontLanguage.COUNT) w.u8(shape.charOffsets.getOrElse(i) { 0 })
        w.i32(shape.height)
        var attribute = shape.reservedAttributes
        attribute = withBits(attribute, CS_ITALIC, 1, if (shape.italic) 1 else 0)
        attribute = withBits(attribute, CS_BOLD, 1, if (shape.bold) 1 else 0)
        attribute = withBits(attribute, CS_UNDERLINE, 2, shape.underline.code)
        attribute = withBits(attribute, CS_UNDERLINE_STYLE, 4, shape.underlineStyle.code)
        attribute = withBits(attribute, CS_OUTLINE, 3, shape.outline)
        attribute = withBits(attribute, CS_SHADOW, 2, shape.shadow)
        attribute = withBits(attribute, CS_EMBOSS, 1, if (shape.emboss) 1 else 0)
        attribute = withBits(attribute, CS_ENGRAVE, 1, if (shape.engrave) 1 else 0)
        attribute = withBits(attribute, CS_SUPERSCRIPT, 1, if (shape.superscript) 1 else 0)
        attribute = withBits(attribute, CS_SUBSCRIPT, 1, if (shape.subscript) 1 else 0)
        attribute = withBits(attribute, CS_STRIKEOUT, 3, shape.strikeOut.code)
        attribute = withBits(attribute, CS_STRIKEOUT_STYLE, 3, shape.strikeOutStyle.code)
        w.i32(attribute)
        w.u8(shape.shadowOffsetX)
        w.u8(shape.shadowOffsetY)
        w.i32(shape.textColor)
        w.i32(shape.underlineColor)
        w.i32(shape.shadeColor)
        w.i32(shape.shadowColor)
        w.u16(shape.borderFillId)
        w.i32(shape.strikeOutColor)
        return w.toByteArray()
    }

    // Paragraph shape attribute-1 bits.
    private const val PS_LINE_SPACING_KIND = 0 // 2 bits, used before 5.0.2.5
    private const val PS_ALIGN = 2 // 3 bits

    /** Line spacing kind and alignment; everything above bit 4 is preserved as read. */
    private const val PS_MODELLED_MASK = 0x1F

    fun readParaShape(record: HwpRecord, header: FileHeader?): ParaShape {
        val r = record.reader()
        val shape = ParaShape()
        val attribute1 = r.i32()
        shape.align = ParaAlign.of(bits(attribute1, PS_ALIGN, 3))
        shape.attribute1Reserved = attribute1 and PS_MODELLED_MASK.inv()
        var legacyKind = bits(attribute1, PS_LINE_SPACING_KIND, 2)
        shape.marginLeft = r.i32()
        shape.marginRight = r.i32()
        shape.indent = r.i32()
        shape.spaceBefore = r.i32()
        shape.spaceAfter = r.i32()
        var legacySpacing = r.i32()
        shape.tabDefId = r.u16()
        shape.numberingId = r.u16()
        shape.borderFillId = r.u16()
        shape.borderOffsetLeft = r.i16()
        shape.borderOffsetRight = r.i16()
        shape.borderOffsetTop = r.i16()
        shape.borderOffsetBottom = r.i16()
        if (r.remaining >= 4) shape.attribute2 = r.i32()
        var modernKind: Int? = null
        var modernSpacing: Int? = null
        if (r.remaining >= 4) {
            val attribute3 = r.i32()
            modernKind = bits(attribute3, 0, 2)
            shape.attribute3 = attribute3 and 0x3.inv()
        }
        if (r.remaining >= 4) modernSpacing = r.i32()

        // 5.0.2.5 moved line spacing into its own field; older documents keep it in attribute 1.
        val useModern = modernSpacing != null && (header == null || header.atLeast(5, 0, 2, 5))
        shape.lineSpacingKind = LineSpacingKind.of(if (useModern) modernKind ?: legacyKind else legacyKind)
        shape.lineSpacing = if (useModern) modernSpacing!! else legacySpacing
        return shape
    }

    fun writeParaShape(shape: ParaShape): ByteArray {
        val w = ByteWriter(54)
        var attribute1 = shape.attribute1Reserved
        attribute1 = withBits(attribute1, PS_ALIGN, 3, shape.align.code)
        attribute1 = withBits(attribute1, PS_LINE_SPACING_KIND, 2, shape.lineSpacingKind.code)
        w.i32(attribute1)
        w.i32(shape.marginLeft)
        w.i32(shape.marginRight)
        w.i32(shape.indent)
        w.i32(shape.spaceBefore)
        w.i32(shape.spaceAfter)
        w.i32(shape.lineSpacing)
        w.u16(shape.tabDefId)
        w.u16(shape.numberingId)
        w.u16(shape.borderFillId)
        w.u16(shape.borderOffsetLeft)
        w.u16(shape.borderOffsetRight)
        w.u16(shape.borderOffsetTop)
        w.u16(shape.borderOffsetBottom)
        w.i32(shape.attribute2)
        w.i32(withBits(shape.attribute3, 0, 2, shape.lineSpacingKind.code))
        w.i32(shape.lineSpacing)
        return w.toByteArray()
    }

    fun readStyle(record: HwpRecord): DocStyle {
        val r = record.reader()
        val name = r.hwpString()
        val englishName = r.hwpString()
        val attribute = if (r.remaining >= 1) r.u8() else 0
        val nextStyle = if (r.remaining >= 1) r.u8() else 0
        val languageId = if (r.remaining >= 2) r.i16() else 0x0412
        val paraShapeId = if (r.remaining >= 2) r.u16() else 0
        val charShapeId = if (r.remaining >= 2) r.u16() else 0
        val lockForm = if (r.remaining >= 2) r.u16() else 0
        return DocStyle(
            name = name,
            englishName = englishName,
            paraShapeId = paraShapeId,
            charShapeId = charShapeId,
            type = attribute,
            nextStyleId = nextStyle,
            languageId = languageId,
            lockForm = lockForm,
        )
    }

    fun writeStyle(style: DocStyle): ByteArray {
        val w = ByteWriter(64)
        w.hwpString(style.name)
        w.hwpString(style.englishName)
        w.u8(style.type)
        w.u8(style.nextStyleId)
        w.u16(style.languageId)
        w.u16(style.paraShapeId)
        w.u16(style.charShapeId)
        w.u16(style.lockForm)
        return w.toByteArray()
    }

    fun readBinData(record: HwpRecord): BinDataEntry {
        val r = record.reader()
        val attribute = r.u16()
        val typeCode = attribute and 0x000F
        val kind = when (typeCode) {
            0 -> BinDataKind.LINK
            2 -> BinDataKind.STORAGE
            else -> BinDataKind.EMBEDDING
        }
        val entry = BinDataEntry(id = 0, kind = kind)
        if (kind == BinDataKind.LINK) {
            entry.linkPath = r.hwpString()
            if (r.remaining >= 2) r.hwpString() // relative path, unused
        } else {
            entry.id = if (r.remaining >= 2) r.u16() else 0
            if (kind == BinDataKind.EMBEDDING && r.remaining >= 2) entry.extension = r.hwpString()
            entry.streamName = String.format("BIN%04X.%s", entry.id, entry.extension)
        }
        return entry
    }

    fun writeBinData(entry: BinDataEntry): ByteArray {
        val w = ByteWriter(32)
        // Embedded binaries are written with "compressed by storage default" so the container's
        // own compression setting decides, exactly as 한글 writes them.
        w.u16(entry.kind.code)
        if (entry.kind == BinDataKind.LINK) {
            w.hwpString(entry.linkPath ?: "")
            w.hwpString("")
        } else {
            w.u16(entry.id)
            if (entry.kind == BinDataKind.EMBEDDING) w.hwpString(entry.extension)
        }
        return w.toByteArray()
    }

    fun writeFaceName(face: FontFace): ByteArray {
        val w = ByteWriter(64)
        var attribute = 0
        if (face.substituteName != null) attribute = attribute or FACE_HAS_SUBSTITUTE
        if (face.typeInfo != null) attribute = attribute or FACE_HAS_TYPE_INFO
        if (face.baseFontName != null) attribute = attribute or FACE_HAS_BASE_FONT
        w.u8(attribute)
        w.hwpString(face.name)
        face.substituteName?.let {
            w.u8(face.substituteType)
            w.hwpString(it)
        }
        face.typeInfo?.let { w.bytes(it, 0, minOf(10, it.size)) }
        face.baseFontName?.let { w.hwpString(it) }
        return w.toByteArray()
    }

    fun writeDocumentProperties(p: DocumentProperties, sectionCount: Int): ByteArray {
        val w = ByteWriter(26)
        w.u16(sectionCount)
        w.u16(p.startingPageNumber)
        w.u16(p.startingFootnoteNumber)
        w.u16(p.startingEndnoteNumber)
        w.u16(p.startingPictureNumber)
        w.u16(p.startingTableNumber)
        w.u16(p.startingEquationNumber)
        w.i32(p.caretListId)
        w.i32(p.caretParagraphId)
        w.i32(p.caretPosition)
        return w.toByteArray()
    }

    // ---- writing ---------------------------------------------------------------------------

    /**
     * Rebuilds the DocInfo record list.
     *
     * Records whose type appears in [REGENERATED] come from the document model; every other record
     * from the source file is carried over unchanged, in the order the specification lays out.
     */
    fun write(document: HwpDocument): List<HwpRecord> {
        val source = document.docInfoRecords.orEmpty()
        val preserved = source.filter { it.tagId !in REGENERATED }

        val out = ArrayList<HwpRecord>()

        fun record(tag: Int, payload: ByteArray) = HwpRecord(tag, 0, payload)

        // Decide the contents of every table first: the id mapping that precedes them has to state
        // exact counts, and a mismatch there makes the whole DocInfo unreadable.
        val fills = document.borderFills.ifEmpty { listOf(BorderFill(id = 0)) }
        val tabDefs = preserved.filter { it.tagId == HwpTag.TAB_DEF }
            .ifEmpty { listOf(HwpRecord(HwpTag.TAB_DEF, 0, Hwp5Template.defaultTabDef())) }
        val numberings = preserved.filter { it.tagId == HwpTag.NUMBERING }
        val bullets = preserved.filter { it.tagId == HwpTag.BULLET }

        out.add(record(HwpTag.DOCUMENT_PROPERTIES, writeDocumentProperties(document.properties, document.sections.size)))

        val counts = IntArray(MAP_COUNT_MIN)
        counts[MAP_BIN_DATA] = document.binData.size
        for (i in 0 until FontLanguage.COUNT) {
            counts[MAP_FONT_FIRST + i] = document.fontTables[i].size
        }
        counts[MAP_BORDER_FILL] = fills.size
        counts[MAP_CHAR_SHAPE] = document.charShapes.size
        counts[MAP_TAB_DEF] = tabDefs.size
        counts[MAP_NUMBERING] = numberings.size
        counts[MAP_BULLET] = bullets.size
        counts[MAP_PARA_SHAPE] = document.paraShapes.size
        counts[MAP_STYLE] = document.styles.size
        val mappings = ByteWriter(MAP_COUNT_MIN * 4)
        for (c in counts) mappings.i32(c)
        out.add(record(HwpTag.ID_MAPPINGS, mappings.toByteArray()))

        for (entry in document.binData) out.add(record(HwpTag.BIN_DATA, writeBinData(entry)))
        for (table in document.fontTables) {
            for (face in table) out.add(record(HwpTag.FACE_NAME, writeFaceName(face)))
        }
        for (fill in fills) {
            out.add(record(HwpTag.BORDER_FILL, fill.raw ?: defaultBorderFill()))
        }
        for (shape in document.charShapes) out.add(record(HwpTag.CHAR_SHAPE, writeCharShape(shape)))
        for (r in tabDefs) out.add(r)
        for (r in numberings) out.add(r)
        for (r in bullets) out.add(r)
        for (shape in document.paraShapes) out.add(record(HwpTag.PARA_SHAPE, writeParaShape(shape)))
        for (style in document.styles) out.add(record(HwpTag.STYLE, writeStyle(style)))

        // Everything else the source carried: document data, compatibility, track changes, memos.
        val tail = preserved.filter {
            it.tagId != HwpTag.TAB_DEF && it.tagId != HwpTag.NUMBERING && it.tagId != HwpTag.BULLET
        }
        out.addAll(tail)
        return out
    }

    private val REGENERATED = setOf(
        HwpTag.DOCUMENT_PROPERTIES,
        HwpTag.ID_MAPPINGS,
        HwpTag.BIN_DATA,
        HwpTag.FACE_NAME,
        HwpTag.BORDER_FILL,
        HwpTag.CHAR_SHAPE,
        HwpTag.PARA_SHAPE,
        HwpTag.STYLE,
    )

    /** A no-border, no-fill definition - the one every document needs at index 0. */
    fun defaultBorderFill(): ByteArray {
        val w = ByteWriter(32)
        w.u16(0)                       // attributes
        repeat(4) {                    // left, right, top, bottom
            w.u8(LineStyle.SOLID.code) // line kind
            w.u8(0)                    // width
            w.i32(0)                   // colour
        }
        w.u8(LineStyle.SOLID.code)     // diagonal
        w.u8(0)
        w.i32(0)
        w.i32(0)                       // fill kind: none
        return w.toByteArray()
    }
}
