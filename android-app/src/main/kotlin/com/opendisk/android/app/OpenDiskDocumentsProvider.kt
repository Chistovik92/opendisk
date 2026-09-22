package com.opendisk.android.app

import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.util.Log
import android.webkit.MimeTypeMap
import com.opendisk.android.LibrcloneTransport
import com.opendisk.bridge.RcloneClient
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.FileNotFoundException
import java.util.concurrent.Executors

/**
 * Облако как источник файлов для всей системы.
 *
 * Это ответ на «подключить» с телефона. Смонтировать облако диском, как на
 * компьютере, Android не даёт: файловые системы монтирует ядро, приложению
 * этого не позволено. Зато система умеет спрашивать файлы у приложения —
 * через поставщика документов. Подключённое облако после этого видно в
 * «Файлах» и в любом окне выбора файла, рядом с памятью телефона.
 *
 * С 0.5.9 — и на запись: с файлами в облаке работают как с обычными.
 * Сохранить из любого приложения прямо в облако, отредактировать документ
 * на месте, создать папку, переименовать, перенести, скопировать (в том
 * числе между облаками), удалить — всё из «Файлов» или окна выбора файла.
 *
 * Поставщика система поднимает сама и в своё время — экрана приложения при
 * этом может не быть вовсе. Поэтому список подключённых облаков лежит в
 * настройках ([MobileSettings]), а не в модели экрана.
 */
class OpenDiskDocumentsProvider : DocumentsProvider() {

    private var client: RcloneClient? = null

    /**
     * Поток, на котором система сообщает о закрытии файла, открытого на запись,
     * и одна очередь заливок: файлы уходят в облако по одному и в том порядке,
     * в каком их сохранили, — поздняя правка не обгонит раннюю.
     */
    private val closeHandler by lazy { Handler(HandlerThread("opendisk-documents").apply { start() }.looper) }
    private val uploads = Executors.newSingleThreadExecutor()

    override fun onCreate(): Boolean = true

