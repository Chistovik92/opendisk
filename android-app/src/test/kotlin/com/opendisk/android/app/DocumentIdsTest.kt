package com.opendisk.android.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Идентификаторы документов и режимы открытия — то, что проверяется без телефона. */
class DocumentIdsTest {

    @Test
    fun `ids keep the format the system has already stored`() {
        assertEquals("яндекс/", DocumentIds.of("яндекс", ""))
        assertEquals("яндекс/Фото/a.jpg", DocumentIds.of("яндекс", "Фото/a.jpg"))
        assertEquals("яндекс" to "Фото/a.jpg", DocumentIds.split("яндекс/Фото/a.jpg"))
        assertEquals("яндекс" to "", DocumentIds.split("яндекс/"))
    }

    @Test
    fun `parent and child walk the tree`() {
        assertEquals("g/Docs", DocumentIds.parent("g/Docs/a.pdf"))
        assertEquals("g/", DocumentIds.parent("g/a.pdf"))
        assertNull(DocumentIds.parent("g/"))
        assertEquals("g/a.pdf", DocumentIds.child("g/", "a.pdf"))
        assertEquals("g/Docs/a.pdf", DocumentIds.child("g/Docs", "a.pdf"))
    }

    @Test
    fun `a sibling with a common prefix is not a child`() {
        assertTrue(DocumentIds.isChild("g/Docs", "g/Docs/a.pdf"))
        assertTrue(DocumentIds.isChild("g/", "g/Docs/a.pdf"))
        assertFalse(DocumentIds.isChild("g/Docs", "g/Docs2/a.pdf"))
        // Облако с похожим именем — другое облако.
        assertFalse(DocumentIds.isChild("g/", "gg/a.pdf"))
    }

    @Test
    fun `local copies of different files never collide`() {
        assertNotEquals(DocumentIds.localName("g/a b.txt"), DocumentIds.localName("g/a_b.txt"))
        assertNotEquals(DocumentIds.version(10, "2024-01-01T00:00:00Z"), DocumentIds.version(11, "2024-01-01T00:00:00Z"))
    }

    @Test
    fun `open modes follow the ContentResolver contract`() {
        assertEquals(OpenMode(write = false, truncate = false), OpenMode.parse("r"))
        assertEquals(OpenMode(write = true, truncate = true), OpenMode.parse("w"))
        assertEquals(OpenMode(write = true, truncate = true), OpenMode.parse("wt"))
        assertEquals(OpenMode(write = true, truncate = true), OpenMode.parse("rwt"))
        assertEquals(OpenMode(write = true, truncate = false), OpenMode.parse("wa"))
        assertEquals(OpenMode(write = true, truncate = false), OpenMode.parse("rw"))
        assertTrue(OpenMode.parse("rw").needsExistingContent)
        assertFalse(OpenMode.parse("wt").needsExistingContent)
    }
}
