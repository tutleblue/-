package kr.geulbeot.hwp.model

import kr.geulbeot.hwp.util.HwpUnit

/** Which on-disk format a document came from, or is being written to. */
enum class DocumentFormat(val extension: String, val displayName: String, val mimeType: String) {
    HWP5("hwp", "한글 문서 (.hwp)", "application/x-hwp"),
    HWPX("hwpx", "한글 표준 문서 (.hwpx)", "application/hwp+zip"),
    PLAIN_TEXT("txt", "텍스트 (.txt)", "text/plain"),
    ;

    companion object {
        fun fromFileName(name: String): DocumentFormat? = when (name.substringAfterLast('.', "").lowercase()) {
            "hwp" -> HWP5
            "hwpx", "hwpml" -> HWPX
            "txt", "text" -> PLAIN_TEXT
            else -> null
        }
    }
}

/**
 * A font entry from the DocInfo font table.
 *
 * HWP keeps seven parallel font tables - Hangul, Latin, Hanja, Japanese, other, symbol and user -
 * so one run of text can render different scripts in different faces. [FontLanguage] indexes them.
 */
data class FontFace(
    var name: String,
    var type: Int = 0,
    var substituteType: Int = 0,
    var substituteName: String? = null,
    var baseFontName: String? = null,
    var typeInfo: ByteArray? = null,
) {
    override fun equals(other: Any?): Boolean = other is FontFace && other.name == name
    override fun hashCode(): Int = name.hashCode()
}

enum class FontLanguage(val index: Int, val label: String) {
    HANGUL(0, "한글"),
    LATIN(1, "영문"),
    HANJA(2, "한자"),
    JAPANESE(3, "일어"),
    OTHER(4, "기타"),
    SYMBOL(5, "기호"),
    USER(6, "사용자"),
    ;

    companion object {
        const val COUNT = 7
        fun byIndex(i: Int): FontLanguage = entries.firstOrNull { it.index == i } ?: HANGUL
    }
}

enum class UnderlineKind(val code: Int, val label: String) {
    NONE(0, "없음"),
    BOTTOM(1, "아래"),
    TOP(2, "위"),
    ;

    companion object {
        fun of(code: Int): UnderlineKind = entries.firstOrNull { it.code == code } ?: NONE
    }
}

enum class LineStyle(val code: Int, val label: String) {
    SOLID(0, "실선"),
    DASH(1, "긴 파선"),
    DOT(2, "점선"),
    DASH_DOT(3, "-.-.-."),
    DASH_DOT_DOT(4, "-..-.."),
    LONG_DASH(5, "긴 파선"),
    CIRCLE(6, "원형"),
    DOUBLE(7, "이중 실선"),
    THIN_THICK(8, "얇고 굵은 이중선"),
    THICK_THIN(9, "굵고 얇은 이중선"),
    THIN_THICK_THIN(10, "얇고 굵고 얇은 삼중선"),
    WAVE(11, "물결"),
    DOUBLE_WAVE(12, "이중 물결"),
    ;

    companion object {
        fun of(code: Int): LineStyle = entries.firstOrNull { it.code == code } ?: SOLID
    }
}

enum class StrikeOutKind(val code: Int, val label: String) {
    NONE(0, "없음"),
    SINGLE(1, "취소선"),
    ;

    companion object {
        fun of(code: Int): StrikeOutKind = if (code == 0) NONE else SINGLE
    }
}

/**
 * Character formatting, i.e. what 한글 shows in 글자 모양.
 *
 * Sizes are kept in HWP's own unit (1/100 pt) so a document read and written back is bit-identical;
 * [sizePt] is the view the UI works in.
 */
data class CharShape(
    var fontIds: IntArray = IntArray(FontLanguage.COUNT),
    var widthRatios: IntArray = IntArray(FontLanguage.COUNT) { 100 },
    var spacings: IntArray = IntArray(FontLanguage.COUNT),
    var relativeSizes: IntArray = IntArray(FontLanguage.COUNT) { 100 },
    var charOffsets: IntArray = IntArray(FontLanguage.COUNT),
    /** Base size in 1/100 pt; 1000 means 10.0pt. */
    var height: Int = 1000,
    var bold: Boolean = false,
    var italic: Boolean = false,
    var underline: UnderlineKind = UnderlineKind.NONE,
    var underlineStyle: LineStyle = LineStyle.SOLID,
    var strikeOut: StrikeOutKind = StrikeOutKind.NONE,
    var strikeOutStyle: LineStyle = LineStyle.SOLID,
    var outline: Int = 0,
    var shadow: Int = 0,
    var emboss: Boolean = false,
    var engrave: Boolean = false,
    var superscript: Boolean = false,
    var subscript: Boolean = false,
    /** COLORREF (0x00BBGGRR). Use HwpColor to convert. */
    var textColor: Int = 0x000000,
    var underlineColor: Int = 0x000000,
    var shadeColor: Int = 0xFFFFFF,
    var shadowColor: Int = 0xFFFFFF,
    var strikeOutColor: Int = 0x000000,
    var borderFillId: Int = 0,
    var shadowOffsetX: Int = 0,
    var shadowOffsetY: Int = 0,
    /** Bits this app does not model, kept so a round trip does not silently drop them. */
    var reservedAttributes: Int = 0,
) {
    var sizePt: Double
        get() = HwpUnit.fontHeightToPoint(height)
        set(value) {
            height = HwpUnit.fontHeightFromPoint(value.coerceIn(0.5, 400.0))
        }

    fun fontIdFor(language: FontLanguage): Int = fontIds.getOrElse(language.index) { 0 }

    fun setFontForAllLanguages(id: Int) {
        for (i in fontIds.indices) fontIds[i] = id
    }

    fun copyShape(): CharShape = copy(
        fontIds = fontIds.copyOf(),
        widthRatios = widthRatios.copyOf(),
        spacings = spacings.copyOf(),
        relativeSizes = relativeSizes.copyOf(),
        charOffsets = charOffsets.copyOf(),
    )

    /** Structural identity, used to reuse an existing shape id instead of growing the table. */
    fun signature(): String = buildString {
        append(fontIds.joinToString(",")).append('|')
        append(widthRatios.joinToString(",")).append('|')
        append(spacings.joinToString(",")).append('|')
        append(relativeSizes.joinToString(",")).append('|')
        append(charOffsets.joinToString(",")).append('|')
        append(height).append('|').append(bold).append('|').append(italic).append('|')
        append(underline.code).append('|').append(underlineStyle.code).append('|')
        append(strikeOut.code).append('|').append(strikeOutStyle.code).append('|')
        append(outline).append('|').append(shadow).append('|').append(emboss).append('|').append(engrave).append('|')
        append(superscript).append('|').append(subscript).append('|')
        append(textColor).append('|').append(underlineColor).append('|').append(shadeColor).append('|')
        append(shadowColor).append('|').append(strikeOutColor).append('|').append(borderFillId).append('|')
        append(shadowOffsetX).append('|').append(shadowOffsetY).append('|').append(reservedAttributes)
    }

    override fun equals(other: Any?): Boolean = other is CharShape && other.signature() == signature()
    override fun hashCode(): Int = signature().hashCode()
}

