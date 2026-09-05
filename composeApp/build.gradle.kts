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
    // Пробрасываем то, что нужно тестам против настоящей системы: путь к rclone,
    // каталог ресурсов (в нём лежит встроенный установщик WinFsp) и явное
    // разрешение ставить драйвер — см. MountDriverInstallTest.
    listOf(
        "opendisk.rclone.path",
        "compose.application.resources.dir",
        "opendisk.test.driverInstall",
    ).forEach { name ->
        providers.systemProperty(name).orNull?.let { systemProperty(name, it) }
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
    // Конкретные файлы, а не каталог: рядом в тот же appResources пишет
    // downloadWinFsp, а перекрывающиеся выходы Gradle отслеживать не умеет.
    outputs.file(outputDir.map { it.file(binaryName) })
    outputs.file(outputDir.map { it.file("rclone-LICENSE.txt") })

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

// ---------------------------------------------------------------------------
// Встроенный WinFsp (только Windows)
//
// Без WinFsp rclone не может смонтировать облако в букву диска, а ставить
// драйвер руками пользователь не должен. Официальный подписанный установщик
// (2 МБ) кладём в дистрибутив, приложение предлагает запустить его, когда
// обнаруживает, что WinFsp нет. Сам драйвер ставится отдельным MSI: вложенные
// установки MSI не поддерживаются, а бутстрапер jpackage делать не умеет.
// ---------------------------------------------------------------------------

val winFspVersion = "2.1.25156"
val winFspSha256 = "073a70e00f77423e34bed98b86e600def93393ba5822204fac57a29324db9f7a"

val downloadWinFsp by tasks.registering {
    group = "build"
    description = "Скачивает официальный установщик WinFsp $winFspVersion в ресурсы приложения"

    val version = winFspVersion
    val expectedSha = winFspSha256
    val archiveFile = layout.buildDirectory.file("winfsp/winfsp-$version.msi")
    val outputDir = bundledRcloneDir
    val licenseFile = layout.projectDirectory.file("../licenses/winfsp-LICENSE.txt")
    val skip = providers.gradleProperty("opendisk.skipRcloneDownload").orNull == "true"
    val isWindowsBuild = rcloneTarget.startsWith("windows")

    inputs.property("version", version)
    inputs.property("sha256", expectedSha)
    inputs.property("windows", isWindowsBuild)
    inputs.file(licenseFile)
    outputs.file(outputDir.map { it.file("winfsp.msi") })
    outputs.file(outputDir.map { it.file("winfsp-LICENSE.txt") })

    doLast {
        val out = outputDir.get().asFile
        out.mkdirs()

        if (!isWindowsBuild) {
            logger.lifecycle("Сборка не под Windows — WinFsp не нужен, пропускаю")
            return@doLast
        }
        if (skip) {
            logger.lifecycle("opendisk.skipRcloneDownload=true — WinFsp пропущен")
            return@doLast
        }

        val installer = archiveFile.get().asFile
        installer.parentFile.mkdirs()

        if (!installer.exists() || sha256(installer) != expectedSha) {
            val url = "https://github.com/winfsp/winfsp/releases/download/v${version.substringBefore('.')}." +
                "${version.split('.')[1]}/winfsp-$version.msi"
            logger.lifecycle("Скачиваю WinFsp: $url")
            URI(url).toURL().openStream().use { input ->
                installer.outputStream().use { output -> input.copyTo(output) }
            }
        }

        val actualSha = sha256(installer)
        check(actualSha == expectedSha) {
            "SHA-256 установщика WinFsp не совпала.\n  ожидалась: $expectedSha\n  получена:  $actualSha"
        }

        installer.copyTo(File(out, "winfsp.msi"), overwrite = true)
        // WinFsp под GPLv3 — его лицензия обязана ехать рядом.
        licenseFile.asFile.copyTo(File(out, "winfsp-LICENSE.txt"), overwrite = true)
        logger.lifecycle("WinFsp $version готов: ${File(out, "winfsp.msi")}")
    }
}

// ---------------------------------------------------------------------------
// Собственный установщик на WiX (только Windows)
//
// jpackage не умеет закрывать работающее приложение: обновление поверх
// запущенной копии упиралось в занятые файлы, Windows Installer откладывал их
// замену, сам перезагружал компьютер — и установка оставалась повреждённой.
// Своё описание установщика позволяет добавить util:CloseApplication, который
// закрывает приложение до того, как трогать файлы.
//
// Инструменты WiX не скачиваются отдельно: их уже приносит плагин Compose
// задачей unzipWix ради своего packageMsi.
// ---------------------------------------------------------------------------

val wixDir = rootProject.layout.buildDirectory.dir("wix311")
val wixWorkDir = layout.buildDirectory.dir("wixBuild")
val wixOutputDir = layout.buildDirectory.dir("distributions")

val packageWixMsi by tasks.registering {
    group = "compose desktop"
    description = "Собирает MSI на WiX — с закрытием работающего приложения при обновлении"

    dependsOn("createDistributable", ":unzipWix")

    val appImage = layout.buildDirectory.dir("compose/binaries/main/app/OpenDisk")
    val productWxs = layout.projectDirectory.file("wix/Product.wxs")
    val licenseRtf = layout.projectDirectory.file("wix/License.rtf")
    val version = project.version.toString()
    val tools = wixDir
    val work = wixWorkDir
    val output = wixOutputDir

    inputs.file(productWxs)
    inputs.file(licenseRtf)
    inputs.property("version", version)
    outputs.file(output.map { it.file("OpenDisk-$version.msi") })

    doLast {
        val toolsDir = tools.get().asFile
        val heat = File(toolsDir, "heat.exe")
        check(heat.isFile) {
            "Инструменты WiX не найдены в $toolsDir. Их распаковывает задача unzipWix плагина Compose."
        }

        val workDir = work.get().asFile
        workDir.deleteRecursively()
        workDir.mkdirs()

        val imageDir = appImage.get().asFile
        check(File(imageDir, "OpenDisk.exe").isFile) {
            "Образ приложения не собран: ${File(imageDir, "OpenDisk.exe")}"
        }

        val harvested = File(workDir, "AppFiles.wxs")

        // heat перечисляет все файлы образа в группу компонентов AppFiles.
        //
        // -ag, а не -gg: -gg выдаёт случайные GUID-ы при каждой сборке, и тогда
        // Windows Installer не видит, что файлы старой и новой версии — одни
        // и те же. Ссылки не пересчитываются, и удаление старой версии сносит
        // только что скопированные файлы: при одном порядке действий пропадали
        // библиотеки JVM, при другом — почти весь образ целиком.
        // -ag оставляет Guid="*", а его light считает от пути и keypath —
        // одинаковые файлы получают одинаковый GUID в любой сборке.
        runWixTool(
            heat.absolutePath, "dir", imageDir.absolutePath,
            "-nologo", "-ag", "-sfrag", "-srd", "-sreg", "-scom",
            "-cg", "AppFiles",
            "-dr", "INSTALLDIR",
            "-var", "var.AppDir",
            "-out", harvested.absolutePath,
        )

        // -arch x64 делает компоненты 64-битными и кладёт приложение
        // в Program Files, а не в Program Files (x86).
        runWixTool(
            File(toolsDir, "candle.exe").absolutePath,
            "-nologo", "-arch", "x64",
            "-dAppDir=${imageDir.absolutePath}",
            "-dVersion=$version",
            "-dLicenseRtf=${licenseRtf.asFile.absolutePath}",
            "-ext", "WixUtilExtension",
            "-ext", "WixUIExtension",
            "-out", workDir.absolutePath + File.separator,
            productWxs.asFile.absolutePath,
            harvested.absolutePath,
        )

        val msi = File(output.get().asFile, "OpenDisk-$version.msi")
        msi.parentFile.mkdirs()

        // ICE60 ругается на файлы без версии в компонентах — для образа JVM
        // это норма и чинить нечего.
        runWixTool(
            File(toolsDir, "light.exe").absolutePath,
            "-nologo",
            // ICE60 — про файлы без версии внутри образа JVM, это норма.
            // ICE38/43/57 считают компоненты с ярлыками пользовательскими и
            // требуют ключ в HKCU. Установка машинная, ProgramMenuFolder и
            // DesktopFolder здесь общие для всех — претензия не по адресу.
            // Что ярлыки ставятся и снимаются как надо, проверено установкой.
            "-sice:ICE60", "-sice:ICE38", "-sice:ICE43", "-sice:ICE57",
            // Без явной культуры light собирает базу в кодовой странице 1252
            // и падает с LGHT0311 на любой кириллице в строках установщика.
            "-cultures:ru-ru",
            "-ext", "WixUtilExtension",
            "-ext", "WixUIExtension",
            "-b", imageDir.absolutePath,
            "-out", msi.absolutePath,
            File(workDir, "Product.wixobj").absolutePath,
            File(workDir, "AppFiles.wixobj").absolutePath,
        )

        logger.lifecycle("Установщик собран: ${msi.absolutePath}")
    }
}

/**
 * Запускает инструмент WiX и падает с его собственным выводом.
 * candle и light пишут причину в stdout, и без неё разбираться в ошибке
 * сборки установщика бессмысленно.
 */
fun runWixTool(vararg command: String) {
    val process = ProcessBuilder(*command).redirectErrorStream(true).start()
    val output = process.inputStream.bufferedReader().readText()
    val exit = process.waitFor()
    check(exit == 0) {
        File(command.first()).name + " завершился с кодом " + exit + ":\n" + output
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
    dependsOn(downloadRclone, downloadWinFsp)

    // Compose не считает содержимое каталога ресурсов своим входом: после
    // появления там winfsp.msi задача осталась UP-TO-DATE, и установщик собрался
    // без него — при зелёной сборке. Объявляем вход явно.
    inputs.dir(bundledRcloneDir)

    // Права на копируемые файлы Gradle выставляет свои, и встроенный rclone
    // приезжал в deb с правами 644 — то есть неисполняемым. Починить их на
    // месте приложение не может: каталог установки принадлежит root.
    //
    // Настройка filePermissions на этой задаче не сработала: Compose создаёт
    // её сам и настраивает после нас. Поэтому выставляем бит выполнения уже
    // на этапе выполнения, когда всё скопировано и перебить это некому.
    val binaryName = rcloneBinaryName
    doLast {
        // Ищем обходом: раскладку каталога назначения задаёт Compose, и
        // полагаться на неё не хочется.
        val destination = (this as Sync).destinationDir
        destination.walkTopDown()
            .filter { it.isFile && it.name == binaryName }
            .forEach { binary ->
                if (!binary.setExecutable(true)) {
                    logger.warn("Не удалось сделать $binary исполняемым — на Linux приложение не запустит rclone")
                }
            }
    }
}

compose.desktop {
    application {
        mainClass = "com.opendisk.app.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Deb, TargetFormat.Rpm, TargetFormat.Msi, TargetFormat.Dmg)
            packageName = "OpenDisk"
            packageVersion = "0.1.14"
            // Только ASCII: WiX собирает MSI в кодовой странице 1252 и падает
            // с LGHT0311 на кириллице в метаданных установщика.
            description = "Open cross-platform client for cloud drives"
            vendor = "OpenDisk contributors"

            appResourcesRootDir.set(appResourcesRoot)

            windows {
                // Без этого jpackage не создаёт вообще никаких ярлыков, и после
                // установки приложение можно запустить только вручную из
                // Program Files. Найдено при обкатке установщика 0.1.5.
                menu = true
                menuGroup = "OpenDisk"
                shortcut = true
                dirChooser = true

                // Закреплено тем самым значением, которое jpackage вывел из имени
                // пакета для 0.1.5, — иначе установка новой версии встанет рядом
                // со старой вместо обновления.
                upgradeUuid = "017F57A7-5FBD-3E70-A0A0-07906627B269"
            }

            linux {
                // Имена пакетов в Debian и RPM должны быть в нижнем регистре,
                // иначе jpackage отказывается собирать.
                packageName = "opendisk"
                // Обязательное поле для .deb. Личный адрес в публичный пакет
                // класть незачем, поэтому noreply-адрес GitHub.
                debMaintainer = "chistovik92@users.noreply.github.com"
                // Обязательное поле для .rpm.
                rpmLicenseType = "MIT"
                menuGroup = "Utility"
                appCategory = "utils"
                appRelease = "1"
                shortcut = true
            }

            macOS {
                // macOS не принимает MAJOR = 0 в версии бандла (.dmg),
                // поэтому для него версия задаётся отдельно
                packageVersion = "1.0.14"
            }
        }
    }
}
