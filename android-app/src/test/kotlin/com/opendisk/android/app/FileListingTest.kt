package com.opendisk.android.app

import com.opendisk.bridge.RcloneClient.Entry
import java.time.ZoneId
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Что показывает папка: типы, сортировка, скрытые, поиск, путь.
 *
 * Ошибка тут не роняет приложение, а молча прячет или путает файлы — именно
 * поэтому проверяется отдельно от экрана.
 */
class FileListingTest {

    private fun file(name: String, size: Long = 0, time: String = "") = Entry(name, name, size, false, time)
    private fun dir(name: String) = Entry(name, name, -1, true, "")

    @Test
    fun `kind follows the extension, not the case`() {
        assertEquals(FileKind.IMAGE, FileKind.byName("IMG_0001.JPG"))
        assertEquals(FileKind.VIDEO, FileKind.byName("film.mkv"))
        assertEquals(FileKind.AUDIO, FileKind.byName("song.flac"))
        assertEquals(FileKind.DOCUMENT, FileKind.byName("отчёт.pdf"))
        assertEquals(FileKind.ARCHIVE, FileKind.byName("backup.tar.gz"))
        assertEquals(FileKind.APK, FileKind.byName("OpenDisk.apk"))
        assertEquals(FileKind.OTHER, FileKind.byName("README"))
        // «.bashrc» — имя без расширения, а не файл типа «bashrc».
        assertEquals("", extension(".bashrc"))
        assertEquals("", extension("trailing."))
        assertEquals(FileKind.FOLDER, FileKind.of(dir("DCIM")))
    }

    @Test
    fun `folders stay first in every order and direction`() {
        val entries = listOf(file("a.txt", 1), dir("zeta"), file("b.txt", 9), dir("alpha"))
        for (sort in SortOrder.entries) for (desc in listOf(false, true)) {
            val shown = arrange(entries, ListingOptions(sort = sort, descending = desc))
            assertEquals(listOf(true, true, false, false), shown.map { it.isDir }, "$sort desc=$desc")
        }
    }

    @Test
    fun `sorting by size and date and direction`() {
        val entries = listOf(
            file("mid", 5, "2026-01-02T00:00:00Z"),
            file("big", 9, "2026-01-01T00:00:00Z"),
            file("small", 1, "2026-01-03T00:00:00Z"),
        )
        assertEquals(listOf("small", "mid", "big"), arrange(entries, ListingOptions(SortOrder.SIZE)).map { it.name })
        assertEquals(listOf("big", "mid", "small"), arrange(entries, ListingOptions(SortOrder.SIZE, descending = true)).map { it.name })
        assertEquals(listOf("big", "mid", "small"), arrange(entries, ListingOptions(SortOrder.DATE)).map { it.name })
        assertEquals(listOf("big", "mid", "small"), arrange(entries, ListingOptions(SortOrder.NAME)).map { it.name })
    }

    @Test
    fun `name sort ignores case`() {
        val shown = arrange(listOf(file("b"), file("A"), file("c")), ListingOptions())
        assertEquals(listOf("A", "b", "c"), shown.map { it.name })
    }

    @Test
    fun `hidden files are shown only on request`() {
        val entries = listOf(file(".nomedia"), file("photo.jpg"), dir(".thumbnails"))
        assertEquals(listOf("photo.jpg"), arrange(entries, ListingOptions()).map { it.name })
        assertEquals(3, arrange(entries, ListingOptions(showHidden = true)).size)
    }

    @Test
    fun `search matches part of the name in any case`() {
        val entries = listOf(file("Holiday.JPG"), file("notes.txt"), dir("Holidays"))
        assertEquals(listOf("Holidays", "Holiday.JPG"), arrange(entries, ListingOptions(), "holi").map { it.name })
        assertEquals(emptyList(), arrange(entries, ListingOptions(), "нет такого"))
        assertEquals(3, arrange(entries, ListingOptions(), "  ").size)
    }

    @Test
    fun `breadcrumbs lead to every level`() {
        val crumbs = breadcrumbs("Телефон", "DCIM/Camera/2026")
        assertEquals(listOf("Телефон", "DCIM", "Camera", "2026"), crumbs.map { it.label })
        assertEquals(listOf("", "DCIM", "DCIM/Camera", "DCIM/Camera/2026"), crumbs.map { it.path })
        assertEquals(listOf(Crumb("Яндекс", "")), breadcrumbs("Яндекс", ""))
    }

    @Test
    fun `modification time is shown in the local zone, empty when unknown`() {
        val shown = formatModTime("2026-03-05T22:30:00Z", ZoneId.of("Asia/Yekaterinburg"), Locale.ENGLISH)
        assertEquals("6 Mar 2026, 03:30", shown)
        assertEquals("", formatModTime(""))
        assertEquals("", formatModTime("вчера"))
    }
}
