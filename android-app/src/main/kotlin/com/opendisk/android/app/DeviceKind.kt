package com.opendisk.android.app

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.dp

/**
 * На чём запущено приложение. Один apk на телефоны, планшеты, приставки
 * Android TV и часы Wear OS: rclone внутри один и тот же, отличается только
 * то, как разложен экран.
 */
enum class DeviceKind {
    /** Телефон или планшет — сенсорный экран, всё как было. */
    HANDHELD,

    /** Телевизор или приставка: пульт вместо пальца, экран с полями по краям. */
    TV,

    /** Часы: крошечный, часто круглый экран. */
    WATCH;

    /**
     * Поля вокруг содержимого.
     *
     * Телевизор может обрезать края кадра (overscan) — Google советует
     * держать содержимое в 48×27 dp от краёв. На круглых часах углы
     * квадратного экрана не видны вовсе: отступ примерно в седьмую часть
     * ширины прячет от них только пустоту.
     */
    fun safePadding(roundScreen: Boolean): PaddingValues = when (this) {
        HANDHELD -> PaddingValues(0.dp)
        TV -> PaddingValues(horizontal = 48.dp, vertical = 27.dp)
        WATCH -> if (roundScreen) PaddingValues(horizontal = 24.dp, vertical = 16.dp) else PaddingValues(4.dp)
    }

    companion object {
        fun of(context: Context): DeviceKind {
            val packages = context.packageManager
            val uiMode = context.getSystemService(UiModeManager::class.java)?.currentModeType
            return when {
                packages.hasSystemFeature(PackageManager.FEATURE_WATCH) ||
                    uiMode == Configuration.UI_MODE_TYPE_WATCH -> WATCH
                packages.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
                    uiMode == Configuration.UI_MODE_TYPE_TELEVISION -> TV
                else -> HANDHELD
            }
        }
    }
}
