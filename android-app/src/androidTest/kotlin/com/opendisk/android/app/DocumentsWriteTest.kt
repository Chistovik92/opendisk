package com.opendisk.android.app

import android.net.Uri
import android.provider.DocumentsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.opendisk.android.LibrcloneTransport
import com.opendisk.bridge.RcloneClient
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Облако на запись — тем путём, которым ходят «Файлы» и чужие приложения:
 * через ContentResolver и DocumentsContract.
 *
 * Облако здесь — псевдоним (`alias`) на папку приложения: rclone работает
 * с ним так же, как с настоящим облаком, но сеть не нужна, а результат
 * видно прямо на диске.
 */
@RunWith(AndroidJUnit4::class)
class DocumentsWriteTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val resolver = context.contentResolver
    private val settings = MobileSettings(context)
    private val before = settings.read()
    private val storage = File(context.filesDir, "write-test-cloud")
    private lateinit var client: RcloneClient

    @Before
    fun setUp() {
        storage.deleteRecursively()
        storage.mkdirs()
        LibrcloneTransport.useConfig(File(context.filesDir, "rclone.conf"))
        client = RcloneClient(LibrcloneTransport.get())
        runBlocking {
            runCatching { client.deleteRemote(CLOUD) }
            client.createRemote(CLOUD, "alias", mapOf("remote" to storage.absolutePath))
        }
        settings.write(before.copy(connected = before.connected + CLOUD))
    }

    @After
    fun tearDown() {
        settings.write(before)
        runBlocking { runCatching { client.deleteRemote(CLOUD) } }
        storage.deleteRecursively()
    }

    @Test
    fun savedFileReachesTheCloud() {
        val uri = DocumentsContract.createDocument(resolver, document("$CLOUD/"), "text/plain", "note.txt")
        assertNotNull(uri, "поставщик не создал файл")

        resolver.openOutputStream(uri, "wt")!!.use { it.write("привет из приложения".toByteArray()) }

        val onDisk = File(storage, "note.txt")
        waitUntil("файл не долетел до облака") { onDisk.isFile && onDisk.readText() == "привет из приложения" }
        assertEquals("привет из приложения", read(uri))
    }

    @Test
    fun foldersRenameAndDeleteWork() {
        val folder = DocumentsContract.createDocument(
            resolver, document("$CLOUD/"), DocumentsContract.Document.MIME_TYPE_DIR, "Папка",
        )!!
        assertTrue(File(storage, "Папка").isDirectory)

        File(storage, "Папка/a.txt").writeText("a")
        val file = document("$CLOUD/Папка/a.txt")
        val renamed = DocumentsContract.renameDocument(resolver, file, "b.txt")
        assertNotNull(renamed)
        assertFalse(File(storage, "Папка/a.txt").exists())
        assertEquals("a", File(storage, "Папка/b.txt").readText())

        assertTrue(DocumentsContract.deleteDocument(resolver, folder))
        assertFalse(File(storage, "Папка").exists())
    }

    @Test
    fun changedCloudFileIsNotServedFromAStaleCopy() {
        File(storage, "doc.txt").writeText("первая")
        val uri = document("$CLOUD/doc.txt")
        assertEquals("первая", read(uri))

        File(storage, "doc.txt").writeText("вторая версия")
        assertEquals("вторая версия", read(uri))
    }

    private fun document(id: String): Uri = DocumentsContract.buildDocumentUri(OpenDiskDocumentsProvider.AUTHORITY, id)

    private fun read(uri: Uri): String = resolver.openInputStream(uri)!!.use { it.readBytes().decodeToString() }

    private fun waitUntil(message: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(200)
        }
        throw AssertionError(message)
    }

    private companion object {
        const val CLOUD = "write-test"
    }
}
