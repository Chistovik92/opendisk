package com.opendisk.bridge

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Проверяет перевод местного пути в путь внутри облака.
 *
 * Ошибка здесь не падает, а тихо выдаёт ссылку не на тот файл — либо не выдаёт
 * вовсе, — поэтому случаев много и они мелкие. Живой диск не нужен: вся работа
 * со строками.
 */
class MountedPathsTest {

    @Test
    fun `windows drive letter maps to path inside the cloud`() {
        val resolved = MountedPaths.resolve(
            localPath = """Z:\Отчёты\март.pdf""",
            mounts = mapOf("яндекс" to "Z:"),
            caseInsensitive = true,
        )

        assertEquals(RemoteFile("яндекс", "Отчёты/март.pdf"), resolved)
    }

    @Test
    fun `trailing separator in the mount point changes nothing`() {
        // Точка монтирования приходит и как «Z:», и как «Z:\» — в настройках её
        // вводит человек, и запрещать ему второй вариант незачем.
        val resolved = MountedPaths.resolve(
            localPath = """Z:\файл.txt""",
            mounts = mapOf("диск" to """Z:\"""),
            caseInsensitive = true,
        )

        assertEquals(RemoteFile("диск", "файл.txt"), resolved)
    }

    @Test
    fun `drive letter case does not matter on windows`() {
        // Проводник отдаёт «Z:\», а из адресной строки легко приезжает «z:\».
        val resolved = MountedPaths.resolve(
            localPath = """z:\файл.txt""",
            mounts = mapOf("диск" to "Z:"),
            caseInsensitive = true,
        )

        assertEquals(RemoteFile("диск", "файл.txt"), resolved)
    }

    @Test
    fun `case matters where the file system says it does`() {
        // На Linux «/home/u/OpenDisk» и «/home/u/opendisk» — разные каталоги,
        // и делать вид, что это одно и то же, нельзя.
        val resolved = MountedPaths.resolve(
            localPath = "/home/u/opendisk/яндекс/файл.txt",
            mounts = mapOf("яндекс" to "/home/u/OpenDisk/яндекс"),
            caseInsensitive = false,
        )

        assertNull(resolved)
    }

    @Test
    fun `linux mount point maps to path inside the cloud`() {
        val resolved = MountedPaths.resolve(
            localPath = "/home/u/OpenDisk/яндекс/Документы/смета.xlsx",
            mounts = mapOf("яндекс" to "/home/u/OpenDisk/яндекс"),
            caseInsensitive = false,
        )

        assertEquals(RemoteFile("яндекс", "Документы/смета.xlsx"), resolved)
    }

    @Test
    fun `neighbour with the same prefix is not mistaken for the mount point`() {
        // Ровно та ошибка, ради которой сравнение идёт по границе сегмента:
        // строка начинается так же, а каталог совершенно другой.
        val resolved = MountedPaths.resolve(
            localPath = "/home/u/OpenDisk/яндекс-архив/файл.txt",
            mounts = mapOf("яндекс" to "/home/u/OpenDisk/яндекс"),
            caseInsensitive = false,
        )

        assertNull(resolved)
    }

    @Test
    fun `the innermost mount point wins`() {
        // Один диск подключён внутрь другого. Файл держит вложенный, и ссылку
        // должен выдавать именно он — у внешнего этого файла попросту нет.
        val resolved = MountedPaths.resolve(
            localPath = "/home/u/OpenDisk/архив/2025/акт.pdf",
            mounts = mapOf(
                "общий" to "/home/u/OpenDisk",
                "архив" to "/home/u/OpenDisk/архив",
            ),
            caseInsensitive = false,
        )

        assertEquals(RemoteFile("архив", "2025/акт.pdf"), resolved)
    }

    @Test
    fun `file on another mounted drive belongs to that drive`() {
        // Главное свойство: ссылку выдаёт то облако, на диске которого лежит
        // файл, а не то, с чьей кнопки начали. Человек мог уйти в файловом
        // диалоге на соседний подключённый диск.
        val resolved = MountedPaths.resolve(
            localPath = """Y:\общее\договор.docx""",
            mounts = mapOf("яндекс" to "Z:", "гугл" to "Y:"),
            caseInsensitive = true,
        )

        assertEquals(RemoteFile("гугл", "общее/договор.docx"), resolved)
    }

    @Test
    fun `file outside every mounted drive resolves to nothing`() {
        val resolved = MountedPaths.resolve(
            localPath = """C:\Users\u\Desktop\файл.txt""",
            mounts = mapOf("яндекс" to "Z:"),
            caseInsensitive = true,
        )

        assertNull(resolved)
    }

    @Test
    fun `mount point itself resolves to the root of the cloud`() {
        val resolved = MountedPaths.resolve(
            localPath = """Z:\""",
            mounts = mapOf("яндекс" to "Z:"),
            caseInsensitive = true,
        )

        assertEquals(RemoteFile("яндекс", ""), resolved)
    }

    @Test
    fun `dot segments are resolved before matching`() {
        // «Z:\папка\..\файл» указывает на файл в корне диска, и путь внутри
        // облака должен получиться именно такой — иначе ссылка уедет не туда.
        val resolved = MountedPaths.resolve(
            localPath = """Z:\папка\..\файл.txt""",
            mounts = mapOf("диск" to "Z:"),
            caseInsensitive = true,
        )

        assertEquals(RemoteFile("диск", "файл.txt"), resolved)
    }

    @Test
    fun `nothing is mounted so nothing resolves`() {
        assertNull(MountedPaths.resolve("""Z:\файл.txt""", emptyMap(), caseInsensitive = true))
    }

    @Test
    fun `normalize collapses separators and keeps the root`() {
        assertEquals("Z:", MountedPaths.normalize("""Z:\"""))
        assertEquals("/", MountedPaths.normalize("/"))
        assertEquals("/home/u", MountedPaths.normalize("/home//u/"))
        assertEquals("//сервер/ресурс", MountedPaths.normalize("""\\сервер\ресурс"""))
        assertEquals("", MountedPaths.normalize("   "))
    }

    @Test
    fun `case sensitivity follows the operating system`() {
        assertTrue(MountedPaths.defaultCaseInsensitive("Windows 11"))
        assertTrue(MountedPaths.defaultCaseInsensitive("Mac OS X"))
        assertTrue(!MountedPaths.defaultCaseInsensitive("Linux"))
    }
}
