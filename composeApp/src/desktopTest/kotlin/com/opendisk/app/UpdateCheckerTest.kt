package com.opendisk.app

import com.opendisk.app.UpdateChecker.InstallTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Какой файл выпуска брать — под то, как установлено приложение.
 *
 * Ошибка здесь молчаливая: проверка обновлений просто перестанет находить
 * установщик, и понять это можно будет только по жалобе «а почему оно не
 * обновляется». Ровно так и вышло в 0.5.0: выпуски перешли на .exe, а здесь
 * искался .msi.
 *
 * Сравнение версий и разбор списка выпусков — общие с Android и проверяются
 * в rclone-bridge (AppReleasesTest).
 */
class UpdateCheckerTest {

    @Test
    fun `x64 windows gets the full x64 installer and its checksums`() {
        val update = UpdateChecker.newestUpdate(RELEASES_JSON, "0.5.0", InstallTarget.WINDOWS, "amd64")

        assertEquals("0.5.12", update?.version)
        assertEquals("OpenDisk-0.5.12-x64-offline.exe", update?.assetName)
        assertEquals("https://example.invalid/SHA256SUMS-Windows-x64", update?.checksumsUrl)
        assertEquals(InstallTarget.WINDOWS, update?.target)
    }

    @Test
    fun `arm windows gets the arm installer and its checksums`() {
        // Установщик другой архитектуры встал бы, но работал бы под эмуляцией —
        // или не встал бы вовсе. Путать их нельзя.
        val update = UpdateChecker.newestUpdate(RELEASES_JSON, "0.5.0", InstallTarget.WINDOWS, "aarch64")

        assertEquals("OpenDisk-0.5.12-arm64-offline.exe", update?.assetName)
        assertEquals("https://example.invalid/SHA256SUMS-Windows-arm64", update?.checksumsUrl)
    }

    @Test
    fun `release before 0_5_12 has only the old name, and it is the full installer`() {
        val json = """
            [{"tag_name":"v0.5.11","html_url":"https://example.invalid/0.5.11","draft":false,
              "assets":[{"name":"OpenDisk-0.5.11-x64.exe","browser_download_url":"https://example.invalid/x.exe"}]}]
        """.trimIndent()

        assertEquals(
            "OpenDisk-0.5.11-x64.exe",
            UpdateChecker.newestUpdate(json, "0.5.10", InstallTarget.WINDOWS)?.assetName,
        )
    }

    @Test
    fun `each linux installation gets its own package`() {
        fun assetFor(target: InstallTarget) = UpdateChecker.newestUpdate(RELEASES_JSON, "0.5.0", target)

        assertEquals("opendisk_0.5.12-1_amd64.deb", assetFor(InstallTarget.DEB)?.assetName)
        // rpm для Fedora и для ALT — разные пакеты с разными зависимостями.
        // Один вместо другого не встанет.
        assertEquals("opendisk-0.5.12-1.x86_64.rpm", assetFor(InstallTarget.RPM)?.assetName)
        assertEquals("opendisk-0.5.12-alt1.x86_64.rpm", assetFor(InstallTarget.RPM_ALT)?.assetName)
        assertEquals("OpenDisk-0.5.12-x86_64.AppImage", assetFor(InstallTarget.APPIMAGE)?.assetName)
        assertEquals("https://example.invalid/SHA256SUMS-Linux", assetFor(InstallTarget.DEB)?.checksumsUrl)
    }

    @Test
    fun `linux on arm has nothing to install`() {
        // Под Linux собирается только x86-64.
        val update = UpdateChecker.newestUpdate(RELEASES_JSON, "0.5.0", InstallTarget.DEB, "aarch64")

        assertEquals("0.5.12", update?.version)
        assertNull(update?.asset)
        assertNull(update?.target)
    }

    @Test
    fun `mac gets the image for its processor`() {
        // Имя образа — с единицей вместо нуля (macOS не принимает версию
        // бандла с нулём в начале), поэтому ищем по окончанию.
        assertEquals(
            "OpenDisk-1.5.12-arm64.dmg",
            UpdateChecker.newestUpdate(RELEASES_JSON, "0.5.0", InstallTarget.MACOS, "aarch64")?.assetName,
        )
        assertEquals(
            "https://example.invalid/SHA256SUMS-macOS-x64",
            UpdateChecker.newestUpdate(RELEASES_JSON, "0.5.0", InstallTarget.MACOS, "x86_64")?.checksumsUrl,
        )
    }

