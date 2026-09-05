package com.opendisk.bridge

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RcloneProcessTest {

    @Test
    fun `rcBaseUrl formats address correctly`() {
        val process = RcloneProcess(rclonePath = "rclone", rcAddr = "127.0.0.1:5572")
        assertTrue(process.rcBaseUrl == "http://127.0.0.1:5572")
    }

    @Test
    fun `isRunning is false before start`() {
        val process = RcloneProcess(rclonePath = "rclone")
        assertFalse(process.isRunning())
    }
}
