package com.opendisk.android.app

import android.content.Context
import android.os.Environment
import com.opendisk.bridge.RcloneClient
import java.io.File

/**
 * Диск во встроенном файловом менеджере.
 *
 * С 0.5.6 облака открываются в самом OpenDisk как папки, а не как список,
 * из которого можно только получить ссылку. Рядом с облаками — память
 * телефона, карта памяти и, если телефон с root, вся файловая система:
 * файлы переносятся между ними копированием и вставкой, как в любом
 * файловом менеджере.
 *
 * Облака и память телефона читает один и тот же rclone внутри приложения —
 * у него есть и облачные бэкенды, и обычный локальный. Поэтому копирование
 * из облака в телефон и обратно — один вызов `operations/copyfile`, без
 * своего кода передачи. Только root идёт мимо rclone: читать чужие каталоги
 * процессу приложения не дано, это делает `su`.
 */
sealed interface Disk {
    /** Устойчивый ключ для списков. */
    val key: String

    /** Облако из `rclone.conf`. */
    data class Cloud(val name: String) : Disk {
        override val key get() = "cloud:$name"
    }

    /** Каталог на устройстве, который приложение читает само: память телефона, карта. */
    data class Local(val root: String, val removable: Boolean) : Disk {
        override val key get() = "local:$root"
    }

    /** Вся файловая система через `su`. */
    data object Root : Disk {
        override val key get() = "root"
    }
}

/** Файловая система для rclone; у root её нет — он работает через `su`. */
val Disk.fs: String?
    get() = when (this) {
        is Disk.Cloud -> RcloneClient.cloudFs(name)
        is Disk.Local -> root
        Disk.Root -> null
    }

/**
 * Настоящий путь на устройстве; null — у облака такого нет.
 *
 * Нужен там, где файл уходит не в rclone: открыть в другом приложении,
 * скопировать через `su`.
 */
fun Disk.absolutePath(path: String): String? = when (this) {
    is Disk.Cloud -> null
    is Disk.Local -> joinPath(root, path)
    Disk.Root -> joinPath("/", path)
}

/** Путь внутри диска: папка + имя, без ведущего слэша. */
fun childPath(parent: String, name: String): String =
    if (parent.isEmpty()) name else "$parent/$name"

internal fun joinPath(base: String, path: String): String {
    val inner = path.trim('/')
    if (inner.isEmpty()) return base
    return base.trimEnd('/') + "/" + inner
}

/** Память и карты, которые видит приложение. */
object LocalVolumes {

    /** Основная память телефона — то, что человек называет «память телефона». */
    fun primary(): Disk.Local =
        Disk.Local(Environment.getExternalStorageDirectory().absolutePath, removable = false)

    /**
     * Карты памяти и флешки через OTG.
     *
     * Прямого списка томов у старых API нет, зато у каждого тома есть
     * личная папка приложения `…/Android/data/<пакет>/files` — всё, что
     * выше `Android`, и есть корень тома.
     */
    fun removable(context: Context): List<Disk.Local> {
        val primaryRoot = primary().root
        return context.getExternalFilesDirs(null)
            .filterNotNull()
            .mapNotNull { dir -> dir.absolutePath.substringBefore("/Android/data/", "").ifEmpty { null } }
            .filter { it != primaryRoot && File(it).canRead() }
            .distinct()
            .map { Disk.Local(it, removable = true) }
    }
}

/** Что лежит в буфере файлового менеджера после «Копировать» или «Вырезать». */
data class FileClip(
    val disk: Disk,
    val entry: RcloneClient.Entry,
    /** true — «Вырезать»: после вставки исходник удаляется. */
    val move: Boolean,
)
