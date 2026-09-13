package com.opendisk.android.app

import android.app.Notification
import android.app.NotificationManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Значок в шторке появляется со сводкой подключений и исчезает, когда
 * подключений не осталось.
 *
 * Проверяется по тому, что система действительно показывает, а не по тому,
 * что приложение попросило показать: служба переднего плана без правильного
 * типа в манифесте на новых Android просто не стартует — и значка нет.
 */
@RunWith(AndroidJUnit4::class)
class StatusServiceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val strings = MobileStrings.of(MobileSettings(context).read().language)

    @After
    fun stop() {
        StatusService.sync(context, StatusSnapshot())
    }

    @Test
    fun connectedCloudsShowASummaryInTheShade() {
        StatusService.sync(
            context,
            StatusSnapshot(
                clouds = listOf(
                    StatusSnapshot.CloudStatus("yandex", connected = true, space = "занято 1 ГБ из 10 ГБ"),
                    StatusSnapshot.CloudStatus("mail", connected = false, space = ""),
                ),
            ),
        )

        val shown = waitFor { ours() != null }
        assertTrue(shown, "значка OpenDisk в шторке нет")
        val extras = ours()!!.notification.extras
        assertEquals(strings.statusTitle(1, 2), extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        val details = extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString()
        assertTrue("yandex" in details && "mail" in details, "в сводке не все облака: $details")

        StatusService.sync(context, StatusSnapshot())
        assertTrue(waitFor { ours() == null }, "значок остался, хотя подключений нет")
    }

    @Test
    fun signInIsShownInTheShade() {
        StatusService.sync(context, StatusSnapshot(signingIn = "gdrive"))

        assertTrue(waitFor { ours() != null }, "значка входа в шторке нет")
        assertEquals(
            strings.signInNotificationTitle("gdrive"),
            ours()!!.notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString(),
        )
    }

    private fun ours() = manager.activeNotifications.firstOrNull { it.packageName == context.packageName && it.id == 1 }

    private fun waitFor(condition: () -> Boolean): Boolean {
        repeat(50) {
            if (condition()) return true
            Thread.sleep(100)
        }
        return condition()
    }
}
