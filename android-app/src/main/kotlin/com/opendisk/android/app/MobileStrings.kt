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
class MobileStrings(private val russian: Boolean) {

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
    val serverAddress = t("Адрес сервера", "Server address")
    val host = t("Хост", "Host")
    val login = t("Логин", "Login")
    val password = t("Пароль", "Password")
    val adding = t("Добавляю…", "Adding…")
    val add = t("Добавить", "Add")

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

    // --- Размеры -------------------------------------------------------------

    val bytes = t("Б", "B")
    val sizeUnits = if (russian) listOf("КБ", "МБ", "ГБ", "ТБ", "ПБ") else listOf("KB", "MB", "GB", "TB", "PB")

    fun usedOf(used: String, total: String) = t("занято $used из $total", "$used of $total used")
    fun usedOnly(used: String) = t("занято $used", "$used used")
    fun totalOnly(total: String) = t("всего $total", "$total total")

    companion object {
        fun system(): MobileStrings =
            MobileStrings(russian = Locale.getDefault().language.equals("ru", ignoreCase = true))
    }
}
