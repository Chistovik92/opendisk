package com.opendisk.app

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Хранилище настроек облаков.
 *
 * Правила поиска пути проверяются для всех ОС сразу — окружение и имя ОС
 * передаются параметрами, поэтому тесты не зависят от машины, на которой идут.
 */
class AppSettingsTest {

    private fun settingsIn(dir: File = createTempDirectory("settings").toFile()) =
        AppSettings(File(dir, "settings.json"))

    @Test
    fun `returns defaults when nothing is stored`() {
        val settings = settingsIn()

        val cloud = settings.forCloud("yandex")

        assertEquals(CloudSettings.DEFAULT_CACHE_MODE, cloud.cacheMode)
        assertNull(cloud.mountPoint)
    }

    @Test
    fun `keeps settings between reads`() {
        val dir = createTempDirectory("settings").toFile()
        settingsIn(dir).update("yandex", CloudSettings(cacheMode = "full", mountPoint = "X:"))

        // Новый экземпляр читает тот же файл — так же, как после перезапуска.
        val reloaded = settingsIn(dir).forCloud("yandex")

        assertEquals("full", reloaded.cacheMode)
        assertEquals("X:", reloaded.mountPoint)
    }

    @Test
    fun `settings of one cloud do not affect another`() {
        val dir = createTempDirectory("settings").toFile()
        val settings = settingsIn(dir)
        settings.update("first", CloudSettings(cacheMode = "off"))
        settings.update("second", CloudSettings(cacheMode = "full"))

        assertEquals("off", settings.forCloud("first").cacheMode)
        assertEquals("full", settings.forCloud("second").cacheMode)
    }

    @Test
    fun `rename moves settings to the new name`() {
        val settings = settingsIn()
        settings.update("before", CloudSettings(cacheMode = "full", mountPoint = "/mnt/x"))

        settings.rename("before", "after")

        assertEquals("full", settings.forCloud("after").cacheMode)
        assertEquals("/mnt/x", settings.forCloud("after").mountPoint)
        // Старое имя не должно остаться: иначе настройки копились бы мусором.
        assertEquals(CloudSettings.DEFAULT_CACHE_MODE, settings.forCloud("before").cacheMode)
    }

    @Test
    fun `forget removes settings of a deleted cloud`() {
        val settings = settingsIn()
        settings.update("gone", CloudSettings(cacheMode = "full"))

        settings.forget("gone")

        assertTrue(settings.load().isEmpty())
    }

    @Test
    fun `broken settings file does not break the app`() {
        val dir = createTempDirectory("settings").toFile()
        File(dir, "settings.json").writeText("{ это не json")

        // Испорченный файл — не повод не запускаться: возвращаем умолчания.
        assertTrue(settingsIn(dir).load().isEmpty())
        assertEquals(CloudSettings.DEFAULT_CACHE_MODE, settingsIn(dir).forCloud("any").cacheMode)
    }

    @Test
    fun `windows keeps settings in APPDATA`() {
        val file = AppSettings.defaultFile(
            env = { name -> if (name == "APPDATA") "C:/Users/user/AppData/Roaming" else null },
            userHome = "C:/Users/user",
            osName = "Windows 11",
        )

        assertEquals(File("C:/Users/user/AppData/Roaming/opendisk/settings.json"), file)
    }

    @Test
    fun `linux follows XDG_CONFIG_HOME`() {
        val file = AppSettings.defaultFile(
            env = { name -> if (name == "XDG_CONFIG_HOME") "/xdg" else null },
            userHome = "/home/user",
            osName = "Linux",
        )

        assertEquals(File("/xdg/opendisk/settings.json"), file)
    }

    @Test
    fun `linux without XDG falls back to dot-config`() {
        val file = AppSettings.defaultFile(
            env = { null },
            userHome = "/home/user",
            osName = "Linux",
        )

        assertEquals(File("/home/user/.config/opendisk/settings.json"), file)
    }

    @Test
    fun `global settings survive cloud changes and back`() {
        val dir = createTempDirectory("settings").toFile()
        val settings = settingsIn(dir)

        settings.updateGlobal(GlobalSettings(autostart = true, bandwidthLimit = "2M"))
        // Запись настроек облака не должна затирать общие — они в одном файле.
        settings.update("yandex", CloudSettings(cacheMode = "full"))

        val reloaded = settingsIn(dir)
        assertTrue(reloaded.global().autostart)
        assertEquals("2M", reloaded.global().bandwidthLimit)
        assertEquals("full", reloaded.forCloud("yandex").cacheMode)
    }

    @Test
    fun `global settings do not erase clouds`() {
        val dir = createTempDirectory("settings").toFile()
        val settings = settingsIn(dir)

        settings.update("yandex", CloudSettings(mountOnStartup = true))
        settings.updateGlobal(GlobalSettings(bandwidthLimit = "1M"))

        val reloaded = settingsIn(dir)
        assertTrue(reloaded.forCloud("yandex").mountOnStartup)
        assertEquals("1M", reloaded.global().bandwidthLimit)
    }

    @Test
    fun `startup flag is remembered per cloud`() {
        val dir = createTempDirectory("settings").toFile()
        val settings = settingsIn(dir)

        settings.update("auto", CloudSettings(mountOnStartup = true))
        settings.update("manual", CloudSettings(mountOnStartup = false))

        val reloaded = settingsIn(dir)
        assertTrue(reloaded.forCloud("auto").mountOnStartup)
        assertFalse(reloaded.forCloud("manual").mountOnStartup)
    }

    @Test
    fun `every cache mode offered in the UI is understood by rclone`() {
        // Значения уходят в rclone как есть, поэтому опечатка здесь означала бы
        // отказ монтирования с невнятной ошибкой.
        val allowed = setOf("off", "minimal", "writes", "full")

        assertEquals(allowed, CACHE_MODES.map { it.value }.toSet())
        assertTrue(CloudSettings.DEFAULT_CACHE_MODE in allowed)
        assertTrue(CACHE_MODES.all { it.explanation.isNotBlank() })
    }
}
