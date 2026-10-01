package com.aura.agent.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val AuraDarkColorScheme = darkColorScheme(
    primary = Color(0xFF0EA5E9),
    onPrimary = Color.White,
    secondary = Color(0xFF38BDF8),
    background = Color(0xFF0A0E17),
    surface = Color(0xFF0F172A),
    onBackground = Color.White,
    onSurface = Color.White,
    error = Color(0xFFEF4444)
)

@Composable
fun AuraTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = AuraDarkColorScheme,
        content = content
    )
}
