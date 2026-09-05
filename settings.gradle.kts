rootProject.name = "opendisk"

pluginManagement {
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()

        // librclone нигде не опубликована: её собирает наш же CI и кладёт
        // в релиз, а Gradle-задача :android-core:downloadLibrclone скачивает
        // в этот каталог. Отдельный репозиторий нужен потому, что подключить
        // .aar простым файлом нельзя — AGP на этом отказывается собирать модуль.
        maven {
            name = "librclone"
            url = File(rootDir, "android-core/build/librclone-repo").toURI()
            // Только сам файл: .pom для него никто не выпускает.
            metadataSources { artifact() }
            // Чтобы этот каталог не опрашивался при разрешении всего остального.
            content { includeModule("org.rclone", "librclone") }
        }
    }
}

include(":composeApp")
include(":rclone-bridge")

// Модуль Android-клиента подключается только по явной просьбе:
//
//   ./gradlew -Popendisk.android=true :android-core:assembleDebug
//   OPENDISK_ANDROID=true ./gradlew build
//
// Наличия Android SDK в системе для этого нарочно недостаточно. Сначала условие
// было именно таким — и десктопная релизная сборка на раннерах GitHub, где SDK
// стоит всегда, начала тянуть Android-модуль и полусотню мегабайт librclone. Тот,
// кто собирает установщики, Android не просил.
val androidRequested = startParameter.projectProperties["opendisk.android"] == "true" ||
    System.getenv("OPENDISK_ANDROID") == "true"

if (androidRequested) {
    include(":android-core")
}
