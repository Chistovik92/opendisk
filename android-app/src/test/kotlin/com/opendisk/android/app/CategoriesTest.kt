package com.opendisk.android.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Категории главного экрана и закладки.
 *
 * Сам запрос к MediaStore проверяется на эмуляторе; здесь — то, из чего он
 * собран: условия выборки, перевод абсолютного пути в путь на диске и
 * хранение закладок.
 */
class CategoriesTest {

    private val root = "/storage/emulated/0"

    @Test
    fun `media categories select by media type`() {
        assertEquals(CategoryQuery("media_type = ?", listOf("1")), categoryQuery(FileCategory.IMAGES, root, 0))
        assertEquals(listOf("2"), categoryQuery(FileCategory.AUDIO, root, 0).args)
        assertEquals(listOf("3"), categoryQuery(FileCategory.VIDEO, root, 0).args)
    }

    @Test
    fun `documents are chosen by extension, case-insensitively`() {
        val query = categoryQuery(FileCategory.DOCUMENTS, root, 0)
        assertEquals(DOCUMENT_EXTENSIONS.size, query.args.size)
        assertEquals(DOCUMENT_EXTENSIONS.size, query.selection.split("LOWER(_data) LIKE ?").size - 1)
        assertTrue("%.pdf" in query.args)
        assertTrue(query.selection.startsWith("(") && query.selection.endsWith(")"))
    }

    @Test
    fun `downloads are only the primary Download folder`() {
        assertEquals(listOf("$root/Download/%"), categoryQuery(FileCategory.DOWNLOADS, "$root/", 0).args)
    }

    @Test
    fun `new files are those added within a week`() {
        val now = 1_000_000L
        val query = categoryQuery(FileCategory.NEW, root, now)
        assertEquals(listOf((now - 7 * 86_400).toString()), query.args)
    }

    @Test
    fun `paths are made relative to the disk root and strangers are dropped`() {
        assertEquals("DCIM/Camera/a.jpg", relativeTo(root, "$root/DCIM/Camera/a.jpg"))
        assertEquals("a.jpg", relativeTo("$root/", "$root/a.jpg"))
        // Карта памяти и чужие каталоги — не наш диск.
        assertNull(relativeTo(root, "/storage/1234-ABCD/DCIM/a.jpg"))
        // Каталог с похожим началом имени не должен совпасть.
        assertNull(relativeTo(root, "/storage/emulated/01/a.jpg"))
        assertNull(relativeTo(root, root))
    }

    @Test
    fun `bookmarks survive being saved and loaded, in order`() {
        val list = listOf(
            Bookmark(Disk.Local(root, removable = false), "DCIM/Camera"),
            Bookmark(Disk.Cloud("gdrive"), ""),
            Bookmark(Disk.Local("/storage/1234-ABCD", removable = true), "Музыка"),
            Bookmark(Disk.Root, "etc"),
        )
        assertEquals(list, Bookmarks.decode(Bookmarks.encode(list)))
    }

    @Test
    fun `broken or empty bookmark data gives an empty list, not a crash`() {
        assertEquals(emptyList(), Bookmarks.decode(null))
        assertEquals(emptyList(), Bookmarks.decode(""))
        assertEquals(emptyList(), Bookmarks.decode("мусор\u0002cloud\u0001"))
    }

    @Test
    fun `the same folder is not bookmarked twice`() {
        val one = Bookmark(Disk.Cloud("x"), "a")
        assertEquals(1, Bookmarks.decode(Bookmarks.encode(listOf(one, one))).size)
    }
}
