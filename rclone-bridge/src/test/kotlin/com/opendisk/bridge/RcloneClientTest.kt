package com.opendisk.bridge

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Тесты обёртки над RC API: настоящий rclone не запускается, HTTP-ответы мокаются.
 * Проверяем и разбор ответов, и то, что уходит в запросе, — на теле запроса легко
 * ошибиться молча (rclone просто вернёт 500 с невнятным текстом).
 */
class RcloneClientTest {

    private val requests = mutableListOf<HttpRequestData>()

    /** Отдаёт заданный JSON на любой запрос и запоминает сам запрос для проверок. */
    private fun clientRespondingWith(
        json: String,
        status: HttpStatusCode = HttpStatusCode.OK,
    ): RcloneClient {
        val engine = MockEngine { request ->
            requests += request
            respond(
                content = json,
                status = status,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        return RcloneClient(BASE_URL, httpClient(engine))
    }

    /**
     * Клиент нарочно голый, без единого плагина.
     *
     * Раньше тесты ставили сюда ContentNegotiation, а рабочий код обходился без
     * него — и молча разошлись: транспорт отправлял тело так, как умел только
     * настроенный в тестах клиент. Все моки при этом были зелёными, а живой
     * вызов падал на "Fail to prepare request body". Пусть тесты работают через
     * то же, через что работает приложение.
     */
    private fun httpClient(engine: MockEngine) = HttpClient(engine)

    private fun lastRequestBody(): String = (requests.last().body as TextContent).text

    @Test
    fun `listRemotes parses response and calls the right endpoint`() = runBlocking {
        val client = clientRespondingWith("""{"remotes":["gdrive","yandex"]}""")

        val remotes = client.listRemotes()

        assertEquals(listOf("gdrive", "yandex"), remotes)
        assertEquals("$BASE_URL/config/listremotes", requests.single().url.toString())
    }

    @Test
    fun `listRemotes tolerates response without remotes field`() = runBlocking {
        val client = clientRespondingWith("{}")

        assertEquals(emptyList(), client.listRemotes())
    }

    @Test
    fun `createRemote sends name type and parameters`() = runBlocking {
        val client = clientRespondingWith("{}")

        client.createRemote("gdrive", "drive", mapOf("scope" to "drive"))

        val body = lastRequestBody()
        assertContains(body, "\"name\":\"gdrive\"")
        assertContains(body, "\"type\":\"drive\"")
        assertContains(body, "\"scope\":\"drive\"")
        assertEquals("$BASE_URL/config/create", requests.last().url.toString())
    }

    @Test
    fun `updateRemote sends only what was changed`() = runBlocking {
        val client = clientRespondingWith("{}")

        client.updateRemote("disk", mapOf("url" to "https://other.example"))

        val body = lastRequestBody()
        assertContains(body, "\"name\":\"disk\"")
        assertContains(body, "\"url\":\"https://other.example\"")
        // Ключей, которых не передавали, в запросе быть не должно: rclone меняет
        // ровно присланное, и лишний пустой ключ затёр бы настоящее значение.
        assertFalse(body.contains("\"pass\""), "в запрос попал ключ, который не меняли")
        assertEquals("$BASE_URL/config/update", requests.last().url.toString())
    }

    @Test
    fun `getRemote returns raw backend config`() = runBlocking {
        val client = clientRespondingWith("""{"type":"yandex","token":"{...}"}""")

        val config = client.getRemote("disk")

        assertEquals("\"yandex\"", config["type"].toString())
        assertContains(lastRequestBody(), "\"name\":\"disk\"")
        assertEquals("$BASE_URL/config/get", requests.last().url.toString())
    }

    @Test
    fun `mount sends remote with colon and vfs cache mode`() = runBlocking {
        val client = clientRespondingWith("{}")

        client.mount("gdrive", "/mnt/gdrive", vfsCacheMode = "full")

        val body = lastRequestBody()
        // rclone ожидает именно "gdrive:" — без двоеточия он трактует это как путь.
        assertContains(body, "\"fs\":\"gdrive:\"")
        assertContains(body, "\"mountPoint\":\"/mnt/gdrive\"")
        assertContains(body, "\"CacheMode\":\"full\"")
    }

    @Test
    fun `listMounts parses mount points`() = runBlocking {
        val client = clientRespondingWith(
            """{"mountPoints":[{"Fs":"gdrive:","MountPoint":"/mnt/gdrive"}]}""",
        )

        val mounts = client.listMounts()

        assertEquals(1, mounts.size)
        assertEquals("gdrive:", mounts.single().Fs)
        assertEquals("/mnt/gdrive", mounts.single().MountPoint)
    }

    @Test
    fun `coreStats parses progress and ignores unknown fields`() = runBlocking {
        val client = clientRespondingWith(
            """
            {
              "bytes": 1024,
              "speed": 512.5,
              "transfers": 2,
              "errors": 0,
              "checks": 7,
              "elapsedTime": 3.5,
              "eta": 12,
              "unknownFutureField": "ignored",
              "transferring": [
                {"name":"photo.jpg","size":2048,"bytes":1024,"percentage":50,"speed":512.5}
              ]
            }
            """.trimIndent(),
        )

        val stats = client.coreStats()

        assertEquals(1024, stats.bytes)
        assertEquals(512.5, stats.speed)
        assertEquals(12, stats.eta)
        assertEquals("photo.jpg", stats.transferring.single().name)
        assertEquals(50, stats.transferring.single().percentage)
    }

    @Test
    fun `coreStats passes group when asked for one`() = runBlocking {
        val client = clientRespondingWith("{}")

        client.coreStats(group = "job/7")

        assertContains(lastRequestBody(), "\"group\":\"job/7\"")
    }

    @Test
    fun `jobStatus parses finished job`() = runBlocking {
        val client = clientRespondingWith(
            """{"id":7,"finished":true,"success":true,"error":"","duration":1.5,"group":"job/7"}""",
        )

        val status = client.jobStatus(7)

        assertTrue(status.finished)
        assertTrue(status.success)
        assertEquals(7, status.id)
        assertContains(lastRequestBody(), "\"jobid\":7")
    }

    @Test
    fun `rclone error is surfaced with its own message`() = runBlocking {
        val engine = MockEngine { request ->
            requests += request
            respondError(
                status = HttpStatusCode.InternalServerError,
                content = """{"error":"directory not found","path":"mount/mount","status":500}""",
            )
        }
        val client = RcloneClient(BASE_URL, httpClient(engine))

        val failure = assertFailsWith<RcloneRcException> {
            client.mount("gdrive", "/nope")
        }

        assertEquals("mount/mount", failure.endpoint)
        assertEquals(500, failure.statusCode)
        assertEquals("directory not found", failure.rcloneError)
        assertContains(failure.message.orEmpty(), "directory not found")
    }

    @Test
    fun `non-json error body is passed through as is`() {
        assertEquals("404 page not found", extractError("404 page not found"))
        assertEquals("пустой ответ", extractError("   "))
        assertEquals("boom", extractError("""{"error":"boom"}"""))
    }

    private companion object {
        const val BASE_URL = "http://127.0.0.1:5572"
    }
}
