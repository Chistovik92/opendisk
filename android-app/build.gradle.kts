plugins {
    id("com.android.application")
    kotlin("android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.opendisk.android.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.opendisk.android"
        // 24 — тот же уровень, под который собрана librclone (-androidapi 24).
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = project.version.toString()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Раздача с учётом размера.
    //
    // librclone — это полная копия rclone на каждую архитектуру, около 56 МБ.
    // Один apk на всех весил бы больше сотни мегабайт, из которых человеку
    // нужна ровно половина. Разделение по ABI даёт каждому свой файл.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "x86_64")
            // Универсальный apk нужен: в релиз кладём именно его как запасной
            // вариант для тех, кто не знает архитектуру своего телефона,
            // а раздельные — как основной.
            isUniversalApk = true
        }
    }

    buildTypes {
        release {
            // Без подписи ключом разработчика: ключа у проекта нет, а
            // самоподписанный в репозитории — это ключ, который есть у всех.
            // Debug-подпись честнее: она сразу говорит, что сборка не из
            // магазина, и не создаёт видимости доверенной подписи.
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = false
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // Ядро: транспорт поверх librclone и общий с десктопом разбор ответов.
    implementation(project(":android-core"))

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")

    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    androidTestImplementation(composeBom)
    androidTestImplementation(kotlin("test"))
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

// librclone скачивает задача соседнего модуля, а читают её файл и задачи
// этого — например, сбор отчёта о зависимостях. Для Gradle это чтение чужого
// выхода без объявленной связи, и сборка падает на проверке.
//
// Причина та же, что и в android-core: библиотека не зависимость по данным,
// а предусловие — файл должен лежать в локальном репозитории до того, как
// Gradle начнёт разрешать зависимости. Поэтому цепляем ко всему, что так или
// иначе трогает classpath.
// Перечислять префиксы имён оказалось бесполезным занятием: список задач AGP
// длинный, и каждый раз находилась ещё одна, которая тоже трогает classpath
// (сначала collectReleaseDependencies, потом checkDebugAndroidTestDuplicateClasses).
// Проще сказать правду: этому модулю библиотека нужна для чего угодно, кроме
// уборки за собой.
tasks.configureEach {
    if (!name.startsWith("clean")) {
        dependsOn(":android-core:downloadLibrclone")
    }
}
