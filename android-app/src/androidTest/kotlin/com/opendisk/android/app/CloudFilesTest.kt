package com.opendisk.android.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.opendisk.bridge.RcloneClient
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileNotFoundException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Локальные копии файлов из облака. Облако здесь не нужно: скачивание
 * подменено записью в файл, проверяется то, что вокруг него.
 */
@RunWith(AndroidJUnit4::class)
class CloudFilesTest {

    private val files = CloudFiles(InstrumentationRegistry.getInstrumentation().targetContext)

    private fun entry(size: Long, modTime: String = "2026-09-24T10:00:00Z") =
        RcloneClient.Entry(path = "a.bin", name = "a.bin", size = size, modTime = modTime)

    @Test
    fun cutOffDownloadIsNeverServedAsAWholeFile() {
        val id = "test-" + System.nanoTime() + "/a.bin"

        // Связь оборвалась: пришла половина.
        assertFailsWith<FileNotFoundException> {
            files.cached(id, entry(10)) { dir, name -> File(dir, name).writeBytes(ByteArray(5)) }
        }

        // Следующее открытие качает заново, а не отдаёт обрезок.
        var downloads = 0
        val local = files.cached(id, entry(10)) { dir, name ->
            downloads++
            File(dir, name).writeBytes(ByteArray(10))
        }
        assertEquals(1, downloads)
        assertEquals(10, local.length())
        files.forget(id)
    }

    @Test
    fun sameVersionIsDownloadedOnceAndNewVersionReplacesIt() {
        val id = "test-" + System.nanoTime() + "/a.bin"
        var downloads = 0
        val write: (File, String) -> Unit = { dir, name ->
            downloads++
            File(dir, name).writeBytes(ByteArray(3))
        }

        val first = files.cached(id, entry(3), write)
        files.cached(id, entry(3), write)
        assertEquals(1, downloads)

        val changed = files.cached(id, entry(3, "2026-09-24T11:00:00Z"), write)
        assertEquals(2, downloads)
        assertFalse(first.exists(), "прежняя версия осталась в кэше")
        assertTrue(changed.exists())
        files.forget(id)
    }

    @Test
    fun trimKeepsTheCacheWithinTheLimitAndSparesTheFreshFile() {
        val prefix = "test-" + System.nanoTime()
        val old = files.cached("$prefix/old.bin", entry(1000)) { dir, name ->
            File(dir, name).writeBytes(ByteArray(1000))
        }
        old.setLastModified(System.currentTimeMillis() - 60_000)
        val fresh = files.cached("$prefix/new.bin", entry(1000)) { dir, name ->
            File(dir, name).writeBytes(ByteArray(1000))
        }

        files.trim(keep = fresh, limit = 1500)

        assertFalse(old.exists(), "давно не открытый файл должен уйти первым")
        assertTrue(fresh.exists(), "только что скачанный трогать нельзя")
        files.forget("$prefix/new.bin")
    }
}
