package com.example.autodroid.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val LightColorScheme = lightColorScheme(
    primary = Primary,
    onPrimary = OnPrimary,
    primaryContainer = Primary,
    onPrimaryContainer = OnPrimary,
    secondary = Secondary,
    onSecondary = OnSecondary,
    secondaryContainer = Disabled,
    onSecondaryContainer = Primary,
    background = Background,
    onBackground = Primary,
    surface = Background,
    onSurface = Primary,
    surfaceVariant = Disabled,
    onSurfaceVariant = Secondary,
    outline = Border,
    outlineVariant = Disabled,
    error = Error,
    onError = OnPrimary,
    scrim = Primary,
    inverseSurface = Primary,
    inverseOnSurface = Background,
    inversePrimary = Secondary,
)

@Composable
fun RedialTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LightColorScheme,
        content = content,
    )
}
