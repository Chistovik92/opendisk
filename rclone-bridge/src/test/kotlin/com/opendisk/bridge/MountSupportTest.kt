package com.opendisk.bridge

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Проверка команды запуска установщика драйвера.
 *
 * Ошибка, ради которой этот тест написан: путь к MSI подставлялся прямо в
 * аргумент `-Command`, и на пути с пробелом (в собранном приложении это
 * `C:\Program Files\OpenDisk\...`) команда разваливалась — PowerShell молча не
 * выполнялся, а запрос администратора вообще не появлялся. Проверить это на
 * машине разработчика было невозможно: там путь пробелов не содержит.
 *
 * Тесты работают со строкой пути, а не с [File], чтобы проверять именно
 * windows-экранирование и на Linux, где `File.absolutePath` дописал бы к
 * «C:\...» рабочий каталог.
 */
class MountSupportTest {

    @Test
    fun `path with spaces survives intact`() {
        val path = """C:\Program Files\OpenDisk\app\resources\winfsp.msi"""

        val script = MountSupport.installScriptFor(path)

        assertContains(script, "'$path'")
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

        assertContains(script, """'C:\Users\O''Brien\winfsp.msi'""")
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
