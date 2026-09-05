package com.opendisk.app

import com.opendisk.bridge.MountSupport
import com.opendisk.bridge.RcloneClient
import com.opendisk.bridge.RcloneConfigFile
import com.opendisk.bridge.RcloneConfigLockedException
import com.opendisk.bridge.RcloneProcess
import com.opendisk.bridge.RcloneRcException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Вся работа с rclone на стороне приложения: держит процесс rcd, клиент RC API
 * и состояние экрана. UI только читает [state] и дёргает методы — прямых вызовов
 * rclone в композаблах нет.
 */
class RcloneController(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    /**
     * Поиск бинарника и выбор конфига вынесены в параметры, чтобы контроллер
     * можно было прогнать в тестах на временном конфиге, не трогая настоящий
     * конфиг пользователя.
     */
    private val locateRclone: () -> RcloneProcess.Located? = { RcloneProcess.locate() },
    private val resolveConfig: () -> RcloneConfigFile = { RcloneConfigFile.default() },
    private val settings: AppSettings = AppSettings(AppSettings.defaultFile()),
) {
    /**
     * Строки для сообщений об ошибках. Контроллер не композабл, поэтому берёт
     * их сам и обновляет при смене языка в настройках.
     */
    private var strings: Strings = Strings.of(Language.fromCode(settings.global().language))

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /**
     * События для показа в трее. Именно поток, а не поле состояния:
     * уведомление показывается один раз в момент события, а не живёт
     * в состоянии экрана до следующего изменения.
     */
    private val _notifications = MutableSharedFlow<AppNotification>(extraBufferCapacity = 8)
    val notifications: SharedFlow<AppNotification> = _notifications.asSharedFlow()

    private var process: RcloneProcess? = null
    private var client: RcloneClient? = null

    /**
     * Точки монтирования, созданные этим приложением: облако → точка.
     *
     * Определять по полю `Fs` из `mount/listmounts` нельзя — его формат зависит
     * от бэкенда: для `local` приходит "имя://?/C:/путь", а для `alias` вообще
     * разрешённый путь без имени облака. А вот точку монтирования задаём мы сами,
     * и она в ответе всегда та же. rcd — наш дочерний процесс и умирает вместе
     * с приложением, поэтому все живые маунты созданы в этой же сессии.
     */
    private val ourMounts = ConcurrentHashMap<String, String>()

    fun start() {
        scope.launch {
            val located = locateRclone()
            if (located == null) {
                _state.update {
                    it.copy(
                        session = SessionState.Failed(strings.rcloneNotFound),
                    )
                }
                return@launch
            }

            val config = resolveConfig()
            _state.update {
                it.copy(
                    rcloneDescription = describeRclone(located),
                    configDescription = describeConfig(config),
                    configFilePath = config.path.absolutePath,
                    settingsFilePath = settings.filePath,
                )
            }

            val rcd = RcloneProcess(
                rclonePath = located.file.absolutePath,
                // Свободный порт, а не штатный 5572: на нём может сидеть чужой rcd
                // или наш собственный, переживший аварийное завершение.
                rcAddr = RcloneProcess.freeRcAddr(),
                config = config,
            )
            process = rcd

            try {
                rcd.start()
                rcd.awaitReady()
            } catch (e: IllegalStateException) {
                val message = e.message ?: strings.rcdFailedToStart
                _state.update {
                    it.copy(session = SessionState.Failed(message, rcd.recentOutput()))
                }
                _notifications.tryEmit(
                    AppNotification(title = strings.appDidNotStart, message = message),
                )
                return@launch
            }

            client = RcloneClient(rcd.rcBaseUrl)
            becomeReadyOrAskPassword()
        }
    }

    /**
     * Проверяет доступность конфига. Порт открывается и при зашифрованном конфиге,
     * поэтому именно здесь выясняется, нужен ли пароль.
     */
    private suspend fun becomeReadyOrAskPassword(wrongAttempt: Boolean = false) {
        val api = client ?: return
        try {
            api.ensureConfigReadable()
            _state.update {
                it.copy(session = SessionState.Ready, globalSettings = settings.global())
            }
            loadMountSupport()
            loadRcloneVersion()
            applyBandwidthLimit(settings.global().bandwidthLimit)
            loadProviders()
            reloadClouds()
            mountMarkedClouds()
        } catch (e: RcloneConfigLockedException) {
            _state.update { it.copy(session = SessionState.NeedPassword(wrongAttempt)) }
        } catch (e: RcloneRcException) {
            _state.update { it.copy(session = SessionState.Failed(e.rcloneError)) }
        }
    }

    fun submitPassword(password: String) {
        val api = client ?: return
        scope.launch {
            _state.update { it.copy(session = SessionState.Starting) }
            try {
                api.unlockConfig(password)
                becomeReadyOrAskPassword()
            } catch (e: RcloneConfigLockedException) {
                _state.update { it.copy(session = SessionState.NeedPassword(wrongAttempt = true)) }
            }
        }
    }

    /**
     * Проверяет систему напрямую, а не через RC-эндпоинт `mount/types`: тот на
     * Windows отвечает `["cmount"]` даже без установленного WinFsp, и кнопка
     * «Подключить» оказывалась активной, а монтирование падало с невнятной ошибкой.
     */
    /** Версия встроенного rclone — нужна только для окна «О приложении». */
    private suspend fun loadRcloneVersion() {
        val api = client ?: return
        val version = runCatching { api.version().version }.getOrNull() ?: return
        _state.update { it.copy(rcloneVersion = version) }
    }

    private fun loadMountSupport() {
        _state.update { it.copy(mount = MountSupport.check()) }
    }

    /**
     * Ставит недостающий драйвер встроенным установщиком. Пользователю не нужно
     * ничего искать и скачивать самому — установщик едет внутри дистрибутива.
     */
    fun installMountDriver() {
        val missing = state.value.mount as? MountSupport.Status.Missing ?: return
        val installer = missing.bundledInstaller ?: return

        scope.launch {
            _state.update { it.copy(installingMountDriver = true) }
            val result = withContext(Dispatchers.IO) { MountSupport.installBundled(installer) }
            _state.update {
                it.copy(
                    installingMountDriver = false,
                    mount = MountSupport.check(),
                    globalError = when (result) {
                        MountSupport.InstallResult.Installed -> null

                        MountSupport.InstallResult.Cancelled ->
                            strings.driverInstallCancelled(missing.what)

                        is MountSupport.InstallResult.Failed ->
                            strings.driverInstallFailed(missing.what, result.details)
                    },
                )
            }
        }
    }

    /**
     * Список бэкендов для мастера. Не критичен для работы: если запрос не удался,
     * просто останемся без мастера, а список облаков продолжит работать.
     */
    private suspend fun loadProviders() {
        val api = client ?: return
        val providers = runCatching { api.providers() }.getOrDefault(emptyList())
        val ordered = providers.sortedWith(
            compareBy(
                { POPULAR_PROVIDERS.indexOf(it.name).takeIf { index -> index >= 0 } ?: Int.MAX_VALUE },
                { it.name },
            ),
        )
        _state.update { it.copy(providers = ordered) }
    }

    /**
     * Подключает облака, отмеченные как «подключать при запуске».
     *
     * Ошибки не роняют запуск и не копятся в общей ошибке экрана: каждое
     * облако само показывает свою, а пользователь узнаёт об этом уведомлением.
     */
    private suspend fun mountMarkedClouds() {
        if (state.value.mount !is MountSupport.Status.Available) return
        val marked = settings.load().filterValues { it.mountOnStartup }.keys
        val existing = state.value.clouds.map { it.name }.toSet()

        marked.filter { it in existing }.forEach { name ->
            mountAndWait(name)
        }
    }

    private fun applyBandwidthLimit(rate: String) {
        val api = client ?: return
        scope.launch {
            try {
                api.setBandwidthLimit(rate)
            } catch (e: RcloneRcException) {
                _state.update {
                    it.copy(globalError = strings.bandwidthFailed(e.rcloneError))
                }
            }
        }
    }

    fun refresh() {
        scope.launch { reloadClouds() }
    }

    /** Сохраняет общие настройки: автозапуск и ограничение скорости. */
    fun updateGlobalSettings(updated: GlobalSettings) {
        val previous = settings.global()
        settings.updateGlobal(updated)
        strings = Strings.of(Language.fromCode(updated.language))
        _state.update {
            it.copy(
                globalSettings = updated,
                // Описания источника rclone и конфига уже переведены — обновляем
                // их вместе с языком, иначе шапка осталась бы на старом.
                rcloneDescription = locateRclone()?.let(::describeRclone).orEmpty(),
                configDescription = describeConfig(resolveConfig()),
            )
        }

        if (updated.autostart != previous.autostart) {
            if (!Autostart.setEnabled(updated.autostart)) {
                _state.update {
                    it.copy(
                        globalError = strings.autostartFailed,
                    )
                }
            }
        }
        if (updated.bandwidthLimit != previous.bandwidthLimit) {
            applyBandwidthLimit(updated.bandwidthLimit)
        }
    }

    /** Сохраняет настройки облака и обновляет экран. */
    fun updateCloudSettings(name: String, updated: CloudSettings) {
        settings.update(name, updated)
        _state.update { it.copy(settings = settings.load()) }
    }

    private suspend fun reloadClouds() {
        val api = client ?: return
        try {
            val names = api.listRemotes()
            val mounts = runCatching { api.listMounts() }.getOrDefault(emptyList())

            // Маунт мог отвалиться сам (rclone его снял, диск отключили) —
            // тогда запись о нём больше не отражает реальность.
            val alive = mounts.map { it.MountPoint }.toSet()
            ourMounts.entries.removeIf { it.value !in alive }

            val storedSettings = settings.load()
            _state.update { current ->
                current.copy(
                    globalError = null,
                    settings = storedSettings,
                    clouds = names.map { name ->
                        val previous = current.clouds.firstOrNull { it.name == name }
                        CloudUi(
                            name = name,
                            mountPoint = ourMounts[name]?.takeIf { point ->
                                mounts.any { it.MountPoint == point }
                            },
                            about = previous?.about,
                            error = previous?.error,
                        )
                    },
                )
            }

            // Место спрашиваем отдельно и по одному: запрос ходит в сеть, а часть
            // бэкендов его вовсе не поддерживает — общий список не должен от этого страдать.
            names.forEach { name ->
                val about = runCatching { api.about(name) }.getOrNull() ?: return@forEach
                updateCloud(name) { it.copy(about = about) }
            }
        } catch (e: RcloneRcException) {
            _state.update { it.copy(globalError = e.rcloneError) }
        }
    }

    /**
     * Добавляет облако. Пароли пропускаются через `core/obscure`: rclone хранит
     * их в «затемнённом» виде и открытый текст в конфиге не примет.
     */
    fun addCloud(
        name: String,
        type: String,
        parameters: Map<String, String>,
        secretKeys: Set<String>,
        onDone: (String?) -> Unit,
    ) {
        val api = client ?: return
        scope.launch {
            // Для OAuth-облаков rclone держит запрос открытым, пока пользователь
            // подтверждает доступ, и печатает ссылку в свой вывод. Подхватываем
            // её, чтобы показать, если браузер не открылся сам.
            val linkWatcher = launch { watchForOauthLink() }
            try {
                val prepared = parameters.mapValues { (key, value) ->
                    if (key in secretKeys && value.isNotEmpty()) api.obscure(value) else value
                }
                api.createRemote(name, type, prepared.filterValues { it.isNotEmpty() })
                reloadClouds()
                onDone(null)
            } catch (e: RcloneRcException) {
                onDone(e.rcloneError)
            } catch (e: Exception) {
                // Обрыв связи с rcd на длинном OAuth-запросе иначе выглядел бы
                // как навсегда зависший диалог: показываем причину.
                onDone(e.message ?: strings.createFailed)
            } finally {
                linkWatcher.cancel()
                _state.update { it.copy(oauthUrl = null) }
            }
        }
    }

    /** Ищет в выводе rcd ссылку подтверждения доступа, пока идёт создание облака. */
    private suspend fun watchForOauthLink() {
        while (currentCoroutineContext().isActive) {
            val link = process?.recentOutput()?.firstNotNullOfOrNull { line ->
                OAUTH_LINK_PATTERN.find(line)?.value
            }
            if (link != null) {
                _state.update { it.copy(oauthUrl = link) }
                return
            }
            delay(OAUTH_LINK_POLL_MILLIS)
        }
    }

    /**
     * Переименовывает облако. Смонтированное сначала отключаем: маунт держится
     * за старое имя, и после переименования он указывал бы в никуда.
     */
    fun renameCloud(from: String, to: String, onDone: (String?) -> Unit) {
        val api = client ?: return
        scope.launch {
            updateCloud(from) { it.copy(busy = true, error = null) }
            try {
                state.value.clouds.firstOrNull { it.name == from }?.mountPoint?.let { point ->
                    runCatching { api.unmount(point) }
                    ourMounts.remove(from)
                }
                api.renameRemote(from, to)
                settings.rename(from, to)
                reloadClouds()
                onDone(null)
            } catch (e: Exception) {
                val message = (e as? RcloneRcException)?.rcloneError
                    ?: e.message
                    ?: strings.renameFailed
                updateCloud(from) { it.copy(busy = false, error = message) }
                onDone(message)
            }
        }
    }

    fun deleteCloud(name: String) {
        val api = client ?: return
        scope.launch {
            updateCloud(name) { it.copy(busy = true, error = null) }
            try {
                // Смонтированное облако сначала отключаем: иначе останется висящий
                // маунт, указывающий на удалённую конфигурацию.
                state.value.clouds.firstOrNull { it.name == name }?.mountPoint?.let {
                    runCatching { api.unmount(it) }
                    ourMounts.remove(name)
                }
                api.deleteRemote(name)
                settings.forget(name)
                reloadClouds()
            } catch (e: RcloneRcException) {
                updateCloud(name) { it.copy(busy = false, error = e.rcloneError) }
            }
        }
    }

    /**
     * Подключает облако. Режим кэширования и точку монтирования берём из
     * настроек: это то, что пользователь выбрал осознанно, и подставлять
     * вместо них умолчания при каждом подключении было бы неожиданно.
     */
    fun mount(name: String) {
        scope.launch { mountAndWait(name) }
    }

    private suspend fun mountAndWait(name: String) {
        val api = client ?: return
        val cloudSettings = settings.forCloud(name)
        val mountPoint = cloudSettings.mountPoint?.takeIf { it.isNotBlank() }
            ?: defaultMountPoint(name)

        updateCloud(name) { it.copy(busy = true, error = null) }
        try {
            api.mount(name, mountPoint, vfsCacheMode = cloudSettings.cacheMode)
            ourMounts[name] = mountPoint
            reloadClouds()
        } catch (e: RcloneRcException) {
            updateCloud(name) { it.copy(busy = false, error = e.rcloneError) }
            _notifications.tryEmit(
                AppNotification(
                    title = strings.mountFailed(name),
                    message = e.rcloneError,
                ),
            )
        }
    }

    fun unmount(name: String) {
        val api = client ?: return
        val mountPoint = state.value.clouds.firstOrNull { it.name == name }?.mountPoint ?: return
        scope.launch {
            updateCloud(name) { it.copy(busy = true, error = null) }
            try {
                api.unmount(mountPoint)
                ourMounts.remove(name)
                reloadClouds()
            } catch (e: RcloneRcException) {
                updateCloud(name) { it.copy(busy = false, error = e.rcloneError) }
            }
        }
    }

    /** Останавливает rcd. Вызывается при закрытии приложения. */
    fun shutdown() {
        runCatching { client?.close() }
        runCatching { process?.stop() }
        scope.cancel()
    }

    private fun updateCloud(name: String, transform: (CloudUi) -> CloudUi) {
        _state.update { current ->
            current.copy(
                clouds = current.clouds.map { if (it.name == name) transform(it) else it },
            )
        }
    }

    private fun describeRclone(located: RcloneProcess.Located): String = when (located.source) {
        RcloneProcess.Source.BUNDLED -> strings.rcloneBundled
        RcloneProcess.Source.OVERRIDE -> strings.rcloneOverride(located.file.toString())
        RcloneProcess.Source.SYSTEM_PATH -> strings.rcloneSystem(located.file.toString())
    }

    private fun describeConfig(config: RcloneConfigFile): String = when {
        !config.exists() -> strings.configWillBeCreated(config.toString())
        config.isEncrypted() -> strings.configEncrypted(config.toString())
        else -> strings.configPath(config.toString())
    }

    companion object {
        /** Ссылка, которую rclone печатает при запуске браузерной авторизации. */
        private val OAUTH_LINK_PATTERN = Regex("http://127\\.0\\.0\\.1:\\d+/auth\\?state=\\S+")
        private const val OAUTH_LINK_POLL_MILLIS = 400L

        /**
         * Точка монтирования по умолчанию. На Windows это буква диска, поэтому
         * ищем первую свободную; на остальных ОС — каталог в домашней папке.
         */
        fun defaultMountPoint(cloudName: String): String {
            val isWindows = System.getProperty("os.name").lowercase().contains("win")
            if (!isWindows) {
                return File(System.getProperty("user.home"), "OpenDisk/$cloudName").path
            }
            // A и B исторически за флоппи, C — системный: начинаем с D.
            val taken = File.listRoots().map { it.path.first().uppercaseChar() }.toSet()
            val free = ('D'..'Z').firstOrNull { it !in taken } ?: 'Z'
            return "$free:"
        }
    }
}
