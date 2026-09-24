package com.opendisk.bridge

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Выпуски и скачивание обновления — общее для компьютера и телефона.
 *
 * Здесь несколько вещей, на которых легко ошибиться молча: обновление просто
 * перестанет находиться или скачиваться, и узнать об этом можно будет только
 * по жалобе. Ровно так и вышло в 0.5.0 (искался .msi, а выпуски стали .exe)
 * и в 0.5.6–0.5.11 (установщик не успевал скачаться за 30 секунд).
 */
class AppReleasesTest {

    @Test
    fun `versions are compared as numbers, not as text`() {
        assertTrue(AppReleases.isNewer("0.2.5", "0.1.25"))
        // Строкой «0.2.10» меньше «0.2.9» — на этом обновление на десятый
        // выпуск просто не предложилось бы.
        assertTrue(AppReleases.isNewer("0.2.10", "0.2.9"))
        assertTrue(AppReleases.isNewer("v0.5.12", "0.5.11"))
        assertTrue(AppReleases.isNewer("1.0.0", "0.9.9"))

        assertFalse(AppReleases.isNewer("0.2.5", "0.2.5"))
        assertFalse(AppReleases.isNewer("0.2.4", "0.2.5"))
        // Разной длины номера сравниваются как есть: 0.2 это то же, что 0.2.0.
        assertFalse(AppReleases.isNewer("0.2", "0.2.0"))
        assertTrue(AppReleases.isNewer("0.2.1", "0.2"))
    }

    @Test
    fun `unparseable version never looks newer`() {
        assertFalse(AppReleases.isNewer("librclone-v1.75.1", "0.2.5"))
        assertFalse(AppReleases.isNewer("", "0.2.5"))
        assertFalse(AppReleases.isNewer("завтрашняя", "0.2.5"))
        assertEquals(emptyList(), AppReleases.versionParts("v"))
    }

    @Test
    fun `release of the bundled library is not an application update`() {
        assertFalse(AppReleases.isAppTag("librclone-v1.75.1"))
        assertFalse(AppReleases.isAppTag("librclone-ios-v1.75.1"))
        assertTrue(AppReleases.isAppTag("v0.2.5"))
        assertFalse(AppReleases.isAppTag("0.2.5"))
    }

    @Test
    fun `newest release is picked whatever order github returns`() {
        assertEquals("0.5.12", AppReleases.newest(RELEASES_JSON, "0.5.0")?.version)
        assertNull(AppReleases.newest(RELEASES_JSON, "0.5.12"))
        assertNull(AppReleases.newest(RELEASES_JSON, "1.0.0"))
    }

    @Test
    fun `drafts and broken responses are not updates`() {
        val json = """
            [
              {"tag_name":"v9.9.9","html_url":"https://example.invalid/draft","draft":true,"assets":[]},
              {"tag_name":"v0.2.5","html_url":"https://example.invalid/0.2.5","draft":false,"assets":[]}
            ]
        """.trimIndent()

        assertEquals("0.2.5", AppReleases.newest(json, "0.1.0")?.version)
        assertNull(AppReleases.newest("не json", "0.1.0"))
        assertNull(AppReleases.newest("[]", "0.1.0"))
    }

    @Test
    fun `checksum is looked up by the whole file name`() {
        val sums = """
            ${"a".repeat(64)}  OpenDisk-0.5.12-x64.exe.sig
            ${"b".repeat(64)} *OpenDisk-0.5.12-x64.exe
        """.trimIndent()

        assertEquals("b".repeat(64), UpdateDownloader.checksumFor(sums, "OpenDisk-0.5.12-x64.exe"))
        assertNull(UpdateDownloader.checksumFor(sums, "OpenDisk-0.5.12.exe"))
        assertNull(UpdateDownloader.checksumFor("коротко  OpenDisk-0.5.12-x64.exe", "OpenDisk-0.5.12-x64.exe"))
    }

    @Test
    fun `file is written to disk while it downloads and checked`() = runBlocking {
        val body = ByteArray(300_000) { (it % 251).toByte() }
        val progress = mutableListOf<Long>()

        val result = downloader(body, sumsFor(body)).download(ASSET, SUMS, tempDir()) { done, total ->
            progress += done
            assertEquals(body.size.toLong(), total)
        }

        val file = assertIs<UpdateDownloader.Result.Downloaded>(result).file
        assertTrue(body.contentEquals(file.readBytes()))
        // Ход загрузки идёт частями, а не одним куском в конце: 90 МБ без него
        // выглядят как зависание.
        assertTrue(progress.size > 1, "прогресс пришёл одним куском: $progress")
        assertEquals(body.size.toLong(), progress.last())
    }

