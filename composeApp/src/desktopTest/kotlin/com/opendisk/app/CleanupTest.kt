package com.opendisk.app

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Уборка при удалении.
 *
 * Главное здесь — граница между своим и чужим. Ошибка в одну сторону оставляет
 * мусор, в другую — молча удаляет данные пользователя или ломает посторонние
 * программы, и это несравнимо хуже.
 */
class CleanupTest {

    @Test
    fun `own files are the settings directory and the fallback cache`() {
        val home = createTempDirectory("home").toFile()
        val settingsDir = File(home, "AppData/Roaming/opendisk").apply { mkdirs() }
        val settings = File(settingsDir, "settings.json").apply { writeText("{}") }
        val cache = File(home, ".cache/opendisk").apply { mkdirs() }

        val own = Cleanup.ownFiles(settingsFile = settings, userHome = home.path)

        assertEquals(setOf(settingsDir, cache), own.toSet())
    }

    @Test
    fun `missing cache is simply not in the list`() {
        val home = createTempDirectory("home").toFile()
        val settings = File(home, "opendisk/settings.json").apply {
            parentFile.mkdirs()
            writeText("{}")
        }

        val own = Cleanup.ownFiles(settingsFile = settings, userHome = home.path)

        assertEquals(listOf(settings.parentFile), own)
    }

    @Test
    fun `removing takes the whole directory with it`() {
        val dir = createTempDirectory("victim").toFile()
        File(dir, "nested/deep").mkdirs()
        File(dir, "nested/deep/file.txt").writeText("x")

        assertEquals(1, Cleanup.remove(listOf(dir)))
        assertFalse(dir.exists())
    }

    @Test
    fun `removing what is not there is not a failure`() {
        val missing = File(createTempDirectory("empty").toFile(), "нет-такого")

        // Половина путей может просто не существовать — это не повод
        // останавливать удаление приложения.
        assertEquals(0, Cleanup.remove(listOf(missing)))
    }

    @Test
    fun `uninstall command is built only from a product code`() {
        val script = Cleanup.msiUninstallScript(
            "MsiExec.exe /X{12345678-1234-1234-1234-123456789ABC}",
            wait = true,
        )

        assertTrue(script!!.contains("{12345678-1234-1234-1234-123456789ABC}"))
        assertTrue(script.contains("-Verb RunAs"), "удаление драйвера требует прав администратора")
        assertTrue(script.contains("-Wait"))
    }

    @Test
    fun `self uninstall does not wait for the installer`() {
        // Ждать из закрываемого процесса нечего: установщик сам его и закроет.
        val script = Cleanup.msiUninstallScript(
            "MsiExec.exe /X{12345678-1234-1234-1234-123456789ABC}",
            wait = false,
        )

        assertFalse(script!!.contains("-Wait"))
    }

    @Test
    fun `anything but a product code is refused`() {
        // В UninstallString попадаются произвольные программы с ключами тихого
        // удаления. Запускать это вслепую нельзя — снесёт молча и не то.
        assertNull(Cleanup.msiUninstallScript("C:\\Program Files\\Стороннее\\uninst.exe /S", wait = true))
        assertNull(Cleanup.msiUninstallScript("", wait = true))
    }
}
