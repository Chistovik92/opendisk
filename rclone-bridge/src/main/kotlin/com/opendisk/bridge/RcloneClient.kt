package com.opendisk.bridge

import io.ktor.client.HttpClient
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
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
 * Конфиг зашифрован, а пароль не задан или неверен.
 *
 * Отделено от прочих ошибок RC, потому что реакция принципиально другая:
 * не "что-то сломалось", а "спроси у пользователя пароль и перезапусти rcd".
 */
class RcloneConfigLockedException(cause: RcloneRcException) :
    RuntimeException("Конфиг rclone зашифрован: нужен пароль. ${cause.rcloneError}", cause)

/**
 * Типобезопасная обёртка над rclone RC (Remote Control) HTTP API.
 *
 * Список используемых эндпоинтов и их назначение — в docs/ARCHITECTURE.md.
 * Официальная документация rclone RC: https://rclone.org/rc/
 */
class RcloneClient(private val transport: RcloneTransport) : Closeable {

    /**
     * Клиент к отдельно запущенному `rclone rcd` — вариант для десктопа.
     * На Android вместо этого передаётся транспорт поверх librclone.
     */
    constructor(
        baseUrl: String,
        httpClient: HttpClient = HttpRcloneTransport.defaultHttpClient(),
    ) : this(HttpRcloneTransport(baseUrl, httpClient))

    // --- Облака (remotes) ---------------------------------------------------

    @Serializable
    data class RemoteInfo(val name: String, val type: String)

    @Serializable
    private data class ListRemotesResponse(val remotes: List<String> = emptyList())

    suspend fun listRemotes(): List<String> {
        val response: ListRemotesResponse = call("config/listremotes")
        return response.remotes
    }

