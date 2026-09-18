package com.opendisk.android.app

import com.opendisk.bridge.RcloneClient
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Доступ к файлам через `su` — для телефонов с root.
 *
 * Процесс приложения видит только свою песочницу и общую память. Системные
 * каталоги, данные других приложений, `/data` целиком доступны лишь root,
 * и попасть туда можно одним способом — попросить `su` выполнить команду.
 * Magisk, KernelSU и подобные при первой просьбе спрашивают человека,
 * давать ли OpenDisk root, и запоминают ответ.
 *
 * Всё через штатные утилиты toybox, которые есть на любом Android с 6.0:
 * `ls`, `stat`, `cp`, `mv`, `rm`, `mkdir`.
 */
object RootShell {

    /** Результат команды: код возврата и её вывод вместе с ошибками. */
    data class Result(val exitCode: Int, val output: String) {
        val ok: Boolean get() = exitCode == 0
    }

    private val SU_LOCATIONS = listOf(
        "/system/bin/su",
        "/system/xbin/su",
        "/sbin/su",
        "/su/bin/su",
        "/system/sbin/su",
        "/vendor/bin/su",
        "/debug_ramdisk/su",
    )

    /**
     * Есть ли на телефоне `su` вообще. Это ещё не разрешение: его человек
     * даёт в окне менеджера root при первой команде — см. [requestAccess].
     */
    fun available(): Boolean =
        SU_LOCATIONS.any { File(it).exists() } ||
            System.getenv("PATH").orEmpty().split(':').any { File(it, "su").exists() }

    /** Просит root. Менеджер root покажет свой вопрос; ответ — уже здесь. */
    fun requestAccess(): Boolean = run("id").let { it.ok && "uid=0" in it.output }

    /**
     * Выполняет команду оболочки от root.
     *
     * Срок нужен: окно менеджера root человек может оставить без ответа,
     * а копирование большого файла идёт долго — поэтому он у каждой
     * команды свой.
     */
    fun run(command: String, timeoutSeconds: Long = DEFAULT_TIMEOUT_SECONDS): Result = runCatching {
        val process = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
        val output = StringBuilder()
        val reader = Thread {
            runCatching { process.inputStream.bufferedReader().forEachLine { output.appendLine(it) } }
        }.apply { start() }
        val exitCode = awaitExit(process, TimeUnit.SECONDS.toMillis(timeoutSeconds))
        if (exitCode == null) {
            process.destroy()
            return@runCatching Result(-1, "timeout")
        }
        reader.join(1000)
        Result(exitCode, output.toString())
    }.getOrElse { Result(-1, it.message.orEmpty()) }

    /**
     * Ждёт завершения не дольше срока. `waitFor` со сроком появился только в
     * Android 8, а приложение ставится с 7.0 — поэтому опрос вручную.
     */
    private fun awaitExit(process: Process, timeoutMillis: Long): Int? {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            try {
                return process.exitValue()
            } catch (_: IllegalThreadStateException) {
                Thread.sleep(POLL_MILLIS)
            }
        }
        return null
    }

    /**
     * Содержимое папки.
     *
     * `stat -L` идёт по ссылкам: `/sdcard` и половина корня Android — ссылки,
     * и показать их файлами значило бы не пустить в них. Битые ссылки `stat`
     * не осилит — их просто нет в списке.
     */
    fun list(directory: String): List<RcloneClient.Entry> {
        val script = "cd ${quote(directory)} && ls -A | while IFS= read -r f; do " +
            "stat -L -c '%F|%s|%Y|%n' -- \"\$f\" 2>/dev/null; done"
        val result = run(script)
        if (!result.ok && result.output.isBlank()) error("su: ${result.exitCode}")
        val parent = directory.trim('/')
        return result.output.lineSequence()
            .mapNotNull { parseStat(it, parent) }
            .sortedWith(compareByDescending<RcloneClient.Entry> { it.isDir }.thenBy { it.name.lowercase() })
            .toList()
    }

    /** Строка `stat -c '%F|%s|%Y|%n'` → запись списка; имя может содержать «|». */
    internal fun parseStat(line: String, parent: String): RcloneClient.Entry? {
        val parts = line.split('|', limit = 4)
        if (parts.size < 4 || parts[3].isEmpty()) return null
        val isDir = parts[0] == "directory"
        val name = parts[3]
        return RcloneClient.Entry(
            path = if (parent.isEmpty()) name else "$parent/$name",
            name = name,
            size = if (isDir) -1 else parts[1].toLongOrNull() ?: -1,
            isDir = isDir,
            // Секунды эпохи как есть: java.time на minSdk 24 без десахаризации нет.
            modTime = parts[2],
        )
    }

    fun mkdir(path: String): Result = run("mkdir -p ${quote(path)}")

    fun delete(path: String): Result = run("rm -rf ${quote(path)}", LONG_TIMEOUT_SECONDS)

    fun move(from: String, to: String): Result = run("mv -f ${quote(from)} ${quote(to)}", LONG_TIMEOUT_SECONDS)

    fun copy(from: String, to: String): Result = run("cp -rf ${quote(from)} ${quote(to)}", LONG_TIMEOUT_SECONDS)

    /**
     * Копирует в каталог приложения и отдаёт копию приложению: файл,
     * скопированный от root, принадлежит root, и без смены владельца
     * приложение не смогло бы ни прочитать его, ни потом удалить.
     */
    fun copyOut(from: String, to: String, appUid: Int): Result {
        val copied = copy(from, to)
        if (!copied.ok) return copied
        return run("chown -R $appUid:$appUid ${quote(to)} && chmod -R u+rwX ${quote(to)}")
    }

    /** Одинарные кавычки оболочки: внутри них не раскрывается ничего, кроме самой кавычки. */
    internal fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private const val POLL_MILLIS = 50L
    private const val DEFAULT_TIMEOUT_SECONDS = 30L
    private const val LONG_TIMEOUT_SECONDS = 60L * 60
}
