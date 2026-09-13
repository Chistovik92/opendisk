package com.opendisk.bridge

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import kotlin.io.path.createTempDirectory
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class OAuthLinkTest {

    private var rclone: RcloneProcess? = null

    @AfterTest
    fun stopProcess() {
        rclone?.stop()
    }

    @Test
    fun `ссылка находится в строке вывода rclone`() {
        val line = "2026/09/13 14:07:51 NOTICE: Please go to the following link: " +
            "http://127.0.0.1:53682/auth?state=Bd5r4q3_l2omNcyjpxY9nA"
        assertEquals("http://127.0.0.1:53682/auth?state=Bd5r4q3_l2omNcyjpxY9nA", OAuthLink.find(line))
        assertNull(OAuthLink.find("NOTICE: Log in and authorize rclone for access"))
    }

    @Test
    fun `отмена идёт на тот же сервер с тем же state`() {
        assertEquals(
            "http://127.0.0.1:53682/?state=Bd5r4q3_l2omNcyjpxY9nA&error=access_denied",
            OAuthLink.cancelUrl("http://127.0.0.1:53682/auth?state=Bd5r4q3_l2omNcyjpxY9nA"),
        )
        assertNull(OAuthLink.cancelUrl("http://127.0.0.1:53682/auth"))
    }

    /**
     * Весь путь на настоящем rclone: запрос создания Google Диска повисает
     * в ожидании браузера, ссылка появляется в выводе, отмена по ней будит
     * rclone — и запрос возвращается с ошибкой, а не висит без срока.
     *
     * Ровно на этом держится кнопка «Отмена» на телефонах: там запрос идёт
     * внутри приложения, и прервать его иначе нечем.
     */
    @Test
    fun `отмена будит rclone, ждущего браузера`() {
        assumeTrue(RcloneProcess.locate() != null, "rclone не найден")

        val dir = createTempDirectory("oauth-config").toFile()
        val config = RcloneConfigFile(File(dir, "rclone.conf").apply { writeText("") })
        val process = RcloneProcess(
            rclonePath = requireNotNull(RcloneProcess.locate()).file.absolutePath,
            rcAddr = RcloneProcess.freeRcAddr(),
            config = config,
        )
        rclone = process
        process.start()
        runBlocking { process.awaitReady() }

        RcloneClient(process.rcBaseUrl).use { client ->
            runBlocking {
                val creation = async(Dispatchers.IO) {
                    runCatching { client.createRemote("g", "drive", emptyMap()) }
                }

                val link = withTimeout(20_000) {
                    var found: String? = null
                    while (found == null) {
                        found = process.recentOutput().firstNotNullOfOrNull(OAuthLink::find)
                        if (found == null) delay(200)
                    }
                    found
                }

                OAuthLink.cancel(link)

                val result = withTimeout(20_000) { creation.await() }
                assertFalse(result.isSuccess, "создание прошло, хотя вход отменён")
                val error = assertNotNull(result.exceptionOrNull() as? RcloneRcException)
                assertContains(error.rcloneError, "access_denied")
            }
        }
    }
}
