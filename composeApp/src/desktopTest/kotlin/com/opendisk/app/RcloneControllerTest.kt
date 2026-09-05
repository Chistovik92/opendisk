package com.opendisk.app

import com.opendisk.bridge.MountSupport
import com.opendisk.bridge.RcloneConfigFile
import com.opendisk.bridge.RcloneProcess
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import kotlin.io.path.createTempDirectory
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Прогон контроллера против настоящего rclone: всё, что делает экран, кроме
 * отрисовки. Проверяем сценарии целиком — запуск, добавление облака, удаление,
 * разблокировку зашифрованного конфига.
 *
 * Пропускается, если бинарник не найден:
 *
 * ```
 * ./gradlew :composeApp:test -Dopendisk.rclone.path=/путь/к/rclone
 * ```
 */
class RcloneControllerTest {

    private var controller: RcloneController? = null

    @AfterTest
    fun shutdown() {
        controller?.shutdown()
    }

    private fun tempConfig(content: String): RcloneConfigFile {
        val dir = createTempDirectory("controller-config").toFile()
        return RcloneConfigFile(File(dir, "rclone.conf").apply { writeText(content) })
    }

    private fun controllerFor(config: RcloneConfigFile): RcloneController {
        val located = requireNotNull(RcloneProcess.locate())
        return RcloneController(
            locateRclone = { located },
            resolveConfig = { config },
        ).also { controller = it }
    }

    /** Ждём нужное состояние, а не спим наугад: операции идут в фоновых корутинах. */
    private fun awaitState(
        controller: RcloneController,
        timeoutMillis: Long = 20_000,
        predicate: (UiState) -> Boolean,
    ): UiState = runBlocking {
        withTimeout(timeoutMillis) { controller.state.first(predicate) }
    }

    @Test
    fun `starts on a plain config and lists its clouds`() {
        assumeTrue(RcloneProcess.locate() != null, "rclone не найден")

        val controller = controllerFor(tempConfig("[demo]\ntype = local\n"))
        controller.start()

        val ready = awaitState(controller) { it.session == SessionState.Ready && it.clouds.isNotEmpty() }

        assertEquals(listOf("demo"), ready.clouds.map { it.name })
        assertFalse(ready.clouds.single().isMounted)
        assertNull(ready.globalError)
        // Список бэкендов приезжает из самого rclone — без него мастер бесполезен.
        assertTrue(ready.providers.size > 20, "провайдеров пришло ${ready.providers.size}")
        assertTrue(ready.providers.any { it.name == "webdav" })
    }

    @Test
    fun `adds and removes a cloud`() {
        assumeTrue(RcloneProcess.locate() != null, "rclone не найден")

        val config = tempConfig("")
        val controller = controllerFor(config)
        controller.start()
        awaitState(controller) { it.session == SessionState.Ready }

        // Колбэк вызывается после обновления состояния, поэтому ждём именно его,
        // а не появления облака в списке — иначе проверка ошибки уходит в гонку.
        val creation = CompletableDeferred<String?>()
        controller.addCloud(
            name = "wd",
            type = "webdav",
            parameters = mapOf("url" to "https://example.invalid/dav", "user" to "someone"),
            secretKeys = emptySet(),
        ) { creation.complete(it) }

        assertNull(runBlocking { withTimeout(20_000) { creation.await() } })

        val added = awaitState(controller) { state -> state.clouds.any { it.name == "wd" } }
        assertContains(config.path.readText(), "[wd]")
        assertEquals("wd", added.clouds.single { it.name == "wd" }.name)

        controller.deleteCloud("wd")
        awaitState(controller) { it.clouds.none { cloud -> cloud.name == "wd" } }
        assertFalse(config.path.readText().contains("[wd]"))
    }

    @Test
    fun `password is stored obscured, never in clear text`() {
        assumeTrue(RcloneProcess.locate() != null, "rclone не найден")

        val config = tempConfig("")
        val controller = controllerFor(config)
        controller.start()
        awaitState(controller) { it.session == SessionState.Ready }

        val secret = "super-secret-passphrase"
        controller.addCloud(
            name = "wd",
            type = "webdav",
            parameters = mapOf("url" to "https://example.invalid/dav", "pass" to secret),
            secretKeys = setOf("pass"),
        ) { }

        awaitState(controller) { state -> state.clouds.any { it.name == "wd" } }

        val stored = config.path.readText()
        assertContains(stored, "[wd]")
        assertFalse(stored.contains(secret), "пароль оказался в конфиге открытым текстом")
    }

