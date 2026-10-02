package com.opendisk.android.app

import android.content.Context
import android.provider.MediaStore
import com.opendisk.bridge.RcloneClient
import java.time.Instant

/**
 * Категории главного экрана — как «Изображения», «Видео» и «Загрузки» в ES
 * File Explorer: файлы одного рода из всех папок памяти телефона в одном
 * списке. Обход всех папок через rclone на телефоне занял бы минуты, а
 * система уже держит готовый список — MediaStore.
 */
enum class FileCategory {
    IMAGES,
    VIDEO,
    AUDIO,
    DOCUMENTS,
    DOWNLOADS,
    NEW,
    ;

    companion object {
        /** Сколько дней файл считается «новым». */
        const val NEW_DAYS = 7
    }
}

/** Расширения документов: у MediaStore для них нет своего типа, только mime. */
internal val DOCUMENT_EXTENSIONS = listOf(
    "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "ods", "odp", "txt", "rtf", "csv", "epub", "fb2", "djvu",
)

/** Условие выборки и его аргументы — отдельно от запроса, чтобы проверяться без Android. */
data class CategoryQuery(val selection: String, val args: List<String>)

fun categoryQuery(category: FileCategory, root: String, nowSeconds: Long): CategoryQuery {
    val prefix = root.trimEnd('/') + "/"
    return when (category) {
        FileCategory.IMAGES -> CategoryQuery("media_type = ?", listOf(MEDIA_IMAGE.toString()))
        FileCategory.VIDEO -> CategoryQuery("media_type = ?", listOf(MEDIA_VIDEO.toString()))
        FileCategory.AUDIO -> CategoryQuery("media_type = ?", listOf(MEDIA_AUDIO.toString()))
        FileCategory.DOCUMENTS -> CategoryQuery(
            DOCUMENT_EXTENSIONS.joinToString(" OR ", prefix = "(", postfix = ")") { "LOWER(_data) LIKE ?" },
            DOCUMENT_EXTENSIONS.map { "%.$it" },
        )
        // Только основная память: «Загрузки» на карте — другой каталог.
        FileCategory.DOWNLOADS -> CategoryQuery("_data LIKE ?", listOf("${prefix}Download/%"))
        // Размер больше нуля отсекает записи каталогов, которые старые версии
        // Android кладут в ту же таблицу.
        FileCategory.NEW -> CategoryQuery(
            "date_added > ? AND _size > 0",
            listOf((nowSeconds - FileCategory.NEW_DAYS * 86_400L).toString()),
        )
    }
}

private const val MEDIA_IMAGE = 1
private const val MEDIA_AUDIO = 2
private const val MEDIA_VIDEO = 3

/**
 * Путь файла внутри диска или null, если файл лежит вне него.
 *
 * MediaStore отдаёт абсолютные пути «/storage/emulated/0/DCIM/a.jpg», а
 * менеджер работает с путями внутри диска — «DCIM/a.jpg».
 */
fun relativeTo(root: String, absolute: String): String? {
    val prefix = root.trimEnd('/') + "/"
    return absolute.takeIf { it.startsWith(prefix) }?.removePrefix(prefix)?.takeIf { it.isNotEmpty() }
}

object Categories {

    /**
     * Файлы категории на основной памяти телефона, новые — первыми.
     *
     * Скрытые файлы и каталоги кэша приложений не показываются: ни «Новых»,
     * ни «Изображений» от чужих `.thumbnails` человеку не нужно.
     */
    fun load(context: Context, category: FileCategory, root: String, nowSeconds: Long = Instant.now().epochSecond): List<RcloneClient.Entry> {
        val query = categoryQuery(category, root, nowSeconds)
        val columns = arrayOf("_data", "_size", "date_modified")
        val result = mutableListOf<RcloneClient.Entry>()
        context.contentResolver.query(
            MediaStore.Files.getContentUri("external"),
            columns,
            query.selection,
            query.args.toTypedArray(),
            "date_modified DESC",
        )?.use { cursor ->
            while (cursor.moveToNext() && result.size < LIMIT) {
                val absolute = cursor.getString(0) ?: continue
                val relative = relativeTo(root, absolute) ?: continue
                if (relative.split('/').any { it.startsWith(".") }) continue
                if (relative.startsWith("Android/")) continue
                val name = relative.substringAfterLast('/')
                result += RcloneClient.Entry(
                    path = relative,
                    name = name,
                    size = cursor.getLong(1),
                    isDir = false,
                    modTime = Instant.ofEpochSecond(cursor.getLong(2)).toString(),
                )
            }
        }
        return result
    }

    /** Предел списка: десятки тысяч строк в одном списке — это и память, и бесполезная прокрутка. */
    private const val LIMIT = 5_000
}

/** Закладка: папка, к которой хочется возвращаться одним нажатием. */
data class Bookmark(val disk: Disk, val path: String) {
    val key: String get() = disk.key + "\u0001" + path
}

/** Закладки хранятся одной строкой в настройках; порядок — как добавил человек. */
object Bookmarks {
    private const val FIELD = '\u0001'
    private const val RECORD = '\u0002'

    fun encode(bookmarks: List<Bookmark>): String = bookmarks.joinToString(RECORD.toString()) { b ->
        when (val disk = b.disk) {
            is Disk.Cloud -> "cloud$FIELD${disk.name}$FIELD${b.path}"
            is Disk.Local -> "local$FIELD${disk.root}$FIELD${disk.removable}$FIELD${b.path}"
            Disk.Root -> "root$FIELD$FIELD${b.path}"
        }
    }

    fun decode(text: String?): List<Bookmark> {
        if (text.isNullOrEmpty()) return emptyList()
        return text.split(RECORD).mapNotNull { record ->
            val f = record.split(FIELD)
            when (f.firstOrNull()) {
                "cloud" -> f.getOrNull(2)?.let { Bookmark(Disk.Cloud(f[1]), it) }
                "local" -> f.getOrNull(3)?.let { Bookmark(Disk.Local(f[1], f[2].toBoolean()), it) }
                "root" -> f.getOrNull(2)?.let { Bookmark(Disk.Root, it) }
                else -> null
            }
        }.distinctBy { it.key }
    }
}
