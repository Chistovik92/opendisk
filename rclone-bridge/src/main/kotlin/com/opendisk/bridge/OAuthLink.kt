package com.opendisk.bridge

import java.net.HttpURLConnection
import java.net.URI

/**
 * Ссылка подтверждения доступа, которую rclone печатает при входе через браузер.
 *
 * Как устроен вход. На `config/create` для сервиса с OAuth rclone поднимает
 * у себя маленький веб-сервер на `127.0.0.1:53682`, печатает ссылку вида
 * `http://127.0.0.1:53682/auth?state=…` и ждёт. Ссылка перенаправляет на
 * страницу сервиса, сервис после подтверждения возвращает браузер обратно
 * на тот же адрес — с кодом, — и rclone сам меняет код на токен. Сам запрос
 * `config/create` всё это время висит.
 *
 * На компьютере rclone живёт отдельным процессом, и ссылка читается из его
 * вывода. На телефоне rclone работает внутри приложения — и его веб-сервер
 * тоже; ссылку приложение перехватывает из собственного потока ошибок и
 * открывает в браузере. Для телефона это означает, что ни одного сервиса
 * не нужно прописывать отдельно: как входить, знает rclone.
 */
object OAuthLink {

    /** Ссылка в строке вывода rclone. */
    val PATTERN = Regex("http://127\\.0\\.0\\.1:\\d+/auth\\?state=[\\w\\-.~%]+")

    fun find(line: String): String? = PATTERN.find(line)?.value

    /**
     * Адрес, по которому ожидание можно прервать.
     *
     * Висящий `config/create` сам не отменяется: rclone ждёт возврата из
     * браузера без срока. Но его веб-сервер принимает и отказ — ровно в том
     * виде, в каком его прислал бы сервис, если человек нажал «Отклонить».
     * Для этого нужен тот же `state`, что был в ссылке, — чужой запрос
     * сервер отвергнет. Проверено на rclone 1.75.1: после такого запроса
     * `config/create` возвращается с ошибкой «access_denied».
     */
    fun cancelUrl(link: String): String? {
        val uri = runCatching { URI(link) }.getOrNull() ?: return null
        val state = uri.rawQuery?.split('&')
            ?.firstOrNull { it.startsWith("state=") }
            ?.removePrefix("state=")
            ?: return null
        return "${uri.scheme}://${uri.authority}/?state=$state&error=access_denied"
    }

    /**
     * Прерывает ожидание подтверждения. Ошибки глотаем: если сервер уже
     * закрылся, значит, ждать и так нечего.
     */
    fun cancel(link: String) {
        val target = cancelUrl(link) ?: return
        runCatching {
            val connection = URI(target).toURL().openConnection() as HttpURLConnection
            connection.connectTimeout = 2000
            connection.readTimeout = 2000
            try {
                connection.responseCode
            } finally {
                connection.disconnect()
            }
        }
    }
}
