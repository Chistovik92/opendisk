package com.opendisk.app

/**
 * Версия приложения.
 *
 * Номер проставляет лаунчер jpackage через `-Djpackage.app-version` — это видно
 * в `app/OpenDisk.cfg` установленного приложения. При запуске из исходников его
 * нет, и выдумывать номер нельзя: сборка из репозитория не равна выпущенной
 * версии, а проверка обновлений на выдуманном номере предлагала бы «обновиться»
 * до того, что уже собрано.
 *
 * Сравнение версий — общее с Android, см. [com.opendisk.bridge.AppReleases.isNewer].
 */
object AppVersion {

    val current: String? = System.getProperty("jpackage.app-version")?.takeIf { it.isNotBlank() }
}
