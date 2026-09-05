package com.opendisk.bridge

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Проверка команды запуска установщика драйвера.
 *
 * Здесь дважды ломалось на одном и том же — на пути `C:\Program Files\...`,
 * который в собранном приложении содержит пробел:
 *
 * 1. Путь подставлялся в аргумент `-Command`, и Java при запуске процесса сама
 *    расставляла кавычки поверх уже имеющихся — PowerShell молча не выполнялся,
 *    запрос администратора не появлялся вовсе.
 * 2. После перехода на `-EncodedCommand` осталась вторая половина: PowerShell
 *    склеивает элементы `-ArgumentList` пробелами и ничего не экранирует, из-за
 *    чего msiexec получал путь двумя аргументами и отвечал кодом 1639.
 *
 * Тесты работают со строкой пути, а не с [File]: `File.absolutePath` на Linux
 * дописал бы к «C:\...» рабочий каталог, и проверка перестала бы работать в CI.
 */
class MountSupportTest {

    private val windowsPath = """C:\Program Files\OpenDisk\app\resources\winfsp.msi"""

    @Test
    fun `path with spaces is quoted for msiexec`() {
        val script = MountSupport.installScriptFor(windowsPath)

        assertContains(script, "'\"$windowsPath\"'")
    }

    @Test
    fun `script asks for elevation and reports the outcome`() {
        val script = MountSupport.installScriptFor("""C:\tmp\winfsp.msi""")

        // Без runas запрос администратора не появится, и установка драйвера
        // провалится — это ключевая часть команды.
        assertContains(script, "-Verb RunAs")
        assertContains(script, "-Wait")
        // Результат нужен, чтобы отличить отказ пользователя от настоящей ошибки.
        assertContains(script, "EXIT=")
        assertContains(script, "LAUNCH_FAILED=")
    }

    @Test
    fun `single quotes in the path are escaped`() {
        val script = MountSupport.installScriptFor("""C:\Users\O'Brien\winfsp.msi""")

        assertContains(script, """'"C:\Users\O''Brien\winfsp.msi"'""")
    }

    @Test
    fun `missing installer is reported instead of launching anything`() {
        val absent = File("definitely-not-here.msi")

        val result = MountSupport.installBundled(absent)

        assertTrue(result is MountSupport.InstallResult.Failed)
        assertContains((result as MountSupport.InstallResult.Failed).details, "не найден")
    }

    @Test
    fun `check reports a usable status on any platform`() {
        // Тест не знает, установлен ли драйвер на этой машине, но знает, что
        // ответ должен быть одним из двух и не должен падать.
        val status = MountSupport.check()

        assertTrue(
            status is MountSupport.Status.Available || status is MountSupport.Status.Missing,
            "неожиданный статус: $status",
        )
        if (status is MountSupport.Status.Missing) {
            assertFalse(status.what.isBlank())
            assertFalse(status.explanation.isBlank())
        }
    }
}
