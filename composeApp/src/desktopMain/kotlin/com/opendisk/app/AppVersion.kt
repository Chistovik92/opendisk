package com.opendisk.app

/**
 * Версия приложения и сравнение версий.
 *
 * Номер проставляет лаунчер jpackage через `-Djpackage.app-version` — это видно
 * в `app/OpenDisk.cfg` установленного приложения. При запуске из исходников его
 * нет, и выдумывать номер нельзя: сборка из репозитория не равна выпущенной
 * версии, а проверка обновлений на выдуманном номере предлагала бы «обновиться»
 * до того, что уже собрано.
 */
object AppVersion {

    val current: String? = System.getProperty("jpackage.app-version")?.takeIf { it.isNotBlank() }

    /**
     * Больше ли [candidate], чем [current].
     *
     * Сравниваем по числам, а не строками: строкой «0.2.10» меньше «0.2.9»,
     * и обновление на десятый выпуск просто не предложилось бы. Ведущая «v»
     * в тегах GitHub отбрасывается.
     */
    fun isNewer(candidate: String, current: String): Boolean {
        val left = parts(candidate)
        val right = parts(current)
        if (left.isEmpty() || right.isEmpty()) return false

        for (i in 0 until maxOf(left.size, right.size)) {
            val a = left.getOrElse(i) { 0 }
            val b = right.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    /**
     * Разбирает «v0.2.5» в [0, 2, 5]. Всё, что не разбирается в числа,
     * даёт пустой список — такую версию сравнивать не с чем, и предлагать
     * обновление по ней нельзя.
     */
    internal fun parts(version: String): List<Int> {
        val cleaned = version.trim().removePrefix("v")
        if (cleaned.isEmpty()) return emptyList()
        val numbers = cleaned.split('.').map { it.trim().toIntOrNull() }
        return if (numbers.any { it == null }) emptyList() else numbers.filterNotNull()
    }
}
