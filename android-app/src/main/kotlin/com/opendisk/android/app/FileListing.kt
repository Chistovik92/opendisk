package com.opendisk.android.app

import com.opendisk.bridge.RcloneClient
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Как показать содержимое папки: тип файла, сортировка, фильтры, путь.
 *
 * Всё здесь — чистые функции без Android, чтобы проверяться обычными
 * тестами. Экран только рисует то, что они вернули.
 */

/** Что за файл — от этого зависят значок, цвет и то, чем его открывать. */
enum class FileKind(val glyph: String) {
    FOLDER("📁"),
    IMAGE("🖼"),
    VIDEO("🎞"),
    AUDIO("🎵"),
    DOCUMENT("📄"),
    ARCHIVE("🗜"),
    APK("🤖"),
    OTHER("📎"),
    ;

    companion object {
        private val images = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "avif", "svg", "tif", "tiff", "dng")
        private val videos = setOf("mp4", "mkv", "avi", "mov", "webm", "3gp", "m4v", "ts", "flv", "wmv", "mpg", "mpeg")
        private val audios = setOf("mp3", "flac", "wav", "ogg", "m4a", "aac", "opus", "wma", "amr", "mid", "midi")
        private val documents = setOf(
            "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "ods", "odp", "txt", "rtf", "md", "csv",
            "epub", "fb2", "djvu", "html", "htm", "xml", "json", "log",
        )
        private val archives = setOf("zip", "rar", "7z", "tar", "gz", "bz2", "xz", "tgz", "iso")

        fun of(entry: RcloneClient.Entry): FileKind = if (entry.isDir) FOLDER else byName(entry.name)

        fun byName(name: String): FileKind = when (extension(name)) {
            in images -> IMAGE
            in videos -> VIDEO
            in audios -> AUDIO
            in documents -> DOCUMENT
            in archives -> ARCHIVE
            "apk", "apks", "xapk" -> APK
            else -> OTHER
        }
    }
}

/** Расширение в нижнем регистре без точки; у «.bashrc» и «README» его нет. */
fun extension(name: String): String {
    val dot = name.lastIndexOf('.')
    return if (dot <= 0 || dot == name.lastIndex) "" else name.substring(dot + 1).lowercase(Locale.ROOT)
}

/** Порядок в папке. Папки всегда впереди — как в любом файловом менеджере. */
enum class SortOrder { NAME, SIZE, DATE, TYPE }

/**
 * Вид списка — три, как в ES File Explorer: короткий список (одно имя в строке,
 * помещается больше), подробный (размер, дата, у папок число элементов) и плитки.
 */
enum class ViewMode {
    LIST,
    DETAILED,
    GRID,
    ;

    /** Следующий вид по кругу — кнопка в панели переключает именно так. */
    fun next(): ViewMode = entries[(ordinal + 1) % entries.size]
}

/** Как человек настроил папку: сортировка, скрытые, вид. Хранится в настройках. */
data class ListingOptions(
    val sort: SortOrder = SortOrder.NAME,
    val descending: Boolean = false,
    val showHidden: Boolean = false,
    val view: ViewMode = ViewMode.DETAILED,
)

/**
 * То, что реально показывается: фильтр по скрытым и поиску, затем сортировка.
 *
 * Поиск идёт по имени внутри открытой папки, без рекурсии: обход облака
 * целиком — это десятки тысяч запросов (см. [RcloneClient.listFs]).
 */
fun arrange(entries: List<RcloneClient.Entry>, options: ListingOptions, query: String = ""): List<RcloneClient.Entry> {
    val needle = query.trim().lowercase()
    val visible = entries.filter { entry ->
        (options.showHidden || !entry.name.startsWith(".")) &&
            (needle.isEmpty() || entry.name.lowercase().contains(needle))
    }
    val byName = compareBy<RcloneClient.Entry> { it.name.lowercase() }
    val primary: Comparator<RcloneClient.Entry> = when (options.sort) {
        SortOrder.NAME -> byName
        // Размер у папки неизвестен (−1) — они и так идут отдельно, впереди.
        SortOrder.SIZE -> compareBy<RcloneClient.Entry> { it.size }.then(byName)
        SortOrder.DATE -> compareBy<RcloneClient.Entry> { it.modTime }.then(byName)
        SortOrder.TYPE -> compareBy<RcloneClient.Entry> { extension(it.name) }.then(byName)
    }
    val ordered = if (options.descending) primary.reversed() else primary
    // Папки впереди при любом порядке и любом направлении.
    return visible.sortedWith(compareByDescending<RcloneClient.Entry> { it.isDir }.then(ordered))
}

/** Одна ступень пути: что показать и куда перейти по нажатию. */
data class Crumb(val label: String, val path: String)

/**
 * Путь по ступеням: «Телефон › DCIM › Camera». Нажатие на ступень ведёт
 * в эту папку — в глубине облака иначе приходится нажимать «назад»
 * столько раз, сколько в пути папок.
 */
fun breadcrumbs(diskTitle: String, path: String): List<Crumb> {
    val crumbs = mutableListOf(Crumb(diskTitle, ""))
    var current = ""
    path.split('/').filter { it.isNotEmpty() }.forEach { part ->
        current = childPath(current, part)
        crumbs += Crumb(part, current)
    }
    return crumbs
}

/** Дата изменения для строки списка; пусто, если облако её не сказало. */
fun formatModTime(modTime: String, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String {
    if (modTime.isEmpty()) return ""
    return runCatching {
        OffsetDateTime.parse(modTime).atZoneSameInstant(zone)
            .format(DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", locale))
    }.getOrDefault("")
}


/**
 * Пути от [anchor] до [target] включительно в порядке списка.
 * Нет опорной точки или её уже нет в списке — только сам [target].
 */
fun rangeBetween(visible: List<RcloneClient.Entry>, anchor: String?, target: String): Set<String> {
    val to = visible.indexOfFirst { it.path == target }
    val from = visible.indexOfFirst { it.path == anchor }
    if (to < 0) return emptySet()
    if (from < 0) return setOf(target)
    return visible.subList(minOf(from, to), maxOf(from, to) + 1).map { it.path }.toSet()
}
