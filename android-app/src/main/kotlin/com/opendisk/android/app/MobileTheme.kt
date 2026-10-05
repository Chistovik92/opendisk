package com.opendisk.android.app

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/** Запасная палитра для Android до 12: синяя, как у ES File Explorer. */
private val FallbackLight = lightColorScheme(
    primary = Color(0xFF2273E5),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD8E6FD),
    onPrimaryContainer = Color(0xFF0B2E63),
    secondaryContainer = Color(0xFFDCE8FA),
    background = Color(0xFFF4F5F7),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFE8ECF2),
)

private val FallbackDark = darkColorScheme(
    primary = Color(0xFF9ABFFF),
    onPrimary = Color(0xFF0B2E63),
    primaryContainer = Color(0xFF1F3A66),
    onPrimaryContainer = Color(0xFFD8E6FD),
    secondaryContainer = Color(0xFF26364F),
)

/** Крупные скругления Material You: карточки, окна и меню мягче, чем в Material 2. */
private val SoftShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

/**
 * Тема приложения на телефоне: раскладка как у ES File Explorer, дух —
 * Material You.
 *
 * С Android 12 цвета берутся из обоев, как у системных приложений, и
 * верхние панели красятся в тональный контейнер этой палитры, а не в
 * жёсткий синий. Раскладка (круглые значки, кольцо заполнения, панель
 * инструментов) от ES, а оттенки — свои у каждого телефона. До Android 12
 * динамических цветов нет, там запасная синяя палитра.
 */
@Composable
fun OpenDiskTheme(theme: MobileTheme, content: @Composable () -> Unit) {
    val dark = when (theme) {
        MobileTheme.AUTO -> isSystemInDarkTheme()
        MobileTheme.LIGHT -> false
        MobileTheme.DARK -> true
    }
    val context = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> FallbackDark
        else -> FallbackLight
    }
    MaterialTheme(colorScheme = colors, shapes = SoftShapes, content = content)
}
