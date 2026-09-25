package com.opendisk.android.app

import com.opendisk.bridge.AppReleases
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Какой apk брать под устройство.
 *
 * Раздельный apk вдвое меньше универсального, но apk чужой архитектуры не
 * встанет вовсе («Приложение не установлено»). Приставки Android TV и часы —
 * чаще 32-битные ARM, телефоны — 64-битные.
 */
class AppUpdateTest {

    @Test
    fun `phone gets the arm64 apk`() {
        assertEquals("OpenDisk-0.5.12-arm64.apk", AppUpdate.assetFor(ASSETS, listOf("arm64-v8a", "armeabi-v7a"))?.name)
    }

    @Test
    fun `tv box and watch get the arm32 apk`() {
        assertEquals("OpenDisk-0.5.12-arm32.apk", AppUpdate.assetFor(ASSETS, listOf("armeabi-v7a", "armeabi"))?.name)
    }

    @Test
    fun `other processors get the universal apk`() {
        // Для x86-64 (эмуляторы, редкие планшеты) раздельного apk в выпуске нет.
        assertEquals("OpenDisk-0.5.12-universal.apk", AppUpdate.assetFor(ASSETS, listOf("x86_64"))?.name)
        assertEquals("OpenDisk-0.5.12-universal.apk", AppUpdate.assetFor(ASSETS, emptyList())?.name)
    }

    @Test
    fun `missing split falls back to the universal apk`() {
        val onlyUniversal = ASSETS.filter { it.name.endsWith("-universal.apk") }
        assertEquals("OpenDisk-0.5.12-universal.apk", AppUpdate.assetFor(onlyUniversal, listOf("arm64-v8a"))?.name)
        assertNull(AppUpdate.assetFor(emptyList(), listOf("arm64-v8a")))
    }

    private companion object {
        val ASSETS = listOf(
            "OpenDisk-0.5.12-arm32.apk",
            "OpenDisk-0.5.12-arm64.apk",
            "OpenDisk-0.5.12-arm64.exe",
            "OpenDisk-0.5.12-universal.apk",
            "SHA256SUMS-Android",
        ).map { AppReleases.Asset(it, "https://example.invalid/$it") }
    }
}