enum class ParaAlign(val code: Int, val label: String) {
    JUSTIFY(0, "양쪽 정렬"),
    LEFT(1, "왼쪽 정렬"),
    RIGHT(2, "오른쪽 정렬"),
    CENTER(3, "가운데 정렬"),
    DISTRIBUTE(4, "배분 정렬"),
    DIVIDE(5, "나눔 정렬"),
    ;

    companion object {
        fun of(code: Int): ParaAlign = entries.firstOrNull { it.code == code } ?: JUSTIFY
    }
}

enum class LineSpacingKind(val code: Int, val label: String) {
    PERCENT(0, "글자에 따라 (%)"),
    FIXED(1, "고정값"),
    SPACE_ONLY(2, "여백만 지정"),
    AT_LEAST(3, "최소"),
    ;

    companion object {
        fun of(code: Int): LineSpacingKind = entries.firstOrNull { it.code == code } ?: PERCENT
    }
}

/** Paragraph formatting, i.e. 문단 모양. Geometric values are HWPUNIT (1/7200 inch). */
data class ParaShape(
    var align: ParaAlign = ParaAlign.JUSTIFY,
    var lineSpacingKind: LineSpacingKind = LineSpacingKind.PERCENT,
    /** Percent when [lineSpacingKind] is PERCENT, otherwise HWPUNIT. 160 is 한글's default body value. */
    var lineSpacing: Int = 160,
    var marginLeft: Int = 0,
    var marginRight: Int = 0,
    /** Negative means a hanging indent. */
    var indent: Int = 0,
    var spaceBefore: Int = 0,
    var spaceAfter: Int = 0,
    var tabDefId: Int = 0,
    var numberingId: Int = 0,
    var borderFillId: Int = 0,
    var borderOffsetLeft: Int = 0,
    var borderOffsetRight: Int = 0,
    var borderOffsetTop: Int = 0,
    var borderOffsetBottom: Int = 0,
    var condense: Int = 0,
    var attribute1Reserved: Int = 0,
    var attribute2: Int = 0,
    var attribute3: Int = 0,
) {
    fun signature(): String =
        "${align.code}|${lineSpacingKind.code}|$lineSpacing|$marginLeft|$marginRight|$indent|$spaceBefore|" +
            "$spaceAfter|$tabDefId|$numberingId|$borderFillId|$borderOffsetLeft|$borderOffsetRight|" +
            "$borderOffsetTop|$borderOffsetBottom|$condense|$attribute1Reserved|$attribute2|$attribute3"

    override fun equals(other: Any?): Boolean = other is ParaShape && other.signature() == signature()
    override fun hashCode(): Int = signature().hashCode()
}

/** A named style (스타일), pairing a paragraph shape with a character shape. */
data class DocStyle(
    var name: String,
    var englishName: String = name,
    var paraShapeId: Int = 0,
    var charShapeId: Int = 0,
    var type: Int = 0,
    var nextStyleId: Int = 0,
    var languageId: Int = 0x0412, // ko-KR
    var lockForm: Int = 0,
)

/** Border and fill definition. Only the fill colour is modelled; the rest is preserved verbatim. */
data class BorderFill(
    var id: Int,
    var backgroundColor: Int? = null,
    var raw: ByteArray? = null,
) {
    override fun equals(other: Any?): Boolean = other is BorderFill && other.id == id
    override fun hashCode(): Int = id
}

/** An embedded binary - almost always a picture. */
data class BinDataEntry(
    var id: Int,
    var kind: BinDataKind = BinDataKind.EMBEDDING,
    var extension: String = "png",
    var streamName: String? = null,
    var linkPath: String? = null,
    var data: ByteArray? = null,
    /** Manifest item id, used by OWPML where the binary format uses [id]. */
    var itemId: String? = null,
) {
    override fun equals(other: Any?): Boolean = other is BinDataEntry && other.id == id
    override fun hashCode(): Int = id
}

enum class BinDataKind(val code: Int) { LINK(0), EMBEDDING(1), STORAGE(2) }
