package com.opendisk.bridge

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Прерываемое копирование: задача rclone запускается асинхронно, её состояние
 * опрашивается, а по просьбе — останавливается. Настоящий rclone не нужен:
 * проверяется разговор с RC API, а не передача байтов.
 */
class CancellableCopyTest {

    /** Отвечает заготовками по имени метода и запоминает вызовы. */
    private class ScriptedTransport(private val answers: Map<String, ArrayDeque<String>>) : RcloneTransport {
        val calls = mutableListOf<Pair<String, JsonObject>>()

        override suspend fun rpc(endpoint: String, body: JsonObject): JsonObject {
            calls += endpoint to body
            val next = answers[endpoint]?.removeFirstOrNull() ?: "{}"
            return Json.parseToJsonElement(next) as JsonObject
        }

        override fun close() = Unit
    }

    @Test
    fun `copy runs as an rclone job and waits for it`() = runBlocking {
        val transport = ScriptedTransport(
            mapOf(
                "operations/copyfile" to ArrayDeque(listOf("""{"jobid":7}""")),
                "job/status" to ArrayDeque(
                    listOf("""{"id":7,"finished":false}""", """{"id":7,"finished":true,"success":true}"""),
                ),
            ),
        )

        RcloneClient(transport).copyFileCancellable("g:", "a.mp4", "/cache", "a", pollMillis = 1) { false }

        val start = transport.calls.first()
        assertEquals("operations/copyfile", start.first)
        assertEquals("true", start.second["_async"]!!.jsonPrimitive.content)
        assertEquals(2, transport.calls.count { it.first == "job/status" })
    }

    @Test
    fun `cancelling stops the rclone job`() = runBlocking {
        val transport = ScriptedTransport(
            mapOf(
                "operations/copyfile" to ArrayDeque(listOf("""{"jobid":9}""")),
                "job/status" to ArrayDeque(List(10) { """{"id":9,"finished":false}""" }),
            ),
        )
        var polls = 0

        assertFailsWith<CancellationException> {
            RcloneClient(transport).copyFileCancellable("g:", "a.mp4", "/cache", "a", pollMillis = 1) {
                polls++ >= 2
            }
        }

        val stop = transport.calls.last()
        assertEquals("job/stop", stop.first)
        assertEquals("9", stop.second["jobid"]!!.jsonPrimitive.content)
    }

    @Test
    fun `failed job becomes an rclone error with its message`() = runBlocking {
        val transport = ScriptedTransport(
            mapOf(
                "operations/copyfile" to ArrayDeque(listOf("""{"jobid":3}""")),
                "job/status" to ArrayDeque(
                    listOf("""{"id":3,"finished":true,"success":false,"error":"object not found"}"""),
                ),
            ),
        )

        val error = assertFailsWith<RcloneRcException> {
            RcloneClient(transport).copyFileCancellable("g:", "a.mp4", "/cache", "a", pollMillis = 1) { false }
        }
        assertTrue("object not found" in error.rcloneError)
    }
}
