package com.opendisk.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Разбор списка выпусков GitHub.
 *
 * Здесь несколько вещей, на которых легко ошибиться молча — проверка обновлений
 * просто перестанет находить установщик, и понять это можно будет только
 * по жалобе «а почему оно не обновляется». Ровно так и вышло в 0.5.0: выпуски
 * перешли на .exe, а здесь искался .msi.
 */
class UpdateCheckerTest {

    @Test
    fun `versions are compared as numbers, not as text`() {
        assertTrue(AppVersion.isNewer("0.2.5", "0.1.25"))
        // Строкой «0.2.10» меньше «0.2.9» — на этом обновление на десятый
        // выпуск просто не предложилось бы.
        assertTrue(AppVersion.isNewer("0.2.10", "0.2.9"))
        assertTrue(AppVersion.isNewer("v0.2.6", "0.2.5"))
        assertTrue(AppVersion.isNewer("1.0.0", "0.9.9"))

        assertFalse(AppVersion.isNewer("0.2.5", "0.2.5"))
        assertFalse(AppVersion.isNewer("0.2.4", "0.2.5"))
        // Разной длины номера сравниваются как есть: 0.2 это то же, что 0.2.0.
        assertFalse(AppVersion.isNewer("0.2", "0.2.0"))
        assertTrue(AppVersion.isNewer("0.2.1", "0.2"))
    }

    @Test
    fun `unparseable version never looks newer`() {
        assertFalse(AppVersion.isNewer("librclone-v1.75.1", "0.2.5"))
        assertFalse(AppVersion.isNewer("", "0.2.5"))
        assertFalse(AppVersion.isNewer("завтрашняя", "0.2.5"))
        assertEquals(emptyList(), AppVersion.parts("v"))
    }

    @Test
    fun `release of the bundled library is not an application update`() {
        // В том же репозитории лежат выпуски librclone. Без фильтра приложение
        // однажды предложило бы «обновиться» до версии rclone.
        assertFalse(UpdateChecker.isAppTag("librclone-v1.75.1"))
        assertFalse(UpdateChecker.isAppTag("librclone-ios-v1.75.1"))
        assertTrue(UpdateChecker.isAppTag("v0.2.5"))
        assertFalse(UpdateChecker.isAppTag("0.2.5"))
    }

    @Test
    fun `x64 machine gets the x64 installer and its checksums`() {
        val update = UpdateChecker.newestUpdate(RELEASES_JSON, "0.5.0", "Windows 11", "amd64")

        assertEquals("0.5.2", update?.version)
        assertEquals("OpenDisk-0.5.2-x64.exe", update?.assetName)
        assertEquals("https://example.invalid/OpenDisk-0.5.2-x64.exe", update?.assetUrl)
        assertEquals("https://example.invalid/SHA256SUMS-Windows-x64", update?.checksumsUrl)
    }

    @Test
    fun `arm machine gets the arm installer and its checksums`() {
        // Установщик другой архитектуры встал бы, но работал бы под эмуляцией —
        // или не встал бы вовсе. Путать их нельзя.
        val update = UpdateChecker.newestUpdate(RELEASES_JSON, "0.5.0", "Windows 11", "aarch64")

        assertEquals("OpenDisk-0.5.2-arm64.exe", update?.assetName)
        assertEquals("https://example.invalid/SHA256SUMS-Windows-arm64", update?.checksumsUrl)
    }

    @Test
    fun `release without a matching installer is offered as a page, not installed`() {
        // Выпуск в старом формате, только с .msi. Показать обновление надо,
        // а ставить нечего — остаётся ссылка на страницу.
        val json = """
            [{"tag_name":"v0.6.0","html_url":"https://example.invalid/0.6.0","draft":false,
              "assets":[{"name":"OpenDisk-0.6.0.msi","browser_download_url":"https://example.invalid/x.msi"}]}]
        """.trimIndent()

        val update = UpdateChecker.newestUpdate(json, "0.5.2", "Windows 11", "amd64")

        assertEquals("0.6.0", update?.version)
        assertNull(update?.assetUrl)
        assertEquals("https://example.invalid/0.6.0", update?.pageUrl)
    }

    @Test
    fun `installer arch follows the names in the releases`() {
        assertEquals("x64", UpdateChecker.installerArch("amd64"))
        assertEquals("x64", UpdateChecker.installerArch("x86_64"))
        assertEquals("arm64", UpdateChecker.installerArch("aarch64"))
        assertEquals("arm64", UpdateChecker.installerArch("ARM64"))
    }

    @Test
    fun `nothing to update when the installed version is the newest`() {
        assertNull(UpdateChecker.newestUpdate(RELEASES_JSON, "0.5.2", "Windows 11"))
        assertNull(UpdateChecker.newestUpdate(RELEASES_JSON, "1.0.0", "Windows 11"))
    }

    @Test
    fun `drafts are not offered`() {
        val json = """
            [
              {"tag_name":"v9.9.9","html_url":"https://example.invalid/draft","draft":true,"assets":[]},
              {"tag_name":"v0.2.5","html_url":"https://example.invalid/0.2.5","draft":false,"assets":[]}
            ]
        """.trimIndent()

        assertEquals("0.2.5", UpdateChecker.newestUpdate(json, "0.1.0", "Windows 11")?.version)
    }

    @Test
    fun `outside windows there is no package to install automatically`() {
        val update = UpdateChecker.newestUpdate(RELEASES_JSON, "0.5.0", "Linux")

        // Обновление показать нужно, но ставить пакет за пользователя нельзя:
        // на Linux это дело пакетного менеджера. Остаётся ссылка на страницу.
        assertEquals("0.5.2", update?.version)
        assertNull(update?.assetUrl)
        assertEquals("https://example.invalid/0.5.2", update?.pageUrl)
    }

    @Test
    fun `broken response is not an update`() {
        assertNull(UpdateChecker.newestUpdate("не json", "0.1.0", "Windows 11"))
        assertNull(UpdateChecker.newestUpdate("[]", "0.1.0", "Windows 11"))
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
                "tag_name": "v0.5.2",
                "html_url": "https://example.invalid/0.5.2",
                "draft": false,
                "assets": [
                  {"name":"OpenDisk-0.5.2-x64.exe","browser_download_url":"https://example.invalid/OpenDisk-0.5.2-x64.exe"},
                  {"name":"OpenDisk-0.5.2-arm64.exe","browser_download_url":"https://example.invalid/OpenDisk-0.5.2-arm64.exe"},
                  {"name":"OpenDisk-0.5.2-x86_64.AppImage","browser_download_url":"https://example.invalid/x.AppImage"},
                  {"name":"SHA256SUMS-Windows-x64","browser_download_url":"https://example.invalid/SHA256SUMS-Windows-x64"},
                  {"name":"SHA256SUMS-Windows-arm64","browser_download_url":"https://example.invalid/SHA256SUMS-Windows-arm64"}
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
    }
}
