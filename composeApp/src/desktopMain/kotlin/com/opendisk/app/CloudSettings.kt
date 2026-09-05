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
    /** Подключать это облако сразу при запуске приложения. */
    val mountOnStartup: Boolean = false,
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

/**
 * Общие настройки приложения — не привязанные к конкретному облаку.
 */
@Serializable
data class GlobalSettings(
    /** Запускать приложение при входе в систему. */
    val autostart: Boolean = false,
    /**
     * Ограничение скорости в формате rclone: «1M», «500k», «off».
     * Общее на все облака — rclone умеет ограничивать только глобально.
     */
    val bandwidthLimit: String = BANDWIDTH_UNLIMITED,
    /** Язык интерфейса: auto, ru или en. */
    val language: String = Language.AUTO.code,
) {
    companion object {
        const val BANDWIDTH_UNLIMITED = "off"
    }
}

/**
 * Файл настроек приложения.
 *
 * Ошибки чтения намеренно не всплывают: испорченный или недоступный файл
 * настроек не повод не запускать приложение — просто вернутся значения
 * по умолчанию.
 */
class AppSettings(private val file: File) {

    /** Путь к файлу — показывается в «О приложении» для разбора проблем. */
    val filePath: String get() = file.absolutePath

    @Serializable
    private data class Stored(
        val clouds: Map<String, CloudSettings> = emptyMap(),
        val global: GlobalSettings = GlobalSettings(),
    )

    private fun read(): Stored = runCatching {
        if (!file.isFile) return Stored()
        json.decodeFromString<Stored>(file.readText())
    }.getOrDefault(Stored())

    fun load(): Map<String, CloudSettings> = read().clouds

    fun global(): GlobalSettings = read().global

    fun updateGlobal(updated: GlobalSettings) {
        write(read().copy(global = updated))
    }

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
        write(read().copy(clouds = clouds))
    }

    private fun write(stored: Stored) {
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(json.encodeToString(Stored.serializer(), stored))
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