    /**
     * Клиент поднимается лениво: первый вызов распаковывает нативную
     * библиотеку, а поставщика система создаёт заранее — иногда просто
     * «на всякий случай», ничего потом не спросив.
     *
     * Здесь же досылаются заливки, которые не дошли в прошлый раз: сеть
     * пропала или систему выгрузили посреди отправки.
     */
    @Synchronized
    private fun client(): RcloneClient {
        client?.let { return it }
        val context = requireNotNull(context) { "поставщик документов без контекста" }
        LibrcloneTransport.useConfig(File(context.filesDir, "rclone.conf"))
        return RcloneClient(LibrcloneTransport.get()).also {
            client = it
            resumePendingUploads()
        }
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
                add(Root.COLUMN_DOCUMENT_ID, DocumentIds.of(cloud, ""))
                add(Root.COLUMN_TITLE, cloud)
                add(Root.COLUMN_SUMMARY, strings.notificationTitle)
                add(Root.COLUMN_ICON, android.R.drawable.ic_menu_save)
                // FLAG_SUPPORTS_CREATE — облако предлагается местом для
                // «Сохранить как» в любом приложении.
                add(Root.COLUMN_FLAGS, Root.FLAG_SUPPORTS_IS_CHILD or Root.FLAG_SUPPORTS_CREATE)
            }
        }
        cursor.setNotificationUri(requireNotNull(context).contentResolver, rootsUri())
        return cursor
    }

    // --- Документы -----------------------------------------------------------

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val cursor = MatrixCursor(projection ?: DOCUMENT_COLUMNS)
        val (cloud, path) = DocumentIds.split(documentId)
        if (path.isEmpty()) {
            cursor.newRow().apply {
                add(Document.COLUMN_DOCUMENT_ID, documentId)
                add(Document.COLUMN_DISPLAY_NAME, cloud)
                add(Document.COLUMN_MIME_TYPE, Document.MIME_TYPE_DIR)
                add(Document.COLUMN_FLAGS, Document.FLAG_DIR_SUPPORTS_CREATE)
                add(Document.COLUMN_SIZE, null)
                add(Document.COLUMN_LAST_MODIFIED, null)
            }
            return cursor
        }

        val entry = stat(cloud, path) ?: throw FileNotFoundException("в облаке «$cloud» нет «$path»")
        cursor.addEntry(cloud, entry)
        return cursor
    }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val cursor = MatrixCursor(projection ?: DOCUMENT_COLUMNS)
        val (cloud, path) = DocumentIds.split(parentDocumentId)
        call { client().list(cloud, path) }.forEach { cursor.addEntry(cloud, it) }
        // Подписка на изменения папки: после заливки, удаления или переноса
        // «Файлы» перечитывают её сами, без ручного обновления.
        cursor.setNotificationUri(requireNotNull(context).contentResolver, childrenUri(parentDocumentId))
        return cursor
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean =
        DocumentIds.isChild(parentDocumentId, documentId)

    /**
     * Файл системе отдаётся обычным дескриптором, а у файла в облаке его нет,
     * пока он не оказался на диске. Поэтому файл сначала скачивается
     * в папку приложения — целиком: частичного чтения RC API не умеет.
     *
     * На запись — так же, только в отдельную папку заливок: приложение пишет
     * в локальный файл, а когда закрывает его, файл уходит в облако.
     */
    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?,
    ): ParcelFileDescriptor {
        val open = OpenMode.parse(mode)
        return if (open.write) openForWrite(documentId, mode, open, signal) else openForRead(documentId, signal)
    }

    private fun openForRead(documentId: String, signal: CancellationSignal?): ParcelFileDescriptor {
        val (cloud, path) = DocumentIds.split(documentId)
        // Файл, который ещё не долетел до облака, читаем из очереди заливок:
        // иначе приложение, только что сохранившее документ, открыло бы
        // его прежнюю версию.
        pendingUpload(documentId)?.let {
            return ParcelFileDescriptor.open(it, ParcelFileDescriptor.MODE_READ_ONLY)
        }

        val entry = stat(cloud, path) ?: throw FileNotFoundException("в облаке «$cloud» нет «$path»")
        val cacheDir = dir(CACHE_DIR)
        val base = DocumentIds.localName(documentId)
        val localName = base + "." + DocumentIds.version(entry.size, entry.modTime)
        val local = File(cacheDir, localName)

        if (!local.isFile) {
            // Прежние версии этого же файла больше не понадобятся.
            cacheDir.listFiles { f -> f.name.startsWith("$base.") }?.forEach { it.delete() }
            call { client().copyToLocal(cloud, path, cacheDir.absolutePath, localName) }
        }
        signal?.throwIfCanceled()
        return ParcelFileDescriptor.open(local, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    private fun openForWrite(
        documentId: String,
        mode: String,
        open: OpenMode,
        signal: CancellationSignal?,
    ): ParcelFileDescriptor {
        val (cloud, path) = DocumentIds.split(documentId)
        if (path.isEmpty()) throw FileNotFoundException("корень облака «$cloud» — не файл")

        val uploadsDir = dir(UPLOADS_DIR)
        val local = File(uploadsDir, DocumentIds.localName(documentId))
        File(uploadsDir, local.name + TARGET_SUFFIX).writeText(documentId)

        if (open.needsExistingContent && !local.isFile) {
            // «Дописать» или «прочитать и изменить»: начинаем с того, что уже
            // лежит в облаке. Файла там нет — начинаем с пустого.
            if (stat(cloud, path) != null) {
                call { client().copyToLocal(cloud, path, uploadsDir.absolutePath, local.name) }
            }
        }
        if (!local.exists()) local.createNewFile()
        signal?.throwIfCanceled()

        return ParcelFileDescriptor.open(
            local,
            ParcelFileDescriptor.parseMode(mode),
            closeHandler,
        ) { error ->
            // Приложение закрыло файл. С ошибкой — значит, запись оборвалась,
            // и заливать недописанное нельзя: в облаке остаётся прежняя версия.
            if (error == null) enqueueUpload(local) else Log.w(TAG, "запись $documentId оборвалась", error)
        }
    }

    /**
     * Новый файл или папка.
     *
     * Файл создаётся в облаке сразу, пустым: система следом спросит о нём
     * [queryDocument] и откроет на запись, и оба вызова должны его найти.
     */
    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        val (cloud, parentPath) = DocumentIds.split(parentDocumentId)
        val taken = call { client().list(cloud, parentPath) }.map { it.name }.toSet()
        val name = if (displayName in taken) copyName(displayName, taken) else displayName
        val path = childPath(parentPath, name)
        val fs = RcloneClient.cloudFs(cloud)

        if (mimeType == Document.MIME_TYPE_DIR) {
            call { client().mkdir(fs, path) }
        } else {
            val empty = File(dir(UPLOADS_DIR), "empty").apply { writeBytes(ByteArray(0)) }
            call { client().copyFile(empty.parent!!, empty.name, fs, path) }
        }
        notifyChildrenChanged(parentDocumentId)
        return DocumentIds.of(cloud, path)
    }

    override fun deleteDocument(documentId: String) {
        val (cloud, path) = DocumentIds.split(documentId)
        if (path.isEmpty()) throw FileNotFoundException("облако целиком здесь не удаляется")
        val entry = stat(cloud, path) ?: throw FileNotFoundException("в облаке «$cloud» нет «$path»")
        val fs = RcloneClient.cloudFs(cloud)
        call { if (entry.isDir) client().purge(fs, path) else client().deleteFile(fs, path) }
        forgetLocalCopies(documentId)
        revokeDocumentPermission(documentId)
        DocumentIds.parent(documentId)?.let(::notifyChildrenChanged)
    }

    override fun removeDocument(documentId: String, parentDocumentId: String) = deleteDocument(documentId)

    override fun renameDocument(documentId: String, displayName: String): String {
        val parent = DocumentIds.parent(documentId)
            ?: throw FileNotFoundException("облако переименовывается в самом OpenDisk")
        val target = DocumentIds.child(parent, displayName)
        if (target == documentId) return documentId
        transfer(documentId, target, move = true)
        revokeDocumentPermission(documentId)
        notifyChildrenChanged(parent)
        return target
    }

    override fun moveDocument(
        sourceDocumentId: String,
        sourceParentDocumentId: String,
        targetParentDocumentId: String,
    ): String {
        val target = DocumentIds.child(targetParentDocumentId, nameOf(sourceDocumentId))
        transfer(sourceDocumentId, target, move = true)
        revokeDocumentPermission(sourceDocumentId)
        notifyChildrenChanged(sourceParentDocumentId)
        notifyChildrenChanged(targetParentDocumentId)
        return target
    }

    override fun copyDocument(sourceDocumentId: String, targetParentDocumentId: String): String {
        val (targetCloud, targetParentPath) = DocumentIds.split(targetParentDocumentId)
        val taken = call { client().list(targetCloud, targetParentPath) }.map { it.name }.toSet()
        val name = nameOf(sourceDocumentId).let { if (it in taken) copyName(it, taken) else it }
        val target = DocumentIds.child(targetParentDocumentId, name)
        transfer(sourceDocumentId, target, move = false)
        notifyChildrenChanged(targetParentDocumentId)
        return target
    }

    /**
     * Перенос и копирование — одним вызовом rclone, в том числе между
     * разными облаками. Внутри одного облака rclone переносит на стороне
     * сервиса, если тот умеет, без скачивания.
     */
    private fun transfer(sourceId: String, targetId: String, move: Boolean) {
        val (srcCloud, srcPath) = DocumentIds.split(sourceId)
        val (dstCloud, dstPath) = DocumentIds.split(targetId)
        if (srcPath.isEmpty()) throw FileNotFoundException("облако целиком здесь не переносится")
        val entry = stat(srcCloud, srcPath) ?: throw FileNotFoundException("в облаке «$srcCloud» нет «$srcPath»")
        val srcFs = RcloneClient.cloudFs(srcCloud)
        val dstFs = RcloneClient.cloudFs(dstCloud)
        call {
            val api = client()
            when {
                entry.isDir && move -> api.moveDir(srcFs, srcPath, dstFs, dstPath)
                entry.isDir -> api.copyDir(srcFs, srcPath, dstFs, dstPath)
                move -> api.moveFile(srcFs, srcPath, dstFs, dstPath)
                else -> api.copyFile(srcFs, srcPath, dstFs, dstPath)
            }
        }
        if (move) forgetLocalCopies(sourceId)
    }

    // --- Заливка в облако ----------------------------------------------------

    private fun enqueueUpload(local: File) {
        uploads.execute { upload(local) }
    }

    /**
     * Отправляет файл в облако. Не вышло — файл остаётся в очереди и уйдёт
     * при следующем обращении к облаку: потерять сохранённое хуже, чем
     * отправить его позже.
     */
    private fun upload(local: File) {
        val targetFile = File(local.parentFile, local.name + TARGET_SUFFIX)
        val documentId = targetFile.takeIf { it.isFile }?.readText() ?: return
        if (!local.isFile) return
        val (cloud, path) = DocumentIds.split(documentId)
        val sent = local.lastModified() to local.length()
        try {
            runBlocking { client().copyFile(local.parent!!, local.name, RcloneClient.cloudFs(cloud), path) }
        } catch (e: Exception) {
            Log.w(TAG, "не удалось отправить $documentId, повторю позже", e)
            return
        }
        // Пока шла отправка, файл могли открыть и изменить снова — тогда
        // его отправит следующая заливка, а удалять его нельзя.
        if ((local.lastModified() to local.length()) == sent) forgetLocalCopies(documentId)
        DocumentIds.parent(documentId)?.let(::notifyChildrenChanged)
        notifyDocumentChanged(documentId)
    }

    private fun resumePendingUploads() {
        val dir = File(requireNotNull(context).cacheDir, UPLOADS_DIR)
        dir.listFiles { f -> f.name.endsWith(TARGET_SUFFIX) }?.forEach { target ->
            val local = File(dir, target.name.removeSuffix(TARGET_SUFFIX))
            if (local.isFile) enqueueUpload(local) else target.delete()
        }
    }

    private fun pendingUpload(documentId: String): File? =
        File(dir(UPLOADS_DIR), DocumentIds.localName(documentId)).takeIf { it.isFile }

    private fun forgetLocalCopies(documentId: String) {
        val base = DocumentIds.localName(documentId)
        dir(CACHE_DIR).listFiles { f -> f.name.startsWith("$base.") }?.forEach { it.delete() }
        File(dir(UPLOADS_DIR), base).delete()
        File(dir(UPLOADS_DIR), base + TARGET_SUFFIX).delete()
    }

    // --- Мелочи ---------------------------------------------------------------

    private fun MatrixCursor.addEntry(cloud: String, entry: RcloneClient.Entry) {
        val flags = if (entry.isDir) {
            Document.FLAG_DIR_SUPPORTS_CREATE or EDITABLE
        } else {
            Document.FLAG_SUPPORTS_WRITE or EDITABLE
        }
        newRow().apply {
            add(Document.COLUMN_DOCUMENT_ID, DocumentIds.of(cloud, entry.path))
            add(Document.COLUMN_DISPLAY_NAME, entry.name)
            add(Document.COLUMN_MIME_TYPE, mimeOf(entry))
            // Размер папки rclone отдаёт как -1 — это «неизвестно», и показывать
            // вместо него ноль значило бы соврать.
            add(Document.COLUMN_SIZE, entry.size.takeIf { it >= 0 })
            add(Document.COLUMN_LAST_MODIFIED, modTimeMillis(entry.modTime))
            add(Document.COLUMN_FLAGS, flags)
        }
    }

    private fun mimeOf(entry: RcloneClient.Entry): String = when {
        entry.isDir -> Document.MIME_TYPE_DIR
        else -> mimeOfName(entry.name)
    }

    private fun mimeOfName(name: String): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
            ?: "application/octet-stream"

    /** java.time есть только с Android 8; на 7 дата просто не показывается. */
    private fun modTimeMillis(modTime: String): Long? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || modTime.isEmpty()) return null
        return runCatching { java.time.OffsetDateTime.parse(modTime).toInstant().toEpochMilli() }.getOrNull()
    }

    private fun nameOf(documentId: String): String =
        DocumentIds.split(documentId).second.substringAfterLast('/')

    private fun stat(cloud: String, path: String): RcloneClient.Entry? = call { client().stat(cloud, path) }

    private fun dir(name: String): File =
        File(requireNotNull(context).cacheDir, name).apply { mkdirs() }

    private fun rootsUri(): Uri = DocumentsContract.buildRootsUri(AUTHORITY)

    private fun childrenUri(parentId: String): Uri = DocumentsContract.buildChildDocumentsUri(AUTHORITY, parentId)

    private fun notifyChildrenChanged(parentId: String) {
        context?.contentResolver?.notifyChange(childrenUri(parentId), null, false)
    }

    private fun notifyDocumentChanged(documentId: String) {
        context?.contentResolver?.notifyChange(DocumentsContract.buildDocumentUri(AUTHORITY, documentId), null, false)
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

    companion object {
        const val AUTHORITY = "com.opendisk.android.documents"

        private const val TAG = "OpenDiskDocuments"
        private const val CACHE_DIR = "documents"
        private const val UPLOADS_DIR = "uploads"
        private const val TARGET_SUFFIX = ".target"

        /** Общее для файлов и папок: удалить, переименовать, перенести, скопировать. */
        private const val EDITABLE = Document.FLAG_SUPPORTS_DELETE or
            Document.FLAG_SUPPORTS_RENAME or
            Document.FLAG_SUPPORTS_MOVE or
            Document.FLAG_SUPPORTS_COPY or
            Document.FLAG_SUPPORTS_REMOVE

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
            Document.COLUMN_LAST_MODIFIED,
            Document.COLUMN_FLAGS,
        )
    }
}
