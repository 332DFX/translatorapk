package com.translatorapk.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val DarkColors = darkColorScheme(
    primary = Violet,
    onPrimary = Ink,
    primaryContainer = VioletDark,
    onPrimaryContainer = TextPrimary,
    secondary = Teal,
    onSecondary = Ink,
    tertiary = Amber,
    background = Ink,
    onBackground = TextPrimary,
    surface = InkSurface,
    onSurface = TextPrimary,
    surfaceVariant = InkSurfaceHigh,
    onSurfaceVariant = TextSecondary,
    outline = InkOutline,
    error = Rose,
    onError = Ink
)

private val LightColors = lightColorScheme(
    primary = VioletDark,
    onPrimary = PaperSurface,
    primaryContainer = PaperSurfaceHigh,
    onPrimaryContainer = PaperText,
    secondary = androidx.compose.ui.graphics.Color(0xFF0E9E90),
    onSecondary = PaperSurface,
    tertiary = Amber,
    background = Paper,
    onBackground = PaperText,
    surface = PaperSurface,
    onSurface = PaperText,
    surfaceVariant = PaperSurfaceHigh,
    onSurfaceVariant = PaperTextSecondary,
    outline = PaperOutline,
    error = Rose,
    onError = PaperSurface
)

@Composable
fun TranslatorTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = AppTypography,
        content = content
    )
}
