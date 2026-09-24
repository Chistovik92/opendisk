package com.opendisk.app

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Команда запуска установки обновления.
 *
 * Разбор контрольных сумм и само скачивание — общие с Android и проверяются
 * в rclone-bridge (AppReleasesTest).
 *
 * Сам сценарий установки проверяется не здесь, а в CI на настоящей Windows:
 * scripts/verify-windows-install.ps1 запускает тот же файл ресурсов. Здесь —
 * только то, как приложение его вызывает.
 */
class UpdateInstallerTest {

    @Test
    fun `installer path is passed as a parameter, quoted for the shell`() {
        val script = UpdateInstaller.installScript("""C:\Users\Кто-то\AppData\Local\Temp\OpenDisk-0.5.2-x64.exe""")

        // Путь с пробелами и кириллицей ломался бы без кавычек — на этом уже
        // спотыкались установка WinFsp и автозапуск.
        assertTrue(script.contains("""-Installer 'C:\Users\Кто-то\AppData\Local\Temp\OpenDisk-0.5.2-x64.exe'"""))
        assertTrue(script.contains("-Verb RunAs"), "установка в Program Files требует прав администратора")
    }

    @Test
    fun `the exe is run by itself, not through msiexec`() {
        val script = UpdateInstaller.installScript("""C:\t\OpenDisk-0.5.2-x64.exe""")

        // С 0.5.0 установщик — обёртка Burn. msiexec на .exe просто упал бы.
        assertFalse(script.contains("msiexec"), "exe нельзя ставить через msiexec")
        assertTrue(script.contains("'/passive'"), "обновление подтверждено кнопкой — вопросов больше не задаём")
    }

    @Test
    fun `single quotes in the path are escaped for powershell`() {
        val script = UpdateInstaller.installScript("""C:\it's here\OpenDisk.exe""")

        assertTrue(script.contains("it''s here"), "одинарная кавычка оборвала бы строку сценария")
    }

    @Test
    fun `launcher is passed only when known`() {
        val with = UpdateInstaller.installScript("""C:\t\a.exe""", """C:\Program Files\OpenDisk\OpenDisk.exe""")
        val without = UpdateInstaller.installScript("""C:\t\a.exe""", null)

        assertTrue(with.contains("""-Launcher 'C:\Program Files\OpenDisk\OpenDisk.exe'"""))
        assertFalse(without.contains("-Launcher"))
    }

    @Test
    fun `the script waits for this very process to exit before installing`() {
        val script = UpdateInstaller.installScript("""C:\t\a.exe""", null, ownPid = 4242)

        // Пока приложение работает, его файлы заняты: установщик откладывал
        // их удаление до перезагрузки, возвращал 3010, и после этого Burn
        // не брался ни за удаление, ни за следующее обновление.
        assertTrue(script.contains("-WaitForPid '4242'"))
        assertTrue(script.contains("WaitForExit"), "сценарий обязан дождаться выхода приложения")
    }

    @Test
    fun `leftovers are stopped by path, never by name alone`() {
        val script = PowerShellScript.load("install-update.ps1")

        // Чужой rclone, запущенный человеком отдельно, называется так же.
        // Гасить можно только процессы из каталога установки.
        assertTrue(script.contains("StartsWith(\$AppDir"))
    }

    @Test
    fun `comments are stripped before the script goes on the command line`() {
        val script = PowerShellScript.load("install-update.ps1")

        // Командная строка Windows ограничена, а -EncodedCommand раздувает
        // текст почти втрое. Пояснения нужны читающему файл, а не PowerShell.
        assertTrue(script.lines().none { it.trimStart().startsWith("#") })
        assertFalse(script.startsWith("\uFEFF"), "BOM внутри команды лишний")
        assertTrue(script.contains("param("))
    }

    @Test
    fun `linux package is installed by its own kind, then the app is started again`() {
        val file = File("/tmp/opendisk/updates/opendisk_0.5.12-1_amd64.deb")
        val command = UpdateInstaller.shellCommand(
            UpdateChecker.InstallTarget.DEB, file, "/opt/opendisk/bin/OpenDisk", ownPid = 4242,
        )

        assertEquals("linux/install-update.sh", command?.resource)
        assertEquals(listOf("deb", file.absolutePath, "/opt/opendisk/bin/OpenDisk", "4242"), command?.args)
        assertEquals(
            "rpm-alt",
            UpdateInstaller.shellCommand(UpdateChecker.InstallTarget.RPM_ALT, file, "/x", 1)?.args?.first(),
        )
    }

    @Test
    fun `on macos the whole app bundle is replaced, not the launcher inside it`() {
        val command = UpdateInstaller.shellCommand(
            UpdateChecker.InstallTarget.MACOS,
            File("/tmp/OpenDisk-1.5.12-arm64.dmg"),
            "/Applications/OpenDisk.app/Contents/MacOS/OpenDisk",
            ownPid = 7,
        )

        assertEquals("macos/install-update.sh", command?.resource)
        assertEquals("/Applications/OpenDisk.app", command?.args?.get(1))
        // Лаунчер не из пакета приложения — заменять нечего.
        assertNull(UpdateInstaller.shellCommand(UpdateChecker.InstallTarget.MACOS, File("/tmp/a.dmg"), "/usr/bin/x", 1))
    }

    @Test
    fun `shell installers are shipped with the app and wait for it to exit`() {
        for (resource in listOf("linux/install-update.sh", "macos/install-update.sh")) {
            val script = UpdateInstaller.loadShellScript(resource)
            assertTrue(script.startsWith("#!/bin/sh"), resource)
            assertTrue(script.contains("kill -0 \"\$pid\""), "$resource: пока приложение работает, ставить нельзя")
            // CRLF сломал бы sh: «\r» становится частью каждой команды.
            assertFalse(script.contains("\r"), "$resource: переводы строк должны быть LF")
        }
    }
}
