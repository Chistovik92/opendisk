package com.opendisk.android.app

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.webkit.MimeTypeMap
import com.opendisk.android.LibrcloneTransport
import com.opendisk.bridge.RcloneClient
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.FileNotFoundException

/**
 * Облако как источник файлов для всей системы.
 *
 * Это ответ на «подключить» с телефона. Смонтировать облако диском, как на
 * компьютере, Android не даёт: файловые системы монтирует ядро, приложению
 * этого не позволено. Зато система умеет спрашивать файлы у приложения —
 * через поставщика документов. Подключённое облако после этого видно в
 * «Файлах» и в любом окне выбора файла, рядом с памятью телефона.
 *
 * Поставщика система поднимает сама и в своё время — экрана приложения при
 * этом может не быть вовсе. Поэтому список подключённых облаков лежит в
 * настройках ([MobileSettings]), а не в модели экрана.
 *
 * Только чтение. Запись означала бы заливку в облако из чужого приложения
 * в фоне — с обрывами, дозагрузкой и конфликтами; это отдельная работа, и
 * делать её наполовину нельзя. Система об этом знает: права на запись
 * поставщик не объявляет, и «сохранить сюда» просто не предлагается.
 */
class OpenDiskDocumentsProvider : DocumentsProvider() {

    private var client: RcloneClient? = null

    override fun onCreate(): Boolean = true

    /**
     * Клиент поднимается лениво: первый вызов распаковывает нативную
     * библиотеку, а поставщика система создаёт заранее — иногда просто
     * «на всякий случай», ничего потом не спросив.
     */
    private fun client(): RcloneClient {
        client?.let { return it }
        val context = requireNotNull(context) { "поставщик документов без контекста" }
        LibrcloneTransport.useConfig(File(context.filesDir, "rclone.conf"))
        return RcloneClient(LibrcloneTransport.get()).also { client = it }
    }

    private fun settings(): MobileSettings =
        MobileSettings(requireNotNull(context) { "поставщик документов без контекста" })

    private fun strings(): MobileStrings =
        MobileStrings.of(settings().read().language)

    // --- Корни ---------------------------------------------------------------

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val cursor = MatrixCursor(projection ?: ROOT_COLUMNS)
        val strings = strings()
        settings().read().connected.sorted().forEach { cloud ->
            cursor.newRow().apply {
                add(Root.COLUMN_ROOT_ID, cloud)
                add(Root.COLUMN_DOCUMENT_ID, documentId(cloud, ""))
                add(Root.COLUMN_TITLE, cloud)
                add(Root.COLUMN_SUMMARY, strings.notificationTitle)
                add(Root.COLUMN_ICON, android.R.drawable.ic_menu_save)
                add(Root.COLUMN_FLAGS, Root.FLAG_SUPPORTS_IS_CHILD)
            }
        }
        return cursor
    }

    // --- Документы -----------------------------------------------------------

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val cursor = MatrixCursor(projection ?: DOCUMENT_COLUMNS)
        val (cloud, path) = split(documentId)
        if (path.isEmpty()) {
            cursor.newRow().apply {
                add(Document.COLUMN_DOCUMENT_ID, documentId)
                add(Document.COLUMN_DISPLAY_NAME, cloud)
                add(Document.COLUMN_MIME_TYPE, Document.MIME_TYPE_DIR)
                add(Document.COLUMN_FLAGS, 0)
                add(Document.COLUMN_SIZE, null)
            }
            return cursor
        }

        val entry = call { client().stat(cloud, path) }
            ?: throw FileNotFoundException("в облаке «$cloud» нет «$path»")
        cursor.addEntry(cloud, entry)
        return cursor
    }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val cursor = MatrixCursor(projection ?: DOCUMENT_COLUMNS)
        val (cloud, path) = split(parentDocumentId)
        call { client().list(cloud, path) }.forEach { cursor.addEntry(cloud, it) }
        return cursor
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean =
        documentId == parentDocumentId || documentId.startsWith(parentDocumentId.trimEnd('/') + "/")

    /**
     * Файл системе отдаётся обычным дескриптором, а у файла в облаке его нет,
     * пока он не оказался на диске. Поэтому читаемый файл сначала скачивается
     * во временную папку приложения — целиком: частичного чтения RC API не
     * умеет, а обманывать систему «ленивым» дескриптором нечем.
     */
    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?,
    ): ParcelFileDescriptor {
        if ("w" in mode || "t" in mode || "a" in mode) {
            throw FileNotFoundException("OpenDisk отдаёт файлы из облака только на чтение")
        }
        val (cloud, path) = split(documentId)
        val cacheDir = File(requireNotNull(context).cacheDir, CACHE_DIR).apply { mkdirs() }
        // Имя с путём внутри облака: файлы с одинаковыми именами из разных
        // папок иначе затирали бы друг друга.
        val localName = (cloud + "/" + path).replace(Regex("[^A-Za-z0-9._-]"), "_")
        val local = File(cacheDir, localName)

        if (!local.isFile) {
            call { client().copyToLocal(cloud, path, cacheDir.absolutePath, localName) }
        }
        signal?.throwIfCanceled()
        return ParcelFileDescriptor.open(local, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    // --- Мелочи ---------------------------------------------------------------

    private fun MatrixCursor.addEntry(cloud: String, entry: RcloneClient.Entry) {
        newRow().apply {
            add(Document.COLUMN_DOCUMENT_ID, documentId(cloud, entry.path))
            add(Document.COLUMN_DISPLAY_NAME, entry.name)
            add(Document.COLUMN_MIME_TYPE, mimeOf(entry))
            // Размер папки rclone отдаёт как -1 — это «неизвестно», и показывать
            // вместо него ноль значило бы соврать.
            add(Document.COLUMN_SIZE, entry.size.takeIf { it >= 0 })
            add(Document.COLUMN_FLAGS, 0)
        }
    }

    private fun mimeOf(entry: RcloneClient.Entry): String = when {
        entry.isDir -> Document.MIME_TYPE_DIR
        else -> MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(entry.name.substringAfterLast('.', "").lowercase())
            ?: "application/octet-stream"
    }

    /**
     * Вызов rclone из потока, в котором система нас и спросила. Блокировать
     * его можно и нужно: поставщика документов вызывают с биндер-потока,
     * и ответ система ждёт синхронно.
     *
     * Ошибку облака превращаем в [FileNotFoundException]: это единственное,
     * что система от поставщика принимает, а вот падение приложения она
     * покажет человеку как «Файлы» перестали работать.
     */
    private fun <T> call(block: suspend () -> T): T = try {
        runBlocking { block() }
    } catch (e: FileNotFoundException) {
        throw e
    } catch (e: Exception) {
        throw FileNotFoundException(e.message ?: e::class.simpleName.orEmpty())
    }

    private fun documentId(cloud: String, path: String): String =
        if (path.isEmpty()) "$cloud/" else "$cloud/$path"

    private fun split(documentId: String): Pair<String, String> {
        val cloud = documentId.substringBefore('/')
        return cloud to documentId.substringAfter('/', "")
    }

    companion object {
        const val AUTHORITY = "com.opendisk.android.documents"

        private const val CACHE_DIR = "documents"

        private val ROOT_COLUMNS = arrayOf(
            Root.COLUMN_ROOT_ID,
            Root.COLUMN_DOCUMENT_ID,
            Root.COLUMN_TITLE,
            Root.COLUMN_SUMMARY,
            Root.COLUMN_ICON,
            Root.COLUMN_FLAGS,
        )

        private val DOCUMENT_COLUMNS = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE,
            Document.COLUMN_FLAGS,
        )
    }
}
