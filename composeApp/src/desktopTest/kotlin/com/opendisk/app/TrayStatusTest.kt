package com.opendisk.app

import com.opendisk.bridge.RcloneClient.CompletedTransfer
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Скорость и недавние файлы в трее.
 *
 * Ошибка здесь не ломает работу, а показывает человеку неправду: скорость
 * после конца передачи или файл, который не дошёл, в списке «синхронизировано».
 */
class TrayStatusTest {

    @Test
    fun `speed is the growth of the byte counter over elapsed time`() {
        val meter = NetworkMeter()
        // Первый замер скорости не даёт: не с чем сравнивать.
        assertEquals(0.0, meter.sample(1_000, 10_000))
        assertEquals(2_048.0, meter.sample(1_000 + 4_096, 12_000))
        // Передача кончилась — счётчик стоит, и скорость сразу ноль, а не «сглаженная».
        assertEquals(0.0, meter.sample(5_096, 14_000))
    }

    @Test
    fun `counter reset by rclone is not a negative speed`() {
        val meter = NetworkMeter()
        meter.sample(1_000_000, 0)
        assertEquals(0.0, meter.sample(500, 2_000))
        // И дальше считаем от нового нуля.
        assertEquals(1_000.0, meter.sample(2_500, 4_000))
    }

    @Test
    fun `same instant and reset give zero, not infinity`() {
        val meter = NetworkMeter()
        meter.sample(0, 5_000)
        assertEquals(0.0, meter.sample(100, 5_000))
        meter.reset()
        assertEquals(0.0, meter.sample(9_999, 6_000))
    }

    @Test
    fun `speed is formatted in the largest sensible unit`() {
        val units = listOf("B/s", "KB/s", "MB/s", "GB/s")
        assertEquals("0 B/s", formatSpeed(0.0, units))
        assertEquals("900 B/s", formatSpeed(900.0, units))
        assertEquals("1.5 KB/s".replace('.', decimalSeparator()), formatSpeed(1536.0, units))
        assertEquals("2.0 MB/s".replace('.', decimalSeparator()), formatSpeed(2.0 * 1024 * 1024, units))
        assertEquals("3.0 GB/s".replace('.', decimalSeparator()), formatSpeed(3.0 * 1024 * 1024 * 1024 * 1024 / 1024, units))
        assertEquals("0 B/s", formatSpeed(-5.0, units))
    }

    @Test
    fun `background noise is not activity`() {
        assertFalse(TrayStatus(bytesPerSecond = 300.0).active)
        assertTrue(TrayStatus(bytesPerSecond = 5_000.0).active)
    }

    @Test
    fun `recent files are newest first, successful, without repeats and checks`() {
        val clouds = setOf("gdrive", "yandex")
        val transfers = listOf(
            done("old.txt", "2026-10-01T10:00:00Z", dst = "gdrive:"),
            done("new.txt", "2026-10-01T12:00:00Z", dst = "yandex:"),
            done("old.txt", "2026-10-01T11:00:00Z", dst = "gdrive:"),
            done("broken.bin", "2026-10-01T13:00:00Z", dst = "gdrive:", error = JsonPrimitive("couldn't copy")),
            done("checked.dat", "2026-10-01T14:00:00Z", dst = "gdrive:", checked = true),
            done("running.iso", "0001-01-01T00:00:00Z", dst = "gdrive:"),
        )

        val recent = recentFiles(transfers, clouds)

        assertEquals(listOf("new.txt", "old.txt"), recent.map { it.name })
        // Повтор схлопнулся в самый поздний.
        assertEquals("2026-10-01T11:00:00Z", recent.last().completedAt)
    }

    @Test
    fun `direction and cloud come from the sides of the transfer`() {
        val clouds = setOf("gdrive")
        val up = recentFiles(listOf(done("a", "2026-10-01T10:00:00Z", src = """C:\Users\x\cache""", dst = "gdrive:")), clouds).single()
        val down = recentFiles(listOf(done("b", "2026-10-01T10:00:00Z", src = "gdrive:", dst = "/home/x/cache")), clouds).single()

        assertTrue(up.upload)
        assertEquals("gdrive", up.cloud)
        assertFalse(down.upload)
        assertEquals("gdrive", down.cloud)
    }

    @Test
    fun `a windows drive letter is not a cloud name`() {
        assertNull(cloudOf("""C:\cache""", setOf("C", "gdrive")))
        assertNull(cloudOf("/tmp/cache", setOf("gdrive")))
        assertEquals("gdrive", cloudOf("gdrive:folder", setOf("gdrive")))
        assertNull(cloudOf("other:", setOf("gdrive")))
    }

    @Test
    fun `list is limited`() {
        val many = (1..20).map { done("f$it", "2026-10-01T10:%02d:00Z".format(it), dst = "gdrive:") }
        assertEquals(8, recentFiles(many, setOf("gdrive")).size)
        assertEquals("f20", recentFiles(many, setOf("gdrive")).first().name)
    }

    private fun decimalSeparator() = String.format("%.1f", 1.5)[1]

    private fun done(
        name: String,
        at: String,
        src: String = "/tmp/cache",
        dst: String = "gdrive:",
        checked: Boolean = false,
        error: kotlinx.serialization.json.JsonElement = JsonNull,
    ) = CompletedTransfer(name = name, size = 100, bytes = 100, checked = checked, completedAt = at, error = error, srcFs = src, dstFs = dst)
}
