package com.opendisk.android.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Проверяет, что приложение действительно запускается на устройстве.
 *
 * Тест выглядит скромно, а проверяет много: чтобы дойти до пустого списка
 * облаков, приложение обязано загрузить нативную библиотеку rclone (около
 * ста мегабайт кода), проинициализировать её, задать свой путь к конфигу и
 * успешно сходить в RC API за списком облаков. Любая из этих ступеней
 * ломается тихо и только на настоящем Android — на JVM их попросту нет.
 */
@RunWith(AndroidJUnit4::class)
class MainActivityTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    /**
     * Строки на том языке, который выберет само приложение.
     *
     * Сверять с русским текстом напрямую нельзя: с 0.5.2 приложение
     * двуязычное, язык берётся из системы, а эмулятор в CI англоязычный.
     * Ровно на этом в 0.4.0 упали тесты десктопа — второй раз наступать
     * незачем.
     */
    private val strings = MobileStrings.system()

    @Test
    fun appStartsAndFinishesLoadingRclone() {
        // Первый запуск распаковывает нативную библиотеку, и на эмуляторе это
        // занимает секунды, а не миллисекунды. Ждём по признаку, а не по
        // таймауту: надпись про запуск уходит только когда rclone готов.
        rule.waitUntil(timeoutMillis = STARTUP_TIMEOUT_MILLIS) {
            rule.onAllNodesWithText(strings.starting).fetchSemanticsNodes().isEmpty()
        }

        rule.onNodeWithText("OpenDisk").assertIsDisplayed()
    }

    @Test
    fun freshInstallInvitesToAddTheFirstCloud() {
        rule.waitUntil(timeoutMillis = STARTUP_TIMEOUT_MILLIS) {
            rule.onAllNodesWithText(strings.starting).fetchSemanticsNodes().isEmpty()
        }

        // Конфига на свежем устройстве нет. Важно, что это не ошибка и не
        // пустой экран, а приглашение: с него начинается любой первый запуск.
        rule.onNodeWithText(strings.noClouds.lineSequence().first(), substring = true).assertIsDisplayed()
    }

    /**
     * Настройки открываются с главного экрана и показывают то, ради чего
     * заведены. Проверка дешёвая, а ломается такое незаметно: экран есть,
     * кнопки нет — и найти его нельзя.
     */
    @Test
    fun settingsOpenFromTheMainScreen() {
        rule.waitUntil(timeoutMillis = STARTUP_TIMEOUT_MILLIS) {
            rule.onAllNodesWithText(strings.starting).fetchSemanticsNodes().isEmpty()
        }

        rule.onNodeWithText(strings.settings).performClick()
        rule.onNodeWithText(strings.theme).assertIsDisplayed()
        rule.onNodeWithText(strings.themeAuto).assertIsDisplayed()
        rule.onNodeWithText(strings.language).assertIsDisplayed()
    }

    private companion object {
        /**
         * Щедро: на холодном эмуляторе распаковка библиотеки идёт заметно
         * дольше, чем на телефоне, и жёсткий короткий предел давал бы падения,
         * не связанные с тем, что проверяется.
         */
        const val STARTUP_TIMEOUT_MILLIS = 120_000L
    }
}
