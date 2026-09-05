package com.opendisk.bridge

import kotlinx.coroutines.runBlocking
import java.net.ServerSocket
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Проверка против настоящего `rclone rcd`: запускаем процесс, дожидаемся
 * готовности и дёргаем RC API. Мок-тесты в [RcloneClientTest] проверяют разбор
 * ответов, а этот — что формы ответов вообще такие, как мы предполагаем.
 *
 * Тест пропускается, если бинарник не найден. Чтобы прогнать его локально:
 *
 * ```
 * ./gradlew :rclone-bridge:test -Dopendisk.rclone.path=/путь/к/rclone
 * ```
 *
 * Путь к встроенному бинарнику после сборки:
 * `composeApp/build/appResources/common/rclone[.exe]`.
 */
class RcloneIntegrationTest {

    private var rclone: RcloneProcess? = null

    @AfterTest
    fun stopProcess() {
        rclone?.stop()
    }

    /**
     * Свободный порт вместо штатного 5572 — чтобы тест не подрался с уже
     * запущенным у разработчика rclone или с параллельным прогоном.
     */
    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private fun startRcd(): Pair<RcloneProcess, RcloneClient> {
        val binary = RcloneProcess.locate()?.file?.absolutePath
        val process = RcloneProcess(rclonePath = binary!!, rcAddr = "127.0.0.1:${freePort()}")
        rclone = process
        process.start()
        runBlocking { process.awaitReady() }
        return process to RcloneClient(process.rcBaseUrl)
    }

    @Test
    fun `talks to a real rclone rcd`() {
        if (RcloneProcess.locate() == null) return // rclone недоступен — пропускаем

        val (process, client) = startRcd()
        client.use {
            assertTrue(process.isRunning())

            runBlocking {
                val version = client.version()
                assertTrue(version.version.startsWith("v"), "неожиданная версия: ${version.version}")
                assertTrue(version.os.isNotEmpty())

                // На чистой машине облаков нет, но вызов обязан отработать без ошибок.
                client.listRemotes()

                // core/stats на простое не отдаёт transferring — поле должно
                // подставиться пустым списком, а не уронить разбор.
                val stats = client.coreStats()
                assertEquals(0, stats.errors)
                assertTrue(stats.transferring.isEmpty())
            }
        }

        process.stop()
        assertTrue(!process.isRunning())
    }

    @Test
    fun `rclone error reaches caller as RcloneRcException`() {
        if (RcloneProcess.locate() == null) return

        val (_, client) = startRcd()
        client.use {
            val failure = assertFailsWith<RcloneRcException> {
                runBlocking { client.jobStatus(jobId = 999_999) }
            }
            assertEquals(500, failure.statusCode)
            assertContains(failure.rcloneError, "job not found")
        }
    }
}
