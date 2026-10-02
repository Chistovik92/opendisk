package com.opendisk.app

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Находки аудита десктопа 0.6.0: то, что ломалось редко и молча — после
 * обрыва записи, при одновременных правках и на машине с несколькими
 * пользователями.
 */
class DesktopRobustnessTest {

    private fun settingsFile(): File = File(createTempDirectory("robust").toFile(), "settings.json")

    @Test
    fun `an interrupted write leaves the previous settings whole`() {
        val file = settingsFile()
        val settings = AppSettings(file)
        settings.update("yandex", CloudSettings(mountPoint = "X:"))

        // Обрыв посреди записи: временный файл недописан, настоящий не тронут.
        File(file.parentFile, "settings.json.tmp").writeText("{ \"clouds\": { \"обор")

        assertEquals("X:", AppSettings(file).forCloud("yandex").mountPoint)
        // И следующая запись благополучно заменяет недописанный временный файл.
        settings.update("gdrive", CloudSettings(mountPoint = "G:"))
        assertEquals("G:", AppSettings(file).forCloud("gdrive").mountPoint)
        assertEquals("X:", AppSettings(file).forCloud("yandex").mountPoint)
    }

    @Test
    fun `no temporary file is left behind after a write`() {
        val file = settingsFile()
        AppSettings(file).update("a", CloudSettings(mountPoint = "A:"))

        assertFalse(File(file.parentFile, "settings.json.tmp").exists())
    }

    @Test
    fun `a corrupted file is kept aside before it is overwritten`() {
        val file = settingsFile()
        file.writeText("это не json {{{")
        val settings = AppSettings(file)

        // Читается как значения по умолчанию — и приложение запускается.
        assertEquals(null, settings.forCloud("yandex").mountPoint)
        // Но испорченное не пропало: следующая запись иначе стёрла бы его.
        settings.update("yandex", CloudSettings(mountPoint = "Y:"))

        assertEquals("это не json {{{", File(file.parentFile, "settings.json.broken").readText())
        assertEquals("Y:", AppSettings(file).forCloud("yandex").mountPoint)
    }

    @Test
    fun `simultaneous updates of different clouds are all kept`() {
        val file = settingsFile()
        val settings = AppSettings(file)
        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)

        // Без замка «прочитал, поправил, записал» терял правки соседей.
        val jobs = (1..40).map { index ->
            pool.submit {
                start.await()
                settings.update("cloud$index", CloudSettings(mountPoint = "M$index:"))
            }
        }
        start.countDown()
        jobs.forEach { it.get(30, TimeUnit.SECONDS) }
        pool.shutdown()

        val saved = AppSettings(file).load()
        assertEquals(40, saved.size, "потеряно: " + (1..40).filter { "cloud$it" !in saved })
    }

    @Test
    fun `global and cloud settings do not overwrite each other`() {
        val settings = AppSettings(settingsFile())
        settings.update("yandex", CloudSettings(mountPoint = "X:"))
        settings.updateGlobal(GlobalSettings(trayStats = false))
        settings.update("gdrive", CloudSettings(mountPoint = "G:"))

        assertEquals("X:", settings.forCloud("yandex").mountPoint)
        assertFalse(settings.global().trayStats)
    }

    // --- Автозапуск на Linux --------------------------------------------------

    @Test
    fun `an autostart path with spaces is quoted for the desktop entry`() {
        // Без кавычек оболочка режет «/home/u/Мои программы/OpenDisk.AppImage» по
        // пробелу, и приложение после входа в систему не запускалось.
        assertEquals("/opt/opendisk/bin/OpenDisk", Autostart.execQuote("/opt/opendisk/bin/OpenDisk"))
        assertEquals("\"/home/u/Мои программы/OpenDisk.AppImage\"", Autostart.execQuote("/home/u/Мои программы/OpenDisk.AppImage"))
        // Служебные знаки внутри кавычек экранируются по спецификации.
        assertEquals("\"/tmp/a \\\$b \\\"c\\\" \\`d\\` \\\\e\"", Autostart.execQuote("/tmp/a \$b \"c\" `d` \\e"))
    }

    @Test
    fun `the desktop entry uses the quoted path`() {
        val entry = Autostart.desktopEntry(Autostart.Target("/home/u/My Apps/OpenDisk.AppImage"))
        assertTrue(entry.contains("Exec=\"/home/u/My Apps/OpenDisk.AppImage\" "), entry)
    }

    // --- Единственный экземпляр ---------------------------------------------

    @Test
    fun `each user gets a port of their own, inside the dynamic range`() {
        val ports = listOf("alice", "bob", "Администратор", "SYSTEM").map { SingleInstance.portFor(it) }

        assertTrue(ports.all { it in 49152..65151 }, "порты вне диапазона: $ports")
        assertEquals(ports, listOf("alice", "bob", "Администратор", "SYSTEM").map { SingleInstance.portFor(it) }, "порт не должен меняться")
        assertEquals(ports.size, ports.toSet().size, "у этих имён порты разные: $ports")
        // Отрицательный hashCode не должен давать порт ниже диапазона.
        assertTrue(SingleInstance.portFor("polygenelubricants") >= 49152)
    }

    @Test
    fun `the handshake carries the user, so another user is not mistaken for us`() {
        assertNotEquals(SingleInstance.handshakeFor("alice"), SingleInstance.handshakeFor("bob"))
        assertTrue(SingleInstance.handshakeFor("alice").endsWith("alice"))
    }

    @Test
    fun `a second launch of the same user is refused, another user may start`() {
        val suffix = System.nanoTime()
        val alice = "test-alice-$suffix"
        val bob = "test-bob-$suffix"
        var activated = 0

        assertTrue(SingleInstance.acquire(alice) { activated++ }, "первый запуск должен занять замок")
        assertFalse(SingleInstance.acquire(alice) { }, "второй запуск того же пользователя — отказ")
        // Окно первого экземпляра просят показать.
        Thread.sleep(300)
        assertEquals(1, activated)
        assertTrue(SingleInstance.acquire(bob) { }, "другой пользователь запускается независимо")
    }
}
