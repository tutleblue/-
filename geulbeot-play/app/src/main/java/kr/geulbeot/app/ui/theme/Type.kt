package kr.geulbeot.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.sp

/**
 * Typography tuned for Korean.
 *
 * Hangul syllables are square and sit on a taller body than Latin text, so the default Material
 * line heights read cramped. Everything here is a little looser, and line-height alignment is set
 * to centre so mixed Korean and Latin lines do not rock.
 */
private val koreanLineHeight = LineHeightStyle(
    alignment = LineHeightStyle.Alignment.Center,
    trim = LineHeightStyle.Trim.None,
)

private fun korean(
    size: Int,
    lineHeight: Int,
    weight: FontWeight = FontWeight.Normal,
    letterSpacing: Double = 0.0,
) = TextStyle(
    fontFamily = FontFamily.SansSerif,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = lineHeight.sp,
    letterSpacing = letterSpacing.sp,
    lineHeightStyle = koreanLineHeight,
)

val GeulbeotTypography = Typography(
    displaySmall = korean(34, 44, FontWeight.Normal),
    headlineMedium = korean(26, 34, FontWeight.Normal),
    headlineSmall = korean(22, 30, FontWeight.Medium),
    titleLarge = korean(20, 28, FontWeight.Medium),
    titleMedium = korean(16, 24, FontWeight.Medium, 0.1),
    titleSmall = korean(14, 20, FontWeight.Medium, 0.1),
    bodyLarge = korean(16, 26),
    bodyMedium = korean(14, 22),
    bodySmall = korean(12, 18),
    labelLarge = korean(14, 20, FontWeight.Medium, 0.1),
    labelMedium = korean(12, 16, FontWeight.Medium, 0.3),
    labelSmall = korean(11, 15, FontWeight.Medium, 0.3),
)

/**
 * Maps a font named in a document to something the device can actually draw.
 *
 * A phone will not have 함초롬바탕 installed. Rather than silently falling back to the UI font,
 * documents are drawn in a serif or sans family chosen to match the kind of face the document
 * asked for, which keeps a 명조 document looking like one.
 */
object DocumentFonts {

    private val serifNames = setOf(
        "함초롬바탕", "바탕", "바탕체", "명조", "신명조", "궁서", "궁서체", "휴먼명조", "나눔명조",
        "HY신명조", "HY중고딕", "batang", "serif", "times", "myeongjo",
    )

    fun familyFor(name: String): FontFamily {
        val lowered = name.lowercase()
        val isSerif = serifNames.any { lowered.contains(it.lowercase()) }
        return if (isSerif) FontFamily.Serif else FontFamily.SansSerif
    }
}
