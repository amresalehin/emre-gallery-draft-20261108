package com.amresalehin.emregallery.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

// Emre Gallery is strictly monochrome. Palette built from pure neutrals:
// whites, grays, and near-blacks carry the entire interface. Color is not decoration.
private val Light = lightColorScheme(
    primary = Color(0xFF202020),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE8E8E8),
    onPrimaryContainer = Color(0xFF141414),
    secondary = Color(0xFF555555),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFEEEEEE),
    onSecondaryContainer = Color(0xFF222222),
    tertiary = Color(0xFF383838),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFE2E2E2),
    onTertiaryContainer = Color(0xFF141414),
    background = Color(0xFFFFFFFF),
    onBackground = Color(0xFF141414),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF141414),
    surfaceVariant = Color(0xFFE8E8E8),
    onSurfaceVariant = Color(0xFF444444),
    surfaceTint = Color(0x00000000),
    inverseSurface = Color(0xFF282828),
    inverseOnSurface = Color(0xFFF5F5F5),
    inversePrimary = Color(0xFFD4D4D4),
    outline = Color(0xFF757575),
    outlineVariant = Color(0xFFD0D0D0),
    scrim = Color(0x99000000)
)

private val Dark = darkColorScheme(
    primary = Color(0xFFEEEEEE),
    onPrimary = Color(0xFF161616),
    primaryContainer = Color(0xFF303030),
    onPrimaryContainer = Color(0xFFF4F4F4),
    secondary = Color(0xFFBBBBBB),
    onSecondary = Color(0xFF1C1C1C),
    secondaryContainer = Color(0xFF2C2C2C),
    onSecondaryContainer = Color(0xFFEAEAEA),
    tertiary = Color(0xFFD4D4D4),
    onTertiary = Color(0xFF1A1A1A),
    tertiaryContainer = Color(0xFF2E2E2E),
    onTertiaryContainer = Color(0xFFEEEEEE),
    background = Color(0xFF101010),
    onBackground = Color(0xFFEDEDED),
    surface = Color(0xFF101010),
    onSurface = Color(0xFFEDEDED),
    surfaceVariant = Color(0xFF262626),
    onSurfaceVariant = Color(0xFFC0C0C0),
    surfaceTint = Color(0x00000000),
    inverseSurface = Color(0xFFEDEDED),
    inverseOnSurface = Color(0xFF222222),
    inversePrimary = Color(0xFF404040),
    outline = Color(0xFF808080),
    outlineVariant = Color(0xFF404040),
    scrim = Color(0x99000000)
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmreGalleryTheme(
    theme: String = "system",
    surfaceColor: Color? = null,
    content: @Composable () -> Unit
) {
    val darkTheme = when (theme) {
        "dark" -> true
        "light" -> false
        else -> isSystemInDarkTheme()
    }
    val colors = if (darkTheme) Dark else Light
    MaterialTheme(colorScheme = colors) {
        // A null ripple configuration disables Material 3's ripple/press indication
        // throughout the app while preserving click, long-press, and gesture behavior.
        CompositionLocalProvider(LocalRippleConfiguration provides null) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = surfaceColor ?: MaterialTheme.colorScheme.background,
                contentColor = MaterialTheme.colorScheme.onBackground,
                content = content
            )
        }
    }
}
