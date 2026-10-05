package com.opendisk.android.app

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val EsBlue = Color(0xFF2273E5)

private val EsLight = lightColorScheme(
    primary = EsBlue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD8E6FD),
    onPrimaryContainer = Color(0xFF0B2E63),
    secondaryContainer = Color(0xFFDCE8FA),
    background = Color(0xFFF4F5F7),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFE8ECF2),
)

private val EsDark = darkColorScheme(
    primary = Color(0xFF1B5FC4),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF1F3A66),
    secondaryContainer = Color(0xFF26364F),
)

/**
 * Тема приложения на телефоне.
 *
 * Светлая или тёмная — по системе или по выбору человека, но палитра одна:
 * синяя, как у ES File Explorer (см. docs/MOBILE-UI.md).
 */
@Composable
fun OpenDiskTheme(theme: MobileTheme, content: @Composable () -> Unit) {
    val dark = when (theme) {
        MobileTheme.AUTO -> isSystemInDarkTheme()
        MobileTheme.LIGHT -> false
        MobileTheme.DARK -> true
    }
    // Палитра ES File Explorer: синий вместо цветов обоев. Системная палитра
    // Android 12 красила верхнюю панель во что угодно, и экран переставал
    // быть узнаваемым; в `docs/MOBILE-UI.md` синий — часть договора.
    val colors = if (dark) EsDark else EsLight

    MaterialTheme(colorScheme = colors, content = content)
}
