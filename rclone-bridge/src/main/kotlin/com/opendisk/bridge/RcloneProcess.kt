package com.opendisk.bridge

import java.io.File

/**
 * Управляет жизненным циклом дочернего процесса `rclone rcd`.
 *
 * OpenDisk не патчит и не форкает rclone — используем его как внешний бинарник,
 * общаясь только через его же RC (Remote Control) HTTP API. См. docs/ARCHITECTURE.md.
 *
 * Сам бинарник пользователю ставить не нужно: он едет внутри дистрибутива
 * OpenDisk (см. задачу downloadRclone в composeApp/build.gradle.kts).
 */
class RcloneProcess(
    private val rclonePath: String = findRcloneBinary(),
    private val rcAddr: String = "127.0.0.1:5572",
    private val configPath: File? = null,
) {
    private var process: Process? = null

    /** Адрес, по которому RcloneClient должен стучаться после запуска. */
    val rcBaseUrl: String get() = "http://$rcAddr"

    fun start() {
        check(process == null) { "rclone rcd уже запущен" }

        val args = mutableListOf(
            rclonePath,
            "rcd",
            "--rc-addr=$rcAddr",
            "--rc-no-auth", // локальный процесс на localhost, отдельная авторизация избыточна
        )
        configPath?.let { args += "--config=${it.absolutePath}" }

        process = ProcessBuilder(args)
            .redirectErrorStream(true)
            .start()
    }

    fun stop() {
        process?.destroy()
        process = null
    }

    fun isRunning(): Boolean = process?.isAlive == true

    /** Откуда взялся бинарник — GUI показывает это пользователю. */
    enum class Source {
        /** Указан явно через свойство или переменную окружения. */
        OVERRIDE,

        /** Встроен в дистрибутив OpenDisk — основной сценарий. */
        BUNDLED,

        /** Найден в PATH: пользователь поставил rclone сам. */
        SYSTEM_PATH,
    }

    data class Located(val file: File, val source: Source)

    companion object {
        /** Системное свойство, которым можно указать свой бинарник rclone. */
        const val OVERRIDE_PROPERTY = "opendisk.rclone.path"

        /** Переменная окружения с тем же смыслом — удобнее при запуске из IDE. */
        const val OVERRIDE_ENV = "OPENDISK_RCLONE"

        /**
         * Свойство, которое Compose Desktop проставляет и при `run`, и в собранном
         * дистрибутиве. Указывает на каталог с ресурсами приложения, куда сборка
         * кладёт встроенный rclone.
         */
        const val COMPOSE_RESOURCES_PROPERTY = "compose.application.resources.dir"

        private val isWindows: Boolean
            get() = System.getProperty("os.name").lowercase().contains("win")

        private val binaryName: String
            get() = if (isWindows) "rclone.exe" else "rclone"

        /**
         * Ищет rclone в порядке приоритета: явное указание → встроенный в
         * дистрибутив → системный из PATH. Возвращает null, если не нашли нигде.
         */
        fun locate(): Located? =
            override()?.let { Located(it, Source.OVERRIDE) }
                ?: bundled()?.let { Located(it, Source.BUNDLED) }
                ?: onSystemPath()?.let { Located(it, Source.SYSTEM_PATH) }

        /**
         * Путь к rclone для запуска процесса. Если бинарник не найден нигде,
         * возвращает голое имя — пусть ОС попробует сама, а GUI через [locate]
         * покажет пользователю внятную ошибку.
         */
        fun findRcloneBinary(): String = locate()?.file?.absolutePath ?: binaryName

        private fun override(): File? {
            val raw = System.getProperty(OVERRIDE_PROPERTY) ?: System.getenv(OVERRIDE_ENV)
            return raw?.takeIf { it.isNotBlank() }?.let { File(it) }?.takeIf { it.isFile }
        }

        private fun bundled(): File? {
            val resourcesDir = System.getProperty(COMPOSE_RESOURCES_PROPERTY) ?: return null
            val candidate = File(resourcesDir, binaryName)
            if (!candidate.isFile) return null
            // Установщики на Unix умеют терять бит выполнения — восстанавливаем молча.
            if (!candidate.canExecute()) {
                candidate.setExecutable(true)
            }
            return candidate
        }

        private fun onSystemPath(): File? {
            val pathDirs = System.getenv("PATH")?.split(File.pathSeparator).orEmpty()
            return pathDirs.asSequence()
                .map { File(it, binaryName) }
                .firstOrNull { it.isFile && it.canExecute() }
        }
    }
}
