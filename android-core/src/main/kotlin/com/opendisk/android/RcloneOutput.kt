package com.opendisk.android

import android.system.Os
import android.util.Log
import java.io.FileInputStream
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * Вывод rclone, работающего внутри приложения.
 *
 * Нужен ради одной строки — ссылки входа через браузер: rclone печатает её
 * в свой журнал и ждёт, пока человек подтвердит доступ (см.
 * [com.opendisk.bridge.OAuthLink]). На компьютере журнал читается из вывода
 * отдельного процесса, здесь отдельного процесса нет.
 *
 * Как устроено. rclone пишет журнал в поток ошибок — в файловый дескриптор 2
 * (`fs/log`, обработчик по умолчанию берёт `os.Stderr`). gomobile при загрузке
 * библиотеки подставляет на этот дескриптор свой канал в logcat
 * (`internal/mobileinit`, `Dup3` на `os.Stderr.Fd()`). Если после загрузки
 * подставить на дескриптор 2 уже свой канал, всё, что rclone пишет, придёт
 * сюда, — а отсюда уходит в logcat под тегом `rclone`, так что для отладки
 * ничего не теряется. Проверено по исходникам rclone 1.75.1 и gomobile.
 */
object RcloneOutput {

    private const val TAG = "rclone"
    private const val RECENT_LINES = 200

    private val listeners = CopyOnWriteArrayList<(String) -> Unit>()
    private val recent = ArrayDeque<String>()

    @Volatile
    private var capturing = false

    /**
     * Начинает перехват. Вызывать после загрузки librclone: иначе gomobile при
     * загрузке переставит дескриптор обратно на свой канал.
     */
    @Synchronized
    fun capture() {
        if (capturing) return
        val (read, write) = Os.pipe()
        Os.dup2(write, STDERR)
        // Пишущий конец теперь живёт под номером 2 — лишняя копия не нужна.
        Os.close(write)

        thread(isDaemon = true, name = "rclone-output") {
            // Читать нужно без остановки: заполненный канал заблокировал бы
            // rclone на первой же записи в журнал.
            FileInputStream(read).bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    Log.i(TAG, line)
                    remember(line)
                    listeners.forEach { it(line) }
                }
            }
        }
        capturing = true
    }

    /** Последние строки — на случай, если ссылка появилась раньше подписки. */
    fun recentLines(): List<String> = synchronized(recent) { recent.toList() }

    fun addListener(listener: (String) -> Unit) {
        listeners += listener
    }

    fun removeListener(listener: (String) -> Unit) {
        listeners -= listener
    }

    private fun remember(line: String) = synchronized(recent) {
        recent.addLast(line)
        while (recent.size > RECENT_LINES) recent.removeFirst()
    }

    private const val STDERR = 2
}
