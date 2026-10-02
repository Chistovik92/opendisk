package com.opendisk.android.app

import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Архивы: туда и обратно, и главное — чужой архив не должен писать за
 * пределы папки назначения.
 */
class ArchivesTest {

    private fun tempDir(): File = Files.createTempDirectory("arch").toFile().apply { deleteOnExit() }

    @Test
    fun `files and folders survive a round trip`() {
        val work = tempDir()
        val src = File(work, "проект").apply { mkdirs() }
        File(src, "a.txt").writeText("первый")
        File(src, "sub/deep").mkdirs()
        File(src, "sub/deep/b.txt").writeText("второй")
        File(src, "empty").mkdirs()

        val zip = File(work, "проект.zip")
        Archives.createZip(listOf(src), zip)
        val out = File(work, "распаковано")
        val count = Archives.extractZip(zip, out)

        assertEquals(2, count)
        assertEquals("первый", File(out, "проект/a.txt").readText())
        assertEquals("второй", File(out, "проект/sub/deep/b.txt").readText())
        assertTrue(File(out, "проект/empty").isDirectory, "пустая папка не должна пропасть")
    }

    @Test
    fun `an entry leading outside the folder is refused and nothing escapes`() {
        val work = tempDir()
        val zip = File(work, "evil.zip")
        ZipOutputStream(zip.outputStream()).use {
            it.putNextEntry(ZipEntry("ok.txt")); it.write(1); it.closeEntry()
            it.putNextEntry(ZipEntry("../escaped.txt")); it.write(2); it.closeEntry()
        }
        val out = File(work, "out")

        assertFailsWith<Archives.ArchiveException> { Archives.extractZip(zip, out) }
        assertFalse(File(work, "escaped.txt").exists(), "файл вышел за пределы папки назначения")
    }

    @Test
    fun `absolute paths in an archive do not escape either`() {
        val work = tempDir()
        val victim = File(work, "victim.txt")
        val zip = File(work, "abs.zip")
        ZipOutputStream(zip.outputStream()).use {
            it.putNextEntry(ZipEntry(victim.absolutePath.removePrefix("/").replace(File.separatorChar, '/'))); it.write(3); it.closeEntry()
        }
        val out = File(work, "out")
        // Путь станет вложенным в папку назначения — но не настоящим абсолютным.
        runCatching { Archives.extractZip(zip, out) }
        assertFalse(victim.exists())
    }

    @Test
    fun `broken archive leaves no half-written zip behind`() {
        val work = tempDir()
        val missing = File(work, "нет такого")
        val zip = File(work, "a.zip")

        assertFailsWith<Exception> { Archives.createZip(listOf(missing), zip) }
        assertFalse(zip.exists())
    }

    @Test
    fun `folder name comes from the archive name`() {
        assertEquals("отчёт", Archives.folderNameFor("отчёт.zip"))
        assertEquals("a.b", Archives.folderNameFor("a.b.zip"))
        assertEquals("noext", Archives.folderNameFor("noext"))
        assertTrue(Archives.isZip("ФОТО.ZIP"))
        assertFalse(Archives.isZip("a.rar"))
    }
}
