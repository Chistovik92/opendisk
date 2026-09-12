package com.opendisk.android.app

import android.app.Application
import android.provider.DocumentsContract
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.opendisk.android.LibrcloneTransport
import com.opendisk.bridge.PublicLinkUnsupportedException
import com.opendisk.bridge.RcloneClient
import com.opendisk.bridge.RcloneRcException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Одно облако в списке. */
data class CloudRow(
    val name: String,
    val about: RcloneClient.AboutInfo? = null,
    val supportsLinks: Boolean = false,
)

/** Открытая папка облака. */
data class Browsing(
    val cloud: String,
    val path: String = "",
    val entries: List<RcloneClient.Entry> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
) {
    /** Путь на родительский уровень или null, если мы в корне. */
    val parent: String? get() = when {
        path.isEmpty() -> null
        !path.contains('/') -> ""
        else -> path.substringBeforeLast('/')
    }
}

/** Ссылка на файл — то же, что и на десктопе, тем же кодом. */
data class LinkState(
    val cloud: String,
    val remotePath: String,
    val url: String? = null,
    val error: String? = null,
    val busy: Boolean = true,
)

data class MobileState(
    val starting: Boolean = true,
    val clouds: List<CloudRow> = emptyList(),
    val error: String? = null,
    val browsing: Browsing? = null,
    val link: LinkState? = null,
    val adding: Boolean = false,
    /** Открыт экран настроек. */
    val settings: Boolean = false,
    /** Настройки телефона: оформление, язык и список подключённых облаков. */
    val preferences: MobilePreferences = MobilePreferences(),
    /** Версия встроенного rclone — показывается в настройках, как на десктопе. */
    val rcloneVersion: String? = null,
)

/**
 * Вся работа с rclone на телефоне.
 *
 * Заметно короче десктопного контроллера, и не потому, что урезано: на Android
 * нет ни процесса rcd, ни монтирования, ни автозапуска — то есть нет всего, из
 * чего состоит половина того контроллера. Разбор ответов при этом общий:
 * [RcloneClient] — тот же самый класс, что и на компьютере.
 */
class OpenDiskModel(application: Application) : AndroidViewModel(application) {

    private val settings = MobileSettings(application)

    /** Строки на выбранном языке — для сообщений, которые собирает сама модель. */
    val strings: MobileStrings get() = MobileStrings.of(_state.value.preferences.language)

    private val _state = MutableStateFlow(MobileState(preferences = settings.read()))
    val state: StateFlow<MobileState> = _state.asStateFlow()

    private var client: RcloneClient? = null

    init {
        viewModelScope.launch {
            // Первый вызов распаковывает нативную библиотеку — это заметно
            // дольше остальных и на главном потоке подвесило бы запуск.
            withContext(Dispatchers.IO) {
                LibrcloneTransport.useConfig(
                    File(getApplication<Application>().filesDir, "rclone.conf"),
                )
                client = RcloneClient(LibrcloneTransport.get())
            }
            _state.update { it.copy(starting = false) }
            // Уведомление переживает перезапуск телефона не само: система
            // его снимает, а список подключённых облаков остаётся.
            notifySystem(_state.value.preferences)
            reload()
        }
    }

    fun reload() {
        val api = client ?: return
        viewModelScope.launch {
            runCatching { api.version().version }.getOrNull()?.let { version ->
                _state.update { it.copy(rcloneVersion = version) }
            }
            try {
                val names = api.listRemotes()
                _state.update { current ->
                    current.copy(
                        error = null,
                        clouds = names.map { name ->
                            current.clouds.firstOrNull { it.name == name } ?: CloudRow(name)
                        },
                    )
                }

                // Место и возможность ссылок спрашиваем по одному и молча
                // проглатываем отказы: часть бэкендов этого не умеет, и общий
                // список не должен из-за них оставаться пустым.
                names.forEach { name ->
                    runCatching { api.about(name) }.getOrNull()?.let { about ->
                        updateCloud(name) { it.copy(about = about) }
                    }
                    runCatching { api.fsInfo(name) }.getOrNull()?.let { info ->
                        updateCloud(name) { it.copy(supportsLinks = info.supportsPublicLink) }
                    }
                }
            } catch (e: Exception) {
                _state.update { it.copy(error = describe(e)) }
            }
        }
    }

    // --- Просмотр содержимого -----------------------------------------------

    fun open(cloud: String, path: String = "") {
        val api = client ?: return
        _state.update { it.copy(browsing = Browsing(cloud = cloud, path = path)) }
        viewModelScope.launch {
            try {
                val entries = api.list(cloud, path)
                _state.update { current ->
                    // Пользователь мог успеть уйти в другую папку, пока шёл
                    // запрос: тогда ответ уже не про то, что на экране.
                    val open = current.browsing
                    if (open?.cloud != cloud || open.path != path) return@update current
                    current.copy(browsing = open.copy(entries = entries, loading = false))
                }
            } catch (e: Exception) {
                _state.update { current ->
                    val open = current.browsing ?: return@update current
                    current.copy(browsing = open.copy(loading = false, error = describe(e)))
                }
            }
        }
    }

    fun closeBrowser() {
        _state.update { it.copy(browsing = null) }
    }

    // --- Ссылки --------------------------------------------------------------

