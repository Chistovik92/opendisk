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
     * Пытается стать единственным экземпляром.
     *
     * @param onActivate вызывается, когда другой запуск просит показать окно.
     * @return true — мы единственные и можем работать; false — приложение уже
     *   запущено, ему отправлен сигнал показать окно, и нам нужно завершиться.
     */
    fun acquire(onActivate: () -> Unit): Boolean {
        val loopback = InetAddress.getLoopbackAddress()

        val socket = try {
            ServerSocket(PORT, BACKLOG, loopback)
        } catch (e: IOException) {
            // Порт занят. Это либо наш уже запущенный экземпляр, либо чужая
            // программа — различаем рукопожатием, чтобы не завершиться зря.
            return if (signalExistingInstance(loopback)) false else true
        }

        listenForActivation(socket, onActivate)
        return true
    }

    private fun listenForActivation(socket: ServerSocket, onActivate: () -> Unit) {
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
                        if (line == HANDSHAKE) {
                            it.getOutputStream().write("$HANDSHAKE\n".toByteArray())
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
    private fun signalExistingInstance(loopback: InetAddress): Boolean = runCatching {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(loopback, PORT), HANDSHAKE_TIMEOUT_MILLIS)
            socket.soTimeout = HANDSHAKE_TIMEOUT_MILLIS
            socket.getOutputStream().write("$HANDSHAKE\n".toByteArray())
            socket.getOutputStream().flush()
            socket.getInputStream().bufferedReader().readLine() == HANDSHAKE
        }
    }.getOrDefault(false)

    /** Порт из диапазона динамических — вероятность занять чужой минимальна. */
    private const val PORT = 49731
    private const val BACKLOG = 4
    private const val HANDSHAKE = "opendisk-show-window"
    private const val HANDSHAKE_TIMEOUT_MILLIS = 2000
}
