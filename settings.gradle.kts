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

// Модуль Android-клиента подключается, только если в системе есть Android SDK.
//
// Иначе сборка десктопа падала бы у всех, у кого SDK нет, — а нужен он
// исключительно для мобильной части. Заодно librclone (100 МБ) не качается
// туда, где она не нужна: ни в релизную сборку установщиков, ни на машину
// того, кто правит только десктоп.
val androidSdkAvailable = System.getenv("ANDROID_HOME") != null ||
    System.getenv("ANDROID_SDK_ROOT") != null ||
    File(settingsDir, "local.properties").takeIf { it.isFile }
        ?.readLines()
        ?.any { it.trimStart().startsWith("sdk.dir") } == true

if (androidSdkAvailable) {
    include(":android-core")
} else {
    logger.lifecycle("Android SDK не найден — модуль :android-core пропущен (десктоп собирается как обычно)")
}
