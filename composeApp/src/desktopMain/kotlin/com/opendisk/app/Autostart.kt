package com.opendisk.app

import java.io.File
import java.util.Base64
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

    /** Почему автозапуск сейчас недоступен. */
    enum class Unavailable {
        /** Система не умеет — на macOS автозапуск пока не сделан. */
        OPERATING_SYSTEM,

        /** Нечем запускать: приложение работает из каталога сборки. */
        NOT_INSTALLED,
    }

    /**
     * Причина недоступности или null, если всё в порядке.
     *
     * Причин ровно две, и путать их нельзя: «система не умеет» и «нечем
     * запускать» требуют от пользователя разного — во втором случае достаточно
     * поставить приложение.
     */
    fun unavailableReason(): Unavailable? = when {
        isMac && !isWindows -> Unavailable.OPERATING_SYSTEM
        resolveTarget() == null -> Unavailable.NOT_INSTALLED
        else -> null
    }

    /**
     * Можно ли включить автозапуск здесь и сейчас.
     *
     * Мало того, что ОС это умеет, — нужно ещё знать, чем именно запускать
     * приложение. Предлагать галочку, которая заведомо не сработает, нечестно:
     * пользователь получал ошибку уже после нажатия.
     */
    fun isSupported(): Boolean = unavailableReason() == null

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
     * Запускать автозапуском `java -jar` из каталога сборки бессмысленно,
     * поэтому при работе из исходников возвращается null.
     *
     * Раньше путь брался только из свойства `jpackage.app-path` — и автозапуск
     * не работал вообще нигде. Это свойство лаунчер выставляет начиная с JDK 18,
     * а собираемся мы на 17: в установленном приложении его просто нет (строки
     * `jpackage.app-path` нет даже внутри самого `OpenDisk.exe`). Галочка при
     * этом была доступна, а нажатие давало ошибку.
     *
     * Поэтому путь выводим из расположения runtime, а свойство остаётся первым
     * вариантом — на будущее, когда сборка переедет на JDK новее.
     */
    private fun resolveTarget(): Target? {
        // AppImage — единый файл, и запускать нужно именно его, а не лаунчер
        // внутри временно распакованного образа: тот исчезнет после выхода.
        System.getenv(APPIMAGE_ENV)
            ?.takeIf { it.isNotBlank() && File(it).isFile }
            ?.let { return Target(it) }

        System.getProperty("jpackage.app-path")
            ?.takeIf { it.isNotBlank() && File(it).isFile }
            ?.let { return Target(it) }

        val javaHome = System.getProperty("java.home")?.let(::File) ?: return null
        return launcherNear(javaHome, isWindows)?.let { Target(it.absolutePath) }
    }

    /**
     * Ищет лаунчер приложения по расположению runtime.
     *
     * Раскладки jpackage разные и обе проверены на наших пакетах:
     *
     * - Windows: `<приложение>\OpenDisk.exe` рядом с `<приложение>\runtime`;
     * - Linux: `<приложение>/bin/OpenDisk` при runtime в `<приложение>/lib/runtime`.
     *
     * Если ничего не нашлось, это запуск из исходников — там лаунчера и нет.
     */
    internal fun launcherNear(javaHome: File, windows: Boolean): File? {
        val candidates = if (windows) {
            listOf(File(javaHome.parentFile, "$LAUNCHER_NAME.exe"))
        } else {
            listOf(
                File(javaHome.parentFile?.parentFile, "bin/$LAUNCHER_NAME"),
                File(javaHome.parentFile, "bin/$LAUNCHER_NAME"),
            )
        }
        return candidates.firstOrNull { it.isFile }
    }

    // --- Windows ------------------------------------------------------------

    private fun setWindows(enabled: Boolean, target: Target): Boolean {
        runPowerShell(if (enabled) enableScript(RUN_KEY, VALUE_NAME, target) else disableScript(RUN_KEY, VALUE_NAME))
        return isEnabled() == enabled
    }

    /**
     * Скрипт, прописывающий автозапуск.
     *
     * Через `reg.exe` это не работает, и не по мелочи: значение должно быть
     * `"C:\Program Files\OpenDisk\OpenDisk.exe" --hidden` — с кавычками внутри,
     * иначе Windows попробует запустить `C:\Program`. Но Java, собирая
     * командную строку, экранирует эти кавычки по-своему, и `reg add` отвечает
     * «Ошибка. Неверный синтаксис» — проверено, именно так и было.
     *
     * PowerShell через `-EncodedCommand` снимает вопрос экранирования целиком:
     * тем же приёмом в проекте запускается установщик WinFsp, и по той же
     * причине.
     */
    internal fun enableScript(key: String, valueName: String, target: Target): String {
        val value = quoteForPowerShell("\"${target.command}\" $HIDDEN_FLAG")
        return "New-ItemProperty -Path '$key' -Name '$valueName' " +
            "-Value $value -PropertyType String -Force | Out-Null"
    }

    internal fun disableScript(key: String, valueName: String): String =
        "Remove-ItemProperty -Path '$key' -Name '$valueName' -ErrorAction SilentlyContinue"

    internal fun readScript(key: String, valueName: String): String =
        "(Get-ItemProperty -Path '$key' -Name '$valueName' -ErrorAction SilentlyContinue)" +
            ".'$valueName'"

    /** В одинарных кавычках PowerShell экранирует только сами одинарные кавычки. */
    private fun quoteForPowerShell(value: String): String = "'" + value.replace("'", "''") + "'"

    private fun windowsValue(): String? =
        runPowerShell(readScript(RUN_KEY, VALUE_NAME)).trim().takeIf { it.isNotEmpty() }

    /**
     * Читаем только стандартный вывод, поток ошибок не подмешиваем.
     *
     * PowerShell пишет в поток ошибок служебный CLIXML — записи о прогрессе.
     * Со слитыми потоками чтение значения возвращало этот мусор вместо пустоты,
     * то есть выключенный автозапуск выглядел включённым, и выключить его было
     * нельзя: проверка результата не сходилась. Прогресс на всякий случай ещё
     * и отключаем.
     */
    private fun runPowerShell(script: String): String = runCatching {
        val full = "\$ProgressPreference = 'SilentlyContinue'\n$script"
        val encoded = Base64.getEncoder().encodeToString(full.toByteArray(Charsets.UTF_16LE))
        val process = ProcessBuilder(
            "powershell", "-NoProfile", "-NonInteractive", "-EncodedCommand", encoded,
        ).start()

        val output = process.inputStream.bufferedReader().readText()
        if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return@runCatching ""
        }
        output
    }.getOrDefault("")

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

    /** С двоеточием: для PowerShell реестр — это диск, а не просто путь. */
    private const val RUN_KEY = """HKCU:\Software\Microsoft\Windows\CurrentVersion\Run"""
    private const val VALUE_NAME = "OpenDisk"
    private const val LAUNCHER_NAME = "OpenDisk"

    /** Путь к самому файлу .AppImage — его выставляет сам образ при запуске. */
    private const val APPIMAGE_ENV = "APPIMAGE"
    private const val TIMEOUT_SECONDS = 10L
}
