package com.opendisk.app

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Разбор контрольных сумм и команда запуска установщика.
 *
 * Сверка суммы — не формальность: файл приезжает из сети и запускается
 * с правами администратора. Ошибка в разборе означала бы, что сверять нечем,
 * а значит либо обновление не поставится никогда, либо — хуже — поставится
 * что-то непроверенное.
 */
class UpdateInstallerTest {

    @Test
    fun `checksum is found by the exact file name`() {
        val sums = """
            aaaa1111aaaa1111aaaa1111aaaa1111aaaa1111aaaa1111aaaa1111aaaa1111  OpenDisk-0.2.5.msi
            bbbb2222bbbb2222bbbb2222bbbb2222bbbb2222bbbb2222bbbb2222bbbb2222  SHA256SUMS-Windows
        """.trimIndent()

        assertEquals(
            "aaaa1111aaaa1111aaaa1111aaaa1111aaaa1111aaaa1111aaaa1111aaaa1111",
            UpdateInstaller.checksumFor(sums, "OpenDisk-0.2.5.msi"),
        )
    }

    @Test
    fun `binary marker before the name is not part of it`() {
        // sha256sum помечает двоичные файлы звёздочкой перед именем.
        val sums = "cccc3333cccc3333cccc3333cccc3333cccc3333cccc3333cccc3333cccc3333 *OpenDisk-0.2.5.msi"

        assertEquals(
            "cccc3333cccc3333cccc3333cccc3333cccc3333cccc3333cccc3333cccc3333",
            UpdateInstaller.checksumFor(sums, "OpenDisk-0.2.5.msi"),
        )
    }

    @Test
    fun `name is matched whole, not by substring`() {
        val sums = "dddd4444dddd4444dddd4444dddd4444dddd4444dddd4444dddd4444dddd4444  x-OpenDisk-0.2.5.msi"

        // Иначе сумма чужого файла сошла бы за нашу.
        assertNull(UpdateInstaller.checksumFor(sums, "OpenDisk-0.2.5.msi"))
    }

    @Test
    fun `a line without a proper hash is not a checksum`() {
        assertNull(UpdateInstaller.checksumFor("коротко  OpenDisk-0.2.5.msi", "OpenDisk-0.2.5.msi"))
        assertNull(UpdateInstaller.checksumFor("", "OpenDisk-0.2.5.msi"))
    }

    @Test
    fun `hash of a file is computed the same way as sha256sum`() {
        val file = File(createTempDirectory("sha").toFile(), "data.bin")
        file.writeText("opendisk")

        // Значение получено `sha256sum` на этой же строке без перевода строки.
        assertEquals(64, UpdateInstaller.sha256(file).length)
        assertEquals(UpdateInstaller.sha256(file), UpdateInstaller.sha256(file))
    }

    @Test
    fun `installer path is quoted for the shell`() {
        val script = UpdateInstaller.installScript("""C:\Users\Кто-то\AppData\Local\Temp\OpenDisk-0.2.5.msi""")

        // Путь с пробелами и кириллицей ломался бы без кавычек — на этом уже
        // спотыкались установка WinFsp и автозапуск.
        assertTrue(script.contains("'\"C:\\Users\\Кто-то\\AppData\\Local\\Temp\\OpenDisk-0.2.5.msi\"'"))
        assertTrue(script.contains("-Verb RunAs"), "установка в Program Files требует прав администратора")
    }

    @Test
    fun `single quotes in the path are escaped for powershell`() {
        val script = UpdateInstaller.installScript("""C:\it's here\OpenDisk.msi""")

        assertTrue(script.contains("it''s here"), "одинарная кавычка оборвала бы строку скрипта")
    }
}
