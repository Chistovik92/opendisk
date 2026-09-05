plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

dependencies {
    implementation("io.ktor:ktor-client-core:2.3.12")
    implementation("io.ktor:ktor-client-cio:2.3.12")
    implementation("io.ktor:ktor-client-content-negotiation:2.3.12")
    implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.12")
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