    @Test
    fun `unknown installation is offered as a page, not installed`() {
        val update = UpdateChecker.newestUpdate(RELEASES_JSON, "0.5.0", target = null)

        assertEquals("0.5.12", update?.version)
        assertNull(update?.assetUrl)
        assertEquals("https://example.invalid/0.5.12", update?.pageUrl)
    }

    @Test
    fun `installer arch follows the names in the releases`() {
        assertEquals("x64", UpdateChecker.installerArch("amd64"))
        assertEquals("x64", UpdateChecker.installerArch("x86_64"))
        assertEquals("arm64", UpdateChecker.installerArch("aarch64"))
        assertEquals("arm64", UpdateChecker.installerArch("ARM64"))
    }

    @Test
    fun `installation kind is taken from how the app was installed`() {
        val pkexec: (String) -> Boolean = { it == "/usr/bin/pkexec" }
        fun linux(launcher: String?, appImage: String? = null, exists: (String) -> Boolean = pkexec) =
            InstallTarget.detect("Linux", launcher, appImage, exists)

        assertEquals(InstallTarget.WINDOWS, InstallTarget.detect("Windows 11", null, null) { false })
        assertEquals(
            InstallTarget.MACOS,
            InstallTarget.detect("Mac OS X", "/Applications/OpenDisk.app/Contents/MacOS/OpenDisk", null) { false },
        )
        assertEquals(InstallTarget.APPIMAGE, linux("/home/u/OpenDisk.AppImage", appImage = "/home/u/OpenDisk.AppImage"))
        assertEquals(
            InstallTarget.DEB,
            linux("/opt/opendisk/bin/OpenDisk") { pkexec(it) || it == "/var/lib/dpkg/info/opendisk.list" },
        )
        assertEquals(
            InstallTarget.RPM_ALT,
            linux("/opt/opendisk/bin/OpenDisk") { pkexec(it) || it == "/etc/altlinux-release" },
        )
        assertEquals(InstallTarget.RPM, linux("/opt/opendisk/bin/OpenDisk"))
    }

    @Test
    fun `nothing is installed where there is no way to do it`() {
        // Запуск из исходников или распакованного каталога — ставить некуда.
        assertNull(InstallTarget.detect("Linux", null, null) { true })
        assertNull(InstallTarget.detect("Linux", "/home/u/opendisk/bin/OpenDisk", null) { true })
        assertNull(InstallTarget.detect("Mac OS X", "/Users/u/build/OpenDisk", null) { true })
        // Пакет стоит, но права спросить нечем: pkexec нет.
        assertNull(InstallTarget.detect("Linux", "/opt/opendisk/bin/OpenDisk", null) { false })
    }

    private companion object {
        /** Порядок нарочно не по возрастанию: GitHub его не гарантирует. */
        val RELEASES_JSON = """
            [
              {
                "tag_name": "librclone-ios-v1.75.1",
                "html_url": "https://example.invalid/librclone",
                "draft": false,
                "assets": [{"name":"Librclone.xcframework.zip","browser_download_url":"https://example.invalid/x.zip"}]
              },
              {
                "tag_name": "v0.5.12",
                "html_url": "https://example.invalid/0.5.12",
                "draft": false,
                "assets": [
                  ${asset("OpenDisk-0.5.12-x64.exe")},
                  ${asset("OpenDisk-0.5.12-x64-offline.exe")},
                  ${asset("OpenDisk-0.5.12-arm64.exe")},
                  ${asset("OpenDisk-0.5.12-arm64-offline.exe")},
                  ${asset("OpenDisk-0.5.12-x64.msi")},
                  ${asset("OpenDisk-1.5.12-x64.dmg")},
                  ${asset("OpenDisk-1.5.12-arm64.dmg")},
                  ${asset("opendisk_0.5.12-1_amd64.deb")},
                  ${asset("opendisk-0.5.12-1.x86_64.rpm")},
                  ${asset("opendisk-0.5.12-alt1.x86_64.rpm")},
                  ${asset("OpenDisk-0.5.12-x86_64.AppImage")},
                  ${asset("SHA256SUMS-Windows-x64")},
                  ${asset("SHA256SUMS-Windows-arm64")},
                  ${asset("SHA256SUMS-macOS-x64")},
                  ${asset("SHA256SUMS-macOS-arm64")},
                  ${asset("SHA256SUMS-Linux")}
                ]
              },
              {
                "tag_name": "v0.5.0",
                "html_url": "https://example.invalid/0.5.0",
                "draft": false,
                "assets": []
              }
            ]
        """.trimIndent()

        fun asset(name: String) = """{"name":"$name","browser_download_url":"https://example.invalid/$name"}"""
    }
}