    /**
     * Проверяет, что rcd действительно может прочитать конфиг.
     *
     * Одного `RcloneProcess.awaitReady()` мало: rcd поднимает порт и отвечает на
     * `core/version` даже тогда, когда конфиг зашифрован, а пароля нет —
     * расшифровка откладывается до первого обращения к эндпоинтам `config`.
     * Поэтому после
     * запуска нужно сходить в конфиг явно и разобрать причину отказа.
     *
     * @throws RcloneConfigLockedException если нужен или неверен пароль конфига.
     */
    suspend fun ensureConfigReadable() {
        try {
            listRemotes()
        } catch (e: RcloneRcException) {
            if (e.rcloneError.contains(DECRYPT_FAILURE_MARKER, ignoreCase = true)) {
                throw RcloneConfigLockedException(e)
            }
            throw e
        }
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

    /**
     * Переименовывает облако.
     *
     * Отдельного метода в RC API нет, поэтому читаем настройки, создаём копию
     * под новым именем и удаляем старую. Порядок важен: сначала создаём, потом
     * удаляем — если создание не удалось, исходное облако останется на месте.
     *
     * `noObscure` обязателен: значения из конфига уже «затемнены», и без него
     * rclone обработал бы их повторно, превратив пароль в мусор.
     */
    suspend fun renameRemote(from: String, to: String) {
        val existing = getRemote(from)
        val type = existing["type"]?.toString()?.trim('"')
            ?: throw IllegalStateException("у облака '$from' не указан тип — переименование невозможно")

        call<JsonObject>(
            "config/create",
            buildJsonObject {
                put("name", to)
                put("type", type)
                putJsonObject("parameters") {
                    existing.forEach { (key, value) ->
                        if (key != "type") put(key, value)
                    }
                }
                putJsonObject("opt") { put("noObscure", true) }
            },
        )
        deleteRemote(from)
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

    /**
     * Снимает блокировку зашифрованного конфига на уже запущенном rcd —
     * перезапускать процесс не нужно.
     *
     * `config/unlock` возвращает пустой ответ независимо от того, подошёл пароль
     * или нет: проверка происходит только при следующем обращении к конфигу.
     * Поэтому сразу же проверяем результат сами. Неверный пароль ничего не ломает —
     * можно вызвать повторно с правильным.
     *
     * @throws RcloneConfigLockedException если пароль не подошёл.
     */
    suspend fun unlockConfig(password: String) {
        call<JsonObject>("config/unlock", buildJsonObject { put("configPassword", password) })
        ensureConfigReadable()
    }

    /**
     * Превращает пароль в «затемнённый» вид, в котором rclone хранит секреты
     * в конфиге. Класть пароль в `config/create` открытым текстом нельзя —
     * rclone ожидает именно обработанное значение.
     */
    suspend fun obscure(clear: String): String {
        val response: ObscureResponse = call("core/obscure", buildJsonObject { put("clear", clear) })
        return response.obscured
    }

    @Serializable
    private data class ObscureResponse(val obscured: String)

    // --- Провайдеры и место -------------------------------------------------

    /** Описание бэкенда rclone и его настроек — основа мастера добавления облака. */
    @Serializable
    data class Provider(
        @SerialName("Name") val name: String,
        @SerialName("Description") val description: String = "",
        @SerialName("Options") val options: List<Option> = emptyList(),
    )

    @Serializable
    data class Option(
        @SerialName("Name") val name: String,
        @SerialName("Help") val help: String = "",
        @SerialName("Required") val required: Boolean = false,
        @SerialName("IsPassword") val isPassword: Boolean = false,
        @SerialName("Advanced") val advanced: Boolean = false,
    ) {
        /** Первая строка справки: в rclone Help многострочный, в поле формы нужна короткая. */
        val shortHelp: String get() = help.lineSequence().firstOrNull().orEmpty()
    }

    @Serializable
    private data class ProvidersResponse(val providers: List<Provider> = emptyList())

    suspend fun providers(): List<Provider> {
        val response: ProvidersResponse = call("config/providers")
        return response.providers
    }

    /**
     * Занятое и свободное место на облаке. Поля необязательные: не каждый бэкенд
     * умеет это сообщать, и тогда rclone просто не присылает соответствующий ключ.
     */
    @Serializable
    data class AboutInfo(
        val total: Long? = null,
        val used: Long? = null,
        val free: Long? = null,
    )

    suspend fun about(remoteName: String): AboutInfo =
        call("operations/about", buildJsonObject { put("fs", "$remoteName:") })

    /**
     * Ограничение скорости. rclone умеет ограничивать только глобально —
     * на все переносы сразу, а не по отдельным облакам.
     */
    @Serializable
    data class BandwidthLimit(
        val rate: String = "off",
        val bytesPerSecond: Long = -1,
    ) {
        val isUnlimited: Boolean get() = bytesPerSecond < 0
    }

    /** Текущее ограничение скорости. */
    suspend fun bandwidthLimit(): BandwidthLimit = call("core/bwlimit")

    /**
     * Задаёт ограничение скорости в формате rclone: «1M», «500k», «off».
     * На некорректном значении rclone отвечает ошибкой — она долетит
     * до вызывающего как [RcloneRcException].
     */
    suspend fun setBandwidthLimit(rate: String): BandwidthLimit =
        call("core/bwlimit", buildJsonObject { put("rate", rate) })

    @Serializable
    private data class MountTypesResponse(val mountTypes: List<String> = emptyList())

    /**
     * Доступные на этой машине способы монтирования. Пустой список означает,
     * что монтировать нечем: на Windows не установлен WinFsp, на Linux нет FUSE.
     */
    suspend fun mountTypes(): List<String> {
        val response: MountTypesResponse = call("mount/types")
        return response.mountTypes
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
        transport.close()
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
    ): T = rcloneJson.decodeFromJsonElement(transport.rpc(endpoint, body))

    companion object {
        /**
         * Фрагмент, по которому опознаём отказ расшифровки конфига. rclone пишет
         * "unable to decrypt configuration ..." и в случае отсутствующего пароля,
         * и в случае неверного — обе ситуации для нас одинаковы.
         */
        private const val DECRYPT_FAILURE_MARKER = "unable to decrypt configuration"
    }
}
