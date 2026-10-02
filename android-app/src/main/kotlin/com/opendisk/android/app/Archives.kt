package com.opendisk.android.app

import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Zip-архивы: распаковать и сжать — как в ES File Explorer.
 *
 * Только файлы на самом телефоне. Архив в облаке сначала скачивается в память
 * телефона, а потом распаковывается — тащить архив через сеть ради одной
 * распаковки по частям rclone не умеет.
 */
object Archives {

    class ArchiveException(message: String) : IOException(message)

    /**
     * Распаковывает [zip] в новую папку [into] и возвращает число файлов.
     *
     * Два условия безопасности. Имя записи в архиве — чужая строка: «../../x»
     * вывело бы запись за пределы папки назначения и затёрло бы любой файл,
     * до которого дотянется приложение (так устроена уязвимость zip-slip),
     * поэтому каждый путь сверяется с папкой назначения. И места должно
     * хватить на всё распакованное: архив в килобайты может раскрыться в
     * гигабайты, и полупереполненная память хуже отказа.
     */
    fun extractZip(zip: File, into: File): Int {
        val root = into.canonicalFile
        ZipFile(zip).use { archive ->
            val entries = archive.entries().toList()
            val total = entries.filter { !it.isDirectory }.sumOf { it.size.coerceAtLeast(0) }
            root.mkdirs()
            if (total > root.usableSpace) throw ArchiveException("not enough space: need $total bytes")

            var files = 0
            for (entry in entries) {
                val target = File(root, entry.name).canonicalFile
                if (target.path != root.path && !target.path.startsWith(root.path + File.separator)) {
                    throw ArchiveException("unsafe path in archive: ${entry.name}")
                }
                if (entry.isDirectory) {
                    target.mkdirs()
                    continue
                }
                target.parentFile?.mkdirs()
                archive.getInputStream(entry).use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
                // Сохраняем время изменения из архива — по нему сортируют.
                if (entry.time > 0) target.setLastModified(entry.time)
                files++
            }
            return files
        }
    }

    /**
     * Складывает [sources] (файлы и папки целиком) в [out].
     *
     * Если что-то пошло не так посередине, недописанный архив удаляется:
     * обрезанный zip выглядит настоящим и ломается только при распаковке.
     */
    fun createZip(sources: List<File>, out: File) {
        try {
            ZipOutputStream(out.outputStream().buffered()).use { zip ->
                sources.forEach { source -> add(zip, source, source.name) }
            }
        } catch (e: Exception) {
            out.delete()
            throw e
        }
    }

    private fun add(zip: ZipOutputStream, file: File, name: String) {
        if (file.isDirectory) {
            zip.putNextEntry(ZipEntry("$name/").apply { time = file.lastModified() })
            zip.closeEntry()
            file.listFiles()?.sortedBy { it.name }?.forEach { add(zip, it, "$name/${it.name}") }
        } else {
            zip.putNextEntry(ZipEntry(name).apply { time = file.lastModified() })
            file.inputStream().use { it.copyTo(zip) }
            zip.closeEntry()
        }
    }

    /** Имя папки для распаковки: «отчёт.zip» → «отчёт». */
    fun folderNameFor(zipName: String): String = zipName.substringBeforeLast('.', zipName).ifEmpty { zipName }

    fun isZip(name: String): Boolean = name.lowercase().endsWith(".zip")
}
