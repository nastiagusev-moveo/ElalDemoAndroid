package com.elal.demoapp.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val LightColorScheme = lightColorScheme(
    primary = ElAlBlue,
    onPrimary = White,
    secondary = ElAlBlueDark,
    onSecondary = White,
    error = Red,
    onError = White,
    background = White,
    onBackground = ElAlBlueDark,
    surface = White,
    onSurface = ElAlBlueDark
)

@Composable
fun ElAlDemoTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LightColorScheme,
        typography = Typography,
        content = content
    )
}
