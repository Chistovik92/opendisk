package com.opendisk.bridge

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.contentLength
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest

/**
 * Выпуски OpenDisk на GitHub — общее для обновления на всех платформах.
 *
 * Здесь то, что одинаково на компьютере и на телефоне: какой выпуск новее,
 * как скачать файл и убедиться, что скачан именно он. Какой файл брать и
 * как его ставить, решает каждая платформа сама.
 *
 * Живёт в этом модуле потому, что он единственный общий для десктопа и
 * Android. К rclone отношения не имеет.
 */
object AppReleases {

    const val RELEASES_URL = "https://api.github.com/repos/Chistovik92/opendisk/releases"
    private const val USER_AGENT = "OpenDisk update check"

    @Serializable
    data class Release(
        @SerialName("tag_name") val tagName: String = "",
        @SerialName("html_url") val htmlUrl: String = "",
        val draft: Boolean = false,
        val assets: List<Asset> = emptyList(),
    ) {
        val version: String get() = tagName.removePrefix("v")

        fun asset(name: String): Asset? = assets.firstOrNull { it.name == name }
    }

    @Serializable
    data class Asset(
        val name: String = "",
        @SerialName("browser_download_url") val downloadUrl: String = "",
    )

    /**
     * Самый новый выпуск приложения новее [currentVersion], или null —
     * обновляться не на что либо список не удалось получить. Ошибка сети здесь
     * не повод беспокоить человека: проверка фоновая и необязательная.
     */
    suspend fun newest(httpClient: HttpClient, currentVersion: String, url: String = RELEASES_URL): Release? =
        runCatching {
            val response = httpClient.get(url) {
                header("Accept", "application/vnd.github+json")
                header("User-Agent", USER_AGENT)
            }
            if (!response.status.isSuccess()) return null
            newest(response.bodyAsText(), currentVersion)
        }.getOrNull()

    /**
     * Берём весь список, а не `/releases/latest`.
     *
     * `latest` пропускает предварительные выпуски, а все выпуски OpenDisk
     * пока именно такие — обновление не нашлось бы никогда. На тех же
     * граблях стоял скрипт установки для Linux.
     */
    fun newest(json: String, currentVersion: String): Release? {
        val releases = runCatching { lenientJson.decodeFromString<List<Release>>(json) }
            .getOrNull()
            ?: return null

        return releases
            .asSequence()
            .filter { !it.draft }
            // В том же репозитории лежат выпуски встроенной библиотеки
            // (librclone-v1.75.1). Без этого фильтра приложение однажды
            // предложило бы «обновиться» до версии rclone.
            .filter { isAppTag(it.tagName) }
            .filter { isNewer(it.tagName, currentVersion) }
            // Самый новый из подходящих — по тому же сравнению, что и всё
            // остальное. Порядок, в котором их отдал GitHub, не гарантирован.
            .reduceOrNull { best, next -> if (isNewer(next.tagName, best.tagName)) next else best }
    }

    /** Тег выпуска приложения — «v» и дальше только числа с точками. */
    fun isAppTag(tag: String): Boolean = tag.startsWith("v") && versionParts(tag).isNotEmpty()

    /**
     * Больше ли [candidate], чем [current].
     *
     * Сравниваем по числам, а не строками: строкой «0.2.10» меньше «0.2.9»,
     * и обновление на десятый выпуск просто не предложилось бы. Ведущая «v»
     * в тегах GitHub отбрасывается.
     */
    fun isNewer(candidate: String, current: String): Boolean {
        val left = versionParts(candidate)
        val right = versionParts(current)
        if (left.isEmpty() || right.isEmpty()) return false

        for (i in 0 until maxOf(left.size, right.size)) {
            val a = left.getOrElse(i) { 0 }
            val b = right.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    /**
     * Разбирает «v0.2.5» в [0, 2, 5]. Всё, что не разбирается в числа,
     * даёт пустой список — такую версию сравнивать не с чем, и предлагать
     * обновление по ней нельзя.
     */
    fun versionParts(version: String): List<Int> {
        val cleaned = version.trim().removePrefix("v")
        if (cleaned.isEmpty()) return emptyList()
        val numbers = cleaned.split('.').map { it.trim().toIntOrNull() }
        return if (numbers.any { it == null }) emptyList() else numbers.filterNotNull()
    }

    private val lenientJson = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }
}

/**
 * Скачивает файл выпуска и сверяет его SHA-256.
 *
 * Сверка обязательна: файл приезжает из сети и затем ставится — на Windows
 * с правами администратора. Не с чем сверять — значит, и ставить нельзя.
 */
class UpdateDownloader(private val httpClient: HttpClient = downloadHttpClient()) {

