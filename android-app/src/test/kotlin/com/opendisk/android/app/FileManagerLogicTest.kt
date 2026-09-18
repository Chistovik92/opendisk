package com.opendisk.android.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Чистая логика встроенного файлового менеджера — то, что не требует телефона. */
class FileManagerLogicTest {

    @Test
    fun `copy next to the original keeps the extension at the end`() {
        assertEquals("отчёт (2).pdf", copyName("отчёт.pdf", setOf("отчёт.pdf")))
        assertEquals("отчёт (3).pdf", copyName("отчёт.pdf", setOf("отчёт.pdf", "отчёт (2).pdf")))
        assertEquals("Фото (2)", copyName("Фото", setOf("Фото")))
        // Скрытый файл: точка в начале — не расширение.
        assertEquals(".bashrc (2)", copyName(".bashrc", setOf(".bashrc")))
    }

    @Test
    fun `paths inside disks are joined without doubled slashes`() {
        assertEquals("Docs/a.pdf", childPath("Docs", "a.pdf"))
        assertEquals("a.pdf", childPath("", "a.pdf"))
        assertEquals("/storage/emulated/0/DCIM", joinPath("/storage/emulated/0", "DCIM"))
        assertEquals("/data", joinPath("/", "data"))
        assertEquals("/", joinPath("/", ""))
    }

    @Test
    fun `clouds have no device path and root has no rclone file system`() {
        assertNull(Disk.Cloud("yandex").absolutePath("Docs"))
        assertEquals("yandex:", Disk.Cloud("yandex").fs)
        assertNull(Disk.Root.fs)
        assertEquals("/data/local", Disk.Root.absolutePath("data/local"))
        assertEquals("/sdcard/Download", Disk.Local("/sdcard", removable = false).absolutePath("Download"))
    }

    @Test
    fun `stat lines become entries and names may contain the separator`() {
        val dir = RootShell.parseStat("directory|4096|1700000000|app", "data")!!
        assertTrue(dir.isDir)
        assertEquals("data/app", dir.path)
        assertEquals(-1, dir.size)

        val file = RootShell.parseStat("regular file|1234|1700000000|a|b.txt", "")!!
        assertEquals("a|b.txt", file.name)
        assertEquals("a|b.txt", file.path)
        assertEquals(1234, file.size)

        assertNull(RootShell.parseStat("garbage", ""))
    }

    @Test
    fun `shell quoting survives single quotes in names`() {
        assertEquals("'it'\\''s here'", RootShell.quote("it's here"))
        assertEquals("'\$HOME; rm -rf /'", RootShell.quote("\$HOME; rm -rf /"))
    }
}
