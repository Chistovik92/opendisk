package com.opendisk.app

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Настройки подключения одного облака.
 *
 * Живут отдельно от `rclone.conf`: тот принадлежит rclone, у него свой формат
 * и его же правит консольный клиент. Свои ключи туда класть нельзя.
 */
@Serializable
data class CloudSettings(
    /** Режим кэширования VFS: off, minimal, writes или full. */
    val cacheMode: String = DEFAULT_CACHE_MODE,
    /** Куда монтировать. null — подобрать автоматически при подключении. */
    val mountPoint: String? = null,
) {
    companion object {
        /**
         * По умолчанию `writes`, хотя у самого rclone это `off`.
         *
         * С `off` файл нельзя открыть одновременно на чтение и запись, а
         * открытый на запись нельзя перематывать — обычные программы вроде
         * офисных редакторов на таком диске просто не сохраняют файлы.
         * Пользователь, который подключает облако как диск, ожидает, что оно
         * работает; кто хочет экономить место, переключит режим осознанно.
         */
        const val DEFAULT_CACHE_MODE = "writes"
    }
}

/** Режим кэширования и человеческое объяснение, что он означает на практике. */
data class CacheModeOption(
    val value: String,
    val title: String,
    val explanation: String,
)

/**
 * Порядок — от самого экономного к самому совместимому. Формулировки про
 * последствия, а не про устройство: пользователю важно, что именно сломается
 * или займёт место, а не как устроен VFS.
 */
val CACHE_MODES = listOf(
    CacheModeOption(
        value = "off",
        title = "Без кэша",
        explanation = "Ничего не сохраняется на диск. Занимает меньше всего места, но " +
            "многие программы не смогут сохранять файлы прямо в облако.",
    ),
    CacheModeOption(
        value = "minimal",
        title = "Минимальный",
        explanation = "Кэшируется только то, без чего не обойтись. Компромисс между " +
            "экономией места и совместимостью.",
    ),
    CacheModeOption(
        value = "writes",
        title = "При записи — рекомендуется",
        explanation = "Файл, открытый на запись, сначала пишется на диск и уже потом " +
            "уходит в облако. Работает с обычными программами, читаются файлы по-прежнему " +
            "по требованию.",
    ),
    CacheModeOption(
        value = "full",
        title = "Полный",
        explanation = "Всё, что открываете, целиком скачивается в кэш. Максимальная " +
            "совместимость и быстрый повторный доступ, но занимает место на диске.",
    ),
)

/**
 * Файл настроек приложения.
 *
 * Ошибки чтения намеренно не всплывают: испорченный или недоступный файл
 * настроек не повод не запускать приложение — просто вернутся значения
 * по умолчанию.
 */
class AppSettings(private val file: File) {

    @Serializable
    private data class Stored(val clouds: Map<String, CloudSettings> = emptyMap())

    fun load(): Map<String, CloudSettings> = runCatching {
        if (!file.isFile) return emptyMap()
        json.decodeFromString<Stored>(file.readText()).clouds
    }.getOrDefault(emptyMap())

    fun forCloud(name: String): CloudSettings = load()[name] ?: CloudSettings()

    fun update(name: String, settings: CloudSettings) {
        save(load() + (name to settings))
    }

    fun forget(name: String) {
        save(load() - name)
    }

    /** При переименовании облака настройки должны переехать вместе с ним. */
    fun rename(from: String, to: String) {
        val current = load()
        val moved = current[from] ?: return
        save(current - from + (to to moved))
    }

    private fun save(clouds: Map<String, CloudSettings>) {
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(json.encodeToString(Stored.serializer(), Stored(clouds)))
        }
    }

    companion object {
        private val json = Json {
            prettyPrint = true
            ignoreUnknownKeys = true
        }

        /**
         * Путь к файлу настроек по правилам ОС: `%APPDATA%` на Windows,
         * `$XDG_CONFIG_HOME` или `~/.config` на остальных — там же, где
         * привыкли лежать настройки приложений.
         */
        fun defaultFile(
            env: (String) -> String? = System::getenv,
            userHome: String = System.getProperty("user.home"),
            osName: String = System.getProperty("os.name"),
        ): File {
            val home = File(userHome)
            val base = if (osName.lowercase().contains("win")) {
                env("APPDATA")?.takeIf { it.isNotBlank() } ?: File(home, "AppData/Roaming").path
            } else {
                env("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() } ?: File(home, ".config").path
            }
            return File(File(base), "opendisk/settings.json")
        }
    }
}
