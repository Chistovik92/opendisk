package com.opendisk.app

import com.opendisk.bridge.AppReleases
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import java.io.Closeable
import java.io.File

/**
 * Проверка обновлений по списку выпусков на GitHub.
 *
 * Ходит в открытый API без ключа: приложение не авторизуется и ничего о себе
 * не сообщает, кроме обычного заголовка User-Agent, который GitHub требует.
 * Проверку можно выключить в настройках — на случай, если обращение к сети
 * при старте нежелательно.
 *
 * Что новее и как скачать — общее с Android ([AppReleases]); здесь только
 * выбор файла под то, как приложение установлено.
 */
class UpdateChecker(
    private val httpClient: HttpClient = defaultHttpClient(),
    private val releasesUrl: String = AppReleases.RELEASES_URL,
) : Closeable {

    /** Найденное обновление: что показать и что скачивать. */
    data class Update(
        val version: String,
        val pageUrl: String,
        /** Файл под текущую установку; null — подходящего нет, остаётся страница выпуска. */
        val asset: AppReleases.Asset? = null,
        /** Файл с контрольными суммами — без него скачанное проверять нечем. */
        val checksums: AppReleases.Asset? = null,
        val target: InstallTarget? = null,
    ) {
        val assetName: String? get() = asset?.name
        val assetUrl: String? get() = asset?.downloadUrl
        val checksumsUrl: String? get() = checksums?.downloadUrl
    }

    /**
     * Как установлено приложение — от этого зависят и файл, и способ установки.
     *
     * До 0.5.12 сам ставился только установщик Windows, а на Linux и macOS
     * открывалась страница выпуска: пакет там ставится с правами root, и это
     * казалось делом пакетного менеджера. Но человеку от этого не легче —
     * обновление должно ставиться по кнопке везде. Права спрашивает система
     * (pkexec на Linux, окно пароля на macOS), как и установщик на Windows.
     */
    enum class InstallTarget {
        WINDOWS,
        MACOS,
        DEB,
        RPM,
        /** ALT и Simply Linux — у них свои имена зависимостей, rpm Fedora не встанет. */
        RPM_ALT,
        APPIMAGE,
        ;

        val isLinuxPackage: Boolean get() = this == DEB || this == RPM || this == RPM_ALT

        companion object {
            /**
             * @return null — установка, которую обновлять нечем: запуск из
             *         исходников или распакованного каталога, либо пакет без
             *         pkexec, которым спросить права. Тогда остаётся страница.
             */
            fun detect(
                osName: String = System.getProperty("os.name"),
                launcher: String? = Autostart.launcherPath(),
                appImage: String? = System.getenv("APPIMAGE"),
                exists: (String) -> Boolean = { File(it).exists() },
            ): InstallTarget? {
                val os = osName.lowercase()
                return when {
                    os.contains("win") -> WINDOWS
                    os.contains("mac") || os.contains("darwin") ->
                        if (launcher != null && launcher.contains(".app/")) MACOS else null
                    // AppImage — один файл, его можно просто заменить; права
                    // нужны, только если он лежит в системном каталоге.
                    !appImage.isNullOrBlank() -> APPIMAGE
                    // Пакеты deb и rpm ставят приложение в /opt/opendisk.
                    launcher == null || !launcher.startsWith(LINUX_PACKAGE_DIR) -> null
                    PKEXEC_PATHS.none(exists) -> null
                    // Не по дистрибутиву, а по тому, чем приложение поставлено:
                    // на Debian можно поставить и rpm через alien, но обновлять
                    // надо тем же, чем ставили.
                    exists("/var/lib/dpkg/info/opendisk.list") -> DEB
                    exists("/etc/altlinux-release") -> RPM_ALT
                    else -> RPM
                }
            }

            private const val LINUX_PACKAGE_DIR = "/opt/opendisk/"
            internal val PKEXEC_PATHS = listOf("/usr/bin/pkexec", "/bin/pkexec")
        }
    }

    /**
     * @return более новый выпуск или null, если обновляться не на что
     *         либо список не удалось получить.
     */
    suspend fun check(
        currentVersion: String,
        target: InstallTarget? = InstallTarget.detect(),
        osArch: String = System.getProperty("os.arch"),
    ): Update? =
        AppReleases.newest(httpClient, currentVersion, releasesUrl)?.let { toUpdate(it, target, osArch) }

    companion object {
        internal fun newestUpdate(
            json: String,
            currentVersion: String,
            target: InstallTarget?,
            osArch: String = "amd64",
        ): Update? = AppReleases.newest(json, currentVersion)?.let { toUpdate(it, target, osArch) }

        private fun toUpdate(release: AppReleases.Release, target: InstallTarget?, osArch: String): Update {
            val asset = target?.let { assetFor(release.assets, it, osArch) }
            return Update(
                version = release.version,
                pageUrl = release.htmlUrl,
                asset = asset,
                checksums = target?.let { release.asset(checksumsNameFor(it, osArch)) },
                target = asset?.let { target },
            )
        }

        /**
         * Файл под текущую установку.
         *
         * Windows: с 0.5.0 установщик — `.exe`, их два, по архитектуре. До 0.5.2
         * здесь искался `.msi`, которого в выпусках больше нет, — встроенное
         * обновление молча перестало ставить что-либо и только открывало
         * страницу выпуска.
         *
         * Архитектура берётся у JVM, а не у системы. На ARM-ноутбуке с x64-сборкой
         * под эмуляцией JVM видит amd64 — и получает x64-установщик, то есть ту
         * же сборку, что уже стоит. Переход на родную ARM-сборку — осознанное
         * решение человека, а не побочный эффект обновления.
         *
         * Linux собирается только под x86-64 — на другом процессоре брать нечего.
         */
        internal fun assetFor(assets: List<AppReleases.Asset>, target: InstallTarget, osArch: String): AppReleases.Asset? {
            val arch = installerArch(osArch)
            fun endingWith(suffix: String) = assets.firstOrNull { it.name.endsWith(suffix, ignoreCase = true) }
            val linuxX64 = arch == "x64"
            return when (target) {
                // С 0.5.12 полный установщик — «-offline.exe», а прежнее имя носит
                // веб-установщик для старых версий (см. wix/Bundle.wxs). Полный
                // лучше: скачан и сверен здесь целиком, второй загрузки при
                // установке не будет. Прежнее имя — для выпусков до 0.5.12.
                InstallTarget.WINDOWS -> endingWith("-$arch-offline.exe") ?: endingWith("-$arch.exe")
                InstallTarget.MACOS -> endingWith("-$arch.dmg")
                InstallTarget.DEB -> if (linuxX64) endingWith("_amd64.deb") else null
                InstallTarget.RPM -> if (linuxX64) endingWith("-1.x86_64.rpm") else null
                InstallTarget.RPM_ALT -> if (linuxX64) endingWith("-alt1.x86_64.rpm") else null
                InstallTarget.APPIMAGE -> if (linuxX64) endingWith("-x86_64.AppImage") else null
            }
        }

        internal fun checksumsNameFor(target: InstallTarget, osArch: String): String = when (target) {
            InstallTarget.WINDOWS -> "SHA256SUMS-Windows-${installerArch(osArch)}"
            InstallTarget.MACOS -> "SHA256SUMS-macOS-${installerArch(osArch)}"
            else -> "SHA256SUMS-Linux"
        }

        /** Архитектура так, как она записана в именах установщиков. */
        internal fun installerArch(osArch: String): String = when (osArch.lowercase()) {
            "aarch64", "arm64" -> "arm64"
            else -> "x64"
        }

        fun defaultHttpClient(): HttpClient = HttpClient(CIO) {
            engine { requestTimeout = 30_000 }
        }
    }

    override fun close() {
        httpClient.close()
    }
}
