package com.opendisk.app

import java.io.File

/**
 * Буквы дисков Windows и то, за кем они закреплены.
 *
 * С 0.5.6 облако на Windows — это определённая буква: выбрали один раз, и
 * «Яндекс» всегда `Y:`, после каждой перезагрузки и каждого переподключения.
 * Раньше буква подбиралась заново при каждом подключении, и от порядка
 * подключения зависело, на какой букве окажется облако, — ярлыки и
 * пути в программах смотрели то на одно облако, то на другое.
 *
 * Логика отдельно от интерфейса и без обращений к системе, кроме
 * [systemTaken]: так её можно проверить тестом.
 */
object DriveLetters {

    /** A и B исторически за флоппи, C — системный: начинаем с D. */
    val candidates: List<Char> = ('D'..'Z').toList()

    /** Буква из точки монтирования «Z:» или «Z:\»; null — это не буква диска. */
    fun letterOf(mountPoint: String?): Char? {
        val trimmed = mountPoint?.trim()?.trimEnd('\\', '/') ?: return null
        if (trimmed.length != 2 || trimmed[1] != ':' || !trimmed[0].isLetter()) return null
        return trimmed[0].uppercaseChar()
    }

    fun mountPointOf(letter: Char): String = "${letter.uppercaseChar()}:"

    /**
     * Буквы, которые уже заняты в системе: диски, флешки, сетевые папки.
     *
     * Список дисков системы — не всё. Сетевой диск, подключённый «с
     * восстановлением при входе», пока сервер недоступен, в нём отсутствует,
     * хотя проводник его показывает (с красным крестиком) и букву держит:
     * при появлении сервера Windows вернёт диск на неё. Такие диски живут
     * в реестре, в `HKCU\Network\<буква>`, — оттуда их и берём.
     */
    fun systemTaken(): Set<Char> =
        File.listRoots().mapNotNull { it.path.firstOrNull()?.uppercaseChar() }.toSet() +
            rememberedNetworkDrives()

    private fun rememberedNetworkDrives(): Set<Char> = runCatching {
        val process = ProcessBuilder("reg", "query", """HKCU\Network""").redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        process.waitFor()
        parseNetworkKeys(output)
    }.getOrDefault(emptySet())

    /** Строки `HKEY_CURRENT_USER\Network\Z` из вывода `reg query` → буквы. */
    internal fun parseNetworkKeys(output: String): Set<Char> =
        output.lineSequence()
            .map { it.trim() }
            .mapNotNull { line ->
                val key = line.substringAfterLast('\\', "")
                key.singleOrNull()?.takeIf { line.contains("\\Network\\", ignoreCase = true) && it.isLetter() }
            }
            .map { it.uppercaseChar() }
            .toSet()

    /** За какими облаками какие буквы закреплены, кроме [except]. */
    fun pinnedByOthers(settings: Map<String, CloudSettings>, except: String): Map<Char, String> =
        settings
            .filterKeys { it != except }
            .mapNotNull { (name, cloud) -> letterOf(cloud.mountPoint)?.let { it to name } }
            .toMap()

    /**
     * Первая свободная буква для облака.
     *
     * Свободная — значит не занятая в системе, не закреплённая за другим
     * облаком и не отданная только что другому подключению ([reserved]):
     * буква появляется среди дисков системы не в тот же миг, когда rclone
     * сообщил об успехе.
     *
     * Порядок тот же, что и до закрепления, — с `D:`. Облака, подключавшиеся
     * раньше, при первом подключении после обновления получат ту же букву,
     * что и всегда, и ярлыки на них не разъедутся.
     */
    fun firstFree(
        cloud: String,
        settings: Map<String, CloudSettings>,
        systemTaken: Set<Char>,
        reserved: Set<Char> = emptySet(),
    ): Char? {
        val pinned = pinnedByOthers(settings, cloud).keys
        return candidates.firstOrNull { it !in systemTaken && it !in pinned && it !in reserved }
    }

    /** Состояние буквы в списке выбора. */
    sealed interface Choice {
        val letter: Char

        data class Free(override val letter: Char) : Choice

        /** Закреплена за другим облаком — выбрать нельзя, иначе два облака на одной букве. */
        data class OtherCloud(override val letter: Char, val cloud: String) : Choice

        /** Занята в системе: настоящий диск, флешка или чужой сетевой диск. */
        data class System(override val letter: Char) : Choice
    }

    /**
     * Все буквы с их состоянием — для списка в настройках облака.
     *
     * @param ownMounted буква, на которой это облако подключено сейчас: в
     *        системе она занята, но занята им самим, и показывать её занятой
     *        было бы неправдой.
     */
    fun choices(
        cloud: String,
        settings: Map<String, CloudSettings>,
        systemTaken: Set<Char>,
        ownMounted: Char? = null,
    ): List<Choice> {
        val pinned = pinnedByOthers(settings, cloud)
        return candidates.map { letter ->
            when {
                letter in pinned -> Choice.OtherCloud(letter, pinned.getValue(letter))
                letter in systemTaken && letter != ownMounted -> Choice.System(letter)
                else -> Choice.Free(letter)
            }
        }
    }
}
