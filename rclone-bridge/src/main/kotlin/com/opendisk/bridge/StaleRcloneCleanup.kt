package com.opendisk.bridge

import java.io.File
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * Гасит `rclone rcd`, переживший прошлый запуск приложения.
 *
 * Штатное завершение и обычное закрытие окна закрывают rcd сами — за это
 * отвечают [RcloneProcess.stop] и его shutdown hook. Но если приложение убили
 * (диспетчер задач, `kill -9`, выключение по кнопке), хук не отрабатывает, и
 * rcd остаётся жить: держит смонтированные диски, которые уже нечем отключить,
 * и продолжает занимать порт. Пользователь видит буквы дисков, за которыми
 * ничего нет, и не может подключить их заново.
 *
 * Поэтому запущенный rcd записывается в файл, а при следующем запуске
 * приложение проверяет запись и добивает то, что осталось.
 *
 * **Опознание процесса — главное здесь.** Одного PID мало: система переиспользует
 * номера, и по устаревшей записи можно погасить чужой процесс, вообще ни при чём.
 * Поэтому вместе с PID сохраняется момент запуска и путь к программе, и гасится
 * процесс только при совпадении всех трёх.
 */
class StaleRcloneCleanup(private val stateFile: File) {

    /**
     * Чем опознаём процесс. Момент запуска — это то, что делает запись
     * однозначной: PID может повториться, но не с той же секундой старта.
     */
    data class Record(
        val pid: Long,
        val startedAtMillis: Long,
        val command: String,
    ) {
        fun serialize(): String = "$pid\n$startedAtMillis\n$command\n"

        companion object {
            fun parse(text: String): Record? {
                val lines = text.lines()
                if (lines.size < 3) return null
                val pid = lines[0].trim().toLongOrNull() ?: return null
                val startedAt = lines[1].trim().toLongOrNull() ?: return null
                val command = lines[2].trim().ifEmpty { return null }
                return Record(pid, startedAt, command)
            }
        }
    }

    /** Запоминает запущенный rcd, чтобы найти его после аварийного завершения. */
    fun remember(process: Process) {
        val handle = process.toHandle()
        val record = Record(
            pid = handle.pid(),
            // Может отсутствовать: система не обязана отдавать эти сведения.
            // Тогда пишем ноль — сверка это учитывает и такую запись не применит.
            startedAtMillis = handle.info().startInstant().map(Instant::toEpochMilli).orElse(0L),
            command = handle.info().command().orElse(""),
        )
        runCatching {
            stateFile.parentFile?.mkdirs()
            stateFile.writeText(record.serialize())
        }
    }

    /** Убирает запись при штатном завершении: гасить больше нечего. */
    fun forget() {
        runCatching { stateFile.delete() }
    }

    /**
     * Находит и гасит rcd, оставшийся от прошлого запуска.
     *
     * @return PID погашенного процесса или `null`, если гасить было нечего.
     */
    fun killLeftover(): Long? {
        val record = readRecord() ?: return null
        forget()

        val handle = ProcessHandle.of(record.pid).orElse(null) ?: return null
        if (!matches(record, handle)) return null

        handle.destroy()
        // Даём rclone размонтировать то, что он держит. Не дождались — добиваем:
        // висящий процесс с занятыми дисками хуже, чем резко оборванный.
        val exited = runCatching {
            handle.onExit().get(STOP_GRACE_SECONDS, TimeUnit.SECONDS)
        }.isSuccess
        if (!exited) handle.destroyForcibly()
        return record.pid
    }

    private fun readRecord(): Record? =
        runCatching { stateFile.takeIf { it.isFile }?.readText() }
            .getOrNull()
            ?.let { Record.parse(it) }

    companion object {
        private const val STOP_GRACE_SECONDS = 10L

        /**
         * Тот ли это процесс, который мы записали.
         *
         * Три условия, и все обязательны:
         *
         * - процесс жив (иначе гасить нечего);
         * - момент запуска совпадает — это и отсекает чужой процесс, которому
         *   система выдала тот же PID. Ноль означает «система не сказала»:
         *   такую запись применять нельзя, риск убить постороннее не окупается;
         * - программа та же самая. Последняя проверка на случай, если первые две
         *   сойдутся по совсем уж невероятному стечению обстоятельств.
         */
        internal fun matches(record: Record, handle: ProcessHandle): Boolean {
            if (!handle.isAlive) return false
            if (record.startedAtMillis == 0L) return false

            val info = handle.info()
            val startedAt = info.startInstant().map(Instant::toEpochMilli).orElse(null)
                ?: return false
            // Момент старта система округляет по-разному в разных вызовах,
            // поэтому сверяем с точностью до секунды, а не до миллисекунды.
            if (startedAt / 1000 != record.startedAtMillis / 1000) return false

            val command = info.command().orElse(null) ?: return false
            return command == record.command
        }
    }
}
