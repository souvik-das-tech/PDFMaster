package com.bbcl.pdfmaster

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/** Brand colours: the logo's red, plus an accent for each tool family. */
object Brand {
    val red = Color(0xFFE53935)
    val redDark = Color(0xFFB71C1C)
    val gradient = Brush.linearGradient(listOf(Color(0xFFFF5252), Color(0xFFC62828)))

    val create = Color(0xFFE53935)
    val organize = Color(0xFF1E88E5)
    val optimize = Color(0xFF43A047)
    val secure = Color(0xFF8E24AA)
    val edit = Color(0xFFFB8C00)
    val convert = Color(0xFF00897B)
}

private val light = lightColorScheme(
    primary = Color(0xFFC62828),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDAD6),
    onPrimaryContainer = Color(0xFF410002),
    secondary = Color(0xFF775652),
    secondaryContainer = Color(0xFFFFDAD6),
    onSecondaryContainer = Color(0xFF2C1512),
    tertiary = Color(0xFF715B2E),
    background = Color(0xFFFFF8F7),
    surface = Color(0xFFFFF8F7),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFFF0EE),
    surfaceContainer = Color(0xFFFCEAE7),
    surfaceContainerHigh = Color(0xFFF6E4E2),
    surfaceContainerHighest = Color(0xFFF1DEDC),
    error = Color(0xFFBA1A1A),
)

private val dark = darkColorScheme(
    primary = Color(0xFFFFB4AB),
    onPrimary = Color(0xFF690005),
    primaryContainer = Color(0xFF93000A),
    onPrimaryContainer = Color(0xFFFFDAD6),
    secondary = Color(0xFFE7BDB7),
    secondaryContainer = Color(0xFF5D3F3B),
    onSecondaryContainer = Color(0xFFFFDAD6),
    tertiary = Color(0xFFE0C38C),
    background = Color(0xFF1A1111),
    surface = Color(0xFF1A1111),
    surfaceContainerLowest = Color(0xFF140C0B),
    surfaceContainerLow = Color(0xFF231918),
    surfaceContainer = Color(0xFF271D1C),
    surfaceContainerHigh = Color(0xFF322827),
    surfaceContainerHighest = Color(0xFF3D3231),
    error = Color(0xFFFFB4AB),
)

@Composable
fun PdfMasterTheme(content: @Composable () -> Unit) =
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) dark else light, content = content)
