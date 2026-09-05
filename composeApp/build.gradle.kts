import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import java.net.URI
import java.security.MessageDigest
import java.util.zip.ZipFile

plugins {
    kotlin("jvm")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

dependencies {
    implementation(project(":rclone-bridge"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.9.0")

    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(17)

    // Модуль пока собирается плагином kotlin("jvm"), который знает только
    // про src/main/kotlin. Раскладка commonMain/desktopMain из docs/ARCHITECTURE.md
    // подключается вручную — иначе исходники молча не компилируются (NO-SOURCE).
    sourceSets["main"].kotlin.srcDirs("src/commonMain/kotlin", "src/desktopMain/kotlin")
    sourceSets["test"].kotlin.srcDirs("src/desktopTest/kotlin")
    sourceSets["test"].resources.srcDirs("src/desktopTest/resources")
}

tasks.test {
    useJUnitPlatform()

    // Как и в :rclone-bridge — с указанным бинарником включается прогон
    // контроллера против настоящего rcd, без него тест пропускается.
    val rclonePath = providers.systemProperty("opendisk.rclone.path").orNull
    if (rclonePath != null) {
        systemProperty("opendisk.rclone.path", rclonePath)
    }
}

// ---------------------------------------------------------------------------
// Встроенный rclone
//
// rclone не ставится пользователем отдельно — он едет внутри дистрибутива
// OpenDisk. Бинарник не хранится в репозитории (это ~20 МБ на платформу),
// а скачивается на этапе сборки с официального downloads.rclone.org и
// проверяется по SHA-256. См. docs/ARCHITECTURE.md.
// ---------------------------------------------------------------------------

val rcloneVersion = "1.75.1"

// Суммы официальных архивов, зафиксированы намеренно: сборка должна упасть,
// а не молча собрать пакет с подменённым бинарником. При обновлении версии
// сверяйте с https://downloads.rclone.org/v<version>/SHA256SUMS
val rcloneChecksums = mapOf(
    "windows-amd64" to "200eb602c126d82aa38b51e0f6b9ae837473ff99b51278d3f6f837574c494d6e",
    "linux-amd64" to "982b5aa772841168f8e380f139e9e787b2a105403e32b94da8676a0e1c0a13ab",
    "linux-arm64" to "03f2504174034b6d004152ed7369251c9a9ec1f7e0836eda420f5c7a5ec0dff9",
    "osx-amd64" to "29253d0288b8fbbac46baad6e5f6add6cb01d462c79f10805bbd4631c4cdf82c",
    "osx-arm64" to "c61d7a371c62bcbbe882c3423aa4b8bf63485c248dd0f692997b8f0c3f6d0c6f",
)

/**
 * Определяет, какой архив rclone нужен для машины, на которой идёт сборка.
 * jpackage всё равно собирает пакет только под текущую ОС, поэтому
 * кросс-сборка тут не нужна.
 */
val rcloneTarget: String = run {
    val os = System.getProperty("os.name").lowercase()
    val arch = when (val a = System.getProperty("os.arch").lowercase()) {
        "amd64", "x86_64" -> "amd64"
        "aarch64", "arm64" -> "arm64"
        else -> error("Неподдерживаемая архитектура для встроенного rclone: $a")
    }
    when {
        os.contains("win") -> "windows-$arch"
        os.contains("mac") || os.contains("darwin") -> "osx-$arch"
        else -> "linux-$arch"
    }
}

val rcloneBinaryName = if (rcloneTarget.startsWith("windows")) "rclone.exe" else "rclone"

// Compose кладёт содержимое этого каталога внутрь дистрибутива и отдаёт
// приложению путь через системное свойство compose.application.resources.dir.
// Подкаталог common попадает в сборку под любую ОС.
val appResourcesRoot = layout.buildDirectory.dir("appResources")
val bundledRcloneDir = layout.buildDirectory.dir("appResources/common")

val downloadRclone by tasks.registering {
    group = "build"
    description = "Скачивает официальный rclone $rcloneVersion и кладёт его в ресурсы приложения"

    val version = rcloneVersion
    val target = rcloneTarget
    val binaryName = rcloneBinaryName
    val expectedSha = rcloneChecksums[target]
        ?: error("Нет зафиксированной SHA-256 для платформы $target")
    val archiveFile = layout.buildDirectory.file("rclone/rclone-v$version-$target.zip")
    val outputDir = bundledRcloneDir
    val licenseFile = layout.projectDirectory.file("../licenses/rclone-LICENSE.txt")
    val skip = providers.gradleProperty("opendisk.skipRcloneDownload").orNull == "true"

    inputs.property("version", version)
    inputs.property("target", target)
    inputs.property("sha256", expectedSha)
    inputs.file(licenseFile)
    outputs.dir(outputDir)

    doLast {
        val out = outputDir.get().asFile
        out.mkdirs()

        if (skip) {
            logger.lifecycle("opendisk.skipRcloneDownload=true — встроенный rclone пропущен, приложение будет искать его в PATH")
            return@doLast
        }

        val archive = archiveFile.get().asFile
        archive.parentFile.mkdirs()

        if (!archive.exists() || sha256(archive) != expectedSha) {
            val url = "https://downloads.rclone.org/v$version/rclone-v$version-$target.zip"
            logger.lifecycle("Скачиваю rclone: $url")
            URI(url).toURL().openStream().use { input ->
                archive.outputStream().use { output -> input.copyTo(output) }
            }
        }

        val actualSha = sha256(archive)
        check(actualSha == expectedSha) {
            "SHA-256 архива rclone не совпала.\n  ожидалась: $expectedSha\n  получена:  $actualSha\n" +
                "Архив удалён, повторите сборку. Если ошибка повторяется — проверьте суммы " +
                "на https://downloads.rclone.org/v$version/SHA256SUMS"
        }

        ZipFile(archive).use { zip ->
            val entry = zip.entries().asSequence().firstOrNull { it.name.endsWith("/$binaryName") }
                ?: error("В архиве rclone не найден $binaryName")
            zip.getInputStream(entry).use { input ->
                File(out, binaryName).outputStream().use { output -> input.copyTo(output) }
            }
        }

        // rclone распространяется под MIT, и его текст лицензии обязан ехать
        // рядом с бинарником. В архиве с downloads.rclone.org его нет (только
        // бинарник и документация), поэтому берём копию из репозитория.
        licenseFile.asFile.copyTo(File(out, "rclone-LICENSE.txt"), overwrite = true)

        File(out, binaryName).setExecutable(true)
        logger.lifecycle("rclone $version готов: ${File(out, binaryName)}")
    }
}

fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(1 shl 16)
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

// Ресурсы приложения готовятся и для `run`, и для упаковки дистрибутива,
// поэтому rclone оказывается на месте в обоих случаях.
// Задача prepareAppResources регистрируется плагином Compose позже, поэтому
// подписываемся на неё лениво, а не через tasks.named.
tasks.matching { it.name == "prepareAppResources" }.configureEach {
    dependsOn(downloadRclone)
}

compose.desktop {
    application {
        mainClass = "com.opendisk.app.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Deb, TargetFormat.Rpm, TargetFormat.Msi, TargetFormat.Dmg)
            packageName = "OpenDisk"
            packageVersion = "0.1.5"
            // Только ASCII: WiX собирает MSI в кодовой странице 1252 и падает
            // с LGHT0311 на кириллице в метаданных установщика.
            description = "Open cross-platform client for cloud drives"
            vendor = "OpenDisk contributors"

            appResourcesRootDir.set(appResourcesRoot)

            macOS {
                // macOS не принимает MAJOR = 0 в версии бандла (.dmg),
                // поэтому для него версия задаётся отдельно
                packageVersion = "1.0.5"
            }
        }
    }
}
