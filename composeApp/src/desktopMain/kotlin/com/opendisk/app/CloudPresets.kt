package com.opendisk.app

import androidx.compose.ui.graphics.Color

/**
 * Готовое подключение к популярному сервису.
 *
 * Смысл в том, чтобы человеку не приходилось знать, что Яндекс.Диск — это
 * бэкенд `yandex`, а Mail.ru просит не обычный пароль, а пароль для внешних
 * приложений. Нажал плитку — ввёл минимум или подтвердил доступ в браузере.
 *
 * Полный список бэкендов rclone никуда не делся: он спрятан за «другим
 * подключением» для тех, кому нужен S3 конкретного провайдера или экзотика.
 */
data class CloudPreset(
    val id: String,
    val title: String,
    /** Короткое пояснение под названием на плитке. */
    val subtitle: String,
    /** Тип бэкенда rclone. */
    val backend: String,
    /** Параметры, которые подставляются сами и пользователю не показываются. */
    val fixed: Map<String, String> = emptyMap(),
    /** Поля, которые нужно спросить. Для OAuth-сервисов пусто. */
    val fields: List<PresetField> = emptyList(),
    /** Требуется ли подтверждение доступа в браузере. */
    val oauth: Boolean = false,
    /** Подсказка над формой — например, про пароль для внешних приложений. */
    val hint: String? = null,
    /** Цвет плитки. Собственный, не фирменный: логотипы сервисов мы не копируем. */
    val accent: Color,
    /** Символ на плитке. */
    val glyph: String,
)

/**
 * Спросит ли сервис, под каким аккаунтом входить.
 *
 * Определяется тем, переопределён ли адрес авторизации: переопределяем мы его
 * ровно ради параметра выбора аккаунта (`force_confirm`, `force_reapprove`,
 * `prompt=select_account` — у каждого сервиса свой). Где адрес свой, там
 * страница открывается в уже открытом аккаунте молча, и об этом нужно
 * предупредить заранее — иначе второй диск незаметно окажется первым.
 */
val CloudPreset.asksWhichAccount: Boolean
    get() = oauth && "auth_url" in fixed

data class PresetField(
    val key: String,
    val label: String,
    val isPassword: Boolean = false,
    val required: Boolean = true,
    val help: String? = null,
)

/**
 * Плитки нарисованы своими средствами — буква на цветном квадрате, а не
 * официальные логотипы: чужие товарные знаки в дистрибутив мы не кладём.
 * Если появятся права на использование фирменной символики, менять придётся
 * только [CloudPreset.glyph] и [CloudPreset.accent].
 *
 * Список строится от [Strings], а не лежит константой: названия и подсказки
 * переводятся вместе с остальным интерфейсом.
 */
fun cloudPresets(strings: Strings): List<CloudPreset> = listOf(
    CloudPreset(
        id = "yandex",
        title = strings.yandexDisk,
        subtitle = strings.presetBrowserLogin,
        backend = "yandex",
        // force_confirm=yes заставляет Яндекс показать выбор аккаунта. Без него
        // второй диск молча привязывался к тому же аккаунту, что и первый:
        // приложение уже разрешено, сессия в браузере активна — и страница
        // подтверждения просто не показывается. Свои параметры rclone дописывает
        // к этому адресу через «&», проверено на живом флоу.
        fixed = mapOf("auth_url" to "https://oauth.yandex.com/authorize?force_confirm=yes"),
        oauth = true,
        accent = Color(0xFFE53935),
        glyph = "Я",
    ),
    CloudPreset(
        id = "mailru",
        title = strings.mailruCloud,
        subtitle = strings.presetLoginPassword,
        backend = "mailru",
        fields = listOf(
            PresetField("user", strings.fieldLoginOrEmail),
            PresetField(
                key = "pass",
                label = strings.fieldExternalAppPassword,
                isPassword = true,
                help = strings.mailruPasswordHelp,
            ),
        ),
        hint = strings.mailruHint,
        accent = Color(0xFF1E88E5),
        glyph = "@",
    ),
    CloudPreset(
        id = "gdrive",
        title = strings.googleDrive,
        subtitle = strings.presetBrowserLogin,
        backend = "drive",
        // prompt=select_account — штатный параметр Google OAuth: без него вход
        // молча уходит в аккаунт, уже открытый в браузере, и второй диск
        // привязывается к тому же самому.
        //
        // Адрес не выдуман: это ровно тот, которым пользуется сам rclone
        // (backend/drive/drive.go берёт google.Endpoint.AuthURL), к нему только
        // добавлен параметр. Свои параметры rclone дописывает через «&».
        fixed = mapOf(
            "auth_url" to "https://accounts.google.com/o/oauth2/auth?prompt=select_account",
        ),
        oauth = true,
        accent = Color(0xFF43A047),
        glyph = "G",
    ),
    CloudPreset(
        id = "dropbox",
        title = "Dropbox",
        subtitle = strings.presetBrowserLogin,
        backend = "dropbox",
        // Dropbox по той же причине, что и Яндекс: без force_reapprove повторное
        // подключение уходит в уже разрешённый аккаунт без вопросов.
        fixed = mapOf("auth_url" to "https://www.dropbox.com/oauth2/authorize?force_reapprove=true"),
        oauth = true,
        accent = Color(0xFF1565C0),
        glyph = "D",
    ),
    CloudPreset(
        id = "onedrive",
        title = "OneDrive",
        subtitle = strings.presetBrowserLogin,
        backend = "onedrive",
        fixed = mapOf(
            "auth_url" to
                "https://login.microsoftonline.com/common/oauth2/v2.0/authorize?prompt=select_account",
        ),
        oauth = true,
        accent = Color(0xFF0288D1),
        glyph = "O",
    ),
    CloudPreset(
        id = "yandex-webdav",
        title = strings.yandexDiskWebdav,
        subtitle = strings.presetWebdavFallback,
        backend = "webdav",
        fixed = mapOf("url" to "https://webdav.yandex.ru", "vendor" to "other"),
        fields = listOf(
            PresetField("user", strings.fieldLogin),
            PresetField(
                key = "pass",
                label = strings.fieldAppPassword,
                isPassword = true,
                help = strings.yandexAppPasswordHelp,
            ),
        ),
        hint = strings.yandexWebdavHint,
        accent = Color(0xFFD32F2F),
        glyph = "Я",
    ),
    CloudPreset(
        id = "webdav",
        title = "WebDAV",
        subtitle = strings.presetAnyServer,
        backend = "webdav",
        fields = listOf(
            PresetField("url", strings.fieldServerUrl, help = strings.fieldServerUrlHint),
            PresetField("user", strings.fieldLogin, required = false),
            PresetField("pass", strings.fieldPassword, isPassword = true, required = false),
        ),
        accent = Color(0xFF6D4C41),
        glyph = "W",
    ),
    CloudPreset(
        id = "sftp",
        title = "SFTP",
        subtitle = strings.presetSshAccess,
        backend = "sftp",
        fields = listOf(
            PresetField("host", strings.fieldServer),
            PresetField("user", strings.fieldLogin, required = false),
            PresetField("pass", strings.fieldPassword, isPassword = true, required = false),
        ),
        accent = Color(0xFF455A64),
        glyph = "S",
    ),
)
