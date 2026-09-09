package com.opendisk.android.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
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
 *
 * Именно поэтому он инструментальный, а не обычный: подменять здесь нечего,
 * весь смысл в том, что всё это происходит по-настоящему.
 */
@RunWith(AndroidJUnit4::class)
class MainActivityTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun appStartsAndFinishesLoadingRclone() {
        // Первый запуск распаковывает нативную библиотеку, и на эмуляторе это
        // занимает секунды, а не миллисекунды. Ждём по признаку, а не по
        // таймауту: заголовок появляется сразу, а надпись про запуск уходит
        // только когда rclone готов.
        rule.waitUntil(timeoutMillis = STARTUP_TIMEOUT_MILLIS) {
            rule.onAllNodesWithText(STARTING).fetchSemanticsNodes().isEmpty()
        }

        rule.onNodeWithText("OpenDisk").assertIsDisplayed()
    }

    @Test
    fun freshInstallInvitesToAddTheFirstCloud() {
        rule.waitUntil(timeoutMillis = STARTUP_TIMEOUT_MILLIS) {
            rule.onAllNodesWithText(STARTING).fetchSemanticsNodes().isEmpty()
        }

        // Конфига на свежем устройстве нет. Важно, что это не ошибка и не
        // пустой экран, а приглашение: с него начинается любой первый запуск.
        rule.onNodeWithText(NO_CLOUDS, substring = true).assertIsDisplayed()
    }

    private companion object {
        const val STARTING = "Запускаю rclone…"
        const val NO_CLOUDS = "Облаков пока нет"

        /**
         * Щедро: на холодном эмуляторе распаковка библиотеки идёт заметно
         * дольше, чем на телефоне, и жёсткий короткий предел давал бы падения,
         * не связанные с тем, что проверяется.
         */
        const val STARTUP_TIMEOUT_MILLIS = 120_000L
    }
}
