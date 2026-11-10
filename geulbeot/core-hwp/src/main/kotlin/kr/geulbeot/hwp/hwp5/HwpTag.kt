package kr.geulbeot.hwp.hwp5

/**
 * Record tag ids from the published HWP 5.0 format specification.
 *
 * Every tag is an offset from [BEGIN]; the spec lists them that way and keeping the arithmetic
 * visible makes them checkable against the document without a lookup table.
 */
object HwpTag {
    const val BEGIN = 0x010

    // ---- DocInfo -------------------------------------------------------------------------
    const val DOCUMENT_PROPERTIES = BEGIN          // 0x010
    const val ID_MAPPINGS = BEGIN + 1              // 0x011
    const val BIN_DATA = BEGIN + 2                 // 0x012
    const val FACE_NAME = BEGIN + 3                // 0x013
    const val BORDER_FILL = BEGIN + 4              // 0x014
    const val CHAR_SHAPE = BEGIN + 5               // 0x015
    const val TAB_DEF = BEGIN + 6                  // 0x016
    const val NUMBERING = BEGIN + 7                // 0x017
    const val BULLET = BEGIN + 8                   // 0x018
    const val PARA_SHAPE = BEGIN + 9               // 0x019
    const val STYLE = BEGIN + 10                   // 0x01A
    const val DOC_DATA = BEGIN + 11                // 0x01B
    const val DISTRIBUTE_DOC_DATA = BEGIN + 12     // 0x01C
    const val COMPATIBLE_DOCUMENT = BEGIN + 14     // 0x01E
    const val LAYOUT_COMPATIBILITY = BEGIN + 15    // 0x01F
    const val TRACKCHANGE = BEGIN + 16             // 0x020
    const val MEMO_SHAPE = BEGIN + 76              // 0x05C
    const val FORBIDDEN_CHAR = BEGIN + 78          // 0x05E
    const val TRACK_CHANGE = BEGIN + 80            // 0x060
    const val TRACK_CHANGE_AUTHOR = BEGIN + 81     // 0x061

    // ---- BodyText ------------------------------------------------------------------------
    const val PARA_HEADER = BEGIN + 50             // 0x042
    const val PARA_TEXT = BEGIN + 51               // 0x043
    const val PARA_CHAR_SHAPE = BEGIN + 52         // 0x044
    const val PARA_LINE_SEG = BEGIN + 53           // 0x045
    const val PARA_RANGE_TAG = BEGIN + 54          // 0x046
    const val CTRL_HEADER = BEGIN + 55             // 0x047
    const val LIST_HEADER = BEGIN + 56             // 0x048
    const val PAGE_DEF = BEGIN + 57                // 0x049
    const val FOOTNOTE_SHAPE = BEGIN + 58          // 0x04A
    const val PAGE_BORDER_FILL = BEGIN + 59        // 0x04B
    const val SHAPE_COMPONENT = BEGIN + 60         // 0x04C
    const val TABLE = BEGIN + 61                   // 0x04D
    const val SHAPE_COMPONENT_LINE = BEGIN + 62    // 0x04E
    const val SHAPE_COMPONENT_RECTANGLE = BEGIN + 63
    const val SHAPE_COMPONENT_ELLIPSE = BEGIN + 64
    const val SHAPE_COMPONENT_ARC = BEGIN + 65
    const val SHAPE_COMPONENT_POLYGON = BEGIN + 66
    const val SHAPE_COMPONENT_CURVE = BEGIN + 67
    const val SHAPE_COMPONENT_OLE = BEGIN + 68
    const val SHAPE_COMPONENT_PICTURE = BEGIN + 69 // 0x055
    const val SHAPE_COMPONENT_CONTAINER = BEGIN + 70
    const val CTRL_DATA = BEGIN + 71               // 0x057
    const val EQEDIT = BEGIN + 72                  // 0x058
    const val SHAPE_COMPONENT_TEXTART = BEGIN + 74
    const val FORM_OBJECT = BEGIN + 75
    const val MEMO_LIST = BEGIN + 77               // 0x05D
    const val CHART_DATA = BEGIN + 79
    const val VIDEO_DATA = BEGIN + 82
    const val SHAPE_COMPONENT_UNKNOWN = BEGIN + 99

    fun name(tag: Int): String = when (tag) {
        DOCUMENT_PROPERTIES -> "DOCUMENT_PROPERTIES"
        ID_MAPPINGS -> "ID_MAPPINGS"
        BIN_DATA -> "BIN_DATA"
        FACE_NAME -> "FACE_NAME"
        BORDER_FILL -> "BORDER_FILL"
        CHAR_SHAPE -> "CHAR_SHAPE"
        TAB_DEF -> "TAB_DEF"
        NUMBERING -> "NUMBERING"
        BULLET -> "BULLET"
        PARA_SHAPE -> "PARA_SHAPE"
        STYLE -> "STYLE"
        DOC_DATA -> "DOC_DATA"
        PARA_HEADER -> "PARA_HEADER"
        PARA_TEXT -> "PARA_TEXT"
        PARA_CHAR_SHAPE -> "PARA_CHAR_SHAPE"
        PARA_LINE_SEG -> "PARA_LINE_SEG"
        CTRL_HEADER -> "CTRL_HEADER"
        LIST_HEADER -> "LIST_HEADER"
        PAGE_DEF -> "PAGE_DEF"
        TABLE -> "TABLE"
        SHAPE_COMPONENT -> "SHAPE_COMPONENT"
        SHAPE_COMPONENT_PICTURE -> "SHAPE_COMPONENT_PICTURE"
        else -> "TAG_0x${tag.toString(16).uppercase()}"
    }
}

/**
 * Control ids, as they read once decoded from the UINT32 a record stores them in.
 *
 * The conversion lives in `BodyTextCodec.ctrlIdString`; these are the values to compare against.
 * Note the trailing spaces - an id is always exactly four characters.
 */
object CtrlId {
    const val TABLE = "tbl "
    const val GENERAL_SHAPE = "gso "
    const val SECTION_DEF = "secd"
    const val COLUMN_DEF = "cold"
    const val HEADER = "head"
    const val FOOTER = "foot"
    const val FOOTNOTE = "fn  "
    const val ENDNOTE = "en  "
    const val AUTO_NUMBER = "atno"
    const val NEW_NUMBER = "nwno"
    const val PAGE_HIDE = "pghd"
    const val PAGE_NUM_CTRL = "pgct"
    const val PAGE_NUM_POS = "pgnp"
    const val INDEX_MARK = "idxm"
    const val BOOKMARK = "bokm"
    const val OVERLAPPING_LETTER = "tcps"
    const val HYPERLINK = "%hlk"
    const val FIELD_DATE = "%dte"
    const val FIELD_PATH = "%pat"
    const val FIELD_SUMMARY = "%sum"
    const val FIELD_USER_INFO = "%usr"
    const val FIELD_CLICK_HERE = "%clk"
}
