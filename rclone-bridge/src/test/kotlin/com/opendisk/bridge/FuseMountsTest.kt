package com.opendisk.bridge

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FuseMountsTest {

    private val table = FuseMounts.parse(
        """
        sysfs /sys sysfs rw,nosuid,nodev,noexec,relatime 0 0
        /dev/sda2 / ext4 rw,relatime 0 0
        yandex: /home/anna/OpenDisk/yandex fuse.rclone rw,nosuid,nodev,relatime,user_id=1000,group_id=1000 0 0
        Мой\040диск: /home/anna/OpenDisk/Мой\040диск fuse.rclone rw,nosuid,nodev 0 0
        sshfs#host: /mnt/host fuse.sshfs rw 0 0
        """.trimIndent(),
    )

    @Test
    fun `rclone mounts are recognised by their fuse type`() {
        val mount = FuseMounts.mountedAt("/home/anna/OpenDisk/yandex", table)

        assertEquals("yandex:", mount?.source)
        assertTrue(mount!!.isRclone)
        assertFalse(FuseMounts.mountedAt("/mnt/host", table)!!.isRclone)
    }

    @Test
    fun `octal escapes in names are decoded`() {
        val mount = FuseMounts.mountedAt("/home/anna/OpenDisk/Мой диск", table)

        assertEquals("Мой диск:", mount?.source)
    }

    @Test
    fun `trailing slash does not matter`() {
        assertTrue(FuseMounts.mountedAt("/home/anna/OpenDisk/yandex/", table) != null)
    }

    @Test
    fun `nothing is mounted at an ordinary folder`() {
        assertNull(FuseMounts.mountedAt("/home/anna/Documents", table))
    }
}
