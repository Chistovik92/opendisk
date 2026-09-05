plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    // Нужен ради конфигурации api: HttpClient торчит в публичном конструкторе
    // RcloneClient, поэтому потребители модуля обязаны его видеть.
    `java-library`
}

dependencies {
    api("io.ktor:ktor-client-core:2.3.12")
    // JsonObject торчит в сигнатурах RcloneTransport и RcloneClient, поэтому api.
    // Раньше приезжал транзитивно через ktor-serialization-kotlinx-json — то есть
    // модуль зависел от того, что кто-то другой его притащит.
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("io.ktor:ktor-client-cio:2.3.12")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")

    testImplementation(kotlin("test"))
    testImplementation("io.ktor:ktor-client-mock:2.3.12")
}

tasks.test {
    useJUnitPlatform()

    // Путь к rclone прокидываем в тестовую JVM: с ним включается
    // RcloneIntegrationTest против настоящего rcd, без него он пропускается.
    // Встроенный бинарник после сборки лежит в
    // composeApp/build/appResources/common/rclone[.exe].
    val rclonePath = providers.systemProperty("opendisk.rclone.path").orNull
    if (rclonePath != null) {
        systemProperty("opendisk.rclone.path", rclonePath)
    }
}

kotlin {
    jvmToolchain(17)
}
