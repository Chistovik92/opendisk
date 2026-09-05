package com.opendisk.bridge

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.Closeable

/**
 * Ошибка, которую вернул сам rclone через RC API.
 *
 * rclone отвечает на неудачный вызов ненулевым HTTP-статусом и телом вида
 * `{"error": "...", "path": "...", "status": 500}` — разбираем его, чтобы
 * до GUI доходил текст причины, а не голое "500".
 */
class RcloneRcException(
    val endpoint: String,
    val statusCode: Int,
    val rcloneError: String,
) : RuntimeException("rclone RC $endpoint → $statusCode: $rcloneError")

/**
 * Типобезопасная обёртка над rclone RC (Remote Control) HTTP API.
 *
 * Список используемых эндпоинтов и их назначение — в docs/ARCHITECTURE.md.
 * Официальная документация rclone RC: https://rclone.org/rc/
 */
class RcloneClient(
    private val baseUrl: String,
    private val httpClient: HttpClient = defaultHttpClient(),
) : Closeable {

    // --- Облака (remotes) ---------------------------------------------------

    @Serializable
    data class RemoteInfo(val name: String, val type: String)

    @Serializable
    private data class ListRemotesResponse(val remotes: List<String> = emptyList())

    suspend fun listRemotes(): List<String> {
        val response: ListRemotesResponse = call("config/listremotes")
        return response.remotes
    }

    suspend fun createRemote(name: String, type: String, parameters: Map<String, String>) {
        call<JsonObject>(
            "config/create",
            buildJsonObject {
                put("name", name)
                put("type", type)
                putJsonObject("parameters") {
                    parameters.forEach { (key, value) -> put(key, value) }
                }
            },
        )
    }

    /**
     * Настройки одного облака. Набор полей зависит от бэкенда (у Яндекс.Диска
     * одни, у S3 другие), поэтому возвращаем сырой объект, а не фиксированную
     * модель — интерпретирует его тот, кто знает тип облака.
     */
    suspend fun getRemote(name: String): JsonObject =
        call("config/get", buildJsonObject { put("name", name) })

    suspend fun deleteRemote(name: String) {
        call<JsonObject>("config/delete", buildJsonObject { put("name", name) })
    }

    // --- Монтирование -------------------------------------------------------

    suspend fun mount(remoteName: String, mountPoint: String, vfsCacheMode: String = "writes") {
        call<JsonObject>(
            "mount/mount",
            buildJsonObject {
                // rclone ожидает имя облака с двоеточием — иначе это трактуется как путь.
                put("fs", "$remoteName:")
                put("mountPoint", mountPoint)
                putJsonObject("vfsOpt") { put("CacheMode", vfsCacheMode) }
            },
        )
    }

    suspend fun unmount(mountPoint: String) {
        call<JsonObject>("mount/unmount", buildJsonObject { put("mountPoint", mountPoint) })
    }

    @Serializable
    data class MountInfo(val Fs: String, val MountPoint: String)

    @Serializable
    private data class ListMountsResponse(val mountPoints: List<MountInfo> = emptyList())

    suspend fun listMounts(): List<MountInfo> {
        val response: ListMountsResponse = call("mount/listmounts")
        return response.mountPoints
    }

    // --- Прогресс и состояние ----------------------------------------------

    /**
     * Сводная статистика переносов. Поля названы как в rclone, чтобы не
     * расходиться с его документацией; отсутствующие в ответе значения
     * заполняются нулями — набор полей зависит от версии rclone.
     */
    @Serializable
    data class CoreStats(
        val bytes: Long = 0,
        val speed: Double = 0.0,
        val transfers: Long = 0,
        val errors: Long = 0,
        val checks: Long = 0,
        val elapsedTime: Double = 0.0,
        val eta: Long? = null,
        val transferring: List<TransferInfo> = emptyList(),
    )

    @Serializable
    data class TransferInfo(
        val name: String = "",
        val size: Long = 0,
        val bytes: Long = 0,
        val percentage: Int = 0,
        val speed: Double = 0.0,
    )

    /**
     * Статистика по всем операциям или по конкретной группе.
     * Группа асинхронной операции выглядит как `job/<id>` — см. [jobStatus].
     */
    suspend fun coreStats(group: String? = null): CoreStats =
        call(
            "core/stats",
            buildJsonObject { group?.let { put("group", it) } },
        )

    @Serializable
    data class JobStatus(
        val id: Long = 0,
        val finished: Boolean = false,
        val success: Boolean = false,
        val error: String = "",
        val duration: Double = 0.0,
        val group: String = "",
    )

    suspend fun jobStatus(jobId: Long): JobStatus =
        call("job/status", buildJsonObject { put("jobid", jobId) })

    suspend fun stopJob(jobId: Long) {
        call<JsonObject>("job/stop", buildJsonObject { put("jobid", jobId) })
    }

    @Serializable
    data class VersionInfo(
        val version: String = "",
        val os: String = "",
        val arch: String = "",
        val goVersion: String = "",
    )

    /** Используется как проба готовности `rclone rcd` — см. [RcloneProcess.awaitReady]. */
    suspend fun version(): VersionInfo = call("core/version")

    override fun close() {
        httpClient.close()
    }

    // --- Транспорт ----------------------------------------------------------

    /**
     * Тело запроса строится как [JsonObject], а не как `Map<String, Any?>`:
     * у произвольной Map с разнородными значениями нет сериализатора, и такой
     * вызов падал бы в рантайме на SerializationException.
     */
    private suspend inline fun <reified T> call(
        endpoint: String,
        body: JsonObject = JsonObject(emptyMap()),
    ): T {
        val response = httpClient.post("$baseUrl/$endpoint") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        if (!response.status.isSuccess()) {
            throw RcloneRcException(
                endpoint = endpoint,
                statusCode = response.status.value,
                rcloneError = extractError(response.bodyAsText()),
            )
        }
        return response.body()
    }

    companion object {
        fun defaultHttpClient(): HttpClient = HttpClient(CIO) {
            install(ContentNegotiation) {
                json(lenientJson)
            }
        }

        private val lenientJson = Json {
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
                (lenientJson.parseToJsonElement(rawBody) as? JsonObject)
                    ?.get("error")
                    ?.let { element -> element.toString().trim('"') }
            }.getOrNull() ?: fallback
        }
    }
}
