package com.opendisk.bridge

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Правила поиска rclone.conf проверяем для всех ОС сразу — окружение и имя ОС
 * передаются параметрами, поэтому тесты не зависят от машины, на которой идут.
 */
class RcloneConfigFileTest {

    private val home = "/home/user"

    private fun env(vararg pairs: Pair<String, String>): (String) -> String? {
        val map = pairs.toMap()
        return { map[it] }
    }

    @Test
    fun `RCLONE_CONFIG wins over everything else`() {
        val config = RcloneConfigFile.default(
            env = env("RCLONE_CONFIG" to "/custom/my.conf", "APPDATA" to "C:/AppData"),
            userHome = home,
            osName = "Windows 11",
        )

        assertEquals(File("/custom/my.conf"), config.path)
    }

    @Test
    fun `blank RCLONE_CONFIG is ignored`() {
        val config = RcloneConfigFile.default(
            env = env("RCLONE_CONFIG" to "   ", "XDG_CONFIG_HOME" to "/xdg"),
            userHome = home,
            osName = "Linux",
        )

        assertEquals(File("/xdg/rclone/rclone.conf"), config.path)
    }

    @Test
    fun `windows uses APPDATA`() {
        val config = RcloneConfigFile.default(
            env = env("APPDATA" to "C:/Users/user/AppData/Roaming"),
            userHome = home,
            osName = "Windows 11",
        )

        assertEquals(File("C:/Users/user/AppData/Roaming/rclone/rclone.conf"), config.path)
    }

    @Test
    fun `windows without APPDATA falls back to home`() {
        val config = RcloneConfigFile.default(env = env(), userHome = home, osName = "Windows 11")

        assertEquals(File("$home/AppData/Roaming/rclone/rclone.conf"), config.path)
    }

    @Test
    fun `linux without XDG_CONFIG_HOME uses dot-config`() {
        val config = RcloneConfigFile.default(env = env(), userHome = home, osName = "Linux")

        assertEquals(File("$home/.config/rclone/rclone.conf"), config.path)
    }

    @Test
    fun `legacy dot-rclone-conf is used when the modern path is absent`() {
        val fakeHome = createTempDirectory("home").toFile()
        val legacy = File(fakeHome, ".rclone.conf").apply { writeText("[demo]\ntype = local\n") }

        val config = RcloneConfigFile.default(
            env = env(),
            userHome = fakeHome.absolutePath,
            osName = "Linux",
        )

        assertEquals(legacy, config.path)
    }

    @Test
    fun `modern path wins over legacy when both exist`() {
        val fakeHome = createTempDirectory("home").toFile()
        File(fakeHome, ".rclone.conf").writeText("[old]\ntype = local\n")
        val modern = File(fakeHome, ".config/rclone/rclone.conf").apply {
            parentFile.mkdirs()
            writeText("[new]\ntype = local\n")
        }

        val config = RcloneConfigFile.default(
            env = env(),
            userHome = fakeHome.absolutePath,
            osName = "Linux",
        )

        assertEquals(modern, config.path)
    }

    @Test
    fun `plain config is not reported as encrypted`() {
        val dir = createTempDirectory("plain").toFile()
        val file = File(dir, "rclone.conf").apply { writeText("[demo]\ntype = local\n") }

        val config = RcloneConfigFile(file)

        assertTrue(config.exists())
        assertFalse(config.isEncrypted())
    }

    @Test
    fun `encrypted config is detected by its marker`() {
        val config = RcloneConfigFile(encryptedFixture())

        assertTrue(config.exists())
        assertTrue(config.isEncrypted())
    }

    @Test
    fun `missing config is neither existing nor encrypted`() {
        val dir = createTempDirectory("missing").toFile()

        val config = RcloneConfigFile(File(dir, "rclone.conf"))

        assertFalse(config.exists())
        assertFalse(config.isEncrypted())
    }

    companion object {
        /** Пароль от [encryptedFixture] — фикстура содержит только remote `demo` типа local. */
        const val FIXTURE_PASSWORD = "hunter2"

        /**
         * Копия зашифрованного конфига во временном файле: rclone может его
         * переписать, а портить ресурс сборки нельзя.
         */
        fun encryptedFixture(): File {
            val source = requireNotNull(
                RcloneConfigFileTest::class.java.getResourceAsStream("/encrypted-rclone.conf"),
            ) { "фикстура encrypted-rclone.conf не найдена в тестовых ресурсах" }

            val target = File(createTempDirectory("encrypted").toFile(), "rclone.conf")
            source.use { input -> target.outputStream().use { input.copyTo(it) } }
            return target
        }
    }
}
