package com.opendisk.app

import androidx.compose.ui.graphics.Color
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Разбор ответов системы про тему.
 *
 * Проверяется на настоящих ответах `reg`, `defaults` и `gsettings`: живой
 * системы каждого вида под рукой нет, а вот что именно они печатают —
 * известно и не меняется годами.
 */
class AppThemeTest {

    private val windowsDarkTheme = """

        HKEY_CURRENT_USER\Software\Microsoft\Windows\CurrentVersion\Themes\Personalize
            AppsUseLightTheme    REG_DWORD    0x0
            SystemUsesLightTheme    REG_DWORD    0x0

    """.trimIndent()

    private val windowsLightTheme = """

        HKEY_CURRENT_USER\Software\Microsoft\Windows\CurrentVersion\Themes\Personalize
            AppsUseLightTheme    REG_DWORD    0x1

    """.trimIndent()

    @Test
    fun `нулевой AppsUseLightTheme означает тёмную тему`() {
        assertEquals(true, SystemAppearance.parseWindowsDark(windowsDarkTheme))
        assertEquals(false, SystemAppearance.parseWindowsDark(windowsLightTheme))
    }

    @Test
    fun `без ключа темы ответа нет, а не «светлая»`() {
        // На Windows до 1809 ключа нет вовсе, и reg отвечает про это ошибкой.
        assertNull(SystemAppearance.parseWindowsDark("ERROR: The system was unable to find"))
    }

    @Test
    fun `цвет выделения Windows лежит в порядке ABGR`() {
        val output = """

            HKEY_CURRENT_USER\Software\Microsoft\Windows\DWM
                AccentColor    REG_DWORD    0xffd77800

        """.trimIndent()
        // 0xff d7 78 00 — альфа, синий, зелёный, красный.
        assertEquals(Color(0x00, 0x78, 0xd7), SystemAppearance.parseWindowsAccent(output))
    }

    @Test
    fun `ответы GNOME приходят в кавычках`() {
        assertEquals(true, SystemAppearance.parseGnomeDark("'prefer-dark'\n"))
        assertEquals(false, SystemAppearance.parseGnomeDark("'default'\n"))
        assertEquals(true, SystemAppearance.parseGnomeDark("'Adwaita-dark'\n"))
        assertEquals(Color(0xFF3584E4), SystemAppearance.parseGnomeAccent("'blue'\n"))
        // Окружение постарше про цвет выделения не знает и отвечает ошибкой.
        assertNull(SystemAppearance.parseGnomeAccent("No such key 'accent-color'"))
    }

    @Test
    fun `на macOS светлая тема отличается от тёмной ответом defaults`() {
        val dark = SystemAppearance.read(osName = "Mac OS X") { command ->
            if (command.contains("AppleInterfaceStyle")) "Dark\n" else null
        }
        val light = SystemAppearance.read(osName = "Mac OS X") { null }
        assertTrue(dark.dark)
        assertTrue(!light.dark)
    }

    @Test
    fun `неотвечающая система оставляет светлую тему и свои цвета`() {
        val look = SystemAppearance.read(osName = "Linux") { null }
        assertEquals(SystemLook(dark = false, accent = null), look)
    }

    @Test
    fun `без цвета выделения палитра остаётся материаловской`() {
        val light = colorSchemeFor(dark = false, accent = null)
        val dark = colorSchemeFor(dark = true, accent = null)
        assertNotEquals(light.background, dark.background)
        // Тёмная тема тёмная, светлая светлая — иначе перепутаны местами.
        assertTrue(dark.background.luminance() < light.background.luminance())
    }

    @Test
    fun `цвет выделения системы становится основным цветом`() {
        val accent = Color(0x00, 0x78, 0xd7)
        val scheme = colorSchemeFor(dark = false, accent = accent)
        // Не тот же самый цвет: для кнопки он приводится к своей светлоте.
        // Важно, что тон сохранился — иначе кнопки были бы другого цвета.
        assertTrue(abs(scheme.primary.toHsl().hue - accent.toHsl().hue) < 2f)
        assertTrue(scheme.primary.luminance() < scheme.onPrimary.luminance())
    }

    @Test
    fun `серый цвет выделения не ломает палитру`() {
        // Высокая контрастность и оттенки серого: если строить палитру от них,
        // роли перестают отличаться друг от друга.
        val scheme = colorSchemeFor(dark = true, accent = Color(0x80, 0x80, 0x82))
        assertEquals(colorSchemeFor(dark = true, accent = null).primary, scheme.primary)
    }

    @Test
    fun `перевод в HSL и обратно возвращает тот же цвет`() {
        listOf(Color(0x00, 0x78, 0xd7), Color(0xE6, 0x2D, 0x42), Color(0x3A, 0x94, 0x4A))
            .forEach { original ->
                val hsl = original.toHsl()
                val restored = hslColor(hsl.hue, hsl.saturation, hsl.lightness)
                assertTrue(
                    abs(restored.red - original.red) < 0.01f &&
                        abs(restored.green - original.green) < 0.01f &&
                        abs(restored.blue - original.blue) < 0.01f,
                    "$original превратился в $restored",
                )
            }
    }

    private fun Color.luminance(): Float = 0.2126f * red + 0.7152f * green + 0.0722f * blue
}
