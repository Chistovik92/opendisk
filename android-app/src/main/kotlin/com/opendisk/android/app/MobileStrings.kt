package com.opendisk.android.app

import java.util.Locale

/**
 * Строки интерфейса телефона.
 *
 * Устроено так же, как на десктопе (composeApp/.../Strings.kt): оба языка
 * стоят рядом, и пропущенный перевод просто не скомпилируется. Ресурсами
 * Android было бы привычнее, но тогда переводы жили бы в двух файлах и
 * расходились ровно так, как это обычно и происходит.
 *
 * Язык — системный. Сменить его на ходу Android не даёт без пересоздания
 * экрана, а при пересоздании строки берутся заново.
 */
class MobileStrings(val russian: Boolean) {

    private fun t(ru: String, en: String): String = if (russian) ru else en

    val starting = t("Запускаю rclone…", "Starting rclone…")
    val noClouds = t(
        "Облаков пока нет. Добавьте первое на вкладке «Добавить».",
        "No clouds yet. Add the first one on the «Add» tab.",
    )
    val back = t("Назад", "Back")
    val close = t("Закрыть", "Close")
    val cancel = t("Отмена", "Cancel")
    val delete = t("Удалить", "Delete")

    // --- Папки ---------------------------------------------------------------

    val readingFolder = t("Читаю папку…", "Reading the folder…")
    val emptyFolder = t("Папка пуста", "The folder is empty")
    val linkHint = t("ссылка", "link")

    // --- Вкладки и диски -----------------------------------------------------

    val tabDisks = t("Диски", "Disks")
    val tabAdd = t("Добавить", "Add")
    val sectionDevice = t("На этом телефоне", "On this phone")
    val sectionClouds = t("Облака", "Clouds")
    val phoneStorage = t("Память телефона", "Phone storage")
    val sdCard = t("Карта памяти", "Memory card")
    val rootFs = t("Корень системы (root)", "System root (root)")
    val rootHint = t(
        "Вся файловая система телефона. При первом входе менеджер root спросит, " +
            "давать ли OpenDisk доступ.",
        "The whole phone file system. On first entry the root manager asks " +
            "whether to give OpenDisk access.",
    )
    val rootDenied = t(
        "root не выдан. Разрешите OpenDisk в Magisk, KernelSU или другом менеджере root.",
        "Root was not granted. Allow OpenDisk in Magisk, KernelSU or another root manager.",
    )
    val storageAccessNeeded = t(
        "Нужен доступ ко всем файлам — без него OpenDisk не видит память телефона " +
            "и не может скачивать в неё файлы из облаков.",
        "Access to all files is needed — without it OpenDisk cannot see the phone " +
            "storage or download cloud files into it.",
    )
    val grantStorageAccess = t("Разрешить доступ ко всем файлам", "Allow access to all files")
    /**
     * Пункт меню облака. Длинно, но понятно: короткое «В «Файлах»» у
     * переключателя в 0.5.6 не объясняло, что он делает.
     */
    val showInFilesAction = t(
        "Показывать в системных «Файлах» и окнах выбора файла",
        "Show in system Files and file pickers",
    )
    val visibleInFiles = t("видно в «Файлах»", "visible in Files")

    // --- Файловый менеджер ---------------------------------------------------

    val open = t("Открыть", "Open")
    val share = t("Отправить", "Share")
    val downloadToPhone = t("Скачать в телефон", "Download to phone")
    val copyAction = t("Копировать", "Copy")
    val cutAction = t("Вырезать", "Cut")
    val rename = t("Переименовать", "Rename")
    val getLink = t("Ссылка на скачивание", "Download link")
    val newFolder = t("Новая папка", "New folder")
    val refresh = t("Обновить", "Refresh")
    val pasteHere = t("Вставить сюда", "Paste here")
    val more = t("Ещё", "More")
    val ok = t("Готово", "OK")

    fun inClipboard(name: String, move: Boolean) = if (move) {
        t("Вырезано: $name", "Cut: $name")
    } else {
        t("Скопировано: $name", "Copied: $name")
    }

