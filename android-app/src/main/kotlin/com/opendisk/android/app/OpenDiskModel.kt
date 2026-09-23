package com.opendisk.android.app

import android.app.Application
import android.provider.DocumentsContract
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.opendisk.android.LibrcloneTransport
import com.opendisk.android.RcloneOutput
import com.opendisk.bridge.CatalogService
import com.opendisk.bridge.CloudCatalog
import com.opendisk.bridge.OAuthLink
import com.opendisk.bridge.PublicLinkUnsupportedException
import com.opendisk.bridge.RcloneClient
import com.opendisk.bridge.RcloneRcException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
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

/** Вкладки внизу экрана. */
enum class MainTab { DISKS, ADD }

/** Открытая папка диска во встроенном файловом менеджере. */
data class Browsing(
    val disk: Disk,
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

/** Файл готов к открытию другим приложением — экран запускает для него окно выбора. */
data class OpenRequest(val file: File, val name: String, val share: Boolean)

data class MobileState(
    val starting: Boolean = true,
    val clouds: List<CloudRow> = emptyList(),
    val error: String? = null,
    val tab: MainTab = MainTab.DISKS,
    val browsing: Browsing? = null,
    val link: LinkState? = null,
    /** Скопированное или вырезанное — ждёт «Вставить». */
    val clip: FileClip? = null,
    /** Идёт долгая операция: копирование, удаление, скачивание. Текст — что именно. */
    val operation: String? = null,
    /** Короткое сообщение внизу экрана: готово или что пошло не так. */
    val notice: String? = null,
    val openRequest: OpenRequest? = null,
    /** Карты памяти и флешки. */
    val removableVolumes: List<Disk.Local> = emptyList(),
    /** На телефоне есть `su`. */
    val rootAvailable: Boolean = false,
    /** Человек дал root в менеджере root; null — ещё не спрашивали. */
    val rootGranted: Boolean? = null,
    /** Открыт экран настроек. */
    val settings: Boolean = false,
    /** Настройки телефона: оформление, язык и список подключённых облаков. */
    val preferences: MobilePreferences = MobilePreferences(),
    /** Версия встроенного rclone — показывается в настройках, как на десктопе. */
    val rcloneVersion: String? = null,
    /** Все сервисы, которые знает встроенный rclone, — нижняя часть списка. */
    val allServices: List<CatalogService> = emptyList(),
    /** Идёт вход через браузер; null — не идёт. */
    val signIn: SignInState? = null,
)

/** Вход через браузер в процессе. */
data class SignInState(
    val cloud: String,
    /** Ссылка подтверждения; null — rclone её ещё не напечатал. */
    val link: String? = null,
    /** Человек нажал «Отмена»: ошибку «access_denied» показывать не нужно. */
    val cancelled: Boolean = false,
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
            notifySystem()
            reload()
        }

        // Значок в шторке следит за состоянием сам: облака, их место,
        // подключения и вход через браузер меняются в разных местах модели,
        // и звать обновление из каждого значило бы однажды забыть одно из них.
        viewModelScope.launch {
            _state
                .map { current -> statusSnapshot(current) }
                .distinctUntilChanged()
                .collect { StatusService.sync(getApplication(), it) }
        }
    }

    /**
     * Подключённые — из настроек, а не из прочитанного списка облаков.
     *
     * Список rclone читает несколько секунд после запуска, и до 0.5.8 всё
     * это время сводка говорила «подключённых нет»: служба останавливалась,
     * значок пропадал, а через пару секунд появлялся снова. Облака, которые
     * человек отдал системе, известны сразу — они в настройках.
     */
    private fun statusSnapshot(current: MobileState): StatusSnapshot {
        val loaded = current.clouds.associateBy { it.name }
        val names = (current.clouds.map { it.name } + current.preferences.connected).distinct()
        return StatusSnapshot(
            clouds = names.map { name ->
                StatusSnapshot.CloudStatus(
                    name = name,
                    connected = name in current.preferences.connected,
                    space = loaded[name]?.about?.describe(strings).orEmpty(),
                )
            },
            signingIn = current.signIn?.cloud,
            alwaysOn = current.preferences.statusIcon,
        )
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

    // --- Файловый менеджер ---------------------------------------------------

    fun selectTab(tab: MainTab) {
        _state.update { it.copy(tab = tab, browsing = if (tab == MainTab.DISKS) it.browsing else null) }
        if (tab == MainTab.ADD) loadAllServices()
        if (tab == MainTab.DISKS) refreshVolumes()
    }

    /** Карты памяти появляются и пропадают, а `su` могут поставить, пока приложение живо. */
    fun refreshVolumes() {
        val context = getApplication<Application>()
        viewModelScope.launch {
            val (volumes, root) = withContext(Dispatchers.IO) {
                LocalVolumes.removable(context) to RootShell.available()
            }
            _state.update { it.copy(removableVolumes = volumes, rootAvailable = root) }
        }
    }

    fun open(disk: Disk, path: String = "") {
        _state.update { it.copy(browsing = Browsing(disk = disk, path = path)) }
        viewModelScope.launch {
            try {
                // root спрашиваем при первом входе: окно менеджера root должно
                // появиться в ответ на действие человека, а не на запуск.
                if (disk == Disk.Root && _state.value.rootGranted != true) {
                    val granted = withContext(Dispatchers.IO) { RootShell.requestAccess() }
                    _state.update { it.copy(rootGranted = granted) }
                    if (!granted) error(strings.rootDenied)
                }
                val entries = listDisk(disk, path)
                _state.update { current ->
                    // Пользователь мог успеть уйти в другую папку, пока шёл
                    // запрос: тогда ответ уже не про то, что на экране.
                    val open = current.browsing
                    if (open?.disk != disk || open.path != path) return@update current
                    current.copy(browsing = open.copy(entries = entries, loading = false))
                }
            } catch (e: Exception) {
                _state.update { current ->
                    val open = current.browsing ?: return@update current
                    if (open.disk != disk || open.path != path) return@update current
                    current.copy(browsing = open.copy(loading = false, error = describe(e)))
                }
            }
        }
    }

    fun refreshFolder() {
        val current = _state.value.browsing ?: return
        open(current.disk, current.path)
    }

    fun closeBrowser() {
        _state.update { it.copy(browsing = null) }
    }

    fun createFolder(name: String) {
        val open = _state.value.browsing ?: return
        val clean = name.trim().trim('/')
        if (clean.isEmpty()) return
        runOperation(strings.creatingFolder) {
            mkdirOn(open.disk, childPath(open.path, clean))
        }
    }

    fun rename(entry: RcloneClient.Entry, newName: String) {
        val open = _state.value.browsing ?: return
        val clean = newName.trim().trim('/')
        if (clean.isEmpty() || clean == entry.name) return
        val parent = entry.path.substringBeforeLast('/', "")
        runOperation(strings.renaming) {
            transfer(open.disk, entry, open.disk, childPath(parent, clean), move = true)
        }
    }

    fun delete(entry: RcloneClient.Entry) {
        val open = _state.value.browsing ?: return
        runOperation(strings.deleting(entry.name)) { deleteOn(open.disk, entry) }
    }

    /** «Копировать» или «Вырезать»: запоминаем, вставка — в любой папке любого диска. */
    fun clip(entry: RcloneClient.Entry, move: Boolean) {
        val open = _state.value.browsing ?: return
        _state.update { it.copy(clip = FileClip(open.disk, entry, move)) }
    }

    fun cancelClip() = _state.update { it.copy(clip = null) }

    fun paste() {
        val clip = _state.value.clip ?: return
        val open = _state.value.browsing ?: return
        var target = childPath(open.path, clip.entry.name)
        if (clip.disk == open.disk && target == clip.entry.path) {
            // Вырезать и вставить туда же — делать нечего.
            if (clip.move) {
                _state.update { it.copy(clip = null) }
                return
            }
            // Копия рядом с оригиналом — под другим именем, а не поверх него.
            target = childPath(open.path, copyName(clip.entry.name, open.entries.map { it.name }.toSet()))
        }
        // Папку в саму себя не положить: rclone ушёл бы в бесконечное копирование.
        if (clip.disk == open.disk && clip.entry.isDir && (open.path + "/").startsWith(clip.entry.path + "/")) {
            _state.update { it.copy(notice = strings.cannotPasteIntoItself) }
            return
        }
        val label = if (clip.move) strings.moving(clip.entry.name) else strings.copying(clip.entry.name)
        runOperation(label) {
            transfer(clip.disk, clip.entry, open.disk, target, clip.move)
            _state.update { it.copy(clip = null) }
        }
    }

    /** Скачать в память телефона — в «Download/OpenDisk», где его найдёт любое приложение. */
    fun download(entry: RcloneClient.Entry) {
        val open = _state.value.browsing ?: return
        val phone = LocalVolumes.primary()
        runOperation(strings.downloading(entry.name), refresh = false) {
            mkdirOn(phone, DOWNLOAD_DIR)
            transfer(open.disk, entry, phone, childPath(DOWNLOAD_DIR, entry.name), move = false)
            _state.update { it.copy(notice = strings.downloaded("$DOWNLOAD_DIR/${entry.name}")) }
        }
    }

    /**
     * Открыть файл в другом приложении или отправить.
     *
     * Файл из облака или из-под root сначала копируется в кэш приложения:
     * другое приложение получает от системы обычный файл, а не адрес в облаке.
     */
    fun openFile(entry: RcloneClient.Entry, share: Boolean = false) {
        val open = _state.value.browsing ?: return
        runOperation(strings.preparingFile(entry.name), refresh = false) {
            val file = localCopyOf(open.disk, entry)
            _state.update { it.copy(openRequest = OpenRequest(file, entry.name, share)) }
        }
    }

    fun openRequestHandled() = _state.update { it.copy(openRequest = null) }

    fun showNotice(text: String) = _state.update { it.copy(notice = text) }

    fun backgroundAsked(): Boolean = settings.backgroundAsked

    fun markBackgroundAsked() {
        settings.backgroundAsked = true
    }

    fun storageAccessAsked(): Boolean = settings.storageAccessAsked

    fun markStorageAccessAsked() {
        settings.storageAccessAsked = true
    }

    fun dismissNotice() = _state.update { it.copy(notice = null) }

    /**
     * Долгая операция с индикатором. Ошибка не роняет экран, а приходит
     * сообщением: файловый менеджер, который закрывается от «нет места»,
     * хуже, чем тот, который об этом сообщает.
     */
    private fun runOperation(label: String, refresh: Boolean = true, block: suspend () -> Unit) {
        if (_state.value.operation != null) return
        _state.update { it.copy(operation = label) }
        viewModelScope.launch {
            try {
                block()
            } catch (e: Exception) {
                _state.update { it.copy(notice = describe(e)) }
            } finally {
                _state.update { it.copy(operation = null) }
                if (refresh) refreshFolder()
            }
        }
    }

    // Операции по видам дисков. Облака и память телефона — через rclone,
    // root — через su; перенос между ними — через промежуточную копию.

    private suspend fun listDisk(disk: Disk, path: String): List<RcloneClient.Entry> {
        val fs = disk.fs ?: return withContext(Dispatchers.IO) { RootShell.list(disk.absolutePath(path)!!) }
        return api().listFs(fs, path)
    }

    private suspend fun mkdirOn(disk: Disk, path: String) {
        val fs = disk.fs ?: return root { RootShell.mkdir(disk.absolutePath(path)!!) }
        api().mkdir(fs, path)
    }

    private suspend fun deleteOn(disk: Disk, entry: RcloneClient.Entry) {
        val fs = disk.fs ?: return root { RootShell.delete(disk.absolutePath(entry.path)!!) }
        if (entry.isDir) api().purge(fs, entry.path) else api().deleteFile(fs, entry.path)
    }

    /** Копирует или переносит файл либо папку с диска на диск. */
    private suspend fun transfer(
        from: Disk,
        entry: RcloneClient.Entry,
        to: Disk,
        targetPath: String,
        move: Boolean,
    ) {
        val srcFs = from.fs
        val dstFs = to.fs
        if (srcFs != null && dstFs != null) {
            val api = api()
            when {
                entry.isDir && move -> api.moveDir(srcFs, entry.path, dstFs, targetPath)
                entry.isDir -> api.copyDir(srcFs, entry.path, dstFs, targetPath)
                move -> api.moveFile(srcFs, entry.path, dstFs, targetPath)
                else -> api.copyFile(srcFs, entry.path, dstFs, targetPath)
            }
            return
        }

        val srcAbs = from.absolutePath(entry.path)
        val dstAbs = to.absolutePath(targetPath)
        when {
            // Оба конца на устройстве: su справится сам.
            srcAbs != null && dstAbs != null -> root {
                if (move) RootShell.move(srcAbs, dstAbs) else RootShell.copy(srcAbs, dstAbs)
            }
            // Из облака под root: сначала в кэш приложения, оттуда через su.
            srcAbs == null && dstAbs != null -> withStage { stage ->
                transfer(from, entry, stage, entry.name, move = false)
                root { RootShell.copy(joinPath(stage.root, entry.name), dstAbs) }
                if (move) deleteOn(from, entry)
            }
            // Из-под root в облако: su копирует в кэш и отдаёт копию приложению.
            srcAbs != null -> withStage { stage ->
                val uid = getApplication<Application>().applicationInfo.uid
                root { RootShell.copyOut(srcAbs, joinPath(stage.root, entry.name), uid) }
                transfer(stage, entry.copy(path = entry.name), to, targetPath, move = false)
                if (move) root { RootShell.delete(srcAbs) }
            }
            else -> error("unsupported transfer")
        }
    }

    /** Обычный файл на устройстве с содержимым записи — для открытия в другом приложении. */
    private suspend fun localCopyOf(disk: Disk, entry: RcloneClient.Entry): File {
        if (disk is Disk.Local) return File(disk.absolutePath(entry.path)!!)
        val dir = File(getApplication<Application>().cacheDir, OPEN_DIR)
        withContext(Dispatchers.IO) {
            // Открытые раньше файлы не копим: кэш не должен расти от просмотров.
            dir.deleteRecursively()
            dir.mkdirs()
        }
        transfer(disk, entry, Disk.Local(dir.absolutePath, removable = false), entry.name, move = false)
        return File(dir, entry.name)
    }

    private suspend fun withStage(block: suspend (Disk.Local) -> Unit) {
        val dir = File(getApplication<Application>().cacheDir, "stage/${System.nanoTime()}")
        withContext(Dispatchers.IO) { dir.mkdirs() }
        try {
            block(Disk.Local(dir.absolutePath, removable = false))
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { dir.deleteRecursively() }
        }
    }

    private suspend fun root(command: () -> RootShell.Result) {
        val result = withContext(Dispatchers.IO) { command() }
        if (!result.ok) error(result.output.trim().ifEmpty { "su: ${result.exitCode}" })
    }

    private fun api(): RcloneClient = client ?: error(strings.starting)

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


    /**
     * Полный список сервисов из самого rclone — один раз за запуск: он не
     * меняется, пока не сменилась библиотека, а строится из сотни бэкендов.
     */
    fun loadAllServices() {
        val api = client ?: return
        if (_state.value.allServices.isNotEmpty()) return
        viewModelScope.launch {
            runCatching { api.providers() }.getOrNull()?.let { providers ->
                _state.update { it.copy(allServices = CloudCatalog.fromProviders(providers)) }
            }
        }
    }

    /**
     * Добавляет облако. Пароли пропускаются через `core/obscure` — ровно как
     * на десктопе: rclone хранит их в «затемнённом» виде и открытый текст
     * в конфиге не примет.
     *
     * Для сервисов со входом через браузер запрос `config/create` висит, пока
     * человек подтверждает доступ: rclone внутри приложения поднимает сервер
     * подтверждения и печатает ссылку. Ссылка перехватывается из его вывода
     * ([RcloneOutput]) и кладётся в [MobileState.signIn] — экран открывает её
     * во вкладке браузера.
     */
    fun addCloud(
        service: CatalogService,
        name: String,
        values: Map<String, String>,
        onDone: (String?) -> Unit,
    ) {
        val api = client ?: return
        val cloud = name.trim()
        val existedBefore = _state.value.clouds.any { it.name == cloud }
        val secretKeys = service.fields.filter { it.isPassword }.map { it.key }.toSet()

        // Ссылки, напечатанные раньше — например, при прошлой отменённой
        // попытке, — не наши: их сервер уже закрыт.
        val staleLinks = RcloneOutput.recentLines().mapNotNull(OAuthLink::find).toSet()
        // Слушатель вызывается из потока чтения вывода rclone, не с главного.
        val listener: (String) -> Unit = { line ->
            val link = OAuthLink.find(line)?.takeIf { it !in staleLinks }
            if (link != null) {
                var cancelledEarly = false
                _state.update { current ->
                    val signIn = current.signIn
                    if (signIn == null || signIn.link != null) return@update current
                    cancelledEarly = signIn.cancelled
                    current.copy(signIn = signIn.copy(link = link))
                }
                // «Отмену» могли нажать раньше, чем rclone напечатал ссылку, —
                // тогда отменять было нечем, и отменяем сейчас.
                if (cancelledEarly) OAuthLink.cancel(link)
            }
        }
        if (service.oauth) {
            _state.update { it.copy(signIn = SignInState(cloud = cloud)) }
            RcloneOutput.addListener(listener)
        }

        viewModelScope.launch {
            var created = false
            try {
                // Переносы строк режем и здесь, а не только в поле ввода:
                // rclone отказывается от значения с ними целиком, а причина
                // в его ответе видна только ему самому.
                val prepared = (service.fixed + values)
                    .mapValues { (_, value) -> oneLine(value) }
                    .filterValues { it.isNotEmpty() }
                    .mapValues { (key, value) ->
                        if (key in secretKeys) api.obscure(value) else value
                    }
                api.createRemote(cloud, service.backend, prepared)
                created = true
                // Добавили — показываем его среди дисков.
                _state.update { it.copy(tab = MainTab.DISKS, browsing = null, signIn = null) }
                reload()
                onDone(null)
            } catch (e: Exception) {
                val cancelled = _state.value.signIn?.cancelled == true
                _state.update { it.copy(signIn = null) }
                // Отменённый вход — не ошибка, говорить о нём нечего.
                onDone(if (cancelled) null else describe(e))
            } finally {
                RcloneOutput.removeListener(listener)
                // rclone записывает облако в конфиг до входа в браузер: без
                // этой уборки после отказа в списке оставалось бы облако без
                // токена — оно не открывается, а имя занимает.
                if (!created && !existedBefore) {
                    withContext(NonCancellable) {
                        runCatching { api.deleteRemote(cloud) }
                        reload()
                    }
                }
            }
        }
    }

    /**
     * Прерывает вход через браузер.
     *
     * Сам запрос `config/create` не отменяется ничем — rclone ждёт возврата из
     * браузера без срока. Но его сервер подтверждения принимает отказ в том же
     * виде, в каком его прислал бы сервис, и после этого запрос возвращается.
     */
    fun cancelSignIn() {
        val signIn = _state.value.signIn ?: return
        _state.update { it.copy(signIn = signIn.copy(cancelled = true)) }
        val link = signIn.link ?: return
        viewModelScope.launch(Dispatchers.IO) { OAuthLink.cancel(link) }
    }

    fun deleteCloud(name: String) {
        val api = client ?: return
        viewModelScope.launch {
            runCatching { api.deleteRemote(name) }
                .onFailure { e -> _state.update { it.copy(error = describe(e)) } }
            // Удалённое облако не должно остаться ни открытым, ни в буфере.
            _state.update { current ->
                current.copy(
                    browsing = current.browsing?.takeIf { it.disk != Disk.Cloud(name) },
                    clip = current.clip?.takeIf { it.disk != Disk.Cloud(name) },
                )
            }
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

    fun setStatusIcon(enabled: Boolean) =
        applyPreferences(_state.value.preferences.copy(statusIcon = enabled))

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
        notifySystem()
    }

    private fun notifySystem() {
        val context = getApplication<Application>()
        runCatching {
            context.contentResolver.notifyChange(
                DocumentsContract.buildRootsUri(OpenDiskDocumentsProvider.AUTHORITY),
                null,
            )
        }
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

    private companion object {
        /** Куда скачиваются файлы в памяти телефона. */
        const val DOWNLOAD_DIR = "Download/OpenDisk"

        /** Папка кэша для файлов, открытых в других приложениях. */
        const val OPEN_DIR = "open"
    }
}

/**
 * Имя для копии рядом с оригиналом: «отчёт.pdf» → «отчёт (2).pdf».
 * Расширение остаётся в конце — иначе файл перестал бы открываться.
 */
fun copyName(name: String, taken: Set<String>): String {
    val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
    val base = name.substring(0, dot)
    val extension = name.substring(dot)
    var index = 2
    while ("$base ($index)$extension" in taken) index++
    return "$base ($index)$extension"
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
