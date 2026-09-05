import java.net.URI
import java.security.MessageDigest

plugins {
    id("com.android.library")
    kotlin("android")
}

// ---------------------------------------------------------------------------
// Встроенная librclone
//
// На Android нельзя поднять `rclone rcd` отдельным процессом: система не даёт
// приложению исполнять свои файлы. Поэтому rclone линкуется в приложение как
// нативная библиотека и вызывается функцией внутри процесса.
//
// Библиотеку собирает .github/workflows/librclone-android.yml и кладёт в релиз
// с тегом librclone-v<версия>. Здесь она только скачивается — ставить Go и NDK
// ради сборки Android-модуля не нужно.
//
// Версия обязана совпадать с rcloneVersion в composeApp/build.gradle.kts:
// расхождение означало бы разные формы ответов RC API на телефоне и на
// компьютере при одном и том же коде разбора.
// ---------------------------------------------------------------------------

val librcloneVersion = "1.75.1"
val librcloneSha256 = "785cd5f684886bb876fd1567c39bc3aaaad94887ab866dbeaa16d4aadc5da581"

// Библиотека кладётся в локальный репозиторий и подключается как обычная
// зависимость `org.rclone:librclone`, а не файлом.
//
// Не для красоты: AGP отказывается собирать модуль, у которого .aar подключён
// файлом (`Direct local .aar file dependencies are not supported`) — получился
// бы пакет без классов и нативных библиотек внутри. На том же спотыкается lint.
// Через репозиторий это обычная зависимость, которая нормально доезжает и до
// приложения, и до отчётов.
val librcloneRepo = layout.buildDirectory.dir("librclone-repo")
val librcloneAar = librcloneRepo.map {
    it.file("org/rclone/librclone/$librcloneVersion/librclone-$librcloneVersion.aar")
}

val downloadLibrclone by tasks.registering {
    group = "build"
    description = "Скачивает librclone $librcloneVersion для Android"

    val version = librcloneVersion
    val expectedSha = librcloneSha256
    val target = librcloneAar

    inputs.property("version", version)
    inputs.property("sha256", expectedSha)
    outputs.file(target)

    doLast {
        val file = target.get().asFile
        file.parentFile.mkdirs()

        if (!file.exists() || sha256(file) != expectedSha) {
            val url = "https://github.com/Chistovik92/opendisk/releases/download/" +
                "librclone-v$version/librclone.aar"
            logger.lifecycle("Скачиваю librclone: $url (100 МБ, это надолго)")
            URI(url).toURL().openStream().use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            }
        }

        val actualSha = sha256(file)
        check(actualSha == expectedSha) {
            "SHA-256 librclone.aar не совпала.\n  ожидалась: $expectedSha\n  получена:  $actualSha"
        }
    }
}

fun sha256(file: java.io.File): String {
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

android {
    namespace = "com.opendisk.android"
    compileSdk = 35

    defaultConfig {
        // 24 — тот же уровень, под который собрана librclone (-androidapi 24).
        minSdk = 24
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            // Ровно те архитектуры, что есть в .aar. Явный список нужен, чтобы
            // сборка падала при попытке собрать под архитектуру без библиотеки,
            // а не отдавала приложение, падающее при первом вызове.
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // Разбор ответов RC API общий с десктопом — ради этого транспорт и вынесен
    // отдельным слоем. Ktor и работа с процессами оттуда на Android не
    // используются, но и не мешают: код просто не вызывается.
    api(project(":rclone-bridge"))
    // @aar, потому что у зависимости нет .pom: репозиторий настроен на поиск
    // по самому файлу (см. settings.gradle.kts).
    implementation("org.rclone:librclone:$librcloneVersion@aar")

    androidTestImplementation(kotlin("test"))
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}

// Скачивание — не задача-зависимость по данным, а предусловие: файл должен
// лежать в репозитории до того, как Gradle начнёт разрешать зависимости.
// Поэтому цепляем ко всему, что так или иначе трогает classpath.
tasks.configureEach {
    if (name.startsWith("compile") || name.startsWith("assemble") ||
        name.startsWith("bundle") || name.startsWith("generate") || name.startsWith("lint")
    ) {
        dependsOn(downloadLibrclone)
    }
}
