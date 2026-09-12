package com.opendisk.app

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Оформление: как в системе, светлое или тёмное. */
enum class ThemeChoice(val code: String) {
    AUTO("auto"),
    LIGHT("light"),
    DARK("dark"),
    ;

    companion object {
        fun fromCode(code: String): ThemeChoice = entries.firstOrNull { it.code == code } ?: AUTO
    }
}

/**
 * Как выглядит система: тёмная ли тема и каким цветом она подсвечивает
 * выделенное. Цвет может быть неизвестен — тогда берём свой.
 */
data class SystemLook(val dark: Boolean = false, val accent: Color? = null)

/**
 * Тема и цвет выделения из настроек системы.
 *
 * Читаются штатными средствами каждой ОС — реестром, `defaults`, `gsettings`.
 * Java своего способа не даёт, а Compose на десктопе системную тему не знает
 * вовсе: там, где на Android есть `isSystemInDarkTheme`, здесь пусто.
 *
 * Всё, что не прочиталось, возвращает null, и приложение остаётся на светлой
 * теме со своими цветами. Это сознательно: чужая система может ответить чем
 * угодно, и падать из-за оформления приложение не должно.
 */
object SystemAppearance {

    fun read(
        osName: String = System.getProperty("os.name").orEmpty(),
        run: (List<String>) -> String? = ::runCommand,
    ): SystemLook {
        val os = osName.lowercase()
        return when {
            os.contains("win") -> SystemLook(
                dark = run(REG_THEME)?.let(::parseWindowsDark) ?: false,
                accent = run(REG_ACCENT)?.let(::parseWindowsAccent),
            )

            os.contains("mac") -> SystemLook(
                // `defaults read` для светлой темы завершается ошибкой: ключа
                // просто нет. Цвет выделения не читаем — там индекс из набора
                // Apple, и угадывать его соответствие цвету мы не станем.
                dark = run(MAC_THEME)?.trim().equals("Dark", ignoreCase = true),
            )

            else -> SystemLook(
                dark = run(GNOME_THEME)?.let(::parseGnomeDark)
                    ?: run(GNOME_GTK_THEME)?.let(::parseGnomeDark)
                    ?: false,
                accent = run(GNOME_ACCENT)?.let(::parseGnomeAccent),
            )
        }
    }

    /**
     * `AppsUseLightTheme` равен нулю при тёмной теме. Ключа может не быть
     * вовсе — на Windows до 1809 темы приложений не было.
     */
    fun parseWindowsDark(output: String): Boolean? =
        regDword(output, "AppsUseLightTheme")?.let { it == 0L }

    /**
     * `AccentColor` в реестре лежит в порядке ABGR, а не привычном ARGB:
     * это цвет в том виде, в каком его хранит сама Windows.
     */
    fun parseWindowsAccent(output: String): Color? {
        val value = regDword(output, "AccentColor") ?: return null
        val blue = ((value shr 16) and 0xFF).toInt()
        val green = ((value shr 8) and 0xFF).toInt()
        val red = (value and 0xFF).toInt()
        return Color(red, green, blue)
    }

    private fun regDword(output: String, name: String): Long? = output.lineSequence()
        .map { it.trim() }
        .firstOrNull { it.startsWith(name, ignoreCase = true) && it.contains("REG_DWORD") }
        ?.substringAfter("REG_DWORD")
        ?.trim()
        ?.removePrefix("0x")
        ?.toLongOrNull(16)

    /**
     * GNOME отвечает строкой в кавычках: `'prefer-dark'`. Название темы GTK —
     * запасной путь для окружений постарше, там признак один: слово dark
     * в названии.
     */
    fun parseGnomeDark(output: String): Boolean? {
        val value = output.trim().trim('\'').lowercase()
        if (value.isEmpty()) return null
        return when {
            value.contains("dark") -> true
            value == "default" || value == "prefer-light" -> false
            else -> null
        }
    }

    /** Цвет выделения GNOME 47 и новее приходит названием, а не кодом. */
    fun parseGnomeAccent(output: String): Color? =
        GNOME_ACCENTS[output.trim().trim('\'').lowercase()]

    private val GNOME_ACCENTS = mapOf(
        "blue" to Color(0xFF3584E4),
        "teal" to Color(0xFF2190A4),
        "green" to Color(0xFF3A944A),
        "yellow" to Color(0xFFC88800),
        "orange" to Color(0xFFED5B00),
        "red" to Color(0xFFE62D42),
        "pink" to Color(0xFFD56199),
        "purple" to Color(0xFF9141AC),
        "slate" to Color(0xFF6F8396),
    )

    private val REG_THEME = listOf(
        "reg", "query",
        "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize",
        "/v", "AppsUseLightTheme",
    )
    private val REG_ACCENT = listOf(
        "reg", "query", "HKCU\\Software\\Microsoft\\Windows\\DWM", "/v", "AccentColor",
    )
    private val MAC_THEME = listOf("defaults", "read", "-g", "AppleInterfaceStyle")
    private val GNOME_THEME =
        listOf("gsettings", "get", "org.gnome.desktop.interface", "color-scheme")
    private val GNOME_GTK_THEME =
        listOf("gsettings", "get", "org.gnome.desktop.interface", "gtk-theme")
    private val GNOME_ACCENT =
        listOf("gsettings", "get", "org.gnome.desktop.interface", "accent-color")

