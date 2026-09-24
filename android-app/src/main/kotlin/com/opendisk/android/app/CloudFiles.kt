package com.opendisk.android.app

import android.content.Context
import android.provider.DocumentsContract
import android.util.Log
import com.opendisk.bridge.RcloneClient
import java.io.File
import java.io.FileNotFoundException

/**
 * Локальные копии файлов из облака: то, что скачано для чтения, и то, что
 * сохранено и ждёт отправки.
 *
 * Отдельно от поставщика документов, потому что с очередью отправки работает
 * ещё и [UploadWorker] — система запускает его сама, когда появляется сеть,
 * а поставщика в этот момент может и не быть.
 */
class CloudFiles(context: Context) {

    private val app = context.applicationContext
    private val cacheDir = File(app.cacheDir, CACHE_DIR)
    private val uploadsDir = File(app.cacheDir, UPLOADS_DIR)

    // --- Скачанное для чтения -------------------------------------------------

    /**
     * Локальная копия файла той версии, что лежит в облаке, — скачивает, если
     * её ещё нет.
     *
     * Качается во временный файл и переименовывается, только когда размер
     * сошёлся с облачным. Раньше файл писался сразу под итоговым именем: если
     * систему выгружали посреди загрузки, в следующий раз обрезанный файл
     * считался готовым и отдавался как целый — фото открывалось наполовину,
     * документ не открывался вовсе.
     */
    fun cached(
        documentId: String,
        entry: RcloneClient.Entry,
        download: (dir: File, name: String) -> Unit,
    ): File {
        cacheDir.mkdirs()
        val base = DocumentIds.localName(documentId)
        val local = File(cacheDir, base + VERSION_MARK + DocumentIds.version(entry.size, entry.modTime))
        if (local.isFile) {
            // Для очистки по давности: открытое недавно уходит последним.
            local.setLastModified(System.currentTimeMillis())
            return local
        }

        // Прежние версии этого же файла больше не понадобятся.
        versionsOf(base).forEach { it.delete() }
        val partial = File(cacheDir, local.name + PARTIAL)
        partial.delete()
        try {
            download(cacheDir, partial.name)
            if (entry.size >= 0 && partial.length() != entry.size) {
                throw FileNotFoundException(
                    "скачано ${partial.length()} байт из ${entry.size} — файл в облаке меняется или связь оборвалась",
                )
            }
            if (!partial.renameTo(local)) throw FileNotFoundException("не удалось сохранить копию «$documentId»")
        } finally {
            partial.delete()
        }
        trim(keep = local)
        return local
    }

    /**
     * Держит кэш в пределах [limit].
     *
     * Скачанное для чтения нужно ровно до тех пор, пока человек смотрит
     * файл, а раньше оставалось навсегда: каждое открытое из облака видео
     * навсегда занимало место в памяти телефона. Уходят давно не открытые,
     * только что скачанный — никогда.
     */
    fun trim(keep: File? = null, limit: Long = cacheLimit()) {
        val files = cacheDir.listFiles { f -> f.isFile && !f.name.endsWith(PARTIAL) }?.toList().orEmpty()
        var total = files.sumOf { it.length() }
        for (file in files.sortedBy { it.lastModified() }) {
            if (total <= limit) break
            if (file == keep) continue
            val size = file.length()
            if (file.delete()) total -= size
        }
    }

    /** Десятая часть свободного места, но не больше гигабайта и не меньше 100 МБ. */
    private fun cacheLimit(): Long =
        (app.cacheDir.usableSpace / 10).coerceIn(100L * MB, 1024L * MB)

    private fun versionsOf(base: String): List<File> =
        cacheDir.listFiles { f -> f.name.startsWith(base + VERSION_MARK) }?.toList().orEmpty()

    // --- Очередь отправки ------------------------------------------------------

    /**
     * Файл, в который приложение пишет сохраняемое. Куда его отправить,
     * записано рядом: очередь переживает перезапуск процесса.
     */
    fun uploadFile(documentId: String): File {
        uploadsDir.mkdirs()
        val local = File(uploadsDir, DocumentIds.localName(documentId))
        File(uploadsDir, local.name + TARGET).writeText(documentId)
        return local
    }

    /** Сохранённое, но ещё не отправленное — его и надо отдавать при чтении. */
    fun pendingUpload(documentId: String): File? =
        File(uploadsDir, DocumentIds.localName(documentId)).takeIf { it.isFile }

    /** Всё, что ждёт отправки. */
    fun pendingUploads(): List<File> =
        uploadsDir.listFiles { f -> f.name.endsWith(TARGET) }?.mapNotNull { target ->
            File(uploadsDir, target.name.removeSuffix(TARGET)).takeIf { it.isFile }
                ?: run { target.delete(); null }
        }.orEmpty()

    /**
     * Отправляет один файл.
     *
     * @return true — отправлен или отправлять нечего; false — не вышло, файл
     *   остался в очереди.
     */
    fun upload(local: File, client: RcloneClient): Boolean {
        val target = File(local.parentFile, local.name + TARGET)
        val documentId = target.takeIf { it.isFile }?.readText() ?: return true
        if (!local.isFile) return true
        val (cloud, path) = DocumentIds.split(documentId)
        val sent = local.lastModified() to local.length()
        try {
            kotlinx.coroutines.runBlocking {
                client.copyFile(local.parent!!, local.name, RcloneClient.cloudFs(cloud), path)
            }
        } catch (e: Exception) {
            Log.w(TAG, "не удалось отправить $documentId, повторю позже", e)
            return false
        }
        // Пока шла отправка, файл могли открыть и изменить снова — тогда его
        // отправит следующая заливка, а удалять его нельзя.
        if ((local.lastModified() to local.length()) == sent) forget(documentId)
        notifyChanged(documentId)
        return true
    }

    /** Отправляет всё из очереди; true — очередь пуста. */
    fun uploadAll(client: RcloneClient): Boolean = pendingUploads().map { upload(it, client) }.all { it }

    /** Забывает все локальные копии документа — после удаления, переноса, отправки. */
    fun forget(documentId: String) {
        val base = DocumentIds.localName(documentId)
        versionsOf(base).forEach { it.delete() }
        File(uploadsDir, base).delete()
        File(uploadsDir, base + TARGET).delete()
    }

    /** Пустой файл для «создать документ» — создаётся в облаке сразу. */
    fun emptyFile(): File {
        uploadsDir.mkdirs()
        return File(uploadsDir, EMPTY).apply { writeBytes(ByteArray(0)) }
    }

    private fun notifyChanged(documentId: String) {
        val resolver = app.contentResolver
        DocumentIds.parent(documentId)?.let {
            resolver.notifyChange(DocumentsContract.buildChildDocumentsUri(AUTHORITY, it), null)
        }
        resolver.notifyChange(DocumentsContract.buildDocumentUri(AUTHORITY, documentId), null)
    }

    companion object {
        private const val TAG = "OpenDiskFiles"
        private const val AUTHORITY = OpenDiskDocumentsProvider.AUTHORITY
        private const val CACHE_DIR = "documents"
        private const val UPLOADS_DIR = "uploads"
        private const val TARGET = ".target"
        private const val PARTIAL = ".part"
        private const val EMPTY = "empty"
        private const val MB = 1024L * 1024L

        /**
         * Разделитель имени и версии. `@` в имени копии не встречается
         * (DocumentIds.localName оставляет только буквы, цифры и `._-`), и
         * поиск версий одного файла не зацепит соседний: с точкой имя
         * «a-1f» совпадало бы с началом «a-1f.txt-…».
         */
        private const val VERSION_MARK = "@"
    }
}
