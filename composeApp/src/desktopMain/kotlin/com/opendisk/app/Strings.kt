package com.opendisk.app

import androidx.compose.runtime.staticCompositionLocalOf
import java.util.Locale

/** Язык интерфейса. */
enum class Language(val code: String) {
    /** По языку системы. */
    AUTO("auto"),
    RUSSIAN("ru"),
    ENGLISH("en"),
    ;

    companion object {
        fun fromCode(code: String): Language =
            entries.firstOrNull { it.code == code } ?: AUTO
    }
}

/**
 * Строки интерфейса.
 *
 * Оба языка стоят рядом в одном месте намеренно: при раздельных файлах перевода
 * половина строк рано или поздно расходится или теряется, а здесь пропущенный
 * перевод не скомпилируется.
 *
 * Строки с подстановками принимают их параметрами, а не собираются склейкой:
 * порядок слов в языках разный, и склейка ломает перевод.
 */
class Strings(private val russian: Boolean) {

    private fun t(ru: String, en: String): String = if (russian) ru else en

    val languageTitle: String = t("Русский", "English")

    // --- Общее --------------------------------------------------------------

    val cancel = t("Отмена", "Cancel")
    val save = t("Сохранить", "Save")
    val back = t("Назад", "Back")
    val delete = t("Удалить", "Delete")
    val rename = t("Переименовать", "Rename")
    val settings = t("Настройки", "Settings")
    val refresh = t("Обновить", "Refresh")
    val nothingFound = t("Ничего не найдено", "Nothing found")

    // --- Главный экран ------------------------------------------------------

    val addCloud = t("Добавить облако", "Add cloud")
    val appSettings = t("Настройки приложения", "Application settings")
    val startingRclone = t("Запускаю rclone...", "Starting rclone...")
    val noCloudsYet = t(
        "Облаков пока нет. Начните с кнопки «Добавить облако».",
        "No clouds yet. Start with the «Add cloud» button.",
    )
    val rcloneOutput = t("Вывод rclone:", "rclone output:")
    val connect = t("Подключить", "Connect")
    val disconnect = t("Отключить", "Disconnect")
    val notConnected = t("не подключено", "not connected")

    fun connectedTo(mountPoint: String) =
        t("подключено к $mountPoint", "connected at $mountPoint")

    fun cacheLabel(mode: String) = t("кэш: $mode", "cache: $mode")

    fun errorPrefix(message: String) = t("Ошибка: $message", "Error: $message")

    // --- Место на облаке ----------------------------------------------------

    fun usedOf(used: String, total: String) =
        t("занято $used из $total", "$used of $total used")

    fun usedOnly(used: String) = t("занято $used", "$used used")

    fun totalOnly(total: String) = t("всего $total", "$total total")

    val bytes = t("Б", "B")
    val sizeUnits = if (russian) {
        listOf("КБ", "МБ", "ГБ", "ТБ", "ПБ")
    } else {
        listOf("KB", "MB", "GB", "TB", "PB")
    }

    // --- Откуда взяты rclone и конфиг ---------------------------------------

    val rcloneBundled = t("rclone: встроенный", "rclone: bundled")
    fun rcloneOverride(path: String) =
        t("rclone: задан вручную ($path)", "rclone: set manually ($path)")

    fun rcloneSystem(path: String) = t("rclone: системный ($path)", "rclone: system ($path)")

    fun configPath(path: String) = t("конфиг: $path", "config: $path")
    fun configWillBeCreated(path: String) =
        t("конфиг: будет создан в $path", "config: will be created at $path")

    fun configEncrypted(path: String) =
        t("конфиг: зашифрован ($path)", "config: encrypted ($path)")

    // --- Ошибки -------------------------------------------------------------

    val rcloneNotFound = t(
        "rclone не найден. В собранном дистрибутиве он идёт в комплекте; " +
            "при запуске из исходников его кладёт задача :composeApp:downloadRclone.",
        "rclone not found. It ships inside the built distribution; when running " +
            "from sources it is placed by the :composeApp:downloadRclone task.",
    )
    val rcdFailedToStart = t("не удалось запустить rclone rcd", "failed to start rclone rcd")
    val appDidNotStart = t("OpenDisk не запустился", "OpenDisk did not start")
    val createFailed = t("не удалось создать облако", "failed to create the cloud")
    val renameFailed = t("не удалось переименовать", "failed to rename")

    fun mountFailed(name: String) =
        t("Не удалось подключить «$name»", "Could not connect «$name»")

    fun bandwidthFailed(reason: String) = t(
        "Не удалось применить ограничение скорости: $reason",
        "Could not apply the speed limit: $reason",
    )

    // --- Драйвер монтирования -----------------------------------------------

    val mountUnavailable = t("Подключение дисков недоступно", "Mounting is unavailable")

