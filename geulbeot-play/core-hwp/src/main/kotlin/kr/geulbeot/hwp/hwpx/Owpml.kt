package kr.geulbeot.hwp.hwpx

import kr.geulbeot.hwp.model.FontLanguage
import kr.geulbeot.hwp.model.LineSpacingKind
import kr.geulbeot.hwp.model.LineStyle
import kr.geulbeot.hwp.model.ParaAlign
import kr.geulbeot.hwp.model.UnderlineKind
import kr.geulbeot.hwp.util.HwpColor

/**
 * Names, namespaces and enumerations of OWPML - the XML vocabulary inside a `.hwpx` file,
 * standardised as KS X 6101.
 *
 * Unlike HWP 5.0's binary records, OWPML spells its values out as words, so most of this file is
 * the mapping between those words and the shared document model.
 */
object Owpml {

    const val MIMETYPE = "application/hwp+zip"

    const val ENTRY_MIMETYPE = "mimetype"
    const val ENTRY_VERSION = "version.xml"
    const val ENTRY_SETTINGS = "settings.xml"
    const val ENTRY_CONTAINER = "META-INF/container.xml"
    const val ENTRY_MANIFEST = "META-INF/manifest.xml"
    const val ENTRY_CONTENT = "Contents/content.hpf"
    const val ENTRY_HEADER = "Contents/header.xml"
    const val ENTRY_PREVIEW_TEXT = "Preview/PrvText.txt"
    const val ENTRY_PREVIEW_IMAGE = "Preview/PrvImage.png"
    const val PREFIX_SECTION = "Contents/section"
    const val PREFIX_BINDATA = "BinData/"

    const val NS_HEAD = "http://www.hancom.co.kr/hwpml/2011/head"
    const val NS_CORE = "http://www.hancom.co.kr/hwpml/2011/core"
    const val NS_SECTION = "http://www.hancom.co.kr/hwpml/2011/section"
    const val NS_PARAGRAPH = "http://www.hancom.co.kr/hwpml/2011/paragraph"
    const val NS_APP = "http://www.hancom.co.kr/hwpml/2011/app"
    const val NS_VERSION = "http://www.hancom.co.kr/hwpml/2011/version"
    const val NS_MASTER_PAGE = "http://www.hancom.co.kr/hwpml/2011/masterpage"
    const val NS_OPF = "http://www.idpf.org/2007/opf/"
    const val NS_DC = "http://purl.org/dc/elements/1.1/"
    const val NS_OCF = "urn:oasis:names:tc:opendocument:xmlns:container"
    const val NS_ODF_MANIFEST = "urn:oasis:names:tc:opendocument:xmlns:manifest:1.0"

    /** OWPML numbers the reference lists from 1; id 0 means "unset" in several places. */
    const val VERSION_XML = "1.4"

    fun languageName(language: FontLanguage): String = when (language) {
        FontLanguage.HANGUL -> "HANGUL"
        FontLanguage.LATIN -> "LATIN"
        FontLanguage.HANJA -> "HANJA"
        FontLanguage.JAPANESE -> "JAPANESE"
        FontLanguage.OTHER -> "OTHER"
        FontLanguage.SYMBOL -> "SYMBOL"
        FontLanguage.USER -> "USER"
    }

    fun languageOf(name: String?): FontLanguage = when (name?.uppercase()) {
        "HANGUL" -> FontLanguage.HANGUL
        "LATIN" -> FontLanguage.LATIN
        "HANJA" -> FontLanguage.HANJA
        "JAPANESE" -> FontLanguage.JAPANESE
        "OTHER" -> FontLanguage.OTHER
        "SYMBOL" -> FontLanguage.SYMBOL
        "USER" -> FontLanguage.USER
        else -> FontLanguage.HANGUL
    }

    /** The attribute name OWPML uses for a per-script value, e.g. `hangul="0"`. */
    fun languageAttribute(language: FontLanguage): String = when (language) {
        FontLanguage.HANGUL -> "hangul"
        FontLanguage.LATIN -> "latin"
        FontLanguage.HANJA -> "hanja"
        FontLanguage.JAPANESE -> "japanese"
        FontLanguage.OTHER -> "other"
        FontLanguage.SYMBOL -> "symbol"
        FontLanguage.USER -> "user"
    }

