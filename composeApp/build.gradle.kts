import org.jetbrains.compose.desktop.application.dsl.TargetFormat

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
}

kotlin {
    jvmToolchain(17)

    // Модуль пока собирается плагином kotlin("jvm"), который знает только
    // про src/main/kotlin. Раскладка commonMain/desktopMain из docs/ARCHITECTURE.md
    // подключается вручную — иначе исходники молча не компилируются (NO-SOURCE).
    sourceSets["main"].kotlin.srcDirs("src/commonMain/kotlin", "src/desktopMain/kotlin")
}

compose.desktop {
    application {
        mainClass = "com.opendisk.app.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Deb, TargetFormat.Rpm, TargetFormat.Msi, TargetFormat.Dmg)
            packageName = "OpenDisk"
            packageVersion = "0.1.0"
            description = "Открытый кроссплатформенный клиент виртуальных облачных дисков"
            vendor = "OpenDisk contributors"

            macOS {
                // macOS не принимает MAJOR = 0 в версии бандла (.dmg),
                // поэтому для него версия задаётся отдельно
                packageVersion = "1.0.0"
            }
        }
    }
}