    fun installDriver(what: String) = t("Установить $what", "Install $what")
    fun installingDriver(what: String) = t("Устанавливаю $what...", "Installing $what...")
    fun downloadFrom(url: String) = t("Скачать: $url", "Download: $url")

    fun driverInstallCancelled(what: String) = t(
        "Установка $what отменена. Драйвер ставится в систему, поэтому нужны права " +
            "администратора — без них подключать облака как диски не получится.",
        "Installing $what was cancelled. The driver is installed system-wide and needs " +
            "administrator rights — without them clouds cannot be mounted as drives.",
    )

    fun driverInstallFailed(what: String, details: String) =
        t("Не удалось установить $what: $details", "Could not install $what: $details")

    /**
     * Объяснения про недостающий драйвер живут здесь, а не в модуле rclone-bridge:
     * мост не должен знать про язык интерфейса и вообще про текст для человека.
     */
    val winFspExplanation = t(
        "Чтобы подключать облака как диски, нужен WinFsp — драйвер файловой системы " +
            "для Windows. Он входит в состав OpenDisk, установка займёт несколько секунд " +
            "и потребует подтверждения администратора.",
        "Mounting clouds as drives needs WinFsp, a file system driver for Windows. " +
            "It ships with OpenDisk; installation takes a few seconds and requires " +
            "administrator confirmation.",
    )
    val fuseExplanation = t(
        "Чтобы подключать облака как диски, нужен FUSE. Установите пакет fuse3 " +
            "средствами вашего дистрибутива — например, `sudo apt install fuse3`.",
        "Mounting clouds as drives needs FUSE. Install the fuse3 package with your " +
            "distribution tools — for example, `sudo apt install fuse3`.",
    )
    val macFuseExplanation = t(
        "Чтобы подключать облака как диски, нужен macFUSE — его нужно установить отдельно.",
        "Mounting clouds as drives needs macFUSE, which has to be installed separately.",
    )

    // --- Пароль конфига -----------------------------------------------------

    val configEncryptedTitle = t("Конфигурация rclone зашифрована", "The rclone config is encrypted")
    val configPasswordHint = t(
        "Введите пароль конфига — он нужен, чтобы прочитать список облаков.",
        "Enter the config password — it is needed to read the list of clouds.",
    )
    val password = t("Пароль", "Password")
    val wrongPassword = t("Пароль не подошёл. Попробуйте ещё раз.", "Wrong password. Try again.")
    val unlock = t("Разблокировать", "Unlock")

    // --- Мастер добавления --------------------------------------------------

    val whichCloud = t("Какое облако подключаем?", "Which cloud are we connecting?")
    val otherConnection = t("Другое подключение", "Other connection")
    val otherConnectionFull = t(
        "Другое подключение — весь список бэкендов rclone",
        "Other connection — the full list of rclone backends",
    )

    fun searchBackends(count: Int) =
        t("Поиск по $count бэкендам", "Search across $count backends")

    val nameInList = t("Название в списке", "Name in the list")
    val nameTaken = t("Такое название уже занято", "That name is already taken")
    val connecting = t("Подключаю...", "Connecting...")

    val browserWillOpen = t(
        "После нажатия «Подключить» откроется браузер — там нужно разрешить OpenDisk " +
            "доступ к вашему хранилищу. Пароль в приложение вводить не нужно.",
        "After pressing «Connect» a browser will open — allow OpenDisk to access your " +
            "storage there. No password is entered into the application.",
    )
    val browserAccountWarning = t(
        "Вход пойдёт под тем аккаунтом, в который вы вошли в браузере. Чтобы подключить " +
            "другой — откройте страницу в приватном окне или выйдите из аккаунта " +
            "в браузере перед подключением.",
        "Sign-in will use the account you are already signed into in the browser. " +
            "To connect a different one, open the page in a private window or sign out " +
            "in the browser first.",
    )
    val waitingForBrowser = t(
        "Ожидаю подтверждения в браузере",
        "Waiting for confirmation in the browser",
    )
    val waitingForBrowserHint = t(
        "Разрешите доступ на открывшейся странице. Окно можно не закрывать — " +
            "подключение завершится само.",
        "Allow access on the page that opened. You can leave this window open — " +
            "the connection will finish by itself.",
    )
    val browserDidNotOpen = t(
        "Если браузер не открылся, перейдите по ссылке:",
        "If the browser did not open, follow this link:",
    )
    val backendHasNoRequiredFields = t(
        "У этого бэкенда нет обязательных полей — он настраивается сам, возможно, " +
            "с подтверждением в браузере.",
        "This backend has no required fields — it configures itself, possibly with " +
            "a confirmation in the browser.",
    )

    // --- Настройки облака ---------------------------------------------------

    fun cloudSettingsTitle(name: String) = t("Настройки: $name", "Settings: $name")

