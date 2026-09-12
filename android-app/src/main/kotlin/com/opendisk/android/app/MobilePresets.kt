package com.opendisk.android.app

import androidx.compose.ui.graphics.Color

/** Поле, которое спрашиваем у человека при добавлении облака. */
data class PresetField(
    val key: String,
    val label: String,
    val isPassword: Boolean = false,
    val required: Boolean = true,
)

/**
 * Готовое подключение к сервису — то же, что плитки на компьютере.
 *
 * До этого выпуска на телефоне был список из трёх слов — `webdav`, `sftp`,
 * `ftp`, — и человек должен был сам знать, что Яндекс.Диск подключается по
 * WebDAV, по какому адресу и с каким паролем. Теперь знает приложение.
 */
data class MobilePreset(
    val id: String,
    val title: String,
    val subtitle: String,
    /** Тип бэкенда rclone. */
    val backend: String,
    /** Что подставляется само и не показывается. */
    val fixed: Map<String, String> = emptyMap(),
    val fields: List<PresetField>,
    /** Предупреждение над формой — например, про пароль приложения. */
    val hint: String? = null,
    /** Цвет плитки. Свой, не фирменный: чужие логотипы мы не копируем. */
    val accent: Color,
    val glyph: String,
)

/**
 * Сервисы, которые настраиваются логином и паролем.
 *
 * Google Диск, Dropbox и OneDrive сюда сознательно не попали: они
 * подтверждают доступ в браузере, и этот поток должен вернуть человека из
 * браузера обратно в приложение. На телефоне этого пока нет —
 * [MobileStrings.browserServicesMissing] говорит об этом прямо, вместо того
 * чтобы оставлять человека гадать.
 */
fun mobilePresets(strings: MobileStrings): List<MobilePreset> = listOf(
    MobilePreset(
        id = "yandex-webdav",
        title = strings.yandexWebdav,
        subtitle = strings.appPassword,
        backend = "webdav",
        fixed = mapOf("url" to "https://webdav.yandex.ru", "vendor" to "other"),
        fields = listOf(
            PresetField("user", strings.login),
            PresetField("pass", strings.appPassword, isPassword = true),
        ),
        hint = strings.yandexHint,
        accent = Color(0xFFD32F2F),
        glyph = "Я",
    ),
    MobilePreset(
        id = "mailru",
        title = strings.mailru,
        subtitle = strings.appPassword,
        backend = "mailru",
        fields = listOf(
            PresetField("user", strings.login),
            PresetField("pass", strings.appPassword, isPassword = true),
        ),
        hint = strings.mailruHint,
        accent = Color(0xFF1E88E5),
        glyph = "@",
    ),
    MobilePreset(
        id = "webdav",
        title = "WebDAV",
        subtitle = strings.anyWebdavServer,
        backend = "webdav",
        fields = listOf(
            PresetField("url", strings.serverUrl),
            PresetField("user", strings.login, required = false),
            PresetField("pass", strings.password, isPassword = true, required = false),
        ),
        accent = Color(0xFF6D4C41),
        glyph = "W",
    ),
    MobilePreset(
        id = "sftp",
        title = "SFTP",
        subtitle = strings.sshAccess,
        backend = "sftp",
        fields = listOf(
            PresetField("host", strings.host),
            PresetField("user", strings.login, required = false),
            PresetField("pass", strings.password, isPassword = true, required = false),
        ),
        accent = Color(0xFF455A64),
        glyph = "S",
    ),
    MobilePreset(
        id = "ftp",
        title = "FTP",
        subtitle = strings.ftpServer,
        backend = "ftp",
        fields = listOf(
            PresetField("host", strings.host),
            PresetField("user", strings.login, required = false),
            PresetField("pass", strings.password, isPassword = true, required = false),
        ),
        accent = Color(0xFF00695C),
        glyph = "F",
    ),
)
