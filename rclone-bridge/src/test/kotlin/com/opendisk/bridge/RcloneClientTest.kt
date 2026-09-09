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

        client.mount(
            "gdrive",
            "/mnt/gdrive",
            RcloneClient.MountOptions(vfsCacheMode = "full"),
        )

        val body = lastRequestBody()
        // rclone ожидает именно "gdrive:" — без двоеточия он трактует это как путь.
        assertContains(body, "\"fs\":\"gdrive:\"")
        assertContains(body, "\"mountPoint\":\"/mnt/gdrive\"")
        assertContains(body, "\"CacheMode\":\"full\"")
    }

    @Test
    fun `network mode and volume name go into mountOpt`() = runBlocking {
        val client = clientRespondingWith("{}")

        client.mount(
            "disk",
            "Z:",
            RcloneClient.MountOptions(networkMode = true, volumeName = "Яндекс"),
        )

        val body = lastRequestBody()
        // Именно mountOpt, а не vfsOpt: rclone разбирает их разными наборами
        // настроек, и в чужом блоке параметр молча не применится.
        assertContains(body, "\"mountOpt\":{")
        assertContains(body, "\"NetworkMode\":true")
        assertContains(body, "\"VolumeName\":\"Яндекс\"")
    }

    @Test
    fun `local disk mode sends no network flag at all`() = runBlocking {
        val client = clientRespondingWith("{}")

        client.mount("disk", "/home/user/OpenDisk/disk", RcloneClient.MountOptions())

        // Не «false», а отсутствие ключа: на Linux и macOS такого понятия нет,
        // и посылать его туда незачем.
        assertFalse(lastRequestBody().contains("NetworkMode"))
    }

    @Test
    fun `cache size limit is sent when set`() = runBlocking {
        val client = clientRespondingWith("{}")

        client.mount("disk", "Z:", RcloneClient.MountOptions(cacheMaxSizeBytes = 1024))

        assertContains(lastRequestBody(), "\"CacheMaxSize\":1024")
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
    fun `list returns folders first and then files by name`() = runBlocking {
        val client = clientRespondingWith(
            """{"list":[
                {"Path":"отчёт.pdf","Name":"отчёт.pdf","Size":1024,"IsDir":false},
                {"Path":"Яблоки","Name":"Яблоки","Size":-1,"IsDir":true},
                {"Path":"акт.doc","Name":"акт.doc","Size":512,"IsDir":false},
                {"Path":"архив","Name":"архив","Size":-1,"IsDir":true}
            ]}""",
        )

        val entries = client.list("яндекс", "Документы")

        // Папки вперёд, внутри групп — по алфавиту. rclone порядок не
        // гарантирует, а на экране он должен быть предсказуемым.
        assertEquals(
            listOf("архив", "Яблоки", "акт.doc", "отчёт.pdf"),
            entries.map { it.name },
        )
        assertTrue(entries.first().isDir)

        val body = lastRequestBody()
        assertContains(body, "\"fs\":\"яндекс:\"")
        assertContains(body, "\"remote\":\"Документы\"")
        assertEquals("$BASE_URL/operations/list", requests.last().url.toString())
    }

    @Test
    fun `list of the root asks for an empty path`() = runBlocking {
        val client = clientRespondingWith("""{"list":[]}""")

        assertEquals(emptyList(), client.list("диск"))

        assertContains(lastRequestBody(), "\"remote\":\"\"")
    }

    @Test
    fun `folder size stays unknown instead of pretending to be zero`() = runBlocking {
        val client = clientRespondingWith(
            """{"list":[{"Path":"папка","Name":"папка","Size":-1,"IsDir":true}]}""",
        )

        // rclone отдаёт -1 — это «неизвестно», а не «пусто». Подменять его
        // нулём значило бы показать человеку неверный размер.
        assertEquals(-1, client.list("диск").single().size)
    }

    @Test
    fun `publicLink asks the right cloud for the right file`() = runBlocking {
        val client = clientRespondingWith("""{"url":"https://disk.yandex.ru/d/abc123"}""")

        val url = client.publicLink("яндекс", "Отчёты/март.pdf")

        assertEquals("https://disk.yandex.ru/d/abc123", url)
        val body = lastRequestBody()
        // Двоеточие обязательно: без него rclone принял бы имя облака за путь.
        assertContains(body, "\"fs\":\"яндекс:\"")
        assertContains(body, "\"remote\":\"Отчёты/март.pdf\"")
        assertEquals("$BASE_URL/operations/publiclink", requests.last().url.toString())
    }

    @Test
    fun `publicLink does not send expire and unlink unless asked`() = runBlocking {
        val client = clientRespondingWith("""{"url":"https://example/x"}""")

        client.publicLink("диск", "файл.txt")

        val body = lastRequestBody()
        // Пустой `expire` rclone разбирает как длительность и отвечает ошибкой,
        // а `unlink:false` — это уже другая операция, а не «просто ссылка».
        assertFalse(body.contains("expire"), "в запрос попал срок, которого не задавали")
        assertFalse(body.contains("unlink"), "в запрос попал отзыв ссылки")
    }

    @Test
    fun `publicLink passes expiry and revocation through`() = runBlocking {
        val client = clientRespondingWith("""{"url":""}""")

        client.publicLink("диск", "файл.txt", expire = "24h", unlink = true)

        val body = lastRequestBody()
        assertContains(body, "\"expire\":\"24h\"")
        assertContains(body, "\"unlink\":true")
    }

    @Test
    fun `backend without links is told apart from a real failure`() = runBlocking {
        val engine = MockEngine { request ->
            requests += request
            respondError(
                status = HttpStatusCode.InternalServerError,
                content = """{"error":"sftp://host doesn't support public links","status":500}""",
            )
        }
        val client = RcloneClient(BASE_URL, httpClient(engine))

        // Не поломка, а свойство бэкенда: у SFTP, FTP и WebDAV публичных ссылок
        // нет в самом протоколе. Показывать это как ошибку было бы неправдой.
        val failure = assertFailsWith<PublicLinkUnsupportedException> {
            client.publicLink("сервер", "файл.txt")
        }

        assertEquals("сервер", failure.remote)
    }

    @Test
    fun `other errors from publiclink stay ordinary errors`() = runBlocking {
        val engine = MockEngine { request ->
            requests += request
            respondError(
                status = HttpStatusCode.InternalServerError,
                content = """{"error":"object not found","status":500}""",
            )
        }
        val client = RcloneClient(BASE_URL, httpClient(engine))

        val failure = assertFailsWith<RcloneRcException> {
            client.publicLink("яндекс", "нет-такого.txt")
        }

        assertEquals("object not found", failure.rcloneError)
    }

    @Test
    fun `fsInfo reports whether the cloud can make links`() = runBlocking {
        val client = clientRespondingWith(
            """{"Name":"yandex","Features":{"About":true,"PublicLink":true,"Purge":false}}""",
        )

        val info = client.fsInfo("яндекс")

        assertTrue(info.supportsPublicLink)
        assertEquals("yandex", info.name)
        assertContains(lastRequestBody(), "\"fs\":\"яндекс:\"")
        assertEquals("$BASE_URL/operations/fsinfo", requests.last().url.toString())
    }

    @Test
    fun `cloud without the links feature is not offered them`() = runBlocking {
        val client = clientRespondingWith("""{"Name":"sftp","Features":{"About":true}}""")

        // Отсутствие ключа и `false` — одно и то же: ссылок нет.
        assertFalse(client.fsInfo("сервер").supportsPublicLink)
    }

    @Test
    fun `fsInfo survives a backend that reports no features at all`() = runBlocking {
        val client = clientRespondingWith("{}")

        assertFalse(client.fsInfo("странное").supportsPublicLink)
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
