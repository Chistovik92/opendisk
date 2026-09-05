package com.opendisk.bridge

import java.io.File

/**
 * Управляет жизненным циклом дочернего процесса `rclone rcd`.
 *
 * OpenDisk не патчит и не форкает rclone — используем его как внешний бинарник,
 * общаясь только через его же RC (Remote Control) HTTP API. См. docs/ARCHITECTURE.md.
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

    companion object {
        /**
         * Ищет rclone в PATH. Если не найден — вызывающий код должен предложить
         * пользователю установку (см. Этап 0 в ROADMAP.md — на каждой ОС свой сценарий).
         */
        fun findRcloneBinary(): String {
            val pathDirs = System.getenv("PATH")?.split(File.pathSeparator).orEmpty()
            val binaryName = if (System.getProperty("os.name").lowercase().contains("win")) {
                "rclone.exe"
            } else {
                "rclone"
            }
            for (dir in pathDirs) {
                val candidate = File(dir, binaryName)
                if (candidate.exists() && candidate.canExecute()) {
                    return candidate.absolutePath
                }
            }
            // Не нашли — возвращаем голое имя, пусть ОС попытается сама через PATH,
            // а вызывающий код (GUI) отдельно проверит результат и покажет подсказку.
            return binaryName
        }
    }
}