    fun requestLink(cloud: String, entry: RcloneClient.Entry) {
        val api = client ?: return
        _state.update {
            it.copy(link = LinkState(cloud = cloud, remotePath = entry.path))
        }
        viewModelScope.launch {
            try {
                val url = api.publicLink(cloud, entry.path)
                updateLink { it.copy(url = url, busy = false) }
            } catch (e: PublicLinkUnsupportedException) {
                updateLink {
                    it.copy(
                        busy = false,
                        error = strings.linkNotSupported(e.remote),
                    )
                }
            } catch (e: Exception) {
                updateLink { it.copy(busy = false, error = describe(e)) }
            }
        }
    }

    fun dismissLink() {
        _state.update { it.copy(link = null) }
    }

    // --- Добавление облака ---------------------------------------------------

    fun startAdding() = _state.update { it.copy(adding = true) }

    fun cancelAdding() = _state.update { it.copy(adding = false) }

    /**
     * Добавляет облако. Пароли пропускаются через `core/obscure` — ровно как
     * на десктопе: rclone хранит их в «затемнённом» виде и открытый текст
     * в конфиге не примет.
     */
    fun addCloud(
        name: String,
        type: String,
        parameters: Map<String, String>,
        secretKeys: Set<String>,
        onDone: (String?) -> Unit,
    ) {
        val api = client ?: return
        viewModelScope.launch {
            try {
                val prepared = parameters
                    .mapValues { (_, value) -> value.trim() }
                    .filterValues { it.isNotEmpty() }
                    .mapValues { (key, value) ->
                        if (key in secretKeys) api.obscure(value) else value
                    }
                api.createRemote(name.trim(), type, prepared)
                _state.update { it.copy(adding = false) }
                reload()
                onDone(null)
            } catch (e: Exception) {
                onDone(describe(e))
            }
        }
    }

    fun deleteCloud(name: String) {
        val api = client ?: return
        viewModelScope.launch {
            runCatching { api.deleteRemote(name) }
                .onFailure { e -> _state.update { it.copy(error = describe(e)) } }
            // Удалённое облако не должно остаться подключённым: система
            // продолжила бы показывать его корень в «Файлах».
            val current = _state.value.preferences
            if (name in current.connected) {
                applyPreferences(current.copy(connected = current.connected - name))
            }
            reload()
        }
    }

    // --- Подключение ----------------------------------------------------------

    /**
     * Подключить облако — значит отдать его системе: подключённое видно в
     * «Файлах» и в окнах выбора файла. Монтированием диска, как на компьютере,
     * это быть не может — Android такого приложению не позволяет.
     */
    fun setConnected(cloud: String, connected: Boolean) {
        val current = _state.value.preferences
        applyPreferences(
            current.copy(
                connected = if (connected) current.connected + cloud else current.connected - cloud,
            ),
        )
    }

    // --- Настройки ------------------------------------------------------------

    fun openSettings() = _state.update { it.copy(settings = true) }

    fun closeSettings() = _state.update { it.copy(settings = false) }

    fun setTheme(theme: MobileTheme) =
        applyPreferences(_state.value.preferences.copy(theme = theme))

    fun setLanguage(language: MobileLanguage) =
        applyPreferences(_state.value.preferences.copy(language = language))

    /**
     * Сохраняет настройки и рассказывает о них системе.
     *
     * Список корней система держит у себя и сама его не перечитывает: пока ей
     * не сказали, что он изменился, подключённое облако в «Файлах» не появится
     * (а отключённое — не исчезнет).
     */
    private fun applyPreferences(updated: MobilePreferences) {
        settings.write(updated)
        _state.update { it.copy(preferences = updated) }
        notifySystem(updated)
    }

    private fun notifySystem(preferences: MobilePreferences) {
        val context = getApplication<Application>()
        runCatching {
            context.contentResolver.notifyChange(
                DocumentsContract.buildRootsUri(OpenDiskDocumentsProvider.AUTHORITY),
                null,
            )
        }
        ConnectedNotification.update(context, preferences.connected, strings)
    }

    // --- Мелочи --------------------------------------------------------------

    private fun updateCloud(name: String, transform: (CloudRow) -> CloudRow) {
        _state.update { current ->
            current.copy(clouds = current.clouds.map { if (it.name == name) transform(it) else it })
        }
    }

    private fun updateLink(transform: (LinkState) -> LinkState) {
        _state.update { current -> current.copy(link = current.link?.let(transform)) }
    }

    /** Текст ошибки от rclone, а не «что-то пошло не так». */
    private fun describe(e: Throwable): String =
        (e as? RcloneRcException)?.rcloneError ?: e.message ?: e::class.simpleName.orEmpty()
}

/**
 * Человекочитаемый размер. rclone отдаёт байты.
 *
 * Отрицательный — «неизвестно»: так rclone отдаёт размер папок. Показывать
 * вместо него ноль значило бы соврать.
 */
fun formatBytes(bytes: Long, strings: MobileStrings): String {
    if (bytes < 0) return ""
    if (bytes < 1024) return "$bytes ${strings.bytes}"
    var value = bytes.toDouble() / 1024
    var index = 0
    while (value >= 1024 && index < strings.sizeUnits.lastIndex) {
        value /= 1024
        index++
    }
    return String.format("%.1f %s", value, strings.sizeUnits[index])
}

/** «занято 1,6 ТБ из 1,8 ТБ» или пусто, если бэкенд ничего не сообщил. */
fun RcloneClient.AboutInfo.describe(strings: MobileStrings): String {
    val used = used ?: return total?.let { strings.totalOnly(formatBytes(it, strings)) }.orEmpty()
    val total = total ?: return strings.usedOnly(formatBytes(used, strings))
    return strings.usedOf(formatBytes(used, strings), formatBytes(total, strings))
}