    val creatingFolder = t("Создаю папку…", "Creating the folder…")
    val renaming = t("Переименовываю…", "Renaming…")
    fun deleting(name: String) = t("Удаляю «$name»…", "Deleting «$name»…")
    fun copying(name: String) = t("Копирую «$name»…", "Copying «$name»…")
    fun moving(name: String) = t("Переношу «$name»…", "Moving «$name»…")
    fun downloading(name: String) = t("Скачиваю «$name»…", "Downloading «$name»…")
    fun downloaded(path: String) = t("Сохранено в память телефона: $path", "Saved to phone storage: $path")
    fun preparingFile(name: String) = t("Готовлю «$name»…", "Preparing «$name»…")
    val cannotPasteIntoItself = t(
        "Папку нельзя вставить в саму себя.",
        "A folder cannot be pasted into itself.",
    )
    val noAppToOpen = t("Нет приложения, которое открывает такие файлы.", "No app can open this kind of file.")

    fun deleteFileTitle(name: String) = t("Удалить «$name»?", "Delete «$name»?")
    val deleteFileExplanation = t(
        "Файл удалится там, где лежит, — в облаке или на телефоне. Корзины у " +
            "OpenDisk нет.",
        "The file is deleted where it lives — in the cloud or on the phone. " +
            "OpenDisk has no recycle bin.",
    )

    // --- Ссылки --------------------------------------------------------------

    val linkTitle = t("Ссылка на скачивание", "Download link")
    val linkAsking = t("Спрашиваю ссылку у облака…", "Asking the cloud for a link…")
    val linkExplanation = t(
        "Ссылку выдал сам сервис. Файл по ней скачает любой, у кого она есть, " +
            "без входа в аккаунт.",
        "The link comes from the service itself. Anyone who has it can download " +
            "the file without signing in.",
    )
    val copy = t("Копировать", "Copy")
    val copied = t("Скопировано", "Copied")

    fun linkNotSupported(cloud: String) = t(
        "Облако «$cloud» не умеет выдавать ссылки: у его протокола нет такого понятия.",
        "Cloud «$cloud» cannot issue links: its protocol has no such notion.",
    )

    // --- Добавление и удаление ----------------------------------------------

    val newCloud = t("Новое облако", "New cloud")
    val name = t("Название", "Name")

    // --- Выбор сервиса и вход через браузер ------------------------------------

    val chooseService = t("Какое облако добавить", "Which cloud to add")
    val searchServices = t(
        "Поиск: название, страна или другое имя",
        "Search: name, country or another name",
    )
    val loadingAllServices = t(
        "Загружаю полный список сервисов rclone…",
        "Loading the full list of rclone services…",
    )
    val nothingFound = t("Ничего не найдено", "Nothing found")
    val adding = t("Добавляю…", "Adding…")
    val add = t("Добавить", "Add")

    val signInWithBrowser = t("Войти через браузер", "Sign in with a browser")

    /** Что будет после нажатия — до нажатия, а не после. */
    val browserWillOpen = t(
        "Откроется браузер: выберите аккаунт и разрешите доступ. Пароль вводить " +
            "и придумывать не нужно.",
        "A browser will open: pick the account and allow access. No password to " +
            "type or create.",
    )
    val waitingForBrowser = t("Подтвердите доступ в браузере", "Confirm access in the browser")
    val preparingSignIn = t("Готовлю вход…", "Preparing sign-in…")
    val waitingForBrowserHint = t(
        "Когда страница напишет «Success», вернитесь в OpenDisk — нажатием на " +
            "значок в шторке или просто закрыв вкладку. Облако уже будет в списке.",
        "When the page says «Success», come back to OpenDisk — tap the icon in the " +
            "shade or just close the tab. The cloud will already be in the list.",
    )
    val openBrowserAgain = t("Открыть браузер снова", "Open the browser again")
    val noBrowser = t(
        "На этом устройстве нечем открыть страницу входа. Поставьте браузер или добавьте облако, которому хватает логина и пароля (WebDAV, S3, SFTP).",
        "There is no browser on this device to open the sign-in page. Install one, or add a cloud that only needs a login and password (WebDAV, S3, SFTP).",
    )

