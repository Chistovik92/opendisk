package com.opendisk.app

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Запуск приложения при входе в систему.
 *
 * Делается штатными средствами каждой ОС и только для текущего пользователя:
 * на Windows — значение в ветке реестра `Run` пользователя, на Linux — файл
 * `.desktop` в `~/.config/autostart`. Прав администратора это не требует,
 * в отличие от служб и общесистемных автозапусков.
 */
object Autostart {

    /** Чем именно запускать приложение. */
    data class Target(val command: String)

    private val osName: String get() = System.getProperty("os.name").lowercase()
    private val isWindows: Boolean get() = osName.contains("win")
    private val isMac: Boolean get() = osName.contains("mac") || osName.contains("darwin")

    /** Поддерживается ли автозапуск на этой системе. */
    fun isSupported(): Boolean = isWindows || !isMac

    fun isEnabled(): Boolean = when {
        isWindows -> windowsValue() != null
        isMac -> false
        else -> linuxFile().isFile
    }

    /**
     * Включает или выключает автозапуск.
     *
     * @return true, если состояние после вызова соответствует запрошенному.
     */
    fun setEnabled(enabled: Boolean): Boolean {
        val target = resolveTarget() ?: return false
        return when {
            isWindows -> setWindows(enabled, target)
            isMac -> false
            else -> setLinux(enabled, target)
        }
    }

    /**
     * Команда запуска установленного приложения.
     *
     * Берётся из того, чем запущен текущий процесс: у jpackage это лаунчер
     * рядом с приложением. Запускать автозапуском `java -jar` из каталога
     * сборки бессмысленно, поэтому при работе из исходников возвращаем null.
     */
    private fun resolveTarget(): Target? {
        val launcher = System.getProperty("jpackage.app-path")?.takeIf { it.isNotBlank() }
        if (launcher != null && File(launcher).isFile) return Target(launcher)
        return null
    }

    // --- Windows ------------------------------------------------------------

    private fun setWindows(enabled: Boolean, target: Target): Boolean {
        val command = if (enabled) {
            listOf(
                "reg", "add", RUN_KEY, "/v", VALUE_NAME, "/t", "REG_SZ",
                "/d", "\"${target.command}\" $HIDDEN_FLAG", "/f",
            )
        } else {
            listOf("reg", "delete", RUN_KEY, "/v", VALUE_NAME, "/f")
        }
        // Удаление отсутствующего значения — не ошибка: состояние уже нужное.
        val ok = run(command) || !enabled
        return ok && isEnabled() == enabled
    }

    private fun windowsValue(): String? = runCatching {
        val process = ProcessBuilder("reg", "query", RUN_KEY, "/v", VALUE_NAME)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return@runCatching null
        }
        if (process.exitValue() != 0) return@runCatching null
        output.lineSequence()
            .firstOrNull { it.contains(VALUE_NAME) && it.contains("REG_SZ") }
            ?.substringAfter("REG_SZ")
            ?.trim()
    }.getOrNull()

    // --- Linux --------------------------------------------------------------

    private fun setLinux(enabled: Boolean, target: Target): Boolean = runCatching {
        val file = linuxFile()
        if (!enabled) {
            file.delete()
            return@runCatching !file.exists()
        }
        file.parentFile?.mkdirs()
        file.writeText(desktopEntry(target))
        file.isFile
    }.getOrDefault(false)

    private fun linuxFile(): File =
        File(configHome(), "autostart/opendisk.desktop")

    private fun configHome(): File {
        val xdg = System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() }
        return if (xdg != null) File(xdg) else File(System.getProperty("user.home"), ".config")
    }

    /**
     * `X-GNOME-Autostart-enabled` понимают GNOME и производные; остальные
     * среды просто игнорируют неизвестный ключ.
     */
    internal fun desktopEntry(target: Target): String = """
        [Desktop Entry]
        Type=Application
        Name=OpenDisk
        Comment=Открытый клиент облачных дисков
        Exec=${target.command} $HIDDEN_FLAG
        Terminal=false
        X-GNOME-Autostart-enabled=true
    """.trimIndent() + "\n"

    // --- Общее --------------------------------------------------------------

    private fun run(command: List<String>): Boolean = runCatching {
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return@runCatching false
        }
        process.exitValue() == 0
    }.getOrDefault(false)

    private const val RUN_KEY = """HKCU\Software\Microsoft\Windows\CurrentVersion\Run"""
    private const val VALUE_NAME = "OpenDisk"
    private const val TIMEOUT_SECONDS = 10L
}
