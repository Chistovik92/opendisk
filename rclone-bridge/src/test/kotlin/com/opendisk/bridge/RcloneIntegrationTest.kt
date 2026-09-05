package com.opendisk.bridge

import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.io.path.createTempDirectory
import org.junit.jupiter.api.Assumptions.assumeTrue
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

    /** Пустой конфиг во временном каталоге: настоящий конфиг пользователя не трогаем. */
    private fun plainConfig(content: String = "[demo]\ntype = local\n"): RcloneConfigFile {
        val dir = createTempDirectory("plain-config").toFile()
        return RcloneConfigFile(File(dir, "rclone.conf").apply { writeText(content) })
    }

    private fun startRcd(
        config: RcloneConfigFile,
        password: String? = null,
    ): Pair<RcloneProcess, RcloneClient> {
        val binary = requireNotNull(RcloneProcess.locate()).file.absolutePath
        val process = RcloneProcess(
            rclonePath = binary,
            rcAddr = RcloneProcess.freeRcAddr(),
            config = config,
            configPassword = password,
        )
        rclone = process
        process.start()
        runBlocking { process.awaitReady() }
        return process to RcloneClient(process.rcBaseUrl)
    }

    @Test
    fun `talks to a real rclone rcd`() {
        assumeTrue(RcloneProcess.locate() != null, "rclone не найден")

        val (process, client) = startRcd(plainConfig())
        client.use {
            assertTrue(process.isRunning())

            runBlocking {
                val version = client.version()
                assertTrue(version.version.startsWith("v"), "неожиданная версия: ${version.version}")
                assertTrue(version.os.isNotEmpty())

                assertEquals(listOf("demo"), client.listRemotes())

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
        assumeTrue(RcloneProcess.locate() != null, "rclone не найден")

        val (_, client) = startRcd(plainConfig())
        client.use {
            val failure = assertFailsWith<RcloneRcException> {
                runBlocking { client.jobStatus(jobId = 999_999) }
            }
            assertEquals(500, failure.statusCode)
            assertContains(failure.rcloneError, "job not found")
        }
    }

    @Test
    fun `encrypted config without password reports itself as locked`() {
        assumeTrue(RcloneProcess.locate() != null, "rclone не найден")

        val config = RcloneConfigFile(RcloneConfigFileTest.encryptedFixture())
        assertTrue(config.isEncrypted())

        val (process, client) = startRcd(config, password = null)
        client.use {
            // Ключевой момент: rcd поднимается и отвечает, хотя конфиг ему недоступен.
            // Поэтому awaitReady() успешен, а узнаём мы о проблеме только здесь.
            assertTrue(process.isRunning())
            runBlocking { client.version() }

            assertFailsWith<RcloneConfigLockedException> {
                runBlocking { client.ensureConfigReadable() }
            }
        }
    }

    @Test
    fun `encrypted config is readable with the right password`() {
        assumeTrue(RcloneProcess.locate() != null, "rclone не найден")

        val config = RcloneConfigFile(RcloneConfigFileTest.encryptedFixture())
        val (_, client) = startRcd(config, password = RcloneConfigFileTest.FIXTURE_PASSWORD)

        client.use {
            runBlocking {
                client.ensureConfigReadable()
                assertEquals(listOf("demo"), client.listRemotes())
            }
        }
    }

    @Test
    fun `wrong password is reported as locked too`() {
        assumeTrue(RcloneProcess.locate() != null, "rclone не найден")

        val config = RcloneConfigFile(RcloneConfigFileTest.encryptedFixture())
        val (_, client) = startRcd(config, password = "definitely-not-the-password")

        client.use {
            assertFailsWith<RcloneConfigLockedException> {
                runBlocking { client.ensureConfigReadable() }
            }
        }
    }
}
