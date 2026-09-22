package com.opendisk.android.app

/**
 * Идентификаторы документов поставщика и всё, что из них выводится.
 *
 * Идентификатор — `облако/путь/внутри`, корень облака — `облако/`. Система
 * хранит их у себя (выданные приложениям права живут на идентификаторе),
 * поэтому формат менять нельзя: выданный доступ к файлу молча пропал бы.
 *
 * Отдельно от поставщика — чтобы проверять без телефона.
 */
object DocumentIds {

    fun of(cloud: String, path: String): String =
        if (path.isEmpty()) "$cloud/" else "$cloud/${path.trim('/')}"

    /** Облако и путь внутри него; у корня путь пустой. */
    fun split(documentId: String): Pair<String, String> =
        documentId.substringBefore('/') to documentId.substringAfter('/', "").trim('/')

    /** Родительская папка; у корня облака родителя нет. */
    fun parent(documentId: String): String? {
        val (cloud, path) = split(documentId)
        if (path.isEmpty()) return null
        return of(cloud, path.substringBeforeLast('/', ""))
    }

    fun child(parentId: String, name: String): String {
        val (cloud, path) = split(parentId)
        return of(cloud, childPath(path, name))
    }

    fun isChild(parentId: String, documentId: String): Boolean {
        val (parentCloud, parentPath) = split(parentId)
        val (cloud, path) = split(documentId)
        if (cloud != parentCloud) return false
        return parentPath.isEmpty() || path == parentPath || path.startsWith("$parentPath/")
    }

    /**
     * Имя локальной копии. Путь внутри облака в имени обязателен: одинаковые
     * имена из разных папок иначе затирали бы друг друга.
     */
    fun localName(documentId: String): String =
        documentId.replace(UNSAFE, "_") + "-" + Integer.toHexString(documentId.hashCode())

    /**
     * Метка версии файла в облаке для локальной копии. Сменился размер или
     * время изменения — копия устарела и скачивается заново. Раньше скачанный
     * однажды файл отдавался вечно, даже когда в облаке его уже заменили.
     */
    fun version(size: Long, modTime: String): String =
        Integer.toHexString("$size|$modTime".hashCode())

    private val UNSAFE = Regex("[^A-Za-z0-9._-]")
}

/**
 * Что просит режим открытия файла, в тех буквах, какими его передаёт система:
 * `r`, `w`, `wt`, `wa`, `rw`, `rwt`.
 */
data class OpenMode(val write: Boolean, val truncate: Boolean) {
    /** Нужно ли сначала скачать то, что уже лежит в облаке. */
    val needsExistingContent: Boolean get() = !write || !truncate

    companion object {
        fun parse(mode: String): OpenMode {
            val write = 'w' in mode || 'a' in mode
            // Просто «w» по договору ContentResolver тоже обрезает файл —
            // ровно так его понимает ParcelFileDescriptor.parseMode.
            val truncate = 't' in mode || (mode == "w")
            return OpenMode(write = write, truncate = write && truncate)
        }
    }
}