    /**
     * Запуск с ограничением по времени: отсутствующая программа и зависший
     * ответ одинаково не должны задерживать отрисовку окна.
     */
    private fun runCommand(command: List<String>): String? = runCatching {
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        if (!process.waitFor(2, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return null
        }
        if (process.exitValue() == 0) output else null
    }.getOrNull()
}

/**
 * Палитра Material 3 от цвета выделения системы.
 *
 * Настоящие «динамические цвета» Android строит библиотекой тональных палитр,
 * которой на десктопе нет. Здесь тот же приём, но вручную: цвет системы
 * раскладывается по светлоте на несколько тонов, из них и собираются роли
 * палитры. Этого хватает, чтобы кнопки и выделение были того же цвета, что
 * и в системе, и не хватает, чтобы спорить с Material о полутонах.
 *
 * Без цвета выделения возвращается обычная палитра Material — она уже
 * рассчитана и на свету, и в темноте.
 */
fun colorSchemeFor(dark: Boolean, accent: Color?): ColorScheme {
    val base = if (dark) darkColorScheme() else lightColorScheme()
    val hsl = (accent ?: return base).toHsl()
    // Блёклый цвет выделения (серый, почти белый) сделал бы палитру
    // нечитаемой: роли различались бы только светлотой.
    if (hsl.saturation < 0.15f) return base

    fun tone(lightness: Float, saturation: Float = hsl.saturation): Color =
        hslColor(hsl.hue, saturation.coerceIn(0f, 1f), lightness)

    val neutral = hsl.saturation * 0.12f
    val muted = hsl.saturation * 0.5f
    return if (dark) {
        base.copy(
            primary = tone(0.72f),
            onPrimary = tone(0.16f),
            primaryContainer = tone(0.28f),
            onPrimaryContainer = tone(0.90f),
            secondary = tone(0.70f, muted),
            onSecondary = tone(0.18f, muted),
            secondaryContainer = tone(0.26f, muted),
            onSecondaryContainer = tone(0.90f, muted),
            background = tone(0.08f, neutral),
            onBackground = tone(0.92f, neutral),
            surface = tone(0.08f, neutral),
            onSurface = tone(0.92f, neutral),
            surfaceVariant = tone(0.20f, neutral),
            onSurfaceVariant = tone(0.78f, neutral),
            surfaceContainerLowest = tone(0.06f, neutral),
            surfaceContainerLow = tone(0.11f, neutral),
            surfaceContainer = tone(0.13f, neutral),
            surfaceContainerHigh = tone(0.17f, neutral),
            surfaceContainerHighest = tone(0.21f, neutral),
            outline = tone(0.55f, neutral),
            outlineVariant = tone(0.30f, neutral),
        )
    } else {
        base.copy(
            primary = tone(0.40f),
            onPrimary = Color.White,
            primaryContainer = tone(0.90f),
            onPrimaryContainer = tone(0.12f),
            secondary = tone(0.42f, muted),
            onSecondary = Color.White,
            secondaryContainer = tone(0.90f, muted),
            onSecondaryContainer = tone(0.14f, muted),
            background = tone(0.99f, neutral),
            onBackground = tone(0.10f, neutral),
            surface = tone(0.99f, neutral),
            onSurface = tone(0.10f, neutral),
            surfaceVariant = tone(0.91f, neutral),
            onSurfaceVariant = tone(0.30f, neutral),
            surfaceContainerLowest = Color.White,
            surfaceContainerLow = tone(0.97f, neutral),
            surfaceContainer = tone(0.95f, neutral),
            surfaceContainerHigh = tone(0.93f, neutral),
            surfaceContainerHighest = tone(0.90f, neutral),
            outline = tone(0.50f, neutral),
            outlineVariant = tone(0.80f, neutral),
        )
    }
}

/** Цвет в координатах «тон — насыщенность — светлота»: так им удобно двигать. */
data class Hsl(val hue: Float, val saturation: Float, val lightness: Float)

fun Color.toHsl(): Hsl {
    val maximum = max(red, max(green, blue))
    val minimum = min(red, min(green, blue))
    val span = maximum - minimum
    val lightness = (maximum + minimum) / 2f
    if (span < 1e-6f) return Hsl(0f, 0f, lightness)

    val saturation = span / (1f - abs(2f * lightness - 1f)).coerceAtLeast(1e-6f)
    val hue = when (maximum) {
        red -> 60f * (((green - blue) / span) % 6f)
        green -> 60f * ((blue - red) / span + 2f)
        else -> 60f * ((red - green) / span + 4f)
    }
    return Hsl(
        hue = (hue + 360f) % 360f,
        saturation = saturation.coerceIn(0f, 1f),
        lightness = lightness,
    )
}

fun hslColor(hue: Float, saturation: Float, lightness: Float): Color {
    val chroma = (1f - abs(2f * lightness - 1f)) * saturation
    val second = chroma * (1f - abs((hue / 60f) % 2f - 1f))
    val shift = lightness - chroma / 2f
    val (r, g, b) = when {
        hue < 60f -> Triple(chroma, second, 0f)
        hue < 120f -> Triple(second, chroma, 0f)
        hue < 180f -> Triple(0f, chroma, second)
        hue < 240f -> Triple(0f, second, chroma)
        hue < 300f -> Triple(second, 0f, chroma)
        else -> Triple(chroma, 0f, second)
    }
    return Color(
        red = (r + shift).coerceIn(0f, 1f),
        green = (g + shift).coerceIn(0f, 1f),
        blue = (b + shift).coerceIn(0f, 1f),
    )
}