    sealed interface Result {
        data class Downloaded(val file: File) : Result

        data class Failed(val reason: Reason) : Result
    }

    enum class Reason { NO_CHECKSUMS, DOWNLOAD_FAILED, CHECKSUM_MISMATCH }

    /**
     * @param onProgress скачано байт и сколько всего (null — сервер не сказал).
     *        Установщик весит до сотни мегабайт, и без хода загрузки кажется,
     *        что всё зависло.
     */
    suspend fun download(
        asset: AppReleases.Asset,
        checksums: AppReleases.Asset?,
        into: File,
        onProgress: (downloaded: Long, total: Long?) -> Unit = { _, _ -> },
    ): Result {
        val expected = checksums
            ?.let { fetchChecksum(it.downloadUrl, asset.name) }
            ?: return Result.Failed(Reason.NO_CHECKSUMS)

        // Файлы прошлых обновлений здесь больше не нужны, а весят по 90 МБ
        // каждый — до 0.5.12 они копились во временной папке.
        into.deleteRecursively()
        into.mkdirs()
        val file = File(into, asset.name)

        // Поток, а не httpClient.get(): тот сначала читает ответ в память
        // целиком и только потом отдаёт его — 90 МБ в куче, и ни байта на
        // диске, пока не скачается всё.
        val downloaded = runCatching {
            httpClient.prepareGet(asset.downloadUrl).execute { response ->
                if (!response.status.isSuccess()) error("HTTP ${response.status}")
                val total = response.contentLength()
                val channel = response.bodyAsChannel()
                var done = 0L
                val buffer = ByteArray(CHUNK_BYTES)
                file.outputStream().use { output ->
                    while (true) {
                        val read = channel.readAvailable(buffer, 0, buffer.size)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        done += read
                        onProgress(done, total)
                    }
                }
                if (total != null && done != total) error("скачано $done из $total байт")
            }
        }
        if (downloaded.isFailure) {
            file.delete()
            return Result.Failed(Reason.DOWNLOAD_FAILED)
        }

        if (!sha256(file).equals(expected, ignoreCase = true)) {
            file.delete()
            return Result.Failed(Reason.CHECKSUM_MISMATCH)
        }
        return Result.Downloaded(file)
    }

    private suspend fun fetchChecksum(url: String, assetName: String): String? = runCatching {
        val response = httpClient.get(url)
        if (!response.status.isSuccess()) return null
        checksumFor(response.bodyAsText(), assetName)
    }.getOrNull()

    companion object {
        private const val CHUNK_BYTES = 1 shl 16
        private const val SHA256_HEX_LENGTH = 64
        private const val CONNECT_TIMEOUT_MS = 30_000L
        private const val SOCKET_TIMEOUT_MS = 60_000L

        /**
         * Клиент для скачивания — без предела на весь запрос.
         *
         * До 0.5.12 установщик качался клиентом проверки обновлений с
         * `requestTimeout` 30 секунд, а в CIO этот предел покрывает и тело
         * ответа. Установщик весит около 90 МБ: это 24 Мбит/с, чтобы успеть.
         * На более медленной сети обновление обрывалось всегда и говорило
         * только «не удалось скачать» — с 0.5.6 так и было у владельца проекта.
         *
         * Теперь обрыв ловится по тишине: соединение, по которому
         * [SOCKET_TIMEOUT_MS] не пришло ни байта, считается мёртвым. Медленная,
         * но живая сеть докачает, сколько бы это ни заняло.
         */
        fun downloadHttpClient(): HttpClient = HttpClient(CIO) {
            engine { requestTimeout = 0 }
            install(HttpTimeout) {
                requestTimeoutMillis = HttpTimeout.INFINITE_TIMEOUT_MS
                connectTimeoutMillis = CONNECT_TIMEOUT_MS
                socketTimeoutMillis = SOCKET_TIMEOUT_MS
            }
        }

        /**
         * Достаёт сумму нужного файла из `SHA256SUMS-*`. Формат строки такой:
         *
         * ```
         * b1946ac9...  OpenDisk-0.2.5.msi
         * b1946ac9... *OpenDisk-0.5.11-x64.exe
         * ```
         *
         * Имя сверяем целиком, а не по вхождению: `OpenDisk-0.2.5.msi` иначе
         * совпало бы с чем угодно, что его содержит.
         */
        fun checksumFor(sums: String, assetName: String): String? =
            sums.lineSequence()
                .map { it.trim() }
                .firstOrNull { line -> line.substringAfterLast(' ').trimStart('*') == assetName }
                ?.substringBefore(' ')
                ?.takeIf { it.length == SHA256_HEX_LENGTH }

        fun sha256(file: File): String {
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
    }
}
