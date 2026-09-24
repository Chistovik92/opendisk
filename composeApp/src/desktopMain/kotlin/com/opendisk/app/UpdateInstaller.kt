package com.opendisk.app

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.prepareGet
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
class UpdateInstaller(private val httpClient: HttpClient = downloadHttpClient()) {

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

        // Установщики прошлых обновлений здесь больше не нужны, а весят по
        // 90 МБ каждый — до 0.5.12 они копились во временной папке.
        into.deleteRecursively()
        into.mkdirs()
        val file = File(into, assetName)
        // Поток, а не httpClient.get(): тот сначала читает ответ в память
        // целиком и только потом отдаёт его — 90 МБ в куче, и ни байта на
        // диске, пока не скачается всё.
        val downloaded = runCatching {
            httpClient.prepareGet(assetUrl).execute { response ->
                if (!response.status.isSuccess()) error("HTTP ${response.status}")
                file.outputStream().use { output -> response.bodyAsChannel().copyTo(output) }
            }
        }
        if (downloaded.isFailure) {
            file.delete()
            return Result.Failed(strings.updateDownloadFailed)
        }

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
         * Клиент для скачивания установщика — без предела на весь запрос.
         *
         * До 0.5.12 здесь был клиент проверки обновлений с `requestTimeout`
         * 30 секунд, а в CIO этот предел покрывает и тело ответа. Установщик
         * весит около 90 МБ: это 24 Мбит/с, чтобы успеть. На более медленной
         * сети обновление обрывалось всегда и говорило только «не удалось
         * скачать» — с 0.5.6 так и было у владельца проекта.
         *
         * Теперь обрыв ловится по тишине: соединение, по которому
         * [SOCKET_TIMEOUT_MS] не пришло ни байта, считается мёртвым. Медленная,
         * но живая сеть докачает сколько бы это ни заняло.
         */
        fun downloadHttpClient(): HttpClient = HttpClient(CIO) {
            engine { requestTimeout = 0 }
            install(HttpTimeout) {
                requestTimeoutMillis = HttpTimeout.INFINITE_TIMEOUT_MS
                connectTimeoutMillis = CONNECT_TIMEOUT_MS
                socketTimeoutMillis = SOCKET_TIMEOUT_MS
            }
        }

        private const val CONNECT_TIMEOUT_MS = 30_000L
        private const val SOCKET_TIMEOUT_MS = 60_000L

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
