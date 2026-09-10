package com.opendisk.app

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.Closeable

/**
 * Проверка обновлений по списку выпусков на GitHub.
 *
 * Ходит в открытый API без ключа: приложение не авторизуется и ничего о себе
 * не сообщает, кроме обычного заголовка User-Agent, который GitHub требует.
 * Проверку можно выключить в настройках — на случай, если обращение к сети
 * при старте нежелательно.
 */
class UpdateChecker(
    private val httpClient: HttpClient = defaultHttpClient(),
    private val releasesUrl: String = RELEASES_URL,
) : Closeable {

    /** Найденное обновление: что показать и что скачивать. */
    data class Update(
        val version: String,
        val pageUrl: String,
        /** Файл под текущую систему; null — подходящего в выпуске нет. */
        val assetUrl: String? = null,
        val assetName: String? = null,
        /** Файл с контрольными суммами — без него скачанное проверять нечем. */
        val checksumsUrl: String? = null,
    )

    /**
     * @return более новый выпуск или null, если обновляться не на что
     *         либо список не удалось получить. Ошибка сети здесь не повод
     *         беспокоить пользователя: проверка фоновая и необязательная.
     */
    suspend fun check(
        currentVersion: String,
        osName: String = System.getProperty("os.name"),
        osArch: String = System.getProperty("os.arch"),
    ): Update? =
        runCatching {
            val response = httpClient.get(releasesUrl) {
                header("Accept", "application/vnd.github+json")
                header("User-Agent", USER_AGENT)
            }
            if (!response.status.isSuccess()) return null
            newestUpdate(response.bodyAsText(), currentVersion, osName, osArch)
        }.getOrNull()

    @Serializable
    internal data class Release(
        @SerialName("tag_name") val tagName: String = "",
        @SerialName("html_url") val htmlUrl: String = "",
        val draft: Boolean = false,
        val assets: List<Asset> = emptyList(),
    )

    @Serializable
    internal data class Asset(
        val name: String = "",
        @SerialName("browser_download_url") val downloadUrl: String = "",
    )

    companion object {
        const val RELEASES_URL = "https://api.github.com/repos/Chistovik92/opendisk/releases"
        private const val USER_AGENT = "OpenDisk update check"

        /**
         * Берём весь список, а не `/releases/latest`.
         *
         * `latest` пропускает предварительные выпуски, а все выпуски OpenDisk
         * пока именно такие — обновление не нашлось бы никогда. На тех же
         * граблях стоял скрипт установки для Linux.
         */
        internal fun newestUpdate(
            json: String,
            currentVersion: String,
            osName: String,
            osArch: String = "amd64",
        ): Update? {
            val releases = runCatching { lenientJson.decodeFromString<List<Release>>(json) }
                .getOrNull()
                ?: return null

            val newest = releases
                .asSequence()
                .filter { !it.draft }
                // В том же репозитории лежат выпуски встроенной библиотеки
                // (librclone-v1.75.1). Без этого фильтра приложение однажды
                // предложило бы «обновиться» до версии rclone.
                .filter { isAppTag(it.tagName) }
                .filter { AppVersion.isNewer(it.tagName, currentVersion) }
                // Самый новый из подходящих — по тому же сравнению, что и всё
                // остальное. Порядок, в котором их отдал GitHub, не гарантирован.
                .reduceOrNull { best, next ->
                    if (AppVersion.isNewer(next.tagName, best.tagName)) next else best
                }
                ?: return null

            val asset = assetFor(newest.assets, osName, osArch)
            val checksums = checksumsNameFor(osName, osArch)
            return Update(
                version = newest.tagName.removePrefix("v"),
                pageUrl = newest.htmlUrl,
                assetUrl = asset?.downloadUrl,
                assetName = asset?.name,
                checksumsUrl = newest.assets.firstOrNull { it.name == checksums }?.downloadUrl,
            )
        }

        /** Тег выпуска приложения — «v» и дальше только числа с точками. */
        internal fun isAppTag(tag: String): Boolean =
            tag.startsWith("v") && AppVersion.parts(tag).isNotEmpty()

        /**
         * Файл под текущую систему.
         *
         * Автоматически ставится только Windows: там установщик один и умеет
         * закрывать работающее приложение. На Linux пакет зависит от дистрибутива,
         * а ставить его всё равно нужно с правами root через пакетный менеджер —
         * поэтому там открывается страница выпуска.
         *
         * С 0.5.0 установщик — `.exe`, и их два, по архитектуре. До 0.5.2 здесь
         * искался `.msi`, которого в выпусках больше нет, — встроенное
         * обновление молча перестало ставить что-либо и только открывало
         * страницу выпуска.
         *
         * Архитектура берётся у JVM, а не у системы. На ARM-ноутбуке с x64-сборкой
         * под эмуляцией JVM видит amd64 — и получает x64-установщик, то есть ту
         * же сборку, что уже стоит. Переход на родную ARM-сборку — осознанное
         * решение человека, а не побочный эффект обновления.
         */
        internal fun assetFor(assets: List<Asset>, osName: String, osArch: String): Asset? {
            if (!osName.lowercase().contains("win")) return null
            val suffix = "-${installerArch(osArch)}.exe"
            return assets.firstOrNull { it.name.endsWith(suffix, ignoreCase = true) }
        }

        internal fun checksumsNameFor(osName: String, osArch: String): String = when {
            osName.lowercase().contains("win") -> "SHA256SUMS-Windows-${installerArch(osArch)}"
            osName.lowercase().contains("mac") -> "SHA256SUMS-macOS-${installerArch(osArch)}"
            else -> "SHA256SUMS-Linux"
        }

        /** Архитектура так, как она записана в именах установщиков. */
        internal fun installerArch(osArch: String): String = when (osArch.lowercase()) {
            "aarch64", "arm64" -> "arm64"
            else -> "x64"
        }

        fun defaultHttpClient(): HttpClient = HttpClient(CIO) {
            engine { requestTimeout = 30_000 }
        }

        private val lenientJson = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }
    }

    override fun close() {
        httpClient.close()
    }
}
