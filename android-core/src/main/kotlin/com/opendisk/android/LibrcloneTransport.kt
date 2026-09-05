package com.opendisk.android

import com.opendisk.bridge.RcloneTransport
import com.opendisk.bridge.encodeRcloneRequest
import com.opendisk.bridge.parseRcloneResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import org.rclone.gomobile.Gomobile
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Транспорт до rclone, слинкованного внутрь приложения.
 *
 * На десктопе рядом работает отдельный процесс `rclone rcd`, и вызовы уходят
 * к нему по HTTP. На Android так нельзя: система не даёт приложению исполнять
 * свои файлы. Поэтому здесь тот же rclone, но собранный библиотекой
 * (`gomobile bind`), и вызов — обычная функция:
 *
 * ```
 * Gomobile.rcloneRPC(метод, входной_json) -> { Output: json, Status: HTTP-код }
 * ```
 *
 * Форма запросов и ответов та же, что и по HTTP, поэтому
 * [com.opendisk.bridge.RcloneClient] переиспользуется целиком.
 *
 * **Экземпляр должен быть один на процесс.** rclone инициализируется глобально
 * внутри нативной библиотеки, а библиотека загружается один раз на процесс —
 * второй экземпляр работал бы с тем же состоянием, но со своим представлением
 * о том, инициализировано оно или нет.
 */
class LibrcloneTransport private constructor() : RcloneTransport {

    override suspend fun rpc(endpoint: String, body: JsonObject): JsonObject {
        val input = encodeRcloneRequest(body)

        // Вызов синхронный и блокирующий: внутри работает Go со своим
        // планировщиком, и вызовы вроде config/create с OAuth держат поток
        // столько, сколько пользователь подтверждает доступ. На главном потоке
        // это приложение бы заморозило.
        val result = withContext(Dispatchers.IO) {
            Gomobile.rcloneRPC(endpoint, input)
        }

        // Status у gomobile — Go-шный int, а он отображается в Java long.
        // toInt() здесь не косметика: без него это просто не сойдётся по типам.
        return parseRcloneResponse(endpoint, result.status.toInt(), result.output.orEmpty())
    }

    /**
     * Ничего не делает — и это осознанно.
     *
     * `RcloneFinalize` завершает rclone на весь процесс, а библиотеку нельзя
     * загрузить второй раз: следующая попытка работать с облаками упала бы уже
     * без шансов на восстановление. Экземпляр здесь один на процесс и живёт
     * столько же, сколько процесс, поэтому закрывать нечего.
     *
     * Явное завершение — [shutdown], и звать его стоит только тогда, когда
     * процесс всё равно заканчивается.
     */
    override fun close() = Unit

    companion object {
        private val initialized = AtomicBoolean(false)

        @Volatile
        private var instance: LibrcloneTransport? = null

        /**
         * Отдаёт транспорт, инициализируя rclone при первом обращении.
         *
         * Загрузка нативной библиотеки — это распаковка ~100 МБ кода, поэтому
         * первый вызов заметно дольше остальных; делать его на главном потоке
         * не стоит.
         */
        @Synchronized
        fun get(): LibrcloneTransport {
            instance?.let { return it }

            if (initialized.compareAndSet(false, true)) {
                Gomobile.rcloneInitialize()
            }
            return LibrcloneTransport().also { instance = it }
        }

        /**
         * Завершает rclone. Вызывать имеет смысл только при завершении процесса:
         * после этого работать с облаками в этом процессе уже нельзя.
         *
         * Асинхронные задачи rclone при этом не отменяются — librclone их не
         * трогает, останавливать их нужно самим через `job/stop`.
         */
        @Synchronized
        fun shutdown() {
            if (initialized.compareAndSet(true, false)) {
                Gomobile.rcloneFinalize()
                instance = null
            }
        }
    }
}
