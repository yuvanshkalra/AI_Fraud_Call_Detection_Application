package com.fraudcalldetector.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// ── Palette ──────────────────────────────────────────────────────────────────

private val fraudRed   = Color(0xFFB00020)
private val fraudRedLight = Color(0xFFE53935)
private val safeGreen  = Color(0xFF2E7D32)
private val primaryBlue = Color(0xFF1565C0)
private val primaryBlueLight = Color(0xFF1E88E5)
private val surfaceLight = Color(0xFFF8F9FA)
private val surfaceDark  = Color(0xFF121212)

private val LightColorScheme = lightColorScheme(
    primary          = primaryBlue,
    onPrimary        = Color.White,
    primaryContainer = Color(0xFFD1E4FF),
    secondary        = Color(0xFF535F70),
    onSecondary      = Color.White,
    error            = fraudRed,
    background       = Color(0xFFFAFAFA),
    onBackground     = Color(0xFF1A1C1E),
    surface          = surfaceLight,
    onSurface        = Color(0xFF1A1C1E),
    surfaceVariant   = Color(0xFFDFE2EB),
)

private val DarkColorScheme = darkColorScheme(
    primary          = primaryBlueLight,
    onPrimary        = Color(0xFF00315C),
    primaryContainer = Color(0xFF004884),
    secondary        = Color(0xFFBBC7DB),
    onSecondary      = Color(0xFF253140),
    error            = fraudRedLight,
    background       = surfaceDark,
    onBackground     = Color(0xFFE2E2E6),
    surface          = Color(0xFF1E1E1E),
    onSurface        = Color(0xFFE2E2E6),
    surfaceVariant   = Color(0xFF3F4759),
)

// ── Theme entry point ─────────────────────────────────────────────────────────

@Composable
fun FraudCallDetectorTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = FraudCallTypography,
        content = content
    )
}
