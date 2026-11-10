package kr.geulbeot.hwp.hwp5

import kr.geulbeot.hwp.model.ControlSpan
import kr.geulbeot.hwp.model.HwpChar
import kr.geulbeot.hwp.model.PageDef
import kr.geulbeot.hwp.model.Section
import kr.geulbeot.hwp.model.SectionDefControl
import kr.geulbeot.hwp.record.HwpRecord
import kr.geulbeot.hwp.util.ByteWriter
import kr.geulbeot.hwp.util.HwpUnit

/**
 * Builds the records a document created inside this app needs but has no source file to inherit.
 *
 * Every HWP section begins with a section-definition control carrying the page setup. A document
 * opened from a `.hwp` already has one and it is preserved; a brand new document does not, so one
 * is synthesised here.
 *
 * This is the one place in the `.hwp` writer that constructs a control from scratch rather than
 * from a source document, and it is therefore the least certain part of binary export. Saving a new
 * document as `.hwpx` avoids it entirely, which is why that is the default for new files.
 */
object Hwp5Template {

    /**
     * Ensures the section's first paragraph carries a section-definition control.
     *
     * Returns true when one was added, which also means the paragraph must be regenerated rather
     * than written from its source record.
     */
    fun ensureSectionDef(section: Section): Boolean {
        val first = section.paragraphs.firstOrNull() ?: return false
        val existing = first.items.filterIsInstance<ControlSpan>().firstOrNull { it.control is SectionDefControl }
        if (existing != null) {
            // The page setup reaches the file through BodyTextCodec, which rewrites the nested
            // PAGE_DEF whenever the section is marked dirty. Nothing to build here.
            (existing.control as SectionDefControl).pageDef = section.pageDef
            return false
        }

        val control = SectionDefControl().apply { pageDef = section.pageDef }
        control.sourceRecord = buildSectionDefRecord(section.pageDef, null)
        val span = ControlSpan(
            code = HwpChar.EXTENDED_OBJECT,
            raw = BodyTextCodec.extendedControlUnits(HwpChar.EXTENDED_OBJECT, CtrlId.SECTION_DEF),
            ctrlId = CtrlId.SECTION_DEF,
            control = control,
            charShapeId = first.items.firstOrNull()?.charShapeId ?: 0,
        )
        first.items.add(0, span)
        first.dirty = true
        return true
    }

    /**
     * `CTRL_HEADER` for a section definition, with the page setup nested under it.
     *
     * When [source] is given, its payload and any children other than `PAGE_DEF` are kept so an
     * existing document's footnote shapes and page borders survive.
     */
    fun buildSectionDefRecord(page: PageDef, source: HwpRecord?): HwpRecord {
        val payload = source?.payload ?: sectionDefPayload()
        val record = HwpRecord(HwpTag.CTRL_HEADER, 0, payload)
        record.children.add(HwpRecord(HwpTag.PAGE_DEF, 0, BodyTextCodec.writePageDef(page)))
        source?.children?.filter { it.tagId != HwpTag.PAGE_DEF }?.forEach { record.children.add(it.deepCopy()) }
        return record
    }

    private fun sectionDefPayload(): ByteArray {
        val w = ByteWriter(28)
        w.i32(BodyTextCodec.ctrlIdValue(CtrlId.SECTION_DEF))
        w.i32(0)                              // attributes: no hidden header/footer, no text direction
        w.u16(HwpUnit.fromMm(8.0))            // spacing between columns
        w.u16(0)                              // vertical grid
        w.u16(0)                              // horizontal grid
        w.i32(HwpUnit.fromPoint(40.0))        // default tab stop, 한글's own default
        w.u16(0)                              // numbering shape id
        w.u16(1)                              // starting page number
        w.u16(1)                              // starting picture number
        w.u16(1)                              // starting table number
        w.u16(1)                              // starting equation number
        w.i32(0x0412)                         // default language: ko-KR
        return w.toByteArray()
    }

    /** The default tab definition every document references as id 0. */
    fun defaultTabDef(): ByteArray {
        val w = ByteWriter(8)
        w.i32(0) // attributes: no automatic left or right tab
        w.i32(0) // no explicit tab stops
        return w.toByteArray()
    }
}
