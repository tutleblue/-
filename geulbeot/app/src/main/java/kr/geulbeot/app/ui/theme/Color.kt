package kr.geulbeot.app.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * A plain office palette.
 *
 * The brief was an unobtrusive tool, not a branded one, so there is a single restrained blue for
 * anything active and greys for everything else. The blue is the one 한글 itself uses for selected
 * toolbar state, which makes the app feel familiar rather than novel.
 */
private val OfficeBlue = Color(0xFF2F5597)
private val OfficeBlueLight = Color(0xFFA9C7F5)

val GeulbeotLightColors = lightColorScheme(
    primary = OfficeBlue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE5F5),
    onPrimaryContainer = Color(0xFF10294F),
    secondary = Color(0xFF545F70),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD9E2F3),
    onSecondaryContainer = Color(0xFF111C2B),
    tertiary = Color(0xFF3F6B5A),
    onTertiary = Color.White,
    background = Color(0xFFF4F5F7),
    onBackground = Color(0xFF1A1C20),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1A1C20),
    surfaceVariant = Color(0xFFE3E5EA),
    onSurfaceVariant = Color(0xFF44474E),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF7F8FA),
    surfaceContainer = Color(0xFFEFF1F4),
    surfaceContainerHigh = Color(0xFFE9EBEF),
    surfaceContainerHighest = Color(0xFFE3E5EA),
    outline = Color(0xFFB9BDC5),
    outlineVariant = Color(0xFFD9DBE1),
    error = Color(0xFFB3261E),
    onError = Color.White,
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
)

val GeulbeotDarkColors = darkColorScheme(
    primary = OfficeBlueLight,
    onPrimary = Color(0xFF0E2C55),
    primaryContainer = Color(0xFF234170),
    onPrimaryContainer = Color(0xFFD6E3FF),
    secondary = Color(0xFFBCC7DB),
    onSecondary = Color(0xFF263141),
    secondaryContainer = Color(0xFF3C4758),
    onSecondaryContainer = Color(0xFFD8E2F4),
    tertiary = Color(0xFFA5D0BB),
    onTertiary = Color(0xFF0B3A2B),
    background = Color(0xFF121317),
    onBackground = Color(0xFFE3E5E9),
    surface = Color(0xFF1A1C20),
    onSurface = Color(0xFFE3E5E9),
    surfaceVariant = Color(0xFF43474E),
    onSurfaceVariant = Color(0xFFC3C6CE),
    surfaceContainerLowest = Color(0xFF0D0E12),
    surfaceContainerLow = Color(0xFF1A1C20),
    surfaceContainer = Color(0xFF1E2025),
    surfaceContainerHigh = Color(0xFF282A30),
    surfaceContainerHighest = Color(0xFF33353B),
    outline = Color(0xFF8D9199),
    outlineVariant = Color(0xFF43474E),
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFF9DEDC),
)

/**
 * Colours for the document canvas.
 *
 * These sit outside the Material scheme on purpose. A page is a depiction of paper rather than a
 * surface of the app, and tying it to the scheme would make it drift every time the chrome changed.
 */
@Immutable
data class DocumentColors(
    /** The page itself. */
    val paper: Color,
    /** The desk the page sits on. */
    val desk: Color,
    /** The page's edge and shadow line. */
    val paperEdge: Color,
    /** Default body text, when the document does not specify a colour. */
    val ink: Color,
    /** Margin guides and the ruler. */
    val guide: Color,
    /** Table gridlines when the document does not specify borders. */
    val tableGrid: Color,
    /** Background of a search match. */
    val searchHighlight: Color,
    /** Background of the match the user is currently on. */
    val searchCurrent: Color,
)

val LightDocumentColors = DocumentColors(
    paper = Color(0xFFFFFFFF),
    desk = Color(0xFFDDE0E6),
    paperEdge = Color(0xFFC8CCD4),
    ink = Color(0xFF16181C),
    guide = Color(0xFFD2D6DE),
    tableGrid = Color(0xFF9AA0AB),
    searchHighlight = Color(0xFFFFF1A8),
    searchCurrent = Color(0xFFFFC46B),
)

/**
 * In dark mode the page stays a sheet - darker than paper but lighter than the desk - so page
 * breaks and margins remain visible. Inverting to pure black would hide the very things a page
 * view exists to show.
 */
val DarkDocumentColors = DocumentColors(
    paper = Color(0xFF23262B),
    desk = Color(0xFF121317),
    paperEdge = Color(0xFF3A3E45),
    ink = Color(0xFFE6E8EC),
    guide = Color(0xFF383C43),
    tableGrid = Color(0xFF6B7178),
    searchHighlight = Color(0xFF5A4E1E),
    searchCurrent = Color(0xFF8A6A22),
)

val LocalDocumentColors = staticCompositionLocalOf { LightDocumentColors }
