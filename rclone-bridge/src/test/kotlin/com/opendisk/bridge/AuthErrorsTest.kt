package com.opendisk.bridge

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AuthErrorsTest {

    @Test
    fun `expired google token is recognised`() {
        val message = "couldn't find root directory ID: Get \"https://www.googleapis.com/drive/v3/files/root\": " +
            "couldn't fetch token: invalid_grant: maybe token expired? - try refreshing with \"rclone config reconnect gdrive:\""

        assertTrue(AuthErrors.isExpired(message))
    }

    @Test
    fun `ordinary errors are not mistaken for expired access`() {
        assertFalse(AuthErrors.isExpired("directory not found"))
        assertFalse(AuthErrors.isExpired("cannot find winfsp"))
    }
}
