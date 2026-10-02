package com.opendisk.android.app

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Список облаков «нужно войти заново» после фоновой проверки токенов.
 *
 * Ошибка здесь либо вешает на здоровое облако ярлык «войдите заново», либо —
 * хуже — снимает его с протухшего, и человек узнаёт о проблеме по сломанному файлу.
 */
class TokenRefreshTest {

    private fun merge(before: Set<String>, existing: Set<String>, expired: Set<String>, unreachable: Set<String> = emptySet()) =
        TokenRefreshWorker.merge(before, existing, expired, unreachable)

    @Test
    fun `a newly expired cloud is added`() {
        assertEquals(setOf("gdrive"), merge(emptySet(), setOf("gdrive", "yandex"), setOf("gdrive")))
    }

    @Test
    fun `a cloud that answered is no longer marked`() {
        // Вошли заново (или токен ожил) — ярлык снимается.
        assertEquals(emptySet(), merge(setOf("gdrive"), setOf("gdrive"), emptySet()))
    }

    @Test
    fun `a cloud the check could not reach keeps its mark`() {
        // Нет сети: по неудачной проверке о токене не судим, ярлык остаётся как был.
        assertEquals(setOf("gdrive"), merge(setOf("gdrive"), setOf("gdrive"), emptySet(), unreachable = setOf("gdrive")))
        // И здоровому ярлык не вешается.
        assertEquals(emptySet(), merge(emptySet(), setOf("yandex"), emptySet(), unreachable = setOf("yandex")))
    }

    @Test
    fun `a deleted cloud disappears from the list`() {
        assertEquals(setOf("yandex"), merge(setOf("gdrive", "yandex"), setOf("yandex"), emptySet(), unreachable = setOf("yandex")))
    }
}