    val signInChannel = t("Вход через браузер", "Browser sign-in")

    fun signInNotificationTitle(cloud: String) = t("Вход в «$cloud»", "Signing in to «$cloud»")

    val signInNotificationText = t(
        "Подтвердите доступ в браузере, затем нажмите сюда, чтобы вернуться.",
        "Confirm access in the browser, then tap here to come back.",
    )

    fun deleteTitle(cloud: String) = t("Удалить «$cloud»?", "Delete «$cloud»?")

    /**
     * Что именно удаляется. Люди путают «удалить облако из приложения» с
     * «удалить файлы в облаке» — и боятся нажимать, или, хуже, не боятся.
     */
    val deleteExplanation = t(
        "Из приложения пропадёт только подключение. Файлы в самом облаке " +
            "останутся нетронутыми.",
        "Only the connection is removed from the app. The files in the cloud " +
            "itself stay untouched.",
    )

    // --- Подключение ---------------------------------------------------------

    val connect = t("Подключить", "Connect")
    val disconnect = t("Отключить", "Disconnect")
    val connected = t("подключено", "connected")
    val notConnected = t("не подключено", "not connected")

    val openInFiles = t("Открыть в «Файлах»", "Open in Files")

    // --- Уведомление ---------------------------------------------------------

    val notificationChannel = t("Подключённые облака", "Connected clouds")
    val notificationTitle = t("OpenDisk", "OpenDisk")

    fun statusTitle(connected: Int, total: Int) = t(
        "Подключено облаков: $connected из $total",
        "Clouds connected: $connected of $total",
    )

    val statusIcon = t("Значок в шторке всё время", "Keep the icon in the shade")
    val statusIconHint = t(
        "Обычно значок появляется только на время входа через браузер: пока " +
            "впереди вкладка браузера, телефон иначе отнимает у приложения сеть. " +
            "Для «Файлов» он не нужен — когда они читают облако, телефон и так " +
            "считает приложение занятым делом. Включите, если облака в «Файлах» " +
            "перестают открываться: некоторые телефоны обходятся с фоном строже.",
        "Normally the icon shows only while you sign in through the browser: with " +
            "the browser tab in front the phone would otherwise cut the app off the " +
            "network. Files does not need it — while it reads a cloud, the phone " +
            "already counts the app as busy. Turn it on if clouds stop opening in " +
            "Files: some phones treat background apps more harshly.",
    )
    val notificationHint = t(
        "Значок OpenDisk в шторке — это сводка: какие облака подключены и " +
            "сколько в них занято.",
        "The OpenDisk icon in the shade is a summary: which clouds are connected " +
            "and how much space they use.",
    )

    // --- Работа в фоне --------------------------------------------------------

