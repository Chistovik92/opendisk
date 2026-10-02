package com.opendisk.app

import com.opendisk.bridge.RcloneClient

/**
 * Что показывает трей: с какой скоростью приложение сейчас гонит данные по
 * сети и какие файлы недавно ушли в облако или пришли из него.
 *
 * Всё здесь — чистая логика без окон, чтобы проверяться обычными тестами.
 */
data class TrayStatus(
    /** Байт в секунду прямо сейчас, а не за всё время работы. */
    val bytesPerSecond: Double = 0.0,
    val recent: List<RecentFile> = emptyList(),
) {
    val active: Boolean get() = bytesPerSecond >= ACTIVE_THRESHOLD

    companion object {
        /** Меньше килобайта в секунду — это фоновый шум, а не передача. */
        const val ACTIVE_THRESHOLD = 1024.0
    }
}

/** Недавно перенесённый файл. */
data class RecentFile(
    val name: String,
    val size: Long,
    /** true — ушёл в облако, false — пришёл из облака. */
    val upload: Boolean,
    /** Имя облака; null — не удалось определить. */
    val cloud: String?,
    val completedAt: String,
)

/**
 * Текущая скорость по двум замерам счётчика `bytes` из `core/stats`.
 *
 * Берём разницу счётчика за прошедшее время, а не поле `speed` из ответа:
 * оно сглажено за всю передачу и после её конца остаётся ненулевым ещё
 * несколько секунд, а в трее нужна скорость «сейчас».
 */
class NetworkMeter {
    private var lastBytes = -1L
    private var lastAtMillis = 0L

    /** @return байт в секунду с прошлого вызова; 0 при первом замере или сбросе счётчика. */
    fun sample(totalBytes: Long, nowMillis: Long): Double {
        val previousBytes = lastBytes
        val previousAt = lastAtMillis
        lastBytes = totalBytes
        lastAtMillis = nowMillis
        if (previousBytes < 0 || totalBytes < previousBytes) return 0.0
        val seconds = (nowMillis - previousAt) / 1000.0
        if (seconds <= 0) return 0.0
        return (totalBytes - previousBytes) / seconds
    }

    fun reset() {
        lastBytes = -1
    }
}

/**
 * Недавние файлы из завершённых переносов rclone.
 *
 * Берутся только удавшиеся переносы файлов: проверки (`checked`) — это сверка
 * размеров, а не передача, и ошибки в списке «недавно синхронизировано»
 * только сбивали бы с толку. Повторы одного файла схлопываются в самый
 * поздний, самые свежие — первыми.
 *
 * Направление и облако определяются по «файловым системам» переноса: у
 * облака это «имя:», у кэша на диске — путь. Папка кэша rclone (vfs) на
 * стороне чтения и записи — просто локальная сторона.
 */
fun recentFiles(
    transfers: List<RcloneClient.CompletedTransfer>,
    cloudNames: Set<String>,
    limit: Int = RECENT_LIMIT,
): List<RecentFile> =
    transfers
        .asSequence()
        .filter { !it.checked && !it.failed && it.name.isNotEmpty() && it.completedAt.isNotEmpty() && !it.completedAt.startsWith("0001") }
        .sortedByDescending { it.completedAt }
        .distinctBy { it.name }
        .take(limit)
        .map { transfer ->
            val dstCloud = cloudOf(transfer.dstFs, cloudNames)
            val srcCloud = cloudOf(transfer.srcFs, cloudNames)
            RecentFile(
                name = transfer.name,
                size = transfer.size,
                upload = dstCloud != null && srcCloud == null,
                cloud = dstCloud ?: srcCloud,
                completedAt = transfer.completedAt,
            )
        }
        .toList()

private const val RECENT_LIMIT = 8

/** Облако из `имя:путь`; null — это локальный путь, в том числе «C:\…». */
internal fun cloudOf(fs: String, cloudNames: Set<String>): String? {
    val name = fs.substringBefore(':', "")
    // Одна буква перед двоеточием — диск Windows, а не облако.
    return name.takeIf { it.length > 1 && it in cloudNames }
}

/** «1,2 МБ/с». Единицы берутся из языка интерфейса. */
fun formatSpeed(bytesPerSecond: Double, units: List<String>): String {
    var value = bytesPerSecond.coerceAtLeast(0.0)
    var index = 0
    while (value >= 1024 && index < units.lastIndex) {
        value /= 1024
        index++
    }
    // До килобайта целые числа, дальше один знак — «1,2», но не «1,23456».
    return if (index == 0) "${value.toLong()} ${units[0]}" else String.format("%.1f %s", value, units[index])
}
