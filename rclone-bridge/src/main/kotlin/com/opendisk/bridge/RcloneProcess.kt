package com.opendisk.bridge

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Управляет жизненным циклом дочернего процесса `rclone rcd`.
 *
 * OpenDisk не патчит и не форкает rclone — используем его как внешний бинарник,
 * общаясь только через его же RC (Remote Control) HTTP API. См. docs/ARCHITECTURE.md.
 *
 * Сам бинарник пользователю ставить не нужно: он едет внутри дистрибутива
 * OpenDisk (см. задачу downloadRclone в composeApp/build.gradle.kts).
 */
class RcloneProcess(
    private val rclonePath: String = findRcloneBinary(),
    private val rcAddr: String = DEFAULT_RC_ADDR,
    private val config: RcloneConfigFile? = null,
    private val configPassword: String? = null,
) : AutoCloseable {

    private var process: Process? = null

    /**
     * Последние строки вывода rcd. Нужны, чтобы при падении процесса показать
     * пользователю причину, а не просто "не удалось запустить".
     */
    private val output = ArrayDeque<String>()

    /** Адрес, по которому RcloneClient должен стучаться после запуска. */
    val rcBaseUrl: String get() = "http://$rcAddr"

    fun start() {
        check(process == null) { "rclone rcd уже запущен" }

        val args = mutableListOf(
            rclonePath,
            "rcd",
            "--rc-addr=$rcAddr",
            "--rc-no-auth", // локальный процесс на localhost, отдельная авторизация избыточна
            // Пароль от зашифрованного конфига спрашивает GUI и передаёт сюда.
            // Без этого флага rcd на зашифрованном конфиге просто повиснет,
            // ожидая ввода пароля в stdin, которого у него нет.
            "--ask-password=false",
        )
        config?.let { args += "--config=${it.path.absolutePath}" }

        process = try {
            ProcessBuilder(args)
                .redirectErrorStream(true)
                .apply {
                    // Пароль передаём через окружение, а не аргументом: аргументы
                    // командной строки видны в списке процессов любому в системе.
                    configPassword?.let { password ->
                        environment()[RcloneConfigFile.PASSWORD_ENV] = password
                    }
                }
                .start()
        } catch (e: IOException) {
            throw IllegalStateException(
                "Не удалось запустить rclone по пути '$rclonePath'. " +
                    "В собранном дистрибутиве он идёт в комплекте; при запуске из исходников " +
                    "его кладёт в ресурсы задача :composeApp:downloadRclone.",
                e,
            )
        }

        drainOutputInBackground(requireNotNull(process))
        registerShutdownHook(requireNotNull(process))
    }

    /**
     * Гасит rcd, если JVM завершается мимо [stop] — например, приложение убили.
     * Без этого дочерний процесс переживает родителя и продолжает держать порт,
     * из-за чего следующий запуск уже не может подняться.
     */
    private fun registerShutdownHook(running: Process) {
        val hook = Thread { running.destroy() }
        Runtime.getRuntime().addShutdownHook(hook)
        shutdownHook = hook
    }

    private var shutdownHook: Thread? = null

    /**
     * Ждёт, пока rcd начнёт принимать соединения на [rcAddr].
     *
     * Запуск асинхронный: [start] возвращается сразу, а порт открывается через
     * десятки-сотни миллисекунд. Без этого ожидания первый же вызов RC API
     * упирался бы в "connection refused" на холодном старте.
     *
     * @throws IllegalStateException если процесс умер или не поднялся за [timeout].
     */
    suspend fun awaitReady(timeout: Duration = DEFAULT_READY_TIMEOUT) {
        val running = checkNotNull(process) { "rclone rcd не запущен — сначала вызовите start()" }
        val host = rcAddr.substringBeforeLast(ADDR_SEPARATOR)
        val port = rcAddr.substringAfterLast(ADDR_SEPARATOR).toIntOrNull()
            ?: error("Некорректный адрес RC API: '$rcAddr', ожидается host:port")

        val deadline = System.nanoTime() + timeout.inWholeNanoseconds
        while (System.nanoTime() < deadline) {
            if (!running.isAlive) {
                error(
                    "rclone rcd завершился с кодом ${running.exitValue()}.\n" +
                        recentOutput().joinToString("\n"),
                )
            }
            if (canConnect(host, port)) return
            delay(READY_POLL_INTERVAL)
        }
        error(
            "rclone rcd не начал отвечать на $rcAddr за $timeout.\n" +
                recentOutput().joinToString("\n"),
        )
    }

    fun stop() {
        val running = process ?: return
        process = null

        // Хук больше не нужен и, если его не снять, будет удерживать ссылку
        // на процесс до самого конца работы приложения.
        shutdownHook?.let { hook ->
            runCatching { Runtime.getRuntime().removeShutdownHook(hook) }
            shutdownHook = null
        }

        running.destroy()
        // Даём rclone размонтировать то, что он смонтировал; добиваем только если завис.
        if (!running.waitFor(STOP_GRACE_SECONDS, TimeUnit.SECONDS)) {
            running.destroyForcibly()
        }
    }

    fun isRunning(): Boolean = process?.isAlive == true

    /** Копия перехваченного вывода rcd — для показа в GUI и диагностики. */
    fun recentOutput(): List<String> = synchronized(output) { output.toList() }

    override fun close() = stop()

    private fun drainOutputInBackground(running: Process) {
        Thread {
            running.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    synchronized(output) {
                        output.addLast(line)
                        while (output.size > MAX_OUTPUT_LINES) output.removeFirst()
                    }
                }
            }
        }.apply {
            isDaemon = true // не держим JVM, если приложение закрывают
            name = "rclone-rcd-output"
            start()
        }
    }

    private suspend fun canConnect(host: String, port: Int): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                Socket().use { it.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MILLIS) }
            }.isSuccess
        }

    /** Откуда взялся бинарник — GUI показывает это пользователю. */
    enum class Source {
        /** Указан явно через свойство или переменную окружения. */
        OVERRIDE,

        /** Встроен в дистрибутив OpenDisk — основной сценарий. */
        BUNDLED,

        /** Найден в PATH: пользователь поставил rclone сам. */
        SYSTEM_PATH,
    }

    data class Located(val file: File, val source: Source)

    companion object {
        const val DEFAULT_RC_ADDR = "127.0.0.1:5572"

        /**
         * Адрес со свободным портом вместо штатного 5572.
         *
         * На 5572 может уже сидеть чужой `rclone rcd` — в том числе наш собственный,
         * переживший аварийное завершение приложения. Тогда новый процесс не
         * поднимется, и пользователь увидит непонятную ошибку на ровном месте.
         *
         * Порт освобождается сразу после выбора, так что теоретически его может
         * успеть занять кто-то ещё; практически это несопоставимо менее вероятно,
         * чем занятый 5572, а такой случай отловит [awaitReady].
         */
        fun freeRcAddr(host: String = "127.0.0.1"): String {
            val port = java.net.ServerSocket(0).use { it.localPort }
            return "$host:$port"
        }

        /** Системное свойство, которым можно указать свой бинарник rclone. */
        const val OVERRIDE_PROPERTY = "opendisk.rclone.path"

        /** Переменная окружения с тем же смыслом — удобнее при запуске из IDE. */
        const val OVERRIDE_ENV = "OPENDISK_RCLONE"

        /**
         * Свойство, которое Compose Desktop проставляет и при `run`, и в собранном
         * дистрибутиве. Указывает на каталог с ресурсами приложения, куда сборка
         * кладёт встроенный rclone.
         */
        const val COMPOSE_RESOURCES_PROPERTY = "compose.application.resources.dir"

        private const val ADDR_SEPARATOR = ":"
        private val DEFAULT_READY_TIMEOUT = 15.seconds
        private val READY_POLL_INTERVAL = 50.milliseconds
        private const val CONNECT_TIMEOUT_MILLIS = 250
        private const val STOP_GRACE_SECONDS = 10L
        private const val MAX_OUTPUT_LINES = 200

        private val isWindows: Boolean
            get() = System.getProperty("os.name").lowercase().contains("win")

        private val binaryName: String
            get() = if (isWindows) "rclone.exe" else "rclone"

        /**
         * Ищет rclone в порядке приоритета: явное указание → встроенный в
         * дистрибутив → системный из PATH. Возвращает null, если не нашли нигде.
         */
        fun locate(): Located? =
            override()?.let { Located(it, Source.OVERRIDE) }
                ?: bundled()?.let { Located(it, Source.BUNDLED) }
                ?: onSystemPath()?.let { Located(it, Source.SYSTEM_PATH) }

        /**
         * Путь к rclone для запуска процесса. Если бинарник не найден нигде,
         * возвращает голое имя — пусть ОС попробует сама, а GUI через [locate]
         * покажет пользователю внятную ошибку.
         */
        fun findRcloneBinary(): String = locate()?.file?.absolutePath ?: binaryName

        private fun override(): File? {
            val raw = System.getProperty(OVERRIDE_PROPERTY) ?: System.getenv(OVERRIDE_ENV)
            return raw?.takeIf { it.isNotBlank() }?.let { File(it) }?.takeIf { it.isFile }
        }

        private fun bundled(): File? {
            val resourcesDir = System.getProperty(COMPOSE_RESOURCES_PROPERTY) ?: return null
            val candidate = File(resourcesDir, binaryName)
            if (!candidate.isFile) return null
            // Установщики на Unix умеют терять бит выполнения — восстанавливаем молча.
            if (!candidate.canExecute()) {
                candidate.setExecutable(true)
            }
            return candidate
        }

        private fun onSystemPath(): File? {
            val pathDirs = System.getenv("PATH")?.split(File.pathSeparator).orEmpty()
            return pathDirs.asSequence()
                .map { File(it, binaryName) }
                .firstOrNull { it.isFile && it.canExecute() }
        }
    }
}
