package com.opendisk.app

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

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
    /**
     * Показывать диск обычным локальным, а не сетевым. Только Windows.
     *
     * По умолчанию выключено, и это осознанно: как обычный диск Windows
     * индексирует его и опрашивает при каждом открытии «Этого компьютера»,
     * а для диска, за которым сеть, это оборачивается зависанием проводника.
     * Но у сетевого режима есть цена — диск переезжает в раздел «Сетевые
     * расположения», и кто привык искать его среди дисков, там его не находит.
     * Поэтому выбор остаётся за пользователем.
     */
    val showAsLocalDrive: Boolean = false,
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
    /**
     * Оформление: auto, light или dark.
     *
     * По умолчанию — как в системе. Выбор всё же оставлен: тёмная система
     * не всегда означает желание видеть тёмным каждое окно, а на Linux
     * прочитать системную тему получается не в любом окружении.
     */
    val theme: String = ThemeChoice.AUTO.code,
    /**
     * Проверять при запуске, не вышла ли новая версия.
     *
     * Отдельная настройка, потому что это единственное обращение приложения
     * в сеть помимо самих облаков — кому-то оно не нужно, и отключить его
     * должно быть можно.
     */
    val checkUpdates: Boolean = true,
    /**
     * Показывать в трее скорость сети и недавние файлы.
     *
     * Ради этого раз в пару секунд спрашивается rclone, пока приложение
     * живёт свёрнутым, — кому-то это лишнее, и выключить можно.
     */
    val trayStats: Boolean = true,
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

    /**
     * Читать и менять настройки приходится из разных корутин: «прочитал, поправил,
     * записал» без замка теряло правку соседа — настройки двух облаков,
     * сохранённые одновременно, затирали друг друга.
     */
    private val lock = Any()

    private fun read(): Stored {
        if (!file.isFile) return Stored()
        return runCatching { json.decodeFromString<Stored>(file.readText()) }.getOrElse {
            // Испорченный файл не выбрасываем молча: следующая запись положила
            // бы на его место значения по умолчанию, и точки подключения всех
            // облаков пропали бы насовсем. Копия остаётся рядом.
            runCatching { file.copyTo(File(file.parentFile, file.name + ".broken"), overwrite = true) }
            Stored()
        }
    }

    fun load(): Map<String, CloudSettings> = synchronized(lock) { read().clouds }

    fun global(): GlobalSettings = synchronized(lock) { read().global }

    fun updateGlobal(updated: GlobalSettings) = synchronized(lock) {
        write(read().copy(global = updated))
    }

    fun forCloud(name: String): CloudSettings = load()[name] ?: CloudSettings()

    fun update(name: String, settings: CloudSettings) = synchronized(lock) {
        save(read().clouds + (name to settings))
    }

    fun forget(name: String) = synchronized(lock) {
        save(read().clouds - name)
    }

    /** При переименовании облака настройки должны переехать вместе с ним. */
    fun rename(from: String, to: String) = synchronized(lock) {
        val current = read().clouds
        val moved = current[from] ?: return@synchronized
        save(current - from + (to to moved))
    }

    private fun save(clouds: Map<String, CloudSettings>) {
        write(read().copy(clouds = clouds))
    }

    /**
     * Запись через временный файл и переименование: обрыв посередине (питание,
     * убитый процесс, полный диск) оставляет прежний файл целым, а не обрезок,
     * из которого потом читаются одни значения по умолчанию.
     */
    private fun write(stored: Stored) {
        runCatching {
            file.parentFile?.mkdirs()
            val temp = File(file.parentFile, file.name + ".tmp")
            temp.writeText(json.encodeToString(Stored.serializer(), stored))
            try {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (e: AtomicMoveNotSupportedException) {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
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
