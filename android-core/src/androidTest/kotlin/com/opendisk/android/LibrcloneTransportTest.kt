package com.opendisk.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.opendisk.bridge.RcloneClient
import com.opendisk.bridge.RcloneRcException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Проверяет, что librclone действительно работает на устройстве.
 *
 * Тест инструментальный, а не обычный: библиотека нативная, и на JVM без
 * Android её просто нет. Мокать тут нечего — весь смысл в том, чтобы поймать
 * расхождение между тем, чего ждёт [LibrcloneTransport], и тем, что на самом
 * деле отдаёт `gomobile bind`. Именно такие расхождения на этом проекте
 * переживали зелёную сборку и всплывали только при запуске.
 */
@RunWith(AndroidJUnit4::class)
class LibrcloneTransportTest {

    @Test
    fun rcloneAnswersWithItsVersion() = runBlocking {
        val client = RcloneClient(LibrcloneTransport.get())

        val version = client.version()

        // Версия должна быть та же, что зафиксирована для десктопа: библиотека
        // и бинарник собираются из одного тега, и разъезд здесь означал бы, что
        // на телефоне и на компьютере разные формы ответов RC API.
        assertEquals("v$EXPECTED_RCLONE_VERSION", version.version)
        assertEquals("android", version.os)
    }

    @Test
    fun listRemotesWorksOnEmptyConfig() = runBlocking {
        val client = RcloneClient(LibrcloneTransport.get())

        // Конфига на свежем устройстве нет — важно, что это не ошибка, а пустой
        // список: с этого начинается любой первый запуск приложения.
        assertTrue(client.listRemotes().isEmpty())
    }

    @Test
    fun errorFromRcloneKeepsItsMessage() = runBlocking {
        val transport = LibrcloneTransport.get()

        val failure = assertFailsWith<RcloneRcException> {
            transport.rpc("config/get", buildJsonObject { put("name", "нет-такого-облака") })
        }

        // Ради этого транспорт и разбирает Status: без него ошибка rclone
        // выглядела бы как успешный ответ с невнятным содержимым.
        assertTrue(failure.statusCode >= 400, "ожидался код ошибки, получен ${failure.statusCode}")
        assertTrue(failure.rcloneError.isNotBlank(), "текст причины потерялся")
    }

    @Test
    fun unknownMethodIsReportedAsError() = runBlocking {
        val transport = LibrcloneTransport.get()

        val failure = assertFailsWith<RcloneRcException> {
            transport.rpc("такого/метода/нет", JsonObject(emptyMap()))
        }

        assertEquals("такого/метода/нет", failure.endpoint)
    }

    private companion object {
        /** Совпадает с librcloneVersion в android-core/build.gradle.kts. */
        const val EXPECTED_RCLONE_VERSION = "1.75.1"
    }
}
