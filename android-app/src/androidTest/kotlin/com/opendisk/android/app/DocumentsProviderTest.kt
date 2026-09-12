package com.opendisk.android.app

import android.provider.DocumentsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Подключённое облако должно быть видно системе.
 *
 * Проверяется тем же путём, которым ходит приложение «Файлы»: запрос к
 * поставщику документов через ContentResolver. Написать корень в настройки
 * и поверить, что система его увидит, было бы проверкой самой себя — а
 * ломается здесь как раз стык: полномочия в манифесте, авторитет поставщика,
 * имена колонок.
 *
 * Только на настоящем Android: ContentResolver и DocumentsContract на JVM
 * не существуют.
 */
@RunWith(AndroidJUnit4::class)
class DocumentsProviderTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val settings = MobileSettings(context)
    private val before = settings.read()

    @After
    fun restore() {
        settings.write(before)
    }

    @Test
    fun connectedCloudBecomesARootForTheSystem() {
        settings.write(before.copy(connected = setOf(CLOUD)))

        val roots = readRoots()
        assertTrue(CLOUD in roots, "корня «$CLOUD» нет среди $roots")
    }

    @Test
    fun disconnectedCloudDisappearsFromRoots() {
        settings.write(before.copy(connected = setOf(CLOUD)))
        settings.write(before.copy(connected = emptySet()))

        assertEquals(emptyList(), readRoots())
    }

    private fun readRoots(): List<String> {
        val uri = DocumentsContract.buildRootsUri(OpenDiskDocumentsProvider.AUTHORITY)
        val cursor = context.contentResolver.query(uri, null, null, null, null)
        requireNotNull(cursor) { "поставщик документов не ответил на запрос корней" }
        return cursor.use {
            buildList {
                while (it.moveToNext()) {
                    add(it.getString(it.getColumnIndexOrThrow(DocumentsContract.Root.COLUMN_ROOT_ID)))
                }
            }
        }
    }

    private companion object {
        /** Облака с таким именем в конфиге нет: корень строится по списку, а не по rclone. */
        const val CLOUD = "проверочное-облако"
    }
}
