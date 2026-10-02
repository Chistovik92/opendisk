package com.opendisk.app

import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Не даёт запустить второй экземпляр приложения.
 *
 * Окно прячется в трей, а не закрывается, поэтому пользователь легко жмёт ярлык
 * ещё раз — и без этой защиты получал вторую копию со своим процессом rclone
 * и второй иконкой в трее. Теперь повторный запуск просто показывает уже
 * работающее окно и завершается.
 *
 * Замок — слушающий сокет на localhost, а не файл: он гарантированно
 * освобождается при любом завершении процесса, включая аварийное, тогда как
 * файл-замок пришлось бы разбирать вручную после падения.
 */
object SingleInstance {

    /**
     * Порт и «пароль» рукопожатия — свои у каждого пользователя системы.
     *
     * Порт один на всю машину, а пользователей на ней может быть несколько
     * (быстрое переключение, терминальный сервер, автозапуск у каждого). С общим
     * портом второй пользователь, запустив OpenDisk, стучался в экземпляр
     * первого, «показывал» ему окно и молча закрывался — сам оставаясь без
     * приложения. Теперь порт зависит от имени пользователя, а рукопожатие
     * содержит его, так что чужой экземпляр за наш не принимается.
     */
    internal fun portFor(user: String): Int = BASE_PORT + (user.hashCode() and 0x7FFF_FFFF) % PORT_RANGE

    internal fun handshakeFor(user: String): String = "$HANDSHAKE:$user"

    /**
     * Пытается стать единственным экземпляром.
     *
     * @param user чей экземпляр: у каждого пользователя системы свой порт и своё рукопожатие.
     * @param onActivate вызывается, когда другой запуск просит показать окно.
     * @return true — мы единственные и можем работать; false — приложение уже
     *   запущено, ему отправлен сигнал показать окно, и нам нужно завершиться.
     */
    fun acquire(user: String = System.getProperty("user.name").orEmpty(), onActivate: () -> Unit): Boolean {
        val loopback = InetAddress.getLoopbackAddress()
        val port = portFor(user)
        val handshake = handshakeFor(user)

        val socket = try {
            ServerSocket(port, BACKLOG, loopback)
        } catch (e: IOException) {
            // Порт занят. Это либо наш уже запущенный экземпляр, либо чужая
            // программа — различаем рукопожатием, чтобы не завершиться зря.
            return !signalExistingInstance(loopback, port, handshake)
        }

        listenForActivation(socket, handshake, onActivate)
        return true
    }

    private fun listenForActivation(socket: ServerSocket, handshake: String, onActivate: () -> Unit) {
        Thread {
            while (!socket.isClosed) {
                val client = try {
                    socket.accept()
                } catch (e: IOException) {
                    return@Thread
                }
                client.use {
                    runCatching {
                        it.soTimeout = HANDSHAKE_TIMEOUT_MILLIS
                        val line = it.getInputStream().bufferedReader().readLine()
                        if (line == handshake) {
                            it.getOutputStream().write("$handshake\n".toByteArray())
                            it.getOutputStream().flush()
                            onActivate()
                        }
                    }
                }
            }
        }.apply {
            isDaemon = true
            name = "opendisk-single-instance"
            start()
        }
    }

    /**
     * Стучится в занятый порт. Отвечает true, только если там наш экземпляр:
     * чужую программу на этом порту мы не должны принимать за себя, иначе
     * приложение просто не запустится без внятной причины.
     */
    private fun signalExistingInstance(loopback: InetAddress, port: Int, handshake: String): Boolean = runCatching {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(loopback, port), HANDSHAKE_TIMEOUT_MILLIS)
            socket.soTimeout = HANDSHAKE_TIMEOUT_MILLIS
            socket.getOutputStream().write("$handshake\n".toByteArray())
            socket.getOutputStream().flush()
            socket.getInputStream().bufferedReader().readLine() == handshake
        }
    }.getOrDefault(false)

    /** Порты из диапазона динамических (49152+) — вероятность занять чужой минимальна. */
    private const val BASE_PORT = 49152
    private const val PORT_RANGE = 16000
    private const val BACKLOG = 4
    private const val HANDSHAKE = "opendisk-show-window"
    private const val HANDSHAKE_TIMEOUT_MILLIS = 2000
}
