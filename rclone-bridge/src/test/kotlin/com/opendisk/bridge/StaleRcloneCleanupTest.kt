package com.opendisk.bridge

import java.io.File
import java.nio.file.Files
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Проверки опознания процесса.
 *
 * Тесты нарочно построены вокруг «не погасить чужое», а не вокруг «погасить
 * своё»: ошибка в эту сторону тихо убивает постороннюю программу пользователя,
 * и заметить её по симптомам почти невозможно.
 *
 * Опознаём на текущей JVM — это единственный процесс, про который в тесте
 * достоверно известно всё: и PID, и момент запуска, и путь. Гасить его,
 * разумеется, никто не пытается.
 */
class StaleRcloneCleanupTest {

    private val tempDir: File = Files.createTempDirectory("opendisk-stale").toFile()
    private val stateFile = File(tempDir, "rcd.pid")

    @AfterTest
    fun cleanup() {
        tempDir.deleteRecursively()
    }

    private fun currentProcessRecord(): StaleRcloneCleanup.Record {
        val handle = ProcessHandle.current()
        return StaleRcloneCleanup.Record(
            pid = handle.pid(),
            startedAtMillis = handle.info().startInstant().map(Instant::toEpochMilli).orElse(0L),
            command = handle.info().command().orElse(""),
        )
    }

    @Test
    fun `record survives writing and reading`() {
        val record = StaleRcloneCleanup.Record(1234, 1700000000000, "/usr/bin/rclone")

        val parsed = StaleRcloneCleanup.Record.parse(record.serialize())

        assertEquals(record, parsed)
    }

    @Test
    fun `garbage in the file is not a record`() {
        assertNull(StaleRcloneCleanup.Record.parse(""))
        assertNull(StaleRcloneCleanup.Record.parse("не число\n123\n/bin/rclone"))
        assertNull(StaleRcloneCleanup.Record.parse("123\n456"))
        // Пустой путь означает, что система не сказала, какая это программа, —
        // такую запись применять нельзя.
        assertNull(StaleRcloneCleanup.Record.parse("123\n456\n"))
    }

    @Test
    fun `matches the process it was written from`() {
        val record = currentProcessRecord()

        assertTrue(StaleRcloneCleanup.matches(record, ProcessHandle.current()))
    }

    @Test
    fun `does not match when the process started at another time`() {
        // Ровно тот случай, ради которого момент старта и записывается: система
        // выдала тот же PID другой программе.
        val record = currentProcessRecord().copy(startedAtMillis = 1_000_000L)

        assertFalse(StaleRcloneCleanup.matches(record, ProcessHandle.current()))
    }

    @Test
    fun `does not match when the program is different`() {
        val record = currentProcessRecord().copy(command = "/somewhere/else/rclone")

        assertFalse(StaleRcloneCleanup.matches(record, ProcessHandle.current()))
    }

    @Test
    fun `record without a start time is never applied`() {
        // Ноль означает «система не сказала». Гасить по одному PID нельзя.
        val record = currentProcessRecord().copy(startedAtMillis = 0L)

        assertFalse(StaleRcloneCleanup.matches(record, ProcessHandle.current()))
    }

    @Test
    fun `nothing to kill without a state file`() {
        assertNull(StaleRcloneCleanup(stateFile).killLeftover())
    }

    @Test
    fun `stale record about a long gone process is just dropped`() {
        // PID, которого заведомо нет: у процессов номера положительные.
        stateFile.writeText(
            StaleRcloneCleanup.Record(999_999_999, 1700000000000, "/usr/bin/rclone").serialize(),
        )

        val cleanup = StaleRcloneCleanup(stateFile)

        assertNull(cleanup.killLeftover())
        assertFalse(stateFile.exists(), "запись должна убираться, даже если гасить нечего")
    }

    @Test
    fun `forget removes the file`() {
        stateFile.writeText("что угодно")

        StaleRcloneCleanup(stateFile).forget()

        assertFalse(stateFile.exists())
    }

    @Test
    fun `remember writes down a real process`() {
        val process = ProcessHandle.current()
        val cleanup = StaleRcloneCleanup(stateFile)

        // remember принимает Process, а не ProcessHandle, поэтому запускаем
        // настоящий короткий процесс — заодно видно, что запись читается обратно.
        val started = ProcessBuilder(javaExecutable(), "-version")
            .redirectErrorStream(true)
            .start()
        cleanup.remember(started)
        started.waitFor()

        val written = StaleRcloneCleanup.Record.parse(stateFile.readText())
        assertEquals(started.pid(), written?.pid)
        assertTrue(written!!.command.isNotEmpty(), "путь к программе не записался")
        assertTrue(written.pid != process.pid(), "записан не тот процесс")
    }

    private fun javaExecutable(): String =
        ProcessHandle.current().info().command().orElse("java")
}
