package com.opendisk.bridge

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RcloneProcessTest {

    private val binaryName =
        if (System.getProperty("os.name").lowercase().contains("win")) "rclone.exe" else "rclone"

    private val touchedProperties = listOf(
        RcloneProcess.OVERRIDE_PROPERTY,
        RcloneProcess.COMPOSE_RESOURCES_PROPERTY,
    )

    @AfterTest
    fun clearProperties() {
        touchedProperties.forEach(System::clearProperty)
    }

    private fun fakeRcloneIn(dir: File): File =
        File(dir, binaryName).apply {
            writeText("#!/bin/sh\n")
            setExecutable(true)
        }

    @Test
    fun `every process gets its own long random password`() {
        val first = RcloneProcess(rclonePath = "x").credentials
        val second = RcloneProcess(rclonePath = "x").credentials

        assertTrue(first.password.length >= 40, "256 бит в base64 — не короче 43 знаков")
        assertTrue(first.password != second.password)
        assertEquals("opendisk", first.user)
    }

    @Test
    fun `password does not leak through toString`() {
        val credentials = RcCredentials("opendisk", "секрет")
        assertTrue("секрет" !in credentials.toString())
    }

    @Test
    fun `basic auth header is standard`() {
        // «opendisk:pass» в base64 — как это делает любой клиент.
        assertEquals("Basic b3BlbmRpc2s6cGFzcw==", RcCredentials("opendisk", "pass").basicAuthHeader())
    }

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

    @Test
    fun `locate finds bundled binary in compose resources dir`() {
        val resources = createTempDirectory("bundled").toFile()
        val binary = fakeRcloneIn(resources)
        System.setProperty(RcloneProcess.COMPOSE_RESOURCES_PROPERTY, resources.absolutePath)

        val located = RcloneProcess.locate()

        assertEquals(RcloneProcess.Source.BUNDLED, located?.source)
        assertEquals(binary.absolutePath, located?.file?.absolutePath)
    }

    @Test
    fun `explicit override wins over bundled binary`() {
        val bundledDir = createTempDirectory("bundled").toFile()
        fakeRcloneIn(bundledDir)
        System.setProperty(RcloneProcess.COMPOSE_RESOURCES_PROPERTY, bundledDir.absolutePath)

        val overrideDir = createTempDirectory("override").toFile()
        val overrideBinary = fakeRcloneIn(overrideDir)
        System.setProperty(RcloneProcess.OVERRIDE_PROPERTY, overrideBinary.absolutePath)

        val located = RcloneProcess.locate()

        assertEquals(RcloneProcess.Source.OVERRIDE, located?.source)
        assertEquals(overrideBinary.absolutePath, located?.file?.absolutePath)
    }

    @Test
    fun `missing bundled binary does not resolve to resources dir`() {
        val emptyResources = createTempDirectory("empty").toFile()
        System.setProperty(RcloneProcess.COMPOSE_RESOURCES_PROPERTY, emptyResources.absolutePath)

        val located = RcloneProcess.locate()

        // На машине разработчика rclone может лежать в PATH — тогда это законная
        // находка. Важно лишь, что пустой каталог ресурсов не выдаётся за встроенный.
        assertTrue(located == null || located.source == RcloneProcess.Source.SYSTEM_PATH)
    }

    @Test
    fun `override pointing at missing file is ignored`() {
        val dir = createTempDirectory("missing").toFile()
        System.setProperty(RcloneProcess.OVERRIDE_PROPERTY, File(dir, binaryName).absolutePath)
        System.setProperty(RcloneProcess.COMPOSE_RESOURCES_PROPERTY, dir.absolutePath)

        assertNull(RcloneProcess.locate()?.takeIf { it.source == RcloneProcess.Source.OVERRIDE })
    }
}
