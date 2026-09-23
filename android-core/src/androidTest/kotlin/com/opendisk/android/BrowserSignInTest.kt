package com.opendisk.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.opendisk.bridge.OAuthLink
import com.opendisk.bridge.RcloneClient
import com.opendisk.bridge.RcloneRcException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertContains
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Вход через браузер на настоящем Android — без браузера.
 *
 * Проверяется ровно то, на чём держится вход и что ломается только на
 * устройстве: rclone внутри приложения поднимает свой сервер подтверждения,
 * его ссылка доходит до приложения через перехват потока ошибок
 * ([RcloneOutput]), а отмена по этой ссылке будит висящий `config/create`.
 *
 * Сама страница Google здесь не открывается: сети до неё у эмулятора в CI
 * может и не быть, а проверять Google незачем. До печати ссылки rclone
 * в сеть не ходит.
 */
@RunWith(AndroidJUnit4::class)
class BrowserSignInTest {

    // Путь к конфигу задаётся до первого обращения к rclone — как в приложении.
    @Before
    fun useConfig() {
        TestConfig.use()
    }

    @Test
    fun signInLinkReachesTheAppAndCancelWakesRclone() = runBlocking {
        val client = RcloneClient(LibrcloneTransport.get())
        val name = "вход-" + System.nanoTime()

        try {
            val creation = async(Dispatchers.IO) {
                runCatching { client.createRemote(name, "drive", emptyMap()) }
            }

            val link = withTimeout(30_000) {
                var found: String? = null
                while (found == null) {
                    found = RcloneOutput.recentLines().firstNotNullOfOrNull(OAuthLink::find)
                    if (found == null) delay(200)
                }
                found
            }
            assertTrue(link.startsWith("http://127.0.0.1:"), "неожиданная ссылка: $link")

            OAuthLink.cancel(link)

            val result = withTimeout(30_000) { creation.await() }
            val error = assertNotNull(result.exceptionOrNull() as? RcloneRcException, "создание не прервалось")
            assertContains(error.rcloneError, "access_denied")
        } finally {
            runCatching { client.deleteRemote(name) }
        }
    }
}
