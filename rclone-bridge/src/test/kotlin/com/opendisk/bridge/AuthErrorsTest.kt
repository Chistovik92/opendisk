package com.opendisk.bridge

import kotlin.test.Test
import kotlin.test.assertEquals
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

    @Test
    fun `google shared client quota is recognised`() {
        val message = "403 Forbidden: Rate Limit Exceeded"
        assertTrue(AuthErrors.isQuotaExceeded(message))
        assertFalse(AuthErrors.isExpired(message))
    }

    @Test
    fun `dropped connection to oauth server is recognised as network block`() {
        val message = "oauth2.googleapis.com:443: connect: connection reset by peer"
        assertTrue(AuthErrors.isNetworkBlocked(message))
        assertTrue(
            AuthErrors.isNetworkBlocked(
                "Post \"https://oauth2.googleapis.com/token\": " +
                    "read tcp 192.168.1.2:55432->142.250.74.10:443: " +
                    "wsarecv: An existing connection was forcibly closed by the remote host.",
            ),
        )
    }

    @Test
    fun `explain classifies each kind and leaves the rest alone`() {
        assertEquals(AuthProblem.EXPIRED, AuthErrors.explain("couldn't fetch token: invalid_grant"))
        assertEquals(AuthProblem.QUOTA, AuthErrors.explain("403 Forbidden: rate limit exceeded"))
        assertEquals(AuthProblem.NETWORK, AuthErrors.explain("connection reset by peer"))
        assertEquals(AuthProblem.OTHER, AuthErrors.explain("directory not found"))
    }
}