    fun alignName(align: ParaAlign): String = when (align) {
        ParaAlign.JUSTIFY -> "JUSTIFY"
        ParaAlign.LEFT -> "LEFT"
        ParaAlign.RIGHT -> "RIGHT"
        ParaAlign.CENTER -> "CENTER"
        ParaAlign.DISTRIBUTE -> "DISTRIBUTE"
        ParaAlign.DIVIDE -> "DISTRIBUTE_SPACE"
    }

    fun alignOf(name: String?): ParaAlign = when (name?.uppercase()) {
        "LEFT" -> ParaAlign.LEFT
        "RIGHT" -> ParaAlign.RIGHT
        "CENTER" -> ParaAlign.CENTER
        "DISTRIBUTE" -> ParaAlign.DISTRIBUTE
        "DISTRIBUTE_SPACE" -> ParaAlign.DIVIDE
        else -> ParaAlign.JUSTIFY
    }

    fun lineSpacingName(kind: LineSpacingKind): String = when (kind) {
        LineSpacingKind.PERCENT -> "PERCENT"
        LineSpacingKind.FIXED -> "FIXED"
        LineSpacingKind.SPACE_ONLY -> "SPACE_ONLY"
        LineSpacingKind.AT_LEAST -> "AT_LEAST"
    }

    fun lineSpacingOf(name: String?): LineSpacingKind = when (name?.uppercase()) {
        "FIXED" -> LineSpacingKind.FIXED
        "SPACE_ONLY", "BETWEEN_LINES" -> LineSpacingKind.SPACE_ONLY
        "AT_LEAST" -> LineSpacingKind.AT_LEAST
        else -> LineSpacingKind.PERCENT
    }

    fun underlineName(kind: UnderlineKind): String = when (kind) {
        UnderlineKind.NONE -> "NONE"
        UnderlineKind.BOTTOM -> "BOTTOM"
        UnderlineKind.TOP -> "TOP"
    }

    fun underlineOf(name: String?): UnderlineKind = when (name?.uppercase()) {
        "BOTTOM" -> UnderlineKind.BOTTOM
        "TOP" -> UnderlineKind.TOP
        else -> UnderlineKind.NONE
    }

    fun lineStyleName(style: LineStyle): String = when (style) {
        LineStyle.SOLID -> "SOLID"
        LineStyle.DASH -> "DASH"
        LineStyle.DOT -> "DOT"
        LineStyle.DASH_DOT -> "DASH_DOT"
        LineStyle.DASH_DOT_DOT -> "DASH_DOT_DOT"
        LineStyle.LONG_DASH -> "LONG_DASH"
        LineStyle.CIRCLE -> "CIRCLE"
        LineStyle.DOUBLE -> "DOUBLE_SLIM"
        LineStyle.THIN_THICK -> "SLIM_THICK"
        LineStyle.THICK_THIN -> "THICK_SLIM"
        LineStyle.THIN_THICK_THIN -> "SLIM_THICK_SLIM"
        LineStyle.WAVE -> "WAVE"
        LineStyle.DOUBLE_WAVE -> "DOUBLE_WAVE"
    }

    fun lineStyleOf(name: String?): LineStyle = when (name?.uppercase()) {
        "DASH" -> LineStyle.DASH
        "DOT" -> LineStyle.DOT
        "DASH_DOT" -> LineStyle.DASH_DOT
        "DASH_DOT_DOT" -> LineStyle.DASH_DOT_DOT
        "LONG_DASH" -> LineStyle.LONG_DASH
        "CIRCLE" -> LineStyle.CIRCLE
        "DOUBLE_SLIM" -> LineStyle.DOUBLE
        "SLIM_THICK" -> LineStyle.THIN_THICK
        "THICK_SLIM" -> LineStyle.THICK_THIN
        "SLIM_THICK_SLIM" -> LineStyle.THIN_THICK_THIN
        "WAVE" -> LineStyle.WAVE
        "DOUBLE_WAVE" -> LineStyle.DOUBLE_WAVE
        else -> LineStyle.SOLID
    }

    /** OWPML writes colours as `#RRGGBB`, or the word `none` where HWP would use a flag. */
    fun colorName(colorRef: Int): String = HwpColor.toHex(colorRef)

    fun colorOf(value: String?, fallback: Int): Int {
        if (value == null) return fallback
        val trimmed = value.trim()
        if (trimmed.isEmpty() || trimmed.equals("none", ignoreCase = true)) return fallback
        return HwpColor.fromHex(trimmed)
    }
}