    val whatToKeepOnDisk = t("Что хранить на диске", "What to keep on disk")
    val whereToMount = t("Куда подключать", "Where to mount")
    val mountPoint = t("Точка монтирования", "Mount point")
    val mountPointHint = t(
        "Оставьте пустым — подберём свободную сами. На Windows это буква диска, " +
            "на Linux — каталог.",
        "Leave empty and a free one will be picked. On Windows this is a drive letter, " +
            "on Linux a directory.",
    )
    val mountOnStartup = t(
        "Подключать при запуске приложения",
        "Connect when the application starts",
    )
    val settingsApplyOnReconnect = t(
        "Облако сейчас подключено. Новые настройки применятся при следующем " +
            "подключении — отключите и подключите заново.",
        "The cloud is connected right now. New settings apply on the next connection — " +
            "disconnect and connect again.",
    )

    val cacheModes: List<CacheModeOption> = if (russian) {
        listOf(
            CacheModeOption(
                "off", "Без кэша",
                "Ничего не сохраняется на диск. Занимает меньше всего места, но многие " +
                    "программы не смогут сохранять файлы прямо в облако.",
            ),
            CacheModeOption(
                "minimal", "Минимальный",
                "Кэшируется только то, без чего не обойтись. Компромисс между экономией " +
                    "места и совместимостью.",
            ),
            CacheModeOption(
                "writes", "При записи — рекомендуется",
                "Файл, открытый на запись, сначала пишется на диск и уже потом уходит " +
                    "в облако. Работает с обычными программами, читаются файлы по-прежнему " +
                    "по требованию.",
            ),
            CacheModeOption(
                "full", "Полный",
                "Всё, что открываете, целиком скачивается в кэш. Максимальная совместимость " +
                    "и быстрый повторный доступ, но занимает место на диске.",
            ),
        )
    } else {
        listOf(
            CacheModeOption(
                "off", "No cache",
                "Nothing is stored on disk. Uses the least space, but many programs will " +
                    "not be able to save files straight to the cloud.",
            ),
            CacheModeOption(
                "minimal", "Minimal",
                "Only what is unavoidable is cached. A compromise between saving space " +
                    "and compatibility.",
            ),
            CacheModeOption(
                "writes", "On write — recommended",
                "A file opened for writing is written to disk first and uploaded afterwards. " +
                    "Works with ordinary programs, files are still read on demand.",
            ),
            CacheModeOption(
                "full", "Full",
                "Everything you open is downloaded to the cache in full. Best compatibility " +
                    "and fast repeated access, but takes disk space.",
            ),
        )
    }

    // --- Настройки приложения -----------------------------------------------

    val language = t("Язык интерфейса", "Interface language")
    val languageAuto = t("Как в системе", "Same as system")
    val languageChangeHint = t(
        "Смена языка применится сразу.",
        "The language changes immediately.",
    )
    val runAtLogin = t("Запускать при входе в систему", "Start when I sign in")
    val runAtLoginHint = t(
        "Приложение запустится свёрнутым в трей и подключит облака, отмеченные " +
            "как автоподключаемые.",
        "The application starts minimised to the tray and connects the clouds marked " +
            "for automatic connection.",
    )
    val autostartUnsupported = t(
        "На этой системе автозапуск пока не поддерживается.",
        "Autostart is not supported on this system yet.",
    )
    val autostartFailed = t(
        "Не удалось изменить автозапуск. Он настраивается только для установленного " +
            "приложения, не для запуска из сборки.",
        "Could not change autostart. It only works for the installed application, " +
            "not for running from a build directory.",
    )
    val speedLimit = t("Ограничение скорости", "Speed limit")
    val unlimited = t("Без ограничения", "Unlimited")
    val speed = t("Скорость", "Speed")
    val speedExample = t("например, 2M", "for example, 2M")
    val speedHint = t(
        "Килобайты, мегабайты или гигабайты в секунду: 500k, 2M, 1G.",
        "Kilobytes, megabytes or gigabytes per second: 500k, 2M, 1G.",
    )
    val speedInvalid = t(
        "Не разобрать. Ожидается число и единица: 500k, 2M, 1G.",
        "Cannot parse this. A number and a unit are expected: 500k, 2M, 1G.",
    )
    val speedIsGlobal = t(
        "Ограничение действует сразу на все облака — rclone умеет ограничивать только так.",
        "The limit applies to all clouds at once — that is the only way rclone can limit speed.",
    )

    // --- Переименование и удаление ------------------------------------------

    val renameCloud = t("Переименовать облако", "Rename cloud")
    val newName = t("Новое название", "New name")
    val renaming = t("Переименовываю...", "Renaming...")
    val renameWillDisconnect = t(
        "Облако сейчас подключено как диск — при переименовании оно будет отключено, " +
            "подключить его нужно будет заново.",
        "The cloud is connected as a drive — renaming will disconnect it, and you will " +
            "need to connect it again.",
    )
    val deleteCloudTitle = t("Удалить облако?", "Delete the cloud?")

