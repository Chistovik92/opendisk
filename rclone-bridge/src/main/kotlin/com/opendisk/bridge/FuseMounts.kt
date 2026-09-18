package com.opendisk.bridge

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Точки монтирования FUSE на Linux и уборка тех, что остались без хозяина.
 *
 * На Windows диск WinFsp исчезает вместе с процессом rclone. На Linux — нет:
 * если rclone упал или его убили, точка остаётся в таблице монтирования, а
 * любое обращение к ней отвечает «Transport endpoint is not connected».
 * Новый rclone смонтировать туда не может, файловый менеджер на ней
 * подвисает, и до 0.5.6 выход был один — `fusermount -u` руками.
 *
 * Здесь это чинится само: перед подключением и после перезапуска rclone
 * висящая точка снимается «лениво» (`-z`) — так снимается даже та, которую
 * кто-то держит открытой.
 */
object FuseMounts {

    /** Одна строка таблицы монтирования. */
    data class Mount(val source: String, val point: String, val type: String) {
        /** Смонтировано rclone: тип у таких точек `fuse.rclone`. */
        val isRclone: Boolean get() = type == "fuse.rclone"
    }

    /**
     * Разбирает `/proc/mounts`.
     *
     * Пробелы и прочие особые символы ядро записывает восьмеричными кодами
     * (`\040`), а облако с пробелом в имени — обычное дело: «Мой диск».
     */
    fun parse(text: String): List<Mount> = text.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .mapNotNull { line ->
            val fields = line.split(' ')
            if (fields.size < 3) return@mapNotNull null
            Mount(unescape(fields[0]), unescape(fields[1]), fields[2])
        }
        .toList()

    internal fun unescape(field: String): String =
        OCTAL_ESCAPE.replace(field) { match -> match.groupValues[1].toInt(8).toChar().toString() }

    /** Что смонтировано в этой папке сейчас; null — ничего. */
    fun mountedAt(point: String, table: List<Mount> = read()): Mount? {
        val normalized = normalize(point)
        // Последняя запись главная: смонтированное поверх закрывает то, что ниже.
        return table.lastOrNull { normalize(it.point) == normalized }
    }

    /** Точка есть в таблице, но не отвечает: хозяин умер. */
    fun isStale(point: String): Boolean {
        if (mountedAt(point) == null) return false
        // list() возвращает null при любой ошибке ввода-вывода — в том
        // числе на «Transport endpoint is not connected».
        return File(point).list() == null
    }

    /**
     * Снимает точку, оставшуюся от rclone без хозяина.
     *
     * Чужое не трогаем: только точки типа `fuse.rclone`. Живую точку снимаем,
     * только если [evenIfAlive] — это нужно, когда известно, что rclone,
     * который её держал, уже погашен (например, после уборки после сбоя).
     *
     * @return true, если точки после этого нет.
     */
    fun releaseLeftover(point: String, evenIfAlive: Boolean = false): Boolean {
        val mount = mountedAt(point) ?: return true
        if (!mount.isRclone) return false
        if (!evenIfAlive && !isStale(point)) return false
        return unmount(point)
    }

    /**
     * Лениво снимает точку: сначала `fusermount3`, потом `fusermount` старой
     * версии — на разных системах есть то одно, то другое. `umount -l` без
     * прав администратора FUSE-точку не снимет, но на системах, где приложение
     * запущено от root, это последний вариант.
     */
    fun unmount(point: String): Boolean {
        val attempts = listOf(
            listOf("fusermount3", "-uz", point),
            listOf("fusermount", "-uz", point),
            listOf("umount", "-l", point),
        )
        for (command in attempts) {
            runQuietly(command)
            if (mountedAt(point) == null) return true
        }
        return false
    }

    /** Папка есть и в ней что-то лежит: rclone на Linux в такую не монтирует. */
    fun isNonEmptyDirectory(point: String): Boolean {
        val dir = File(point)
        if (!dir.isDirectory) return false
        return dir.list()?.isNotEmpty() == true
    }

    fun read(): List<Mount> = runCatching { parse(File(PROC_MOUNTS).readText()) }.getOrDefault(emptyList())

    private fun normalize(path: String): String = path.trimEnd('/').ifEmpty { "/" }

    private fun runQuietly(command: List<String>) {
        runCatching {
            val process = ProcessBuilder(command).redirectErrorStream(true).start()
            // Вывод не нужен, но непрочитанный буфер может остановить процесс.
            process.inputStream.readBytes()
            if (!process.waitFor(UNMOUNT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) process.destroyForcibly()
        }
    }

    private const val PROC_MOUNTS = "/proc/mounts"
    private const val UNMOUNT_TIMEOUT_SECONDS = 10L
    private val OCTAL_ESCAPE = Regex("""\\([0-7]{3})""")
}
