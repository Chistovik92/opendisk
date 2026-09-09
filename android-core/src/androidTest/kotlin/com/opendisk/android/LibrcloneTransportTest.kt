package com.opendisk.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.opendisk.bridge.MountedPaths
import com.opendisk.bridge.PublicLinkUnsupportedException
import com.opendisk.bridge.RcloneClient
import com.opendisk.bridge.RcloneRcException
import com.opendisk.bridge.RemoteFile
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
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
            transport.rpc("operations/about", buildJsonObject { put("fs", "нет-такого-облака:") })
        }

        // Ради этого транспорт и разбирает Status: без него ошибка rclone
        // выглядела бы как успешный ответ с невнятным содержимым.
        assertTrue(failure.statusCode >= 400, "ожидался код ошибки, получен ${failure.statusCode}")
        assertTrue(failure.rcloneError.isNotBlank(), "текст причины потерялся")
    }

    @Test
    fun askingAboutUnknownCloudIsNotAnError() = runBlocking {
        val transport = LibrcloneTransport.get()

        // Проверено и на живом rcd, и здесь: config/get про несуществующее
        // облако отвечает пустым объектом с кодом 200, а не ошибкой. Знать это
        // важно — код, который ждал бы здесь исключения, молча считал бы любое
        // облако существующим.
        val answer = transport.rpc("config/get", buildJsonObject { put("name", "нет-такого") })

        assertTrue(answer.isEmpty(), "ожидался пустой объект, получено: $answer")
    }

    @Test
    fun unknownMethodIsReportedAsError() = runBlocking {
        val transport = LibrcloneTransport.get()

        val failure = assertFailsWith<RcloneRcException> {
            transport.rpc("такого/метода/нет", JsonObject(emptyMap()))
        }

        assertEquals("такого/метода/нет", failure.endpoint)
    }

    /**
     * Ссылки на файлы работают на телефоне тем же кодом, что и на компьютере.
     *
     * Ради этого транспорт и вынесен отдельным слоем: [RcloneClient] здесь тот
     * же самый, что в десктопном приложении, и проверять надо не логику — она
     * общая и покрыта обычными тестами, — а то, что через librclone эти вызовы
     * действительно проходят и отвечают той же формой.
     *
     * `:memory:` — встроенный бэкенд rclone, живущий в памяти: конфига не
     * требует и на свежем устройстве работает сразу.
     */
    @Test
    fun fsInfoTellsWhetherTheCloudCanMakeLinks() = runBlocking {
        val client = RcloneClient(LibrcloneTransport.get())

        val info = client.fsInfo(":memory")

        assertTrue(info.features.isNotEmpty(), "librclone не отдала список возможностей")
        // Память ссылок не умеет — как и локальный диск, SFTP и FTP.
        assertFalse(info.supportsPublicLink)
    }

    @Test
    fun backendWithoutLinksIsToldApartFromBreakage() = runBlocking {
        val client = RcloneClient(LibrcloneTransport.get())

        // Отдельное исключение, а не общая ошибка RC: интерфейсу надо сказать
        // «этот сервис так не умеет», а не «что-то сломалось».
        val failure = assertFailsWith<PublicLinkUnsupportedException> {
            client.publicLink(":memory", "файл.txt")
        }

        assertEquals(":memory", failure.remote)
    }

    /**
     * Разбор пути общий с десктопом и работает на Android как есть.
     *
     * На телефоне точки монтирования нет — вместо неё будет корень, который
     * `DocumentsProvider` показывает системным «Файлам», — но раскладывать путь
     * на облако и путь внутри него придётся ровно так же.
     */
    @Test
    fun pathInsideACloudIsResolvedTheSameWayAsOnDesktop() {
        val resolved = MountedPaths.resolve(
            localPath = "/облака/яндекс/Документы/смета.xlsx",
            mounts = mapOf("яндекс" to "/облака/яндекс"),
            caseInsensitive = false,
        )

        assertEquals(RemoteFile("яндекс", "Документы/смета.xlsx"), resolved)
    }

    private companion object {
        /** Совпадает с librcloneVersion в android-core/build.gradle.kts. */
        const val EXPECTED_RCLONE_VERSION = "1.75.1"
    }
}
