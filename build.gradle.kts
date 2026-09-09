plugins {
    kotlin("jvm") version "2.1.21" apply false
    kotlin("plugin.serialization") version "2.1.21" apply false
    id("org.jetbrains.compose") version "1.8.2" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.21" apply false
    // Только для :android-core, который подключается лишь при наличии SDK
    // (см. settings.gradle.kts). apply false означает, что на десктопную
    // сборку объявление не влияет.
    id("com.android.library") version "8.6.1" apply false
    id("com.android.application") version "8.6.1" apply false
    kotlin("android") version "2.1.21" apply false
}

allprojects {
    group = "com.opendisk"
    version = "0.5.0"
}
