package com.opendisk.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.opendisk.bridge.RcloneClient
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertTrue

/**
 * rclone внутри приложения ходит в сеть по имени хоста.
 *
 * Все остальные проверки на эмуляторе работают с локальными облаками и в сеть
 * не выходят — поэтому то, что на настоящем телефоне вход в Яндекс.Диск упал
 * на «lookup oauth.yandex.com: no such host», ими не ловилось. Эта проверка
 * отделяет «DNS внутри librclone не работает вообще» от «сеть отняли у
 * приложения, пока оно было в фоне»: здесь приложение на переднем плане.
 *
 * Нужен выход в интернет — у эмулятора на раннере GitHub он есть.
 */
@RunWith(AndroidJUnit4::class)
class NetworkTest {

    @Test
    fun rcloneResolvesHostnamesAndReadsOverHttps() = runBlocking {
        val client = RcloneClient(LibrcloneTransport.get())
        val name = "сеть-" + System.nanoTime()
        try {
            client.createRemote(name, "http", mapOf("url" to "https://downloads.rclone.org/"))
            val entries = client.list(name, "v1.75.1")
            assertTrue(entries.any { it.name.startsWith("rclone-v1.75.1") }, "в списке нет файлов выпуска: $entries")
        } finally {
            runCatching { client.deleteRemote(name) }
        }
    }
}
