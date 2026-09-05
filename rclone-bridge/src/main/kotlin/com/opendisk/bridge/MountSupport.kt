package com.opendisk.bridge

import java.io.File
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * Есть ли в системе то, чем rclone монтирует облака: WinFsp на Windows,
 * FUSE на Linux, macFUSE на macOS.
 *
 * Проверять это через RC-эндпоинт `mount/types` нельзя: на Windows он отвечает
 * `["cmount"]` даже когда WinFsp не установлен, и попытка монтирования падает
 * уже потом с сообщением «cannot find winfsp». Поэтому смотрим на систему
 * напрямую — иначе кнопка «Подключить» была бы активна и приводила к ошибке.
 */
object MountSupport {

    sealed interface Status {

        /** Монтировать можно. */
        data object Available : Status

        /**
         * Монтировать нечем. [bundledInstaller] не null, если установщик едет
         * внутри дистрибутива и его можно запустить прямо из приложения.
         */
        data class Missing(
            val what: String,
            val explanation: String,
            val bundledInstaller: File?,
            val downloadUrl: String?,
        ) : Status
    }

    private val osName: String get() = System.getProperty("os.name").lowercase()

    private val isWindows: Boolean get() = osName.contains("win")
    private val isMac: Boolean get() = osName.contains("mac") || osName.contains("darwin")

    fun check(): Status = when {
        isWindows -> checkWindows()
        isMac -> checkMac()
        else -> checkLinux()
    }

    // --- Windows ------------------------------------------------------------

    private fun checkWindows(): Status {
        if (isWinFspInstalled()) return Status.Available
        return Status.Missing(
            what = "WinFsp",
            explanation = "Чтобы подключать облака как диски, нужен WinFsp — драйвер файловой " +
                "системы для Windows. Он входит в состав OpenDisk, установка займёт несколько секунд " +
                "и потребует подтверждения администратора.",
            bundledInstaller = bundledFile(WINFSP_INSTALLER),
            downloadUrl = "https://winfsp.dev/rel/",
        )
    }

    /**
     * WinFsp прописывает себя в `HKLM\SOFTWARE\WOW6432Node\WinFsp`. Реестр читаем
     * через `reg.exe`: тащить ради одного значения JNI-обвязку не стоит. Если
     * прочитать не удалось — проверяем каталог установки по умолчанию.
     */
    private fun isWinFspInstalled(): Boolean {
        val fromRegistry = readRegistryValue(WINFSP_REGISTRY_KEY, "InstallDir")
        if (fromRegistry != null && File(fromRegistry, "bin").isDirectory) return true

        return DEFAULT_WINFSP_DIRS.any { File(it, "bin").isDirectory }
    }

