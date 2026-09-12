package com.opendisk.android.app

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/**
 * Тема приложения на телефоне.
 *
 * «Как в системе» здесь значит больше, чем на компьютере: с Android 12
 * система отдаёт приложениям целую палитру, построенную от обоев — те же
 * цвета, что у системных кнопок и переключателей. Берём именно её, а не свою:
 * приложение, которое на телефоне выглядит чужим, выглядит и ненадёжным.
 *
 * На выбранной вручную теме динамические цвета не применяются: человек
 * попросил конкретное оформление, а не «как у системы».
 */
@Composable
fun OpenDiskTheme(theme: MobileTheme, content: @Composable () -> Unit) {
    val dark = when (theme) {
        MobileTheme.AUTO -> isSystemInDarkTheme()
        MobileTheme.LIGHT -> false
        MobileTheme.DARK -> true
    }
    val context = LocalContext.current
    val dynamic = theme == MobileTheme.AUTO && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    val colors = when {
        dynamic && dark -> dynamicDarkColorScheme(context)
        dynamic -> dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }

    MaterialTheme(colorScheme = colors, content = content)
}
