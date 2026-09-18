package com.opendisk.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DriveLettersTest {

    @Test
    fun `letter is recognised with or without a trailing slash`() {
        assertEquals('Z', DriveLetters.letterOf("Z:"))
        assertEquals('Z', DriveLetters.letterOf("z:\\"))
        assertNull(DriveLetters.letterOf("""C:\Users\me\OpenDisk"""))
        assertNull(DriveLetters.letterOf(null))
    }

    @Test
    fun `first free letter skips system drives and letters pinned by other clouds`() {
        val settings = mapOf(
            "yandex" to CloudSettings(mountPoint = "E:"),
            "gdrive" to CloudSettings(),
        )

        val letter = DriveLetters.firstFree(
            cloud = "gdrive",
            settings = settings,
            systemTaken = setOf('C', 'D'),
        )

        assertEquals('F', letter)
    }

    @Test
    fun `a cloud may keep its own pinned letter`() {
        val settings = mapOf("yandex" to CloudSettings(mountPoint = "D:"))

        assertEquals('D', DriveLetters.firstFree("yandex", settings, systemTaken = setOf('C')))
    }

    @Test
    fun `letters just handed to another mount are not reused`() {
        val letter = DriveLetters.firstFree(
            cloud = "b",
            settings = emptyMap(),
            systemTaken = setOf('C'),
            reserved = setOf('D'),
        )

        assertEquals('E', letter)
    }

    @Test
    fun `no letter when everything is taken`() {
        val letter = DriveLetters.firstFree("x", emptyMap(), systemTaken = ('C'..'Z').toSet())

        assertNull(letter)
    }

    @Test
    fun `choices mark other clouds and system drives but not the cloud's own mount`() {
        val settings = mapOf(
            "yandex" to CloudSettings(mountPoint = "Y:"),
            "gdrive" to CloudSettings(mountPoint = "G:"),
        )

        val choices = DriveLetters.choices(
            cloud = "gdrive",
            settings = settings,
            systemTaken = setOf('D', 'G', 'Y'),
            ownMounted = 'G',
        ).associateBy { it.letter }

        assertTrue(choices.getValue('Y') is DriveLetters.Choice.OtherCloud)
        assertTrue(choices.getValue('D') is DriveLetters.Choice.System)
        assertTrue(choices.getValue('G') is DriveLetters.Choice.Free)
        assertTrue(choices.getValue('Z') is DriveLetters.Choice.Free)
    }
}