    private fun readRegistryValue(key: String, value: String): String? = runCatching {
        val process = ProcessBuilder("reg", "query", key, "/v", value)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        if (!process.waitFor(REG_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return@runCatching null
        }
        if (process.exitValue() != 0) return@runCatching null

        // Строка вида: "    InstallDir    REG_SZ    C:\Program Files (x86)\WinFsp\"
        output.lineSequence()
            .firstOrNull { it.contains(value) && it.contains("REG_") }
            ?.substringAfter("REG_SZ")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }.getOrNull()

    /** Чем закончилась установка драйвера — от этого зависит текст для пользователя. */
    sealed interface InstallResult {
        data object Installed : InstallResult

        /** Пользователь отказался: отклонил запрос администратора или закрыл установщик. */
        data object Cancelled : InstallResult

        data class Failed(val details: String) : InstallResult
    }

    /**
     * Запускает встроенный установщик и дожидается его завершения.
     *
     * MSI ставится с повышением прав, поэтому запускаем его через ShellExecute
     * с глаголом `runas` — иначе UAC просто не появится.
     */
    fun installBundled(installer: File): InstallResult {
        if (!installer.isFile) {
            return InstallResult.Failed("установщик не найден: ${installer.absolutePath}")
        }

        val output = runCatching { runPowerShell(buildInstallScript(installer)) }
            .getOrElse { return InstallResult.Failed(it.message ?: "не удалось запустить PowerShell") }

        // Даже при успешном коде возврата сверяемся с системой: это единственная
        // проверка, которой можно верить.
        if (check() is Status.Available) return InstallResult.Installed

        return interpretOutput(output)
    }

    private fun interpretOutput(output: String): InstallResult {
        val exitCode = EXIT_CODE_PATTERN.find(output)?.groupValues?.get(1)?.toIntOrNull()
        val launchFailure = LAUNCH_FAILED_PATTERN.find(output)?.groupValues?.get(1)?.trim()

        return when {
            exitCode == MSI_USER_CANCELLED -> InstallResult.Cancelled

            // Start-Process бросает исключение, когда запрос администратора отклонён.
            launchFailure != null && CANCELLED_MARKERS.any { launchFailure.contains(it, true) } ->
                InstallResult.Cancelled

            launchFailure != null -> InstallResult.Failed(launchFailure)
            exitCode != null -> InstallResult.Failed("установщик завершился с кодом $exitCode")
            else -> InstallResult.Failed(output.trim().ifEmpty { "установщик не сообщил о результате" })
        }
    }

    /**
     * Скрипт запуска установщика.
     *
     * Вынесен отдельно, потому что здесь легко ошибиться с экранированием:
     * путь к MSI в собранном приложении содержит пробел («Program Files»).
     */
    internal fun buildInstallScript(installer: File): String =
        installScriptFor(installer.absolutePath)

    /**
     * Принимает готовый путь строкой, а не [File]: тесты должны проверять
     * экранирование windows-пути и на Linux, где `File.absolutePath` превратил
     * бы «C:\...» в путь относительно рабочего каталога.
     */
    internal fun installScriptFor(absolutePath: String): String {
        // Одинарные кавычки PowerShell экранируются удвоением.
        val path = absolutePath.replace("'", "''")
        return """
            try {
                ${'$'}p = Start-Process msiexec -ArgumentList @('/i', '$path', '/qb') -Verb RunAs -Wait -PassThru
                Write-Output ("EXIT=" + ${'$'}p.ExitCode)
            } catch {
                Write-Output ("LAUNCH_FAILED=" + ${'$'}_.Exception.Message)
            }
        """.trimIndent()
    }

    /**
     * Запускает скрипт через `-EncodedCommand`.
     *
     * Раньше скрипт передавался как `-Command` со вставленным путём, и на пути
     * с пробелом всё разваливалось: Java сама расставляет кавычки вокруг
     * аргумента, а внутри уже были свои — PowerShell получал мусор и молча не
     * выполнялся, из-за чего запрос администратора вообще не появлялся.
     * Base64 в UTF-16LE снимает вопрос экранирования целиком.
     */
    private fun runPowerShell(script: String): String {
        val encoded = Base64.getEncoder().encodeToString(script.toByteArray(Charsets.UTF_16LE))
        val process = ProcessBuilder(
            "powershell", "-NoProfile", "-NonInteractive", "-EncodedCommand", encoded,
        ).redirectErrorStream(true).start()

        val output = process.inputStream.bufferedReader().readText()
        if (!process.waitFor(INSTALL_TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
            process.destroyForcibly()
        }
        return output
    }

    // --- Linux и macOS ------------------------------------------------------

    private fun checkLinux(): Status {
        if (File("/dev/fuse").exists()) return Status.Available
        return Status.Missing(
            what = "FUSE",
            explanation = "Чтобы подключать облака как диски, нужен FUSE. Установите пакет " +
                "fuse3 средствами вашего дистрибутива — например, `epm install fuse3`.",
            bundledInstaller = null,
            downloadUrl = null,
        )
    }

    private fun checkMac(): Status {
        if (File("/Library/Filesystems/macfuse.fs").exists()) return Status.Available
        return Status.Missing(
            what = "macFUSE",
            explanation = "Чтобы подключать облака как диски, нужен macFUSE — его нужно " +
                "установить отдельно.",
            bundledInstaller = null,
            downloadUrl = "https://macfuse.github.io/",
        )
    }

    // --- Общее --------------------------------------------------------------

    /** Файл из ресурсов приложения — там же, где лежит встроенный rclone. */
    private fun bundledFile(name: String): File? {
        val dir = System.getProperty(RcloneProcess.COMPOSE_RESOURCES_PROPERTY) ?: return null
        return File(dir, name).takeIf { it.isFile }
    }

    private const val WINFSP_INSTALLER = "winfsp.msi"
    private const val WINFSP_REGISTRY_KEY = """HKLM\SOFTWARE\WOW6432Node\WinFsp"""
    private val DEFAULT_WINFSP_DIRS = listOf(
        """C:\Program Files (x86)\WinFsp""",
        """C:\Program Files\WinFsp""",
    )
    private const val REG_TIMEOUT_SECONDS = 10L
    private const val INSTALL_TIMEOUT_MINUTES = 10L

    private val EXIT_CODE_PATTERN = Regex("EXIT=(-?\\d+)")
    private val LAUNCH_FAILED_PATTERN = Regex("LAUNCH_FAILED=(.*)")

    /** ERROR_INSTALL_USEREXIT — установку прервал сам пользователь. */
    private const val MSI_USER_CANCELLED = 1602

    /**
     * По этим словам опознаём отказ от запроса администратора. Текст исключения
     * приходит на языке системы, поэтому проверяем оба варианта написания.
     */
    private val CANCELLED_MARKERS = listOf("canceled", "cancelled", "отменен", "отменён")
}
