package com.opendisk.app

import androidx.compose.ui.graphics.Color

/**
 * Готовые подключения к популярным сервисам.
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
 */
val CLOUD_PRESETS: List<CloudPreset> = listOf(
    CloudPreset(
        id = "yandex",
        title = "Яндекс.Диск",
        subtitle = "вход через браузер",
        backend = "yandex",
        oauth = true,
        accent = Color(0xFFE53935),
        glyph = "Я",
    ),
    CloudPreset(
        id = "mailru",
        title = "Облако Mail.ru",
        subtitle = "логин и пароль",
        backend = "mailru",
        fields = listOf(
            PresetField("user", "Логин или почта"),
            PresetField(
                key = "pass",
                label = "Пароль для внешних приложений",
                isPassword = true,
                help = "Не основной пароль от почты: его нужно создать в настройках безопасности Mail.ru",
            ),
        ),
        hint = "Mail.ru не принимает основной пароль от аккаунта — нужен отдельный " +
            "пароль для внешних приложений.",
        accent = Color(0xFF1E88E5),
        glyph = "@",
    ),
    CloudPreset(
        id = "gdrive",
        title = "Google Диск",
        subtitle = "вход через браузер",
        backend = "drive",
        oauth = true,
        accent = Color(0xFF43A047),
        glyph = "G",
    ),
    CloudPreset(
        id = "dropbox",
        title = "Dropbox",
        subtitle = "вход через браузер",
        backend = "dropbox",
        oauth = true,
        accent = Color(0xFF1565C0),
        glyph = "D",
    ),
    CloudPreset(
        id = "onedrive",
        title = "OneDrive",
        subtitle = "вход через браузер",
        backend = "onedrive",
        oauth = true,
        accent = Color(0xFF0288D1),
        glyph = "O",
    ),
    CloudPreset(
        id = "yandex-webdav",
        title = "Яндекс.Диск по WebDAV",
        subtitle = "если браузерный вход не подходит",
        backend = "webdav",
        fixed = mapOf("url" to "https://webdav.yandex.ru", "vendor" to "other"),
        fields = listOf(
            PresetField("user", "Логин"),
            PresetField(
                key = "pass",
                label = "Пароль приложения",
                isPassword = true,
                help = "Создаётся на id.yandex.ru в разделе «Пароли приложений»",
            ),
        ),
        hint = "Для Яндекса по WebDAV нужен пароль приложения, а не пароль от аккаунта.",
        accent = Color(0xFFD32F2F),
        glyph = "Я",
    ),
    CloudPreset(
        id = "webdav",
        title = "WebDAV",
        subtitle = "любой сервер",
        backend = "webdav",
        fields = listOf(
            PresetField("url", "Адрес сервера", help = "Например, https://example.com/dav"),
            PresetField("user", "Логин", required = false),
            PresetField("pass", "Пароль", isPassword = true, required = false),
        ),
        accent = Color(0xFF6D4C41),
        glyph = "W",
    ),
    CloudPreset(
        id = "sftp",
        title = "SFTP",
        subtitle = "доступ по SSH",
        backend = "sftp",
        fields = listOf(
            PresetField("host", "Сервер"),
            PresetField("user", "Логин", required = false),
            PresetField("pass", "Пароль", isPassword = true, required = false),
        ),
        accent = Color(0xFF455A64),
        glyph = "S",
    ),
)
