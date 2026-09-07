package com.opendisk.app

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Проверка идентификатора приложения Google до похода в браузер.
 *
 * Без неё неверное значение выясняется только на странице Google — она отвечает
 * «Доступ заблокирован, ошибка 401: invalid_client, The OAuth client was not
 * found», — а окно приложения остаётся ждать подтверждения, которого уже
 * не будет.
 */
class GoogleClientIdTest {

    @Test
    fun `real client id is accepted`() {
        // Встроенный в rclone — образец настоящего.
        assertTrue(looksLikeGoogleClientId("202264815644.apps.googleusercontent.com"))
        assertTrue(looksLikeGoogleClientId("1234-abc.apps.googleusercontent.com"))
    }

    @Test
    fun `empty means use the built-in one`() {
        // Пустое значение допустимо: rclone возьмёт свой общий идентификатор.
        // Медленный, но рабочий — запрещать это нельзя.
        assertTrue(looksLikeGoogleClientId(""))
        assertTrue(looksLikeGoogleClientId("   "))
    }

    @Test
    fun `pasted with whitespace still counts as valid`() {
        // Из браузера значение приезжает то с пробелом, то с переводом строки.
        // Обрезка происходит перед отправкой, поэтому и проверка должна их терпеть.
        assertTrue(looksLikeGoogleClientId(" 202264815644.apps.googleusercontent.com "))
        assertTrue(looksLikeGoogleClientId("202264815644.apps.googleusercontent.com\n"))
    }

    @Test
    fun `typo and wrong value are refused`() {
        assertFalse(looksLikeGoogleClientId("202264815644"))
        assertFalse(looksLikeGoogleClientId("202264815644.apps.googleusercontent"))
        // Секрет вместо идентификатора — частая путаница, поля рядом.
        assertFalse(looksLikeGoogleClientId("GOCSPX-abcdefghijklmnop"))
        assertFalse(looksLikeGoogleClientId("мой проект"))
    }
}
