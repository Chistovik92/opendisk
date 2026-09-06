package com.opendisk.app

import com.opendisk.bridge.MountSupport
import com.opendisk.bridge.RcloneConfigFile
import com.opendisk.bridge.RcloneProcess
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
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

    /** Настройки тоже во временном файле: настоящие трогать нельзя. */
    private lateinit var settings: AppSettings

    private fun controllerFor(config: RcloneConfigFile): RcloneController {
        val located = requireNotNull(RcloneProcess.locate())
        settings = AppSettings(File(createTempDirectory("settings").toFile(), "settings.json"))
        return RcloneController(
            locateRclone = { located },
            resolveConfig = { config },
            settings = settings,
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

        // Точку монтирования контроллер берёт из настроек — задаём её там же,
        // где это делает диалог настроек облака.
        val mountPoint = RcloneController.defaultMountPoint("data")
        settings.update("data", CloudSettings(mountPoint = mountPoint))
        controller.mount("data")

        val mounted = awaitState(controller, timeoutMillis = 40_000) { state ->
            state.clouds.firstOrNull { it.name == "data" }?.isMounted == true
        }
        assertEquals(mountPoint, mounted.clouds.single { it.name == "data" }.mountPoint)

        // Собственно проверка, ради которой всё: файл виден и читается через диск.
        //
        // Путь собираем через File(родитель, имя), а не склейкой с обратным
        // слэшем: на Windows точка монтирования это "Z:", а на Linux — каталог,
        // и склейка давала там путь с буквальным «\» в имени файла. Тест был
        // написан под Windows и до включения в CI больше нигде не запускался.
        val throughMount = File(mountPoint, "hello.txt")
        assertTrue(throughMount.isFile, "файла нет на смонтированном диске $mountPoint")
        assertEquals("привет из облака", throughMount.readText(Charsets.UTF_8))

        controller.unmount("data")
        awaitState(controller, timeoutMillis = 40_000) { state ->
            state.clouds.firstOrNull { it.name == "data" }?.isMounted == false
        }
        assertFalse(throughMount.isFile, "диск $mountPoint остался после отключения")
    }

    /**
     * Переименование пересоздаёт запись в конфиге, поэтому главная опасность —
     * испортить пароль: значения в конфиге уже «затемнены», и повторная
     * обработка превратила бы их в мусор. Проверяем, что строка пароля
     * осталась ровно той же.
     */
    @Test
    fun `rename keeps the stored password intact`() {
        assumeTrue(RcloneProcess.locate() != null, "rclone не найден")

        val config = tempConfig("")
        val controller = controllerFor(config)
        controller.start()
        awaitState(controller) { it.session == SessionState.Ready }

        val creation = CompletableDeferred<String?>()
        controller.addCloud(
            name = "before",
            type = "webdav",
            parameters = mapOf("url" to "https://example.invalid/dav", "pass" to "secret-value"),
            secretKeys = setOf("pass"),
        ) { creation.complete(it) }
        assertNull(runBlocking { withTimeout(20_000) { creation.await() } })
        awaitState(controller) { state -> state.clouds.any { it.name == "before" } }

        val obscuredBefore = passwordLine(config.path.readText())

        val rename = CompletableDeferred<String?>()
        controller.renameCloud("before", "after") { rename.complete(it) }
        assertNull(runBlocking { withTimeout(20_000) { rename.await() } })

        val after = awaitState(controller) { state ->
            state.clouds.any { it.name == "after" } && state.clouds.none { it.name == "before" }
        }
        assertEquals(listOf("after"), after.clouds.map { it.name })

        val stored = config.path.readText()
        assertContains(stored, "[after]")
        assertFalse(stored.contains("[before]"))
        assertEquals(obscuredBefore, passwordLine(stored), "пароль изменился при переименовании")
        assertContains(stored, "https://example.invalid/dav")
    }

    private fun passwordLine(config: String): String =
        config.lineSequence().first { it.trimStart().startsWith("pass") }.trim()

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

    /**
     * Буква диска и каталог различаются не косметически: каталог перед
     * монтированием нужно создать, а букву — ни в коем случае, иначе она
     * окажется занята и rclone смонтировать в неё уже не сможет.
     */
    @Test
    fun `windows drive letters are told apart from directories`() {
        assertTrue(RcloneController.isWindowsDriveLetter("Z:"))
        assertTrue(RcloneController.isWindowsDriveLetter("Z:\\"))
        assertTrue(RcloneController.isWindowsDriveLetter("d:"))

        assertFalse(RcloneController.isWindowsDriveLetter("/home/user/OpenDisk/data"))
        assertFalse(RcloneController.isWindowsDriveLetter("""C:\Users\me\OpenDisk"""))
        assertFalse(RcloneController.isWindowsDriveLetter(""))
    }

    @Test
    fun `directory mount point is created, drive letter is not`() {
        val base = createTempDirectory("mount-point").toFile()
        val directory = File(base, "OpenDisk/data")

        RcloneController.prepareMountPoint(directory.absolutePath)

        // Ради этого всё: на свежей системе каталога ~/OpenDisk нет, а rclone
        // отказывается монтировать в несуществующий путь.
        assertTrue(directory.isDirectory, "каталог точки монтирования не создан")

        // А букву диска создавать нельзя — она должна остаться свободной.
        RcloneController.prepareMountPoint("Z:")
        assertFalse(File("Z:").exists(), "по букве диска что-то создалось")
    }

    /**
     * Сетевой режим — не косметика.
     *
     * По умолчанию rclone отдаёт диск Windows как обычный локальный, и она
     * обходится с ним как с локальным: индексирует, опрашивает свободное место,
     * считает размеры папок. Для диска, за которым сеть, это оборачивается
     * зависанием проводника — что и наблюдалось на живом Google Диске.
     */
    @Test
    fun `on windows a drive letter is mounted as a network drive`() {
        val options = RcloneController.mountOptionsFor(
            name = "Яндекс",
            mountPoint = "Z:",
            settings = CloudSettings(),
            osName = "Windows 11",
        )

        assertTrue(options.networkMode)
        assertEquals("Яндекс", options.volumeName)
    }

    @Test
    fun `mounting into a directory stays a plain mount`() {
        // Ограничение самой Windows: в сетевом режиме монтировать можно только
        // в букву диска. Включить его для каталога значит сломать монтирование.
        val options = RcloneController.mountOptionsFor(
            name = "disk",
            mountPoint = """C:\Users\me\OpenDisk\disk""",
            settings = CloudSettings(),
            osName = "Windows 11",
        )

        assertFalse(options.networkMode)
        assertNull(options.volumeName)
    }

    @Test
    fun `outside windows there is no network mode`() {
        val options = RcloneController.mountOptionsFor(
            name = "disk",
            mountPoint = "/home/me/OpenDisk/disk",
            settings = CloudSettings(),
            osName = "Linux",
        )

        assertFalse(options.networkMode)
    }

    @Test
    fun `cache always has a limit`() {
        val options = RcloneController.mountOptionsFor(
            name = "disk",
            mountPoint = "Z:",
            settings = CloudSettings(cacheMode = "full"),
            osName = "Windows 11",
        )

        // У rclone предела нет, и кэш растёт, пока не кончится место
        // на системном разделе.
        assertEquals(RcloneController.CACHE_MAX_SIZE_BYTES, options.cacheMaxSizeBytes)
        assertEquals("full", options.vfsCacheMode)
    }

    /**
     * Google Диск на общем идентификаторе rclone.
     *
     * Снаружи это выглядит просто как «тормозит»: на живом диске список из
     * 65 файлов занимал 33 секунды против секунды у Яндекса на 81 файле.
     * Причину видно только в конфиге, поэтому о ней нужно сказать.
     */
    @Test
    fun `google drive without a client id is flagged`() {
        val config = Json.parseToJsonElement("""{"type":"drive","client_id":"","token":"..."}""")

        assertTrue(RcloneController.googleWithoutClientId(config as JsonObject))
    }

    @Test
    fun `missing key counts the same as an empty one`() {
        // Для rclone это одно и то же — берётся встроенный идентификатор.
        val config = Json.parseToJsonElement("""{"type":"drive","token":"..."}""")

        assertTrue(RcloneController.googleWithoutClientId(config as JsonObject))
    }

    @Test
    fun `google drive with its own client id is fine`() {
        val config = Json.parseToJsonElement("""{"type":"drive","client_id":"12345.apps.googleusercontent.com"}""")

        assertFalse(RcloneController.googleWithoutClientId(config as JsonObject))
    }

    @Test
    fun `other backends are not about google`() {
        // У Яндекса и WebDAV своего идентификатора не нужно, и предупреждать
        // о нём было бы просто шумом.
        val yandex = Json.parseToJsonElement("""{"type":"yandex","token":"..."}""")
        val webdav = Json.parseToJsonElement("""{"type":"webdav","url":"https://example.invalid"}""")

        assertFalse(RcloneController.googleWithoutClientId(yandex as JsonObject))
        assertFalse(RcloneController.googleWithoutClientId(webdav as JsonObject))
    }
}