    @Test
    fun `tampered file is deleted, not handed over`() = runBlocking {
        val body = ByteArray(1000) { 1 }
        val dir = tempDir()

        val result = downloader(body, sumsFor(ByteArray(1000) { 2 })).download(ASSET, SUMS, dir)

        assertEquals(UpdateDownloader.Result.Failed(UpdateDownloader.Reason.CHECKSUM_MISMATCH), result)
        assertFalse(File(dir, ASSET.name).exists())
    }

    @Test
    fun `without checksums nothing is downloaded`() = runBlocking {
        val body = ByteArray(10)
        assertEquals(
            UpdateDownloader.Result.Failed(UpdateDownloader.Reason.NO_CHECKSUMS),
            downloader(body, sumsFor(body)).download(ASSET, null, tempDir()),
        )
        assertEquals(
            UpdateDownloader.Result.Failed(UpdateDownloader.Reason.NO_CHECKSUMS),
            downloader(body, "").download(ASSET, SUMS, tempDir()),
        )
    }

    @Test
    fun `cut off download is a failure and leaves no file`() = runBlocking {
        val body = ByteArray(1000) { 3 }
        val dir = tempDir()
        // Сервер обещал больше, чем отдал, — так выглядит оборванное соединение.
        val client = HttpClient(MockEngine { request ->
            when (request.url.encodedPath) {
                "/sums" -> respond(sumsFor(body))
                else -> respond(body.copyOf(400), headers = headersOf(HttpHeaders.ContentLength, "1000"))
            }
        })

        val result = UpdateDownloader(client).download(ASSET, SUMS, dir)

        assertEquals(UpdateDownloader.Result.Failed(UpdateDownloader.Reason.DOWNLOAD_FAILED), result)
        assertFalse(File(dir, ASSET.name).exists())
    }

    @Test
    fun `server error is a failure`() = runBlocking {
        val client = HttpClient(MockEngine { request ->
            when (request.url.encodedPath) {
                "/sums" -> respond(sumsFor(ByteArray(1)))
                else -> respondError(HttpStatusCode.NotFound)
            }
        })

        assertEquals(
            UpdateDownloader.Result.Failed(UpdateDownloader.Reason.DOWNLOAD_FAILED),
            UpdateDownloader(client).download(ASSET, SUMS, tempDir()),
        )
    }

    private fun downloader(body: ByteArray, sums: String) = UpdateDownloader(
        HttpClient(MockEngine { request ->
            when (request.url.encodedPath) {
                "/sums" -> respond(sums)
                else -> respond(body, headers = headersOf(HttpHeaders.ContentLength, body.size.toString()))
            }
        }),
    )

    private fun sumsFor(body: ByteArray): String {
        val file = File.createTempFile("sums", ".bin").apply { writeBytes(body); deleteOnExit() }
        return "${UpdateDownloader.sha256(file)} *${ASSET.name}\n"
    }

    private fun tempDir(): File = Files.createTempDirectory("update").toFile().apply { deleteOnExit() }

    private companion object {
        val ASSET = AppReleases.Asset("OpenDisk-0.5.12-x64-offline.exe", "https://example.invalid/file")
        val SUMS = AppReleases.Asset("SHA256SUMS-Windows-x64", "https://example.invalid/sums")

        /** Порядок нарочно не по возрастанию: GitHub его не гарантирует. */
        val RELEASES_JSON = """
            [
              {"tag_name":"v0.5.2","html_url":"https://example.invalid/0.5.2","draft":false,"assets":[]},
              {"tag_name":"librclone-ios-v1.75.1","html_url":"https://example.invalid/l","draft":false,"assets":[]},
              {"tag_name":"v0.5.12","html_url":"https://example.invalid/0.5.12","draft":false,"assets":[]},
              {"tag_name":"v0.5.0","html_url":"https://example.invalid/0.5.0","draft":false,"assets":[]}
            ]
        """.trimIndent()
    }
}
