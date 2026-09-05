package com.opendisk.bridge

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.Closeable

/**
 * Способ доставки вызова до rclone.
 *
 * У RC API один и тот же вид на всех платформах — «метод плюс объект на входе,
 * объект на выходе», — а вот доставка разная. На десктопе рядом живёт процесс
 * `rclone rcd`, и вызов уходит к нему по HTTP. На Android отдельный процесс
 * поднять нельзя: система не даёт приложению исполнять свои файлы, поэтому там
 * rclone линкуется в само приложение (librclone) и вызывается функцией внутри
 * процесса.
 *
 * Разбор ответов — то есть [RcloneClient] целиком — от этого различия не
 * зависит и остаётся общим.
 */
interface RcloneTransport : Closeable {

    /**
     * Выполняет вызов RC API и возвращает разобранный ответ.
     *
     * @throws RcloneRcException если rclone ответил ошибкой.
     */
    suspend fun rpc(endpoint: String, body: JsonObject): JsonObject
}

/**
 * Транспорт до отдельно запущенного `rclone rcd` по HTTP.
 *
 * Адрес берётся у [RcloneProcess.rcBaseUrl]. Аутентификации нет — процесс
 * поднимается на петлевом интерфейсе со случайным портом и живёт ровно столько,
 * сколько приложение.
 */
class HttpRcloneTransport(
    private val baseUrl: String,
    private val httpClient: HttpClient = defaultHttpClient(),
) : RcloneTransport {

    override suspend fun rpc(endpoint: String, body: JsonObject): JsonObject {
        val response = httpClient.post("$baseUrl/$endpoint") {
            contentType(ContentType.Application.Json)
            // Тело сериализуем сами, а не отдаём объектом на откуп ContentNegotiation:
            // клиент сюда можно передать любой, и без установленного плагина
            // ktor падает на отправке с невнятным "Fail to prepare request body".
            setBody(rcloneJson.encodeToString(JsonObject.serializer(), body))
        }
        val text = response.bodyAsText()
        if (!response.status.isSuccess()) {
            throw RcloneRcException(
                endpoint = endpoint,
                statusCode = response.status.value,
                rcloneError = extractError(text),
            )
        }
        return parseObject(endpoint, text)
    }

    override fun close() {
        httpClient.close()
    }

    companion object {
        /**
         * Сколько ждём ответа от rcd.
         *
         * Обычные вызовы отвечают мгновенно, но создание облака с OAuth
         * (Яндекс.Диск, Google Drive, Dropbox) держит запрос открытым всё то
         * время, пока пользователь подтверждает доступ в браузере. Таймаут CIO
         * по умолчанию на это не рассчитан и обрывает запрос — облако не
         * создаётся, а причина никак не видна.
         */
        const val REQUEST_TIMEOUT_MILLIS = 10 * 60 * 1000L

        fun defaultHttpClient(): HttpClient = HttpClient(CIO) {
            engine {
                requestTimeout = REQUEST_TIMEOUT_MILLIS
            }
        }
    }
}

/**
 * Общий для всех транспортов разбор тела ответа.
 *
 * Вынесено из транспорта, а не оставлено в HTTP-варианте: librclone возвращает
 * такой же JSON такой же формы, и дублировать разбор ошибок ради этого
 * не нужно.
 */
internal val rcloneJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

/**
 * Достаёт поле `error` из тела ответа. Если тело не разобралось
 * (rclone может ответить и просто текстом), возвращаем его как есть —
 * лучше показать сырой ответ, чем потерять причину.
 */
internal fun extractError(rawBody: String): String {
    val fallback = rawBody.trim().ifEmpty { "пустой ответ" }
    return runCatching {
        (rcloneJson.parseToJsonElement(rawBody) as? JsonObject)
            ?.get("error")
            ?.let { element -> element.toString().trim('"') }
    }.getOrNull() ?: fallback
}

/**
 * Успешный ответ RC API — всегда объект. Если пришло что-то другое, это не
 * «пустой результат», а сломанная связь с rclone, и молчать об этом нельзя:
 * дальше по коду такой ответ превратился бы в невнятную ошибку разбора.
 */
internal fun parseObject(endpoint: String, rawBody: String): JsonObject {
    val element = runCatching { rcloneJson.parseToJsonElement(rawBody) }.getOrNull()
    return element as? JsonObject ?: throw RcloneRcException(
        endpoint = endpoint,
        statusCode = 200,
        rcloneError = "ожидался JSON-объект, получено: ${rawBody.take(200)}",
    )
}