    val background = t("Работа в фоне", "Working in the background")
    val backgroundAllowed = t("Разрешена", "Allowed")
    val backgroundRestricted = t("Ограничена телефоном", "Restricted by the phone")
    val backgroundAllow = t("Разрешить", "Allow")
    val backgroundSettings = t("Настройки автозапуска", "Auto-start settings")
    val appSettings = t("Настройки приложения", "App settings")
    val backgroundHint = t(
        "Пока OpenDisk не на экране, телефон вправе усыпить его и отрезать " +
            "сеть: тогда облако в «Файлах» не открывается, а вход через браузер " +
            "обрывается на последнем шаге. «Разрешить» откроет системное окно " +
            "Android, где нужно снять ограничение для OpenDisk.",
        "While OpenDisk is off screen the phone may put it to sleep and cut it " +
            "off the network: a cloud then fails to open in Files, and browser " +
            "sign-in breaks at the last step. «Allow» opens the Android dialog " +
            "where you lift the restriction for OpenDisk.",
    )
    val backgroundVendorHint = t(
        "У Xiaomi, Oppo, Realme, vivo, Huawei и Samsung поверх Android есть свои " +
            "ограничения фона, и системного разрешения им мало. Кнопка ниже ведёт " +
            "прямо туда: в список автозапуска прошивки, а где его нет — в " +
            "настройки приложения, раздел про батарею и фоновую работу.",
        "Xiaomi, Oppo, Realme, vivo, Huawei and Samsung add their own background " +
            "limits on top of Android, and the system permission alone is not " +
            "enough for them. The button below goes straight there: to the " +
            "firmware auto-start list, or, where there is none, to the app " +
            "settings and their battery section.",
    )
    val backgroundSettingsMissing = t(
        "На этом телефоне такого экрана нет — откройте настройки приложения " +
            "и разрешите работу в фоне там.",
        "This phone has no such screen — open the app settings and allow " +
            "background work there.",
    )
    val notificationsBlocked = t(
        "Уведомления запрещены в настройках Android — подключение работает, " +
            "но значка в шторке не будет.",
        "Notifications are turned off in the Android settings — connecting still " +
            "works, but there will be no icon in the shade.",
    )

    // --- Настройки -----------------------------------------------------------

    val settings = t("Настройки", "Settings")
    val theme = t("Оформление", "Appearance")
    val themeAuto = t("Как в системе", "Same as system")
    val themeLight = t("Светлое", "Light")
    val themeDark = t("Тёмное", "Dark")
    val themeHint = t(
        "«Как в системе» берёт тему телефона, а на Android 12 и новее — и его " +
            "цвета: те же, что у обоев.",
        "«Same as system» follows the phone theme, and on Android 12 and newer " +
            "its colours too — the ones taken from the wallpaper.",
    )
    val language = t("Язык интерфейса", "Interface language")
    val languageAuto = t("Как в системе", "Same as system")
    val languageChangeHint = t(
        "Смена языка применится сразу.",
        "The language changes immediately.",
    )
    val about = t("О приложении", "About")
    val version = t("Версия", "Version")
    val builtOnRclone = t("Работает на rclone", "Powered by rclone")
    val projectPage = t("Страница проекта", "Project page")

    // --- Размеры -------------------------------------------------------------

    val bytes = t("Б", "B")
    val sizeUnits = if (russian) listOf("КБ", "МБ", "ГБ", "ТБ", "ПБ") else listOf("KB", "MB", "GB", "TB", "PB")

    fun usedOf(used: String, total: String) = t("занято $used из $total", "$used of $total used")
    fun usedOnly(used: String) = t("занято $used", "$used used")
    fun totalOnly(total: String) = t("всего $total", "$total total")

    companion object {
        fun system(): MobileStrings =
            MobileStrings(russian = Locale.getDefault().language.equals("ru", ignoreCase = true))

        fun of(language: MobileLanguage): MobileStrings = when (language) {
            MobileLanguage.RUSSIAN -> MobileStrings(russian = true)
            MobileLanguage.ENGLISH -> MobileStrings(russian = false)
            MobileLanguage.AUTO -> system()
        }
    }
}

/**
 * Язык интерфейса. Отдельный выбор нужен по той же причине, что и на
 * десктопе: система бывает на одном языке, а привычка читать — на другом.
 */
enum class MobileLanguage(val code: String) {
    AUTO("auto"),
    RUSSIAN("ru"),
    ENGLISH("en"),
    ;

    companion object {
        fun fromCode(code: String?): MobileLanguage =
            entries.firstOrNull { it.code == code } ?: AUTO
    }
}

/** Оформление: как в системе, светлое или тёмное. */
enum class MobileTheme(val code: String) {
    AUTO("auto"),
    LIGHT("light"),
    DARK("dark"),
    ;

    companion object {
        fun fromCode(code: String?): MobileTheme = entries.firstOrNull { it.code == code } ?: AUTO
    }
}