    /**
     * Монтирование целиком: облако подключается буквой диска, файлы читаются,
     * отключение убирает диск.
     *
     * Этот тест ловит ошибку, из-за которой приложение не видело смонтированное
     * облако: `mount/listmounts` возвращает не `"имя:"`, а `"имя://?/C:/путь"` —
     * сравнение на точное равенство не совпадало никогда.
     *
     * Пропускается, если в системе нет WinFsp/FUSE.
     */
    @Test
    fun `mounts a cloud as a drive, reads files and unmounts`() {
        assumeTrue(RcloneProcess.locate() != null, "rclone не найден")
        assumeTrue(MountSupport.check() is MountSupport.Status.Available, "нет WinFsp/FUSE")

        val data = createTempDirectory("cloud-data").toFile()
        File(data, "hello.txt").writeText("привет из облака", Charsets.UTF_8)

        val config = tempConfig("[disk]\ntype = local\n")
        val controller = controllerFor(config)
        controller.start()
        val ready = awaitState(controller) { it.session == SessionState.Ready && it.clouds.isNotEmpty() }
        assertTrue(ready.mountAvailable, "WinFsp есть, но приложение считает монтирование недоступным")

        // Бэкенд alias, а не local: у local нет корня в конфиге, он смотрит
        // в текущий каталог — читать из него в тесте было бы нечего.
        val creation = CompletableDeferred<String?>()
        controller.addCloud(
            name = "data",
            type = "alias",
            parameters = mapOf("remote" to data.absolutePath),
            secretKeys = emptySet(),
        ) { creation.complete(it) }
        assertNull(runBlocking { withTimeout(20_000) { creation.await() } })
        awaitState(controller) { state -> state.clouds.any { it.name == "data" } }

        val mountPoint = RcloneController.defaultMountPoint("data")
        controller.mount("data", mountPoint)

        val mounted = awaitState(controller, timeoutMillis = 40_000) { state ->
            state.clouds.firstOrNull { it.name == "data" }?.isMounted == true
        }
        assertEquals(mountPoint, mounted.clouds.single { it.name == "data" }.mountPoint)

        // Собственно проверка, ради которой всё: файл виден и читается через диск.
        val throughMount = File("$mountPoint\\hello.txt")
        assertTrue(throughMount.isFile, "файла нет на смонтированном диске $mountPoint")
        assertEquals("привет из облака", throughMount.readText(Charsets.UTF_8))

        controller.unmount("data")
        awaitState(controller, timeoutMillis = 40_000) { state ->
            state.clouds.firstOrNull { it.name == "data" }?.isMounted == false
        }
        assertFalse(throughMount.isFile, "диск $mountPoint остался после отключения")
    }

    @Test
    fun `encrypted config asks for a password and unlocks with the right one`() {
        assumeTrue(RcloneProcess.locate() != null, "rclone не найден")

        val dir = createTempDirectory("encrypted-controller").toFile()
        val file = File(dir, "rclone.conf")
        javaClass.getResourceAsStream("/encrypted-rclone.conf")!!.use { input ->
            file.outputStream().use { input.copyTo(it) }
        }
        val controller = controllerFor(RcloneConfigFile(file))
        controller.start()

        awaitState(controller) { it.session is SessionState.NeedPassword }

        controller.submitPassword("definitely-wrong")
        val rejected = awaitState(controller) {
            (it.session as? SessionState.NeedPassword)?.wrongAttempt == true
        }
        assertTrue((rejected.session as SessionState.NeedPassword).wrongAttempt)

        controller.submitPassword("hunter2")
        val unlocked = awaitState(controller) {
            it.session == SessionState.Ready && it.clouds.isNotEmpty()
        }
        assertEquals(listOf("demo"), unlocked.clouds.map { it.name })
    }
}
