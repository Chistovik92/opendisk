package com.opendisk.app

import com.opendisk.app.UpdateChecker.InstallTarget
import com.opendisk.bridge.UpdateDownloader
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Скачивает новую версию и запускает её установку.
 *
 * Установка везде устроена одинаково: сценарий ждёт, пока приложение выйдет,
 * ставит обновление и запускает приложение обратно. Выйти приложению после
 * этого — забота вызывающего ([RcloneController.installUpdate]). Права
 * администратора спрашивает система: UAC на Windows, pkexec на Linux, окно
 * пароля на macOS.
 */
class UpdateInstaller(private val downloader: UpdateDownloader = UpdateDownloader()) {

    sealed interface Result {
        /** Установка запущена, приложение должно закрыться. */
        data object Started : Result

        data class Failed(val reason: String) : Result
    }

    /**
     * Скачивает файл, сверяет SHA-256 и запускает установку.
     *
     * Сверка обязательна и без неё скачанное не запускается: файл приезжает
     * из сети и ставится с правами администратора. Не с чем сверять —
     * значит, не запускаем.
     */
    suspend fun download(
        update: UpdateChecker.Update,
        into: File,
        strings: Strings,
        onProgress: (downloaded: Long, total: Long?) -> Unit = { _, _ -> },
    ): Result {
        val asset = update.asset ?: return Result.Failed(strings.updateNoPackage)
        val target = update.target ?: return Result.Failed(strings.updateNoPackage)

        val file = when (val result = downloader.download(asset, update.checksums, into, onProgress)) {
            is UpdateDownloader.Result.Downloaded -> result.file
            is UpdateDownloader.Result.Failed -> return Result.Failed(
                when (result.reason) {
                    UpdateDownloader.Reason.NO_CHECKSUMS -> strings.updateNoChecksums
                    UpdateDownloader.Reason.DOWNLOAD_FAILED -> strings.updateDownloadFailed
                    UpdateDownloader.Reason.CHECKSUM_MISMATCH -> strings.updateChecksumMismatch
                },
            )
        }

        val started = when (target) {
            InstallTarget.WINDOWS -> launchInstaller(file)
            else -> launchShellInstaller(target, file)
        }
        return if (started) Result.Started else Result.Failed(strings.updateLaunchFailed)
    }

    companion object {
        /**
         * Запускает сценарий обновления и говорит, начал ли он работу.
         *
         * Сам сценарий — windows/install-update.ps1: его же запускает CI на
         * настоящей установке, так что проверяется ровно то, что выполнится
         * здесь.
         *
         * Сценарий ждёт выхода приложения и только потом ставит обновление,
         * поэтому его конца отсюда не дождаться — да и незачем. Проверяем
         * одно: он не упал сразу же.
         */
        internal fun launchInstaller(installer: File): Boolean = runCatching {
            val process = PowerShellScript.start(installScript(installer.absolutePath, Autostart.launcherPath()))
            !process.waitFor(LAUNCH_CHECK_SECONDS, TimeUnit.SECONDS)
        }.getOrDefault(false)

        /**
         * Команда запуска windows/install-update.ps1: путь установщика,
         * приложения и номер своего процесса — его выхода сценарий дождётся,
         * прежде чем ставить. Пока приложение работает, его файлы заняты,
         * и установщик откладывает их удаление до перезагрузки (код 3010).
         */
        internal fun installScript(
            absolutePath: String,
            launcher: String? = null,
            ownPid: Long = ProcessHandle.current().pid(),
        ): String =
            PowerShellScript.invocation(
                PowerShellScript.load("install-update.ps1"),
                mapOf(
                    "Installer" to absolutePath,
                    "Launcher" to launcher,
                    "WaitForPid" to ownPid.toString(),
                ),
            )

        /**
         * Linux и macOS: linux/install-update.sh или macos/install-update.sh.
         *
         * Сценарий кладётся файлом рядом со скачанным пакетом и пишет вывод
         * в журнал там же (install-update.log) — окна у него нет, и иначе
         * причину неудачи не узнать. Вывод в файл нужен и затем, чтобы
         * сценарий пережил выход приложения: пиши он в трубу, после выхода
         * читать её было бы некому.
         */
        internal fun launchShellInstaller(target: InstallTarget, file: File): Boolean = runCatching {
            val launcher = Autostart.launcherPath() ?: return false
            val command = shellCommand(target, file, launcher, ProcessHandle.current().pid()) ?: return false
            val dir = file.parentFile
            val script = File(dir, "install-update.sh")
            script.writeText(loadShellScript(command.resource))
            val process = ProcessBuilder(listOf("/bin/sh", script.absolutePath) + command.args)
                .directory(dir)
                .redirectErrorStream(true)
                .redirectOutput(File(dir, "install-update.log"))
                .redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
                .start()
            !process.waitFor(LAUNCH_CHECK_SECONDS, TimeUnit.SECONDS)
        }.getOrDefault(false)

        internal data class ShellCommand(val resource: String, val args: List<String>)

        /** Какой сценарий и с чем запустить; null — ставить этим путём нечего. */
        internal fun shellCommand(target: InstallTarget, file: File, launcher: String, ownPid: Long): ShellCommand? {
            val pid = ownPid.toString()
            return when (target) {
                InstallTarget.WINDOWS -> null
                // Лаунчер — OpenDisk.app/Contents/MacOS/OpenDisk, а заменять
                // нужно весь пакет приложения.
                InstallTarget.MACOS -> {
                    val bundle = launcher.substringBefore(".app/", missingDelimiterValue = "")
                        .takeIf { it.isNotEmpty() } ?: return null
                    ShellCommand("macos/install-update.sh", listOf(file.absolutePath, "$bundle.app", pid))
                }
                else -> {
                    val kind = when (target) {
                        InstallTarget.DEB -> "deb"
                        InstallTarget.RPM -> "rpm"
                        InstallTarget.RPM_ALT -> "rpm-alt"
                        else -> "appimage"
                    }
                    ShellCommand("linux/install-update.sh", listOf(kind, file.absolutePath, launcher, pid))
                }
            }
        }

        internal fun loadShellScript(resource: String): String =
            UpdateInstaller::class.java.getResourceAsStream("/$resource")
                ?.use { it.readBytes().toString(Charsets.UTF_8) }
                ?: error("в сборке нет сценария $resource")

        /**
         * Сколько ждём, не упал ли сценарий сразу. Упасть он может только на
         * разборе или на старте оболочки — это доли секунды.
         */
        private const val LAUNCH_CHECK_SECONDS = 5L
    }
}
