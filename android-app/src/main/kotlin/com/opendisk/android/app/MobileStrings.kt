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
        "Облаков пока нет.\nДобавьте первое кнопкой «+».",
        "No clouds yet.\nAdd the first one with «+».",
    )
    val back = t("Назад", "Back")
    val close = t("Закрыть", "Close")
    val cancel = t("Отмена", "Cancel")
    val delete = t("Удалить", "Delete")

    // --- Папки ---------------------------------------------------------------

    val readingFolder = t("Читаю папку…", "Reading the folder…")
    val emptyFolder = t("Папка пуста", "The folder is empty")
    val linkHint = t("ссылка", "link")

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
        "Когда страница напишет «Success», закройте вкладку — облако появится " +
            "в списке само.",
        "When the page says «Success», close the tab — the cloud will appear in " +
            "the list by itself.",
    )
    val openBrowserAgain = t("Открыть браузер снова", "Open the browser again")

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

    /**
     * Что значит «подключить» на телефоне. Диска, как на компьютере, здесь
     * быть не может: Android не даёт приложению смонтировать файловую систему.
     * Зато облако можно отдать системе поставщиком документов — и тогда оно
     * видно в «Файлах» и в любом окне выбора файла.
     */
    val connectExplanation = t(
        "Подключённое облако видно в системном приложении «Файлы» и в любом " +
            "окне выбора файла — рядом с памятью телефона.",
        "A connected cloud shows up in the system «Files» app and in every file " +
            "picker, next to the phone storage.",
    )
    val openInFiles = t("Открыть в «Файлах»", "Open in Files")

    // --- Уведомление ---------------------------------------------------------

    val notificationChannel = t("Подключённые облака", "Connected clouds")
    val notificationTitle = t("OpenDisk", "OpenDisk")

    fun notificationText(clouds: List<String>): String = when (clouds.size) {
        0 -> t("Облака отключены", "Clouds are disconnected")
        else -> t("Подключено: ", "Connected: ") + clouds.joinToString(", ")
    }

    val notificationHint = t(
        "Пока облако подключено, уведомление висит в шторке: по нему видно, что " +
            "приложение отдаёт файлы системе, и одним касанием оно открывается.",
        "While a cloud is connected the notification stays in the shade: it shows " +
            "that the app is serving files to the system, and opens it in one tap.",
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
