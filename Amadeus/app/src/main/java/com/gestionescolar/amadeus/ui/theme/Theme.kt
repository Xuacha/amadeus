package com.gestionescolar.amadeus.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val HellsingColorScheme = darkColorScheme(
    background = HellsingBlack,
    surface = HellsingCardBlack,
    primary = HellsingCrimson,
    onPrimary = HellsingGold,
    secondary = HellsingRed,
    onBackground = HellsingParchment,
    onSurface = HellsingParchment,
    outline = HellsingBorder,
    error = HellsingError
)

@Composable
fun AmadeusTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = HellsingColorScheme,
        typography = Typography,
        content = content
    )
}
