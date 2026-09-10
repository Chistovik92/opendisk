package com.opendisk.app

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import io.ktor.utils.io.jvm.javaio.copyTo
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Скачивает установщик новой версии и запускает его.
 *
 * Только Windows: там установщик один на всех и умеет закрывать работающее
 * приложение (см. `util:CloseApplication` в composeApp/wix/Product.wxs).
 * На Linux пакет зависит от дистрибутива и ставится пакетным менеджером
 * от root — туда лезть из приложения неправильно, там открывается страница
 * выпуска.
 */
class UpdateInstaller(private val httpClient: HttpClient) {

    sealed interface Result {
        /** Установщик запущен, приложение должно закрыться. */
        data object Started : Result

        data class Failed(val reason: String) : Result
    }

    /**
     * Скачивает файл, сверяет SHA-256 и запускает установку.
     *
     * Сверка обязательна и без неё скачанное не запускается: файл приезжает
     * из сети и исполняется с правами администратора. Не с чем сверять —
     * значит, не запускаем.
     */
    suspend fun download(update: UpdateChecker.Update, into: File, strings: Strings): Result {
        val assetUrl = update.assetUrl ?: return Result.Failed(strings.updateNoPackage)
        val assetName = update.assetName ?: return Result.Failed(strings.updateNoPackage)
        val checksumsUrl = update.checksumsUrl ?: return Result.Failed(strings.updateNoChecksums)

        val expected = fetchChecksum(checksumsUrl, assetName)
            ?: return Result.Failed(strings.updateNoChecksums)

        into.mkdirs()
        val file = File(into, assetName)
        val downloaded = runCatching {
            val response = httpClient.get(assetUrl)
            if (!response.status.isSuccess()) return Result.Failed(strings.updateDownloadFailed)
            file.outputStream().use { output -> response.bodyAsChannel().copyTo(output) }
        }
        if (downloaded.isFailure) return Result.Failed(strings.updateDownloadFailed)

        val actual = sha256(file)
        if (!actual.equals(expected, ignoreCase = true)) {
            file.delete()
            return Result.Failed(strings.updateChecksumMismatch)
        }

        return if (launchInstaller(file)) Result.Started else Result.Failed(strings.updateLaunchFailed)
    }

    private suspend fun fetchChecksum(url: String, assetName: String): String? = runCatching {
        val response = httpClient.get(url)
        if (!response.status.isSuccess()) return null
        checksumFor(response.bodyAsText(), assetName)
    }.getOrNull()

    companion object {
        /**
         * Достаёт сумму нужного файла из `SHA256SUMS-*`. Формат строки такой:
         *
         * ```
         * b1946ac9...  OpenDisk-0.2.5.msi
         * ```
         *
         * Имя сверяем целиком, а не по вхождению: `OpenDisk-0.2.5.msi` иначе
         * совпало бы с чем угодно, что его содержит.
         */
        internal fun checksumFor(sums: String, assetName: String): String? =
            sums.lineSequence()
                .map { it.trim() }
                .firstOrNull { line ->
                    line.substringAfterLast(' ').trimStart('*') == assetName
                }
                ?.substringBefore(' ')
                ?.takeIf { it.length == SHA256_HEX_LENGTH }

        internal fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(1 shl 16)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        /**
         * Запускает сценарий обновления и говорит, начал ли он работу.
         *
         * Сам сценарий — windows/install-update.ps1: его же запускает CI на
         * настоящей установке, так что проверяется ровно то, что выполнится
         * здесь.
         *
         * Сценарий ждёт выхода приложения и только потом ставит обновление,
         * поэтому его конца отсюда не дождаться — да и незачем. Проверяем
         * одно: он не упал сразу же. Выйти приложению после этого — забота
         * вызывающего ([RcloneController.installUpdate]).
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
         * Сколько ждём, не упал ли сценарий сразу. Упасть он может только на
         * разборе или на старте PowerShell — это доли секунды.
         */
        private const val LAUNCH_CHECK_SECONDS = 5L

        private const val SHA256_HEX_LENGTH = 64
    }
}
