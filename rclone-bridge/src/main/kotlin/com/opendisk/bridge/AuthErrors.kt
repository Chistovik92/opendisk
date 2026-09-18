package com.opendisk.bridge

/**
 * Опознание ошибок «доступ к сервису истёк».
 *
 * Токен OAuth живёт не вечно: Google отзывает его через неделю у приложений
 * в режиме тестирования, после смены пароля, после долгого простоя. rclone
 * отвечает на это длинной строкой вида «couldn't fetch token: invalid_grant:
 * maybe token expired? - try refreshing with "rclone config reconnect …"» —
 * с советом запустить команду, которой у человека с OpenDisk нет. Отдельного
 * кода ошибки у RC API нет, поэтому узнаём по тексту.
 */
object AuthErrors {

    private val MARKERS = listOf(
        "invalid_grant",
        "couldn't fetch token",
        "token expired",
        "config reconnect",
        "empty token found",
    )

    fun isExpired(message: String): Boolean =
        MARKERS.any { message.contains(it, ignoreCase = true) }
}
