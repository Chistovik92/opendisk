package com.opendisk.app

import java.io.File
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * Уборка того, что приложение оставляет за собой.
 *
 * Разделено на две части, и разделение здесь главное.
 *
 * **Своё** — настройки, служебные файлы, запись автозапуска, кэш. Это следы
 * работы приложения, они не нужны никому, кроме него, и убираются молча при
 * удалении.
 *
 * **Не своё** — список подключённых облаков (в нём и доступы к ним) и WinFsp.
 * Конфиг облаков это данные пользователя: удалив его, придётся заново
 * подтверждать доступ к каждому хранилищу. WinFsp — отдельная программа,
 * и ей могут пользоваться другие: тот же rclone, поставленный самостоятельно,
 * sshfs-win, другие клиенты облаков. Снести её молча значит сломать их.
 * Поэтому и то и другое удаляется только по явной просьбе.
 */
object Cleanup {

    /**
     * Следы работы самого приложения.
     *
     * Пути собираются по тем же правилам, по которым приложение их создаёт,
     * а не зашиваются строкой: разъедутся — уборка начнёт молча промахиваться.
     */
    fun ownFiles(
        settingsFile: File = AppSettings.defaultFile(),
        userHome: String = System.getProperty("user.home"),
    ): List<File> = listOfNotNull(
        // Каталог с settings.json и rcd.pid целиком.
        settingsFile.parentFile,
        // Запасной каталог для встроенного rclone: туда он копируется, когда
        // в дистрибутиве оказывается без права на исполнение (Linux-пакеты).
        File(userHome, ".cache/opendisk").takeIf { it.exists() },
    )

    /** Конфиг rclone — список облаков и доступы к ним. Данные пользователя. */
    fun rcloneConfig(path: String): File = File(path)

    /**
     * Удаляет переданное. Ошибки не всплывают: половина путей может просто
     * не существовать, и это не повод останавливать удаление приложения.
     *
     * @return сколько путей удалось убрать.
     */
    fun remove(paths: List<File>): Int = paths.count { path ->
        runCatching { path.exists() && path.deleteRecursively() }.getOrDefault(false)
    }

    /** Убирает запись автозапуска — она живёт вне каталога приложения. */
    fun removeAutostart(): Boolean = runCatching { Autostart.setEnabled(false) }.getOrDefault(false)

    // --- WinFsp -------------------------------------------------------------

    /**
     * Команда удаления WinFsp, если он установлен.
     *
     * Ищем среди установленных программ и берём её собственную команду удаления,
     * а не гадаем код продукта: версии выходят, коды меняются.
     */
    fun findWinFspUninstall(): String? = runPowerShell(FIND_WINFSP_SCRIPT)
        .lineSequence()
        .map { it.trim() }
        .firstOrNull { it.isNotEmpty() }

    /**
     * Запускает удаление WinFsp. Права администратора запрашиваются системой:
     * драйвер ставился в систему, и снимается он так же.
     */
    fun uninstallWinFsp(uninstallCommand: String): Boolean {
        val script = msiUninstallScript(uninstallCommand, wait = true) ?: return false
        runPowerShell(script)
        return true
    }

    /**
     * Строит команду запуска удаления по строке из реестра.
     *
     * Из неё берётся только код продукта, а сама она не выполняется. Причина
     * простая: в `UninstallString` попадаются команды с ключами тихого удаления
     * и произвольные исполняемые файлы, и запускать это вслепую нельзя.
     * Обрабатываем поэтому единственный понятный вид — `MsiExec.exe /X{КОД}`,
     * которым записывают себя MSI-установщики; и OpenDisk, и WinFsp такие.
     */
    internal fun msiUninstallScript(uninstallCommand: String, wait: Boolean): String? {
        val code = Regex("""\{[0-9A-Fa-f-]{36}}""").find(uninstallCommand)?.value ?: return null
        val waitFlag = if (wait) " -Wait" else ""
        return "Start-Process msiexec -ArgumentList @('/x', '$code', '/qb') -Verb RunAs$waitFlag"
    }

    private const val FIND_WINFSP_SCRIPT =
        "@('HKLM:\\SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\*'," +
            "'HKLM:\\SOFTWARE\\WOW6432Node\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\*') | " +
            "ForEach-Object { Get-ItemProperty \$_ -ErrorAction SilentlyContinue } | " +
            "Where-Object { \$_.DisplayName -like 'WinFsp*' } | " +
            "ForEach-Object { \$_.UninstallString }"

    private fun runPowerShell(script: String): String = runCatching {
        val full = "\$ProgressPreference = 'SilentlyContinue'\n$script"
        val encoded = Base64.getEncoder().encodeToString(full.toByteArray(Charsets.UTF_16LE))
        val process = ProcessBuilder(
            "powershell", "-NoProfile", "-NonInteractive", "-EncodedCommand", encoded,
        ).start()
        val output = process.inputStream.bufferedReader().readText()
        if (!process.waitFor(TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
            process.destroyForcibly()
            return@runCatching ""
        }
        output
    }.getOrDefault("")

    private const val TIMEOUT_MINUTES = 10L

    // --- Запуск удаления самого приложения ----------------------------------

    /**
     * Находит команду удаления OpenDisk среди установленных программ.
     *
     * Ищем по своему коду обновления (UpgradeCode в composeApp/wix/Product.wxs)
     * нельзя — в реестре удаления его нет, там код продукта, а он меняется
     * с каждой версией. Поэтому по названию, как и для WinFsp.
     */
    fun findSelfUninstall(): String? = runPowerShell(FIND_SELF_SCRIPT)
        .lineSequence()
        .map { it.trim() }
        .firstOrNull { it.isNotEmpty() }

    /**
     * Запускает удаление приложения.
     *
     * Ждать нечего: установщик сам закроет OpenDisk (`util:CloseApplication`),
     * так что дожидаться его из закрываемого процесса бессмысленно.
     */
    fun startSelfUninstall(): Boolean {
        val command = findSelfUninstall() ?: return false
        val script = msiUninstallScript(command, wait = false) ?: return false
        runCatching {
            val full = "\$ProgressPreference = 'SilentlyContinue'\n$script"
            val encoded = Base64.getEncoder().encodeToString(full.toByteArray(Charsets.UTF_16LE))
            ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-EncodedCommand", encoded)
                .start()
        }.getOrElse { return false }
        return true
    }

    private const val FIND_SELF_SCRIPT =
        "@('HKLM:\\SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\*'," +
            "'HKLM:\\SOFTWARE\\WOW6432Node\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\*') | " +
            "ForEach-Object { Get-ItemProperty \$_ -ErrorAction SilentlyContinue } | " +
            "Where-Object { \$_.DisplayName -eq 'OpenDisk' } | " +
            "ForEach-Object { \$_.UninstallString }"
}
