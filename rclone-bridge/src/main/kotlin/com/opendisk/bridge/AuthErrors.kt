package com.opendisk.bridge

/**
 * Опознание ошибок доступа к облаку по тексту ответа rclone.
 *
 * Отдельного кода ошибки у RC API нет: `config/create` и монтирование
 * возвращают просто длинную строку rclone, и различать «доступ истёк»,
 * «сервис задросселил» и «сеть перекрыта» приходится по её содержимому.
 * Это нужно, чтобы сказать человеку, что делать, а не показать ему совет
 * rclone запустить `rclone config reconnect` — команду, которой у него нет.
 */
object AuthErrors {

    private val MARKERS = listOf(
        "invalid_grant",
        "couldn't fetch token",
        "token expired",
        "config reconnect",
        "empty token found",
    )

    /**
     * Google (и не только) отвечает «403: rate limit exceeded» на общий
     * идентификатор rclone, когда им пользуются слишком много людей сразу.
     * Свой идентификатор это лечит; путать с истёкшим доступом нельзя —
     * там нужен повторный вход, а тут — свои учётные данные.
     */
    private val QUOTA_MARKERS = listOf(
        "rate limit exceeded",
        "rate_limit_exceeded",
        "quota exceeded",
        "user rate limit exceeded",
        "usage limits exceeded",
    )

    /**
     * Признаки того, что до сервиса не достучаться: соединение рвут или
     * закрывают на полпути. Частая причина на этой машине — VPN/прокси,
     * из-под которого прямое соединение с oauth-сервером обрывается
     * («forcibly closed by the remote host»). Лечится прокси в настройках.
     */
    private val NETWORK_MARKERS = listOf(
        "forcibly closed by the remote host",
        "connection reset",
        "connection refused",
        "connection timed out",
        "no such host",
        "lookup ",
        "unexpected eof",
        "i/o timeout",
        "deadline exceeded",
        "network is unreachable",
        "tls handshake timeout",
    )

    /** Доступ к сервису истёк или отозван — нужен повторный вход в браузере. */
    fun isExpired(message: String): Boolean =
        MARKERS.any { message.contains(it, ignoreCase = true) }

    /** Сервис ограничил запросы — обычно общий идентификатор Google, нужен свой. */
    fun isQuotaExceeded(message: String): Boolean =
        QUOTA_MARKERS.any { message.contains(it, ignoreCase = true) }

    /** Сеть перекрыла путь к сервису — похоже на VPN/прокси, нужен прокси. */
    fun isNetworkBlocked(message: String): Boolean =
        NETWORK_MARKERS.any { message.contains(it, ignoreCase = true) }

    /** Классификация ошибки доступа: что случилось, а не сырой текст rclone. */
    fun explain(message: String): AuthProblem = when {
        isExpired(message) -> AuthProblem.EXPIRED
        isQuotaExceeded(message) -> AuthProblem.QUOTA
        isNetworkBlocked(message) -> AuthProblem.NETWORK
        else -> AuthProblem.OTHER
    }
}

/** Разновидность проблемы с доступом к облаку. */
enum class AuthProblem {
    /** Доступ истёк или отозван сервисом. */
    EXPIRED,

    /** Сервис ограничил запросы (общий идентификатор, лимиты аккаунта). */
    QUOTA,

    /** Сеть перекрыла путь к сервису (VPN, прокси, брандмауэр). */
    NETWORK,

    /** Что-то другое, не опознанное выше. */
    OTHER,
}