    fun deleteCloudText(name: String) = t(
        "Облако «$name» будет удалено из конфигурации rclone.",
        "The cloud «$name» will be removed from the rclone configuration.",
    )

    val deleteKeepsFiles = t(
        "Файлы в самом облаке не тронутся — удаляется только подключение.",
        "Files in the cloud itself are untouched — only the connection is removed.",
    )

    // --- О приложении -------------------------------------------------------

    val about = t("О приложении", "About")
    val aboutDescription = t(
        "Открытый клиент облачных дисков. Подключает облака как обычные диски, " +
            "файлы подгружаются по требованию.",
        "An open client for cloud drives. Connects clouds as ordinary drives, " +
            "files are fetched on demand.",
    )
    val version = t("Версия", "Version")
    val versionUnknown = t("из сборки", "development build")
    val builtOnRclone = t("Работает на rclone", "Powered by rclone")
    val whereFilesAre = t("Где что лежит", "Where files live")
    val cloudsList = t("Список облаков", "List of clouds")
    val appSettingsFile = t("Настройки приложения", "Application settings")
    val licenses = t("Лицензии", "Licenses")
    val licensesText = t(
        "OpenDisk — MIT. Встроенный rclone — MIT. Установщик WinFsp — GPLv3 " +
            "со специальным исключением; тексты лицензий лежат рядом с приложением.",
        "OpenDisk — MIT. The bundled rclone — MIT. The WinFsp installer — GPLv3 " +
            "with a special exception; license texts sit next to the application.",
    )
    val projectPage = t("Страница проекта", "Project page")
    val reportProblem = t("Сообщить о проблеме", "Report a problem")
    val close = t("Закрыть", "Close")

    // --- Трей ---------------------------------------------------------------

    val showWindow = t("Показать окно", "Show window")
    val quit = t("Выход", "Quit")

    // --- Поля мастера для готовых сервисов ----------------------------------

    val fieldLogin = t("Логин", "Login")
    val fieldLoginOrEmail = t("Логин или почта", "Login or email")
    val fieldPassword = t("Пароль", "Password")
    val fieldServer = t("Сервер", "Server")
    val fieldServerUrl = t("Адрес сервера", "Server address")
    val fieldServerUrlHint = t("Например, https://example.com/dav", "For example, https://example.com/dav")
    val fieldAppPassword = t("Пароль приложения", "Application password")
    val fieldExternalAppPassword = t(
        "Пароль для внешних приложений",
        "Password for external applications",
    )

    val presetBrowserLogin = t("вход через браузер", "sign in via browser")
    val presetLoginPassword = t("логин и пароль", "login and password")
    val presetAnyServer = t("любой сервер", "any server")
    val presetSshAccess = t("доступ по SSH", "access over SSH")
    val presetWebdavFallback = t(
        "если браузерный вход не подходит",
        "if browser sign-in does not suit you",
    )

    val yandexDisk = t("Яндекс.Диск", "Yandex Disk")
    val yandexDiskWebdav = t("Яндекс.Диск по WebDAV", "Yandex Disk over WebDAV")
    val mailruCloud = t("Облако Mail.ru", "Mail.ru Cloud")
    val googleDrive = t("Google Диск", "Google Drive")

    val mailruPasswordHelp = t(
        "Не основной пароль от почты: его нужно создать в настройках безопасности Mail.ru",
        "Not your main mail password: create one in Mail.ru security settings",
    )
    val mailruHint = t(
        "Mail.ru не принимает основной пароль от аккаунта — нужен отдельный пароль " +
            "для внешних приложений.",
        "Mail.ru does not accept your main account password — a separate password for " +
            "external applications is required.",
    )
    val yandexAppPasswordHelp = t(
        "Создаётся на id.yandex.ru в разделе «Пароли приложений»",
        "Created at id.yandex.ru under «App passwords»",
    )
    val yandexWebdavHint = t(
        "Для Яндекса по WebDAV нужен пароль приложения, а не пароль от аккаунта.",
        "Yandex over WebDAV needs an app password, not your account password.",
    )

    companion object {
        /**
         * Язык системы определяем один раз: менять его на ходу пользователь
         * всё равно не может, а вот выбранный в настройках — может.
         */
        private val systemIsRussian: Boolean =
            Locale.getDefault().language.equals("ru", ignoreCase = true)

        fun of(language: Language): Strings = when (language) {
            Language.RUSSIAN -> Strings(russian = true)
            Language.ENGLISH -> Strings(russian = false)
            Language.AUTO -> Strings(russian = systemIsRussian)
        }
    }
}

val LocalStrings = staticCompositionLocalOf { Strings.of(Language.AUTO) }
