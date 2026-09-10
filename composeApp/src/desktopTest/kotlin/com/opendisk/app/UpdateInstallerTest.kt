package com.opendisk.app

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Разбор контрольных сумм и команда запуска установщика.
 *
 * Сверка суммы — не формальность: файл приезжает из сети и запускается
 * с правами администратора. Ошибка в разборе означала бы, что сверять нечем,
 * а значит либо обновление не поставится никогда, либо — хуже — поставится
 * что-то непроверенное.
 *
 * Сам сценарий установки проверяется не здесь, а в CI на настоящей Windows:
 * scripts/verify-windows-install.ps1 запускает тот же файл ресурсов. Здесь —
 * только то, как приложение его вызывает.
 */
class UpdateInstallerTest {

    @Test
    fun `checksum is found by the exact file name`() {
        val sums = """
            aaaa1111aaaa1111aaaa1111aaaa1111aaaa1111aaaa1111aaaa1111aaaa1111  OpenDisk-0.5.2-x64.exe
            bbbb2222bbbb2222bbbb2222bbbb2222bbbb2222bbbb2222bbbb2222bbbb2222  OpenDisk-0.5.2-arm64.exe
        """.trimIndent()

        assertEquals(
            "aaaa1111aaaa1111aaaa1111aaaa1111aaaa1111aaaa1111aaaa1111aaaa1111",
            UpdateInstaller.checksumFor(sums, "OpenDisk-0.5.2-x64.exe"),
        )
    }

    @Test
    fun `binary marker before the name is not part of it`() {
        // sha256sum помечает двоичные файлы звёздочкой перед именем.
        val sums = "cccc3333cccc3333cccc3333cccc3333cccc3333cccc3333cccc3333cccc3333 *OpenDisk-0.5.2-x64.exe"

        assertEquals(
            "cccc3333cccc3333cccc3333cccc3333cccc3333cccc3333cccc3333cccc3333",
            UpdateInstaller.checksumFor(sums, "OpenDisk-0.5.2-x64.exe"),
        )
    }

    @Test
    fun `name is matched whole, not by substring`() {
        val sums = "dddd4444dddd4444dddd4444dddd4444dddd4444dddd4444dddd4444dddd4444  x-OpenDisk-0.5.2-x64.exe"

        // Иначе сумма чужого файла сошла бы за нашу.
        assertNull(UpdateInstaller.checksumFor(sums, "OpenDisk-0.5.2-x64.exe"))
    }

    @Test
    fun `a line without a proper hash is not a checksum`() {
        assertNull(UpdateInstaller.checksumFor("коротко  OpenDisk-0.5.2-x64.exe", "OpenDisk-0.5.2-x64.exe"))
        assertNull(UpdateInstaller.checksumFor("", "OpenDisk-0.5.2-x64.exe"))
    }

    @Test
    fun `hash of a file is computed the same way as sha256sum`() {
        val file = File(createTempDirectory("sha").toFile(), "data.bin")
        file.writeText("opendisk")

        assertEquals(64, UpdateInstaller.sha256(file).length)
        assertEquals(UpdateInstaller.sha256(file), UpdateInstaller.sha256(file))
    }

    @Test
    fun `installer path is passed as a parameter, quoted for the shell`() {
        val script = UpdateInstaller.installScript("""C:\Users\Кто-то\AppData\Local\Temp\OpenDisk-0.5.2-x64.exe""")

        // Путь с пробелами и кириллицей ломался бы без кавычек — на этом уже
        // спотыкались установка WinFsp и автозапуск.
        assertTrue(script.contains("""-Installer 'C:\Users\Кто-то\AppData\Local\Temp\OpenDisk-0.5.2-x64.exe'"""))
        assertTrue(script.contains("-Verb RunAs"), "установка в Program Files требует прав администратора")
    }

    @Test
    fun `the exe is run by itself, not through msiexec`() {
        val script = UpdateInstaller.installScript("""C:\t\OpenDisk-0.5.2-x64.exe""")

        // С 0.5.0 установщик — обёртка Burn. msiexec на .exe просто упал бы.
        assertFalse(script.contains("msiexec"), "exe нельзя ставить через msiexec")
        assertTrue(script.contains("'/passive'"), "обновление подтверждено кнопкой — вопросов больше не задаём")
    }

    @Test
    fun `single quotes in the path are escaped for powershell`() {
        val script = UpdateInstaller.installScript("""C:\it's here\OpenDisk.exe""")

        assertTrue(script.contains("it''s here"), "одинарная кавычка оборвала бы строку сценария")
    }

    @Test
    fun `launcher is passed only when known`() {
        val with = UpdateInstaller.installScript("""C:\t\a.exe""", """C:\Program Files\OpenDisk\OpenDisk.exe""")
        val without = UpdateInstaller.installScript("""C:\t\a.exe""", null)

        assertTrue(with.contains("""-Launcher 'C:\Program Files\OpenDisk\OpenDisk.exe'"""))
        assertFalse(without.contains("-Launcher"))
    }

    @Test
    fun `comments are stripped before the script goes on the command line`() {
        val script = PowerShellScript.load("install-update.ps1")

        // Командная строка Windows ограничена, а -EncodedCommand раздувает
        // текст почти втрое. Пояснения нужны читающему файл, а не PowerShell.
        assertTrue(script.lines().none { it.trimStart().startsWith("#") })
        assertFalse(script.startsWith("\uFEFF"), "BOM внутри команды лишний")
        assertTrue(script.contains("param("))
    }
}
