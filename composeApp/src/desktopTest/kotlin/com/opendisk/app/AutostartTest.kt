package com.opendisk.app

import java.io.File
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
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
    fun `autostart is not offered when there is nothing to launch`() {
        // Тесты идут из каталога сборки: лаунчера рядом нет, значит и галочку
        // показывать нельзя. Раньше isSupported() смотрел только на ОС, поэтому
        // галочка была доступна всегда, а нажатие давало ошибку.
        assertFalse(
            Autostart.isSupported(),
            "при запуске из сборки автозапуск предлагаться не должен",
        )
    }

    @Test
    fun `autostart refuses to register a dev build`() {
        // Прописывать в автозагрузку каталог сборки бессмысленно, и попытка
        // должна честно проваливаться, а не создавать нерабочую запись.
        assertFalse(Autostart.setEnabled(true))
    }

    /**
     * Лаунчер ищется по расположению runtime, а не по свойству
     * `jpackage.app-path`: его выставляет лаунчер JDK 18 и новее, а собираемся
     * мы на 17. Из-за этого автозапуск не работал вообще нигде — галочка была,
     * нажатие давало ошибку. Раскладки ниже сняты с настоящих пакетов 0.1.24.
     */
    @Test
    fun `finds the windows launcher next to the runtime`() {
        val app = createTempDirectory("winapp").toFile()
        File(app, "runtime").mkdirs()
        val launcher = File(app, "OpenDisk.exe").apply { writeText("") }

        assertEquals(
            launcher.absolutePath,
            Autostart.launcherNear(File(app, "runtime"), windows = true)?.absolutePath,
        )
    }

    @Test
    fun `finds the linux launcher next to the runtime`() {
        // /opt/opendisk/bin/OpenDisk при runtime в /opt/opendisk/lib/runtime
        val app = createTempDirectory("linuxapp").toFile()
        File(app, "lib/runtime").mkdirs()
        val launcher = File(app, "bin/OpenDisk").apply {
            parentFile.mkdirs()
            writeText("")
        }

        assertEquals(
            launcher.absolutePath,
            Autostart.launcherNear(File(app, "lib/runtime"), windows = false)?.absolutePath,
        )
    }

    @Test
    fun `no launcher means no autostart`() {
        val nowhere = createTempDirectory("empty").toFile()

        assertNull(Autostart.launcherNear(File(nowhere, "runtime"), windows = true))
        assertNull(Autostart.launcherNear(File(nowhere, "lib/runtime"), windows = false))
    }

    /**
     * Запись в реестр и чтение обратно — целиком, настоящим `reg`.
     *
     * Эта ветка кода до сих пор не выполнялась ни разу: путь к лаунчеру всегда
     * получался пустым, и до записи дело не доходило. А ошибиться тут проще
     * всего на кавычках — в «C:\Program Files\OpenDisk» есть пробел, и без них
     * Windows запускала бы «C:\Program».
     *
     * Пишем в свой временный ключ, а не в настоящую автозагрузку: проверить
     * нужно команду и разбор, а не менять настройки того, кто гоняет тесты.
     */
    @Test
    fun `windows registry round trip keeps the quoted path`() {
        assumeTrue(
            System.getProperty("os.name").lowercase().contains("win"),
            "проверка про реестр Windows",
        )

        val key = """HKCU:\Software\OpenDiskTest"""
        val valueName = "AutostartRoundTrip"
        val target = Autostart.Target("""C:\Program Files\OpenDisk\OpenDisk.exe""")

        try {
            powerShell("New-Item -Path '$key' -Force | Out-Null")
            powerShell(Autostart.enableScript(key, valueName, target))

            val stored = powerShell(Autostart.readScript(key, valueName)).trim()
            // Путь с пробелом обязан приехать обратно в кавычках, а --hidden —
            // снаружи: это аргумент приложения, а не часть пути.
            assertEquals("\"C:\\Program Files\\OpenDisk\\OpenDisk.exe\" --hidden", stored)

            powerShell(Autostart.disableScript(key, valueName))
            assertEquals("", powerShell(Autostart.readScript(key, valueName)).trim())
        } finally {
            powerShell("Remove-Item -Path '$key' -Recurse -Force -ErrorAction SilentlyContinue")
        }
    }

    /**
     * Тот же способ запуска, что и в самом Autostart, — иначе проверка не про него.
     * В том числе без слияния потоков: именно на слитых потоках чтение значения
     * возвращало служебный CLIXML вместо пустоты.
     */
    private fun powerShell(script: String): String {
        val full = "\$ProgressPreference = 'SilentlyContinue'\n$script"
        val encoded = Base64.getEncoder().encodeToString(full.toByteArray(Charsets.UTF_16LE))
        val process = ProcessBuilder(
            "powershell", "-NoProfile", "-NonInteractive", "-EncodedCommand", encoded,
        ).start()
        val output = process.inputStream.bufferedReader().readText()
        assertTrue(process.waitFor(20, TimeUnit.SECONDS), "powershell не завершился")
        return output
    }
}
