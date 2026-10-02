package com.opendisk.android.app

import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.ProxyFileDescriptorCallback
import android.os.storage.StorageManager
import android.system.ErrnoException
import android.system.OsConstants
import com.opendisk.bridge.RcloneClient
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * Чтение файла из облака кусками — видео и большие файлы открываются сразу.
 *
 * До 0.6.0 файл сначала скачивался целиком: RC API частичного чтения не
 * умеет, и «Файлы» ждали конца загрузки, прежде чем показать первый кадр.
 * Теперь для больших файлов система получает виртуальный файл
 * ([StorageManager.openProxyFileDescriptor]): когда плеер просит байты с
 * такого-то места, они читаются из облака запросом `Range` к HTTP-серверу
 * rclone, поднятому внутри приложения ([RcloneClient.serveHttp]).
 *
 * Сервер на облако один и живёт, пока живёт процесс. Он закрыт паролем:
 * порт слышит весь телефон, и без пароля облако читало бы любое приложение.
 */
class CloudStreams(private val client: () -> RcloneClient) {

    /** Сервер одного облака: куда ходить и чем подписываться. */
    class Endpoint(val id: String, val baseUrl: String, val authorization: String)

    private val endpoints = ConcurrentHashMap<String, Endpoint>()
    private val lock = Any()

    /**
     * Библиотека rclone, собранная для Android, не знает метода `serve/start`
     * (ответ 404): серверные команды в неё не вошли. Узнав это один раз, больше
     * не спрашиваем — каждое открытие файла иначе начиналось бы с заведомо
     * неудачного запроса. Как только в приложение приедет библиотека с `serve`,
     * чтение кусками заработает само.
     */
    @Volatile
    var unsupported = false
        private set

    private val thread by lazy { HandlerThread("opendisk-stream").apply { start() } }

    /**
     * Сервер облака; поднимается при первом обращении. Исключение — сервер
     * не поднялся, и вызывающий вернётся к скачиванию целиком.
     */
    private fun endpoint(cloud: String): Endpoint = synchronized(lock) {
        endpoints[cloud]?.let { return it }
        if (unsupported) throw UnsupportedOperationException("в библиотеке rclone нет serve/start")
        val credentials = com.opendisk.bridge.RcCredentials.random()
        val info = try {
            runBlocking { client().serveHttp(RcloneClient.cloudFs(cloud), credentials.user, credentials.password) }
        } catch (e: com.opendisk.bridge.RcloneRcException) {
            if (e.statusCode == 404) unsupported = true
            throw e
        }
        Endpoint(info.id, "http://${info.addr}", credentials.basicAuthHeader()).also { endpoints[cloud] = it }
    }

    /**
     * Виртуальный файл для чтения; кидает исключение, если сервер не поднялся
     * или система не умеет (до Android 8 такого дескриптора нет).
     */
    fun open(storage: StorageManager, cloud: String, path: String, size: Long): ParcelFileDescriptor {
        val endpoint = endpoint(cloud)
        val url = urlFor(endpoint.baseUrl, path)
        val callback = object : ProxyFileDescriptorCallback() {
            override fun onGetSize(): Long = size

            override fun onRead(offset: Long, size: Int, data: ByteArray): Int {
                try {
                    return readRange(url, endpoint.authorization, offset, size, data)
                } catch (e: IOException) {
                    // Обрыв связи — ошибка ввода-вывода для читающего приложения,
                    // а не падение поставщика.
                    throw ErrnoException("onRead", OsConstants.EIO, e)
                }
            }

            override fun onRelease() = Unit
        }
        return storage.openProxyFileDescriptor(ParcelFileDescriptor.MODE_READ_ONLY, callback, Handler(thread.looper))
    }

    /** Останавливает серверы — при выходе процесса они не нужны. */
    fun stopAll() {
        val running = synchronized(lock) { endpoints.values.toList().also { endpoints.clear() } }
        running.forEach { endpoint -> runCatching { runBlocking { client().serveStop(endpoint.id) } } }
        runCatching { thread.quitSafely() }
    }

    companion object {
        /** Начиная с какого размера файл читается кусками, а не скачивается целиком. */
        const val STREAM_THRESHOLD_BYTES = 8L * 1024 * 1024

        /**
         * Адрес файла на сервере: каждая часть пути кодируется отдельно, косые
         * черты остаются. «+» вместо пробела в пути — не пробел, а плюс.
         */
        fun urlFor(base: String, path: String): String =
            base.trimEnd('/') + "/" + path.trim('/').split('/').joinToString("/") { part ->
                URLEncoder.encode(part, "UTF-8").replace("+", "%20")
            }

        /** Заголовок `Range` на байты `[offset, offset + size)`. */
        fun rangeHeader(offset: Long, size: Int): String = "bytes=$offset-${offset + size - 1}"

        /**
         * Читает до [size] байт с места [offset]. Возвращает сколько прочитано;
         * ноль — конец файла. Просят больше, чем осталось, — получают остаток.
         */
        fun readRange(url: String, authorization: String, offset: Long, size: Int, data: ByteArray): Int {
            val connection = URL(url).openConnection() as HttpURLConnection
            try {
                connection.setRequestProperty("Authorization", authorization)
                connection.setRequestProperty("Range", rangeHeader(offset, size))
                connection.connectTimeout = TIMEOUT_MILLIS
                connection.readTimeout = TIMEOUT_MILLIS
                when (connection.responseCode) {
                    HttpURLConnection.HTTP_PARTIAL -> Unit
                    // Конец файла: просили за его пределами.
                    416 -> return 0
                    // Сервер проигнорировал Range и отдал файл с начала: читать с нужного
                    // места пришлось бы, пропуская начало, — это скачивание целиком под
                    // видом чтения кусками. Лучше честная ошибка.
                    HttpURLConnection.HTTP_OK -> throw IOException("сервер не поддерживает чтение частями")
                    else -> throw IOException("HTTP ${connection.responseCode}")
                }
                var read = 0
                connection.inputStream.use { input ->
                    while (read < size) {
                        val count = input.read(data, read, size - read)
                        if (count < 0) break
                        read += count
                    }
                }
                return read
            } finally {
                connection.disconnect()
            }
        }

        private const val TIMEOUT_MILLIS = 30_000
    }
}
