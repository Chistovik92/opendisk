package com.opendisk.app

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Файл автозапуска для Linux.
 *
 * Проверяется именно содержимое, а не запись на диск: ошибка здесь тихая —
 * система просто не запустит приложение при входе, и заметить это можно
 * только при следующей перезагрузке.
 */
class AutostartTest {

    private val target = Autostart.Target("/opt/opendisk/bin/OpenDisk")

    @Test
    fun `desktop entry launches the app hidden`() {
        val entry = Autostart.desktopEntry(target)

        // Без --hidden окно выскакивало бы при каждом входе в систему.
        assertContains(entry, "Exec=/opt/opendisk/bin/OpenDisk --hidden")
    }

    @Test
    fun `desktop entry has the keys autostart needs`() {
        val entry = Autostart.desktopEntry(target)

        assertContains(entry, "[Desktop Entry]")
        assertContains(entry, "Type=Application")
        assertContains(entry, "Name=OpenDisk")
        // Без этого ключа GNOME считает запись выключенной.
        assertContains(entry, "X-GNOME-Autostart-enabled=true")
        // Приложение с интерфейсом не должно открывать терминал.
        assertContains(entry, "Terminal=false")
        assertTrue(entry.endsWith("\n"), "файл .desktop должен заканчиваться переводом строки")
    }

    @Test
    fun `autostart is offered on windows and linux`() {
        val os = System.getProperty("os.name").lowercase()
        val isMac = os.contains("mac") || os.contains("darwin")

        // На macOS автозапуск пока не сделан, и обещать его в интерфейсе нельзя.
        if (isMac) assertFalse(Autostart.isSupported()) else assertTrue(Autostart.isSupported())
    }

    @Test
    fun `autostart refuses to register a dev build`() {
        // Свойство jpackage.app-path есть только у установленного приложения.
        // Прописывать в автозагрузку каталог сборки бессмысленно, и попытка
        // должна честно проваливаться, а не создавать нерабочую запись.
        if (System.getProperty("jpackage.app-path") == null) {
            assertFalse(Autostart.setEnabled(true))
        }
    }
}
