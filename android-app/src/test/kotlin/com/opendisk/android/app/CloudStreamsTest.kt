package com.opendisk.android.app

import java.io.IOException
import java.net.ServerSocket
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Чтение файла из облака кусками: адрес, заголовок и разбор ответов сервера.
 *
 * Настоящий сервер rclone с паролем и `Range` проверяет RcloneIntegrationTest;
 * здесь — наша часть: правильно ли просим и правильно ли понимаем ответ.
 * Ошибка в чтении кусками — не падение, а видео, которое показывает мусор
 * или обрывается на середине.
 */
class CloudStreamsTest {

    private val content = ByteArray(10_000) { (it * 7 % 256).toByte() }
    private var socket: ServerSocket? = null

    @AfterTest
    fun stop() {
        socket?.close()
    }

    /** Что ответить: код, тело (null — без тела). */
    private class Reply(val status: Int, val body: ByteArray? = null)

    /**
     * Крошечный HTTP-сервер на сокетах — `com.sun.net.httpserver` в тестах
     * Android недоступен. Обслуживает запросы по одному и закрывает соединение.
     */
    private fun serve(respond: (headers: Map<String, String>) -> Reply): String {
        val server = ServerSocket(0, 5, java.net.InetAddress.getByName("127.0.0.1"))
        socket = server
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val client = try { server.accept() } catch (e: IOException) { return@thread }
                client.use {
                    val reader = it.getInputStream().bufferedReader()
                    reader.readLine() ?: return@use
                    val headers = mutableMapOf<String, String>()
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                        headers[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim()
                    }
                    val reply = respond(headers)
                    val out = it.getOutputStream()
                    val length = reply.body?.size ?: 0
                    out.write("HTTP/1.1 ${reply.status} X\r\nContent-Length: $length\r\nConnection: close\r\n\r\n".toByteArray())
                    reply.body?.let(out::write)
                    out.flush()
                }
            }
        }
        return "http://127.0.0.1:${server.localPort}/file.mp4"
    }

    /** Ведёт себя как rclone: на `Range` отвечает 206 с нужным куском. */
    private fun rangeServer(): String = serve { headers ->
        val match = Regex("bytes=(\\d+)-(\\d+)").matchEntire(headers["range"].orEmpty())
        if (match == null) {
            Reply(200, content)
        } else {
            val from = match.groupValues[1].toInt()
            val to = minOf(match.groupValues[2].toInt(), content.size - 1)
            if (from >= content.size) Reply(416) else Reply(206, content.copyOfRange(from, to + 1))
        }
    }

    @Test
    fun `address encodes every part of the path and keeps the slashes`() {
        assertEquals(
            "http://127.0.0.1:1/%D0%A4%D0%BE%D1%82%D0%BE%20%D0%BE%D1%82%D0%BF%D1%83%D1%81%D0%BA/a%2Bb.mp4",
            CloudStreams.urlFor("http://127.0.0.1:1/", "/Фото отпуск/a+b.mp4"),
        )
        assertEquals("http://x/y", CloudStreams.urlFor("http://x", "y"))
    }

    @Test
    fun `range header covers exactly the bytes asked for`() {
        assertEquals("bytes=0-0", CloudStreams.rangeHeader(0, 1))
        assertEquals("bytes=1000-1999", CloudStreams.rangeHeader(1000, 1000))
        assertEquals("bytes=4294967296-4294971391", CloudStreams.rangeHeader(4_294_967_296L, 4096))
    }

    @Test
    fun `a piece from the middle is read exactly`() {
        val url = rangeServer()
        val data = ByteArray(1000)

        val read = CloudStreams.readRange(url, "Basic x", 2500, 1000, data)

        assertEquals(1000, read)
        assertContentEquals(content.copyOfRange(2500, 3500), data)
    }

    @Test
    fun `asking past the end returns what is left, then zero`() {
        val url = rangeServer()
        val data = ByteArray(4096)

        // Хвост: просят 4096, осталось 1000.
        assertEquals(1000, CloudStreams.readRange(url, "Basic x", 9000, 4096, data))
        assertContentEquals(content.copyOfRange(9000, 10_000), data.copyOf(1000))
        // За концом — ноль: так читающий понимает, что файл кончился.
        assertEquals(0, CloudStreams.readRange(url, "Basic x", 10_000, 4096, data))
    }

    @Test
    fun `the password is sent with every request`() {
        var seen: String? = null
        val url = serve { headers ->
            seen = headers["authorization"]
            Reply(206, byteArrayOf(1))
        }

        CloudStreams.readRange(url, "Basic c2VjcmV0", 0, 1, ByteArray(1))

        assertEquals("Basic c2VjcmV0", seen)
    }

    @Test
    fun `a server that ignores Range is an error, not a silent full download`() {
        // Ответ 200 вместо 206 — сервер отдал бы весь файл с начала, а плеер получил
        // бы чужие байты. Честная ошибка лучше.
        val url = serve { Reply(200, content) }
        assertFailsWith<IOException> { CloudStreams.readRange(url, "Basic x", 5000, 100, ByteArray(100)) }
    }

    @Test
    fun `refusal is an io error`() {
        val denied = serve { Reply(401) }
        assertFailsWith<IOException> { CloudStreams.readRange(denied, "Basic wrong", 0, 10, ByteArray(10)) }
    }
}
