package kr.geulbeot.app.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/** How the app decides between the light and dark palettes. */
enum class ThemeChoice(val label: String) {
    SYSTEM("기기 설정 따름"),
    LIGHT("밝게"),
    DARK("어둡게"),
}

@Composable
fun GeulbeotTheme(
    themeChoice: ThemeChoice = ThemeChoice.SYSTEM,
    content: @Composable () -> Unit,
) {
    val dark = when (themeChoice) {
        ThemeChoice.SYSTEM -> isSystemInDarkTheme()
        ThemeChoice.LIGHT -> false
        ThemeChoice.DARK -> true
    }
    val colorScheme = if (dark) GeulbeotDarkColors else GeulbeotLightColors
    val documentColors = if (dark) DarkDocumentColors else LightDocumentColors

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            window.statusBarColor = colorScheme.surfaceContainer.toArgb()
            window.navigationBarColor = colorScheme.surfaceContainer.toArgb()
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }

    CompositionLocalProvider(LocalDocumentColors provides documentColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = GeulbeotTypography,
            content = content,
        )
    }
}

/** Reads the current document palette; shorthand for the composition local. */
val documentColors: DocumentColors
    @Composable get() = LocalDocumentColors.current
