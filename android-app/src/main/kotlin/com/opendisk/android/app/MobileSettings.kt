package com.opendisk.android.app

import android.content.Context
import android.content.SharedPreferences

/** Настройки приложения на телефоне и список подключённых облаков. */
data class MobilePreferences(
    val theme: MobileTheme = MobileTheme.AUTO,
    val language: MobileLanguage = MobileLanguage.AUTO,
    /**
     * Облака, отданные системе: они видны в «Файлах» и в окнах выбора файла.
     * Множество имён, а не флаг у облака, потому что этот список читает ещё
     * и поставщик документов — его система запускает сама, без экрана.
     */
    val connected: Set<String> = emptySet(),
)

/**
 * Хранилище настроек телефона.
 *
 * Обычные SharedPreferences, а не файл рядом с `rclone.conf`: этот список
 * читает и [OpenDiskDocumentsProvider], которого система поднимает когда
 * захочет — иногда раньше, чем экран приложения вообще создан.
 */
class MobileSettings(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun read(): MobilePreferences = MobilePreferences(
        theme = MobileTheme.fromCode(prefs.getString(KEY_THEME, null)),
        language = MobileLanguage.fromCode(prefs.getString(KEY_LANGUAGE, null)),
        connected = prefs.getStringSet(KEY_CONNECTED, emptySet()).orEmpty().toSet(),
    )

    fun write(preferences: MobilePreferences) {
        prefs.edit()
            .putString(KEY_THEME, preferences.theme.code)
            .putString(KEY_LANGUAGE, preferences.language.code)
            // Копия множества обязательна: SharedPreferences хранит переданный
            // набор по ссылке, и его последующая правка молча меняет сохранённое.
            .putStringSet(KEY_CONNECTED, preferences.connected.toSet())
            .apply()
    }

    /** Переименование или удаление облака не должно оставлять его в списке. */
    fun forget(cloud: String) {
        val current = read()
        if (cloud !in current.connected) return
        write(current.copy(connected = current.connected - cloud))
    }

    private companion object {
        const val NAME = "opendisk"
        const val KEY_THEME = "theme"
        const val KEY_LANGUAGE = "language"
        const val KEY_CONNECTED = "connected"
    }
}
