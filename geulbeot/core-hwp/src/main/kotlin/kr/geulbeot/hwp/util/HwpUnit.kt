package kr.geulbeot.hwp.util

/**
 * Unit conversions for the two scales HWP uses.
 *
 *  - HWPUNIT: 1/7200 inch. Page size, margins, indents, table widths - anything geometric.
 *  - Font height: 1/100 point. `CHAR_SHAPE.height == 1000` means 10.0pt.
 *
 * A4 in HWPUNIT is 59528 x 84188, which is the value 한글 itself writes, so the round numbers below
 * are the ones to compare against when debugging a page setup.
 */
object HwpUnit {
    const val PER_INCH: Int = 7200
    const val PER_POINT: Double = PER_INCH / 72.0 // 100.0
    const val PER_MM: Double = PER_INCH / 25.4

    fun fromPoint(pt: Double): Int = Math.round(pt * PER_POINT).toInt()
    fun toPoint(unit: Int): Double = unit / PER_POINT

    fun fromMm(mm: Double): Int = Math.round(mm * PER_MM).toInt()
    fun toMm(unit: Int): Double = unit / PER_MM

    fun fromInch(inch: Double): Int = Math.round(inch * PER_INCH).toInt()
    fun toInch(unit: Int): Double = unit.toDouble() / PER_INCH

    /** Font sizes are stored in 1/100 pt. */
    fun fontHeightFromPoint(pt: Double): Int = Math.round(pt * 100.0).toInt()
    fun fontHeightToPoint(height: Int): Double = height / 100.0

    // Common paper sizes in HWPUNIT, portrait (width x height).
    val A4_WIDTH = fromMm(210.0)
    val A4_HEIGHT = fromMm(297.0)
    val B5_WIDTH = fromMm(182.0)
    val B5_HEIGHT = fromMm(257.0)
    val LETTER_WIDTH = fromInch(8.5)
    val LETTER_HEIGHT = fromInch(11.0)
}

/**
 * HWP colours are stored as a 32-bit COLORREF: 0x00BBGGRR, i.e. red in the low byte.
 * Android and most of the rest of the world want 0xAARRGGBB, so conversion belongs in one place.
 */
object HwpColor {
    /** COLORREF (0x00BBGGRR) -> opaque ARGB (0xFFRRGGBB). */
    fun toArgb(colorRef: Int): Int {
        val r = colorRef and 0xFF
        val g = (colorRef ushr 8) and 0xFF
        val b = (colorRef ushr 16) and 0xFF
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    /** ARGB (0xAARRGGBB) -> COLORREF (0x00BBGGRR). Alpha is dropped; HWP has no per-colour alpha. */
    fun fromArgb(argb: Int): Int {
        val r = (argb ushr 16) and 0xFF
        val g = (argb ushr 8) and 0xFF
        val b = argb and 0xFF
        return (b shl 16) or (g shl 8) or r
    }

    /** "#RRGGBB" for XML formats and for debugging. */
    fun toHex(colorRef: Int): String {
        val argb = toArgb(colorRef)
        return String.format("#%06X", argb and 0xFFFFFF)
    }

    fun fromHex(hex: String): Int {
        val s = hex.removePrefix("#").trim()
        val v = s.toLongOrNull(16)?.toInt() ?: return 0
        return fromArgb(if (s.length <= 6) (0xFF shl 24) or v else v)
    }

    const val BLACK: Int = 0x000000
    /** COLORREF for pure white is 0xFFFFFF in either direction. */
    const val WHITE: Int = 0xFFFFFF
}
