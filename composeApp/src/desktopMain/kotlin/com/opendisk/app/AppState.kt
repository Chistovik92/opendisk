package com.opendisk.app

import com.opendisk.bridge.MountSupport
import com.opendisk.bridge.RcloneClient

/**
 * Состояние подключения к rclone rcd.
 *
 * Вынесено в отдельный тип, потому что экран в каждом из этих состояний
 * выглядит принципиально по-разному: список облаков имеет смысл только
 * в [Ready], а в [NeedPassword] нужен не спиннер, а диалог пароля.
 */
sealed interface SessionState {

    /** Процесс запускается и поднимает порт. */
    data object Starting : SessionState

    /**
     * Конфиг зашифрован и пока не расшифрован. Обычное состояние на старте,
     * а не ошибка: пароль спрашиваем и разблокируем конфиг на лету.
     */
    data class NeedPassword(val wrongAttempt: Boolean = false) : SessionState

    /** Работаем. */
    data object Ready : SessionState

    /** Не удалось запуститься; [details] — последние строки вывода rcd. */
    data class Failed(val message: String, val details: List<String> = emptyList()) : SessionState
}

/** Одно облако в списке. */
data class CloudUi(
    val name: String,
    /** Точка монтирования, если облако подключено; null — отключено. */
    val mountPoint: String? = null,
    /** Место на облаке; null — бэкенд не сообщает или ещё не спрашивали. */
    val about: RcloneClient.AboutInfo? = null,
    /** Ошибка последней операции именно с этим облаком. */
    val error: String? = null,
    /** Идёт операция — блокируем кнопки, чтобы не запустить её дважды. */
    val busy: Boolean = false,
) {
    val isMounted: Boolean get() = mountPoint != null
}

data class UiState(
    val session: SessionState = SessionState.Starting,
    val clouds: List<CloudUi> = emptyList(),
    /**
     * Есть ли чем монтировать: WinFsp на Windows, FUSE на Linux, macFUSE на macOS.
     * Пока его нет, облака добавлять можно, а подключать как диск — нет.
     */
    val mount: MountSupport.Status = MountSupport.Status.Available,
    /** Идёт установка недостающего драйвера встроенным установщиком. */
    val installingMountDriver: Boolean = false,
    val rcloneDescription: String = "",
    val configDescription: String = "",
    /** Ошибка, не привязанная к конкретному облаку. */
    val globalError: String? = null,
    /**
     * Список бэкендов rclone для мастера добавления. Берётся из `config/providers`,
     * а не зашивается в код: набор и набор опций зависят от версии rclone.
     */
    val providers: List<RcloneClient.Provider> = emptyList(),
    /**
     * Ссылка для подтверждения доступа, если браузер не открылся сам.
     * rclone печатает её в свой вывод, откуда мы её и достаём.
     */
    val oauthUrl: String? = null,
    /** Настройки облаков: режим кэширования и точка монтирования. */
    val settings: Map<String, CloudSettings> = emptyMap(),
    /** Общие настройки: автозапуск и ограничение скорости. */
    val globalSettings: GlobalSettings = GlobalSettings(),
    /** Версия встроенного rclone — показывается в «О приложении». */
    val rcloneVersion: String? = null,
    /** Пути к файлам, которые спрашивают первым делом при разборе проблем. */
    val configFilePath: String = "",
    val settingsFilePath: String = "",
) {
    val mountAvailable: Boolean get() = mount is MountSupport.Status.Available
}

/** Режим кэширования и человеческое объяснение, что он означает на практике. */
data class CacheModeOption(
    val value: String,
    val title: String,
    val explanation: String,
)

/**
 * Провайдеры, вынесенные в начало списка: с них начинают почти все, а искать их
 * среди семи десятков бэкендов в алфавитном порядке неудобно. WebDAV первым —
 * через него подключаются Яндекс.Диск и Mail.ru.
 */
val POPULAR_PROVIDERS = listOf("webdav", "sftp", "ftp", "s3", "yandex", "drive", "dropbox", "onedrive")

/** Опции, которые показываем в мастере: обязательные плюс типовые логин с паролем. */
fun RcloneClient.Provider.formOptions(): List<RcloneClient.Option> =
    options.filter { !it.advanced && (it.required || it.name in COMMON_OPTIONAL_FIELDS) }
        .distinctBy { it.name }

private val COMMON_OPTIONAL_FIELDS = setOf("user", "pass", "vendor", "host", "port", "url")

/** Человекочитаемый размер: rclone отдаёт байты, показывать их пользователю бессмысленно. */
fun formatBytes(bytes: Long, strings: Strings): String {
    if (bytes < 1024) return "$bytes ${strings.bytes}"
    var value = bytes.toDouble() / 1024
    var unitIndex = 0
    while (value >= 1024 && unitIndex < strings.sizeUnits.lastIndex) {
        value /= 1024
        unitIndex++
    }
    return String.format("%.1f %s", value, strings.sizeUnits[unitIndex])
}

/** Строка вида «занято 1,6 ТБ из 1,8 ТБ» или null, если бэкенд ничего не сообщил. */
fun RcloneClient.AboutInfo.describe(strings: Strings): String? {
    val used = used ?: return total?.let { strings.totalOnly(formatBytes(it, strings)) }
    val total = total ?: return strings.usedOnly(formatBytes(used, strings))
    return strings.usedOf(formatBytes(used, strings), formatBytes(total, strings))
}

/**
 * Событие для показа в трее.
 *
 * Пользователь не сидит в окне приложения — оно живёт свёрнутым, — поэтому
 * о сорвавшемся подключении он иначе просто не узнает.
 */
data class AppNotification(
    val title: String,
    val message: String,
)
