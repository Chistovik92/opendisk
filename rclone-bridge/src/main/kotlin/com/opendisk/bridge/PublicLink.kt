package com.opendisk.bridge

/**
 * Файл на подключённом диске, разложенный обратно на облако и путь внутри него.
 *
 * Это и есть та связка, ради которой всё затевалось: пользователь видит файл по
 * местному пути (`Z:\Отчёты\март.pdf`), а ссылку на скачивание выдаёт то облако,
 * в котором файл лежит на самом деле, — и ему нужен путь в его собственных
 * терминах (`Отчёты/март.pdf`).
 */
data class RemoteFile(
    /** Имя облака в конфиге rclone. */
    val remote: String,
    /** Путь внутри облака, всегда через прямой слэш и без ведущего. */
    val path: String,
)

/**
 * Облако не умеет выдавать публичные ссылки.
 *
 * Отделено от прочих ошибок RC по той же причине, что и
 * [RcloneConfigLockedException]: это не поломка, а свойство бэкенда, и говорить
 * о нём нужно иначе. Ссылки умеют выдавать Яндекс.Диск, Google Drive, Dropbox,
 * OneDrive и подобные; SFTP, FTP и обычный WebDAV — нет, у самого протокола
 * нет такого понятия.
 */
class PublicLinkUnsupportedException(
    val remote: String,
    cause: RcloneRcException,
) : RuntimeException("облако '$remote' не умеет выдавать ссылки: ${cause.rcloneError}", cause)

/**
 * Перевод местного пути на подключённом диске в путь внутри облака.
 *
 * Отдельно от [RcloneClient] и без единого обращения к сети: это чистая работа
 * со строками, и проверять её надо строками же. Живой диск для проверки не
 * нужен, а разночтения тут стоят дорого — ошибка в один сегмент означает ссылку
 * не на тот файл.
 *
 * Лежит в общем модуле, а не в десктопном коде, потому что на Android ровно та
 * же задача: `DocumentsProvider` отдаёт системе свои идентификаторы документов,
 * и их точно так же надо разложить на облако и путь.
 */
object MountedPaths {

    /**
     * Находит облако, которому принадлежит [localPath], и путь внутри него.
     *
     * @param mounts подключённые диски: имя облака → точка монтирования.
     * @param caseInsensitive сравнивать пути без учёта регистра. На Windows —
     *        обязательно: пользователь легко получит `z:\файл` там, где диск
     *        подключён как `Z:`, и это один и тот же файл.
     *
     * @return null, если файл не лежит ни на одном подключённом диске. Это
     *         не ошибка вызова, а обычный случай: человек мог уйти в файловом
     *         диалоге на локальный диск, и сказать ему об этом надо внятно.
     */
    fun resolve(
        localPath: String,
        mounts: Map<String, String>,
        caseInsensitive: Boolean = defaultCaseInsensitive(),
    ): RemoteFile? {
        val file = normalize(localPath)
        if (file.isEmpty()) return null

        // Сначала самые длинные точки монтирования. Одна точка может лежать
        // внутри другой (`~/OpenDisk` и `~/OpenDisk/архив`), и тогда правильный
        // ответ — вложенная: именно её диск держит файл.
        return mounts.entries
            .asSequence()
            .map { (remote, point) -> remote to normalize(point) }
            .filter { (_, root) -> root.isNotEmpty() }
            .sortedByDescending { (_, root) -> root.length }
            .firstNotNullOfOrNull { (remote, root) ->
                relativize(file, root, caseInsensitive)?.let { RemoteFile(remote, it) }
            }
    }

    /**
     * Путь [file] относительно корня [root] или null, если файл не под ним.
     *
     * Сравнение идёт по границе сегмента, а не просто по префиксу строки. Иначе
     * диск, подключённый в `~/OpenDisk/яндекс`, забирал бы себе и файлы из
     * соседнего `~/OpenDisk/яндекс-архив` — строка начинается так же, а каталог
     * совсем другой.
     */
    private fun relativize(file: String, root: String, caseInsensitive: Boolean): String? {
        if (file.equals(root, ignoreCase = caseInsensitive)) return ""

        // Корень уже нормализован, так что завершающий слэш есть только у
        // корня файловой системы («/») — приписывать ему второй нельзя.
        val prefix = if (root.endsWith("/")) root else "$root/"
        if (!file.startsWith(prefix, ignoreCase = caseInsensitive)) return null
        return file.substring(prefix.length)
    }

    /**
     * Приводит путь к одному виду: прямые слэши, без повторов, без `.` и `..`,
     * без завершающего слэша.
     *
     * `..` разворачиваем здесь, а не отбрасываем путь целиком: вычислять по нему
     * путь внутри облака без этого нельзя — `Z:\папка\..\файл` указывает на
     * `файл` в корне, и ссылка должна получиться именно на него.
     */
    internal fun normalize(raw: String): String {
        val unified = raw.trim().replace('\\', '/')
        if (unified.isEmpty()) return ""

        // Сетевой путь Windows (`\\сервер\ресурс`) начинается с двух слэшей,
        // и второй — часть адреса, а не пустой сегмент.
        val prefix = when {
            unified.startsWith("//") -> "//"
            unified.startsWith("/") -> "/"
            else -> ""
        }

        val segments = ArrayDeque<String>()
        unified.split('/').forEach { segment ->
            when {
                segment.isEmpty() || segment == "." -> Unit
                segment == ".." -> segments.removeLastOrNull()
                else -> segments.addLast(segment)
            }
        }
        return prefix + segments.joinToString("/")
    }

    /**
     * Регистр в путях не важен на Windows и macOS, важен на Linux.
     *
     * macOS отнесён к нечувствительным сознательно: по умолчанию его APFS
     * именно такой, а ошибка в эту сторону безобиднее — в худшем случае найдём
     * диск там, где формально не должны были.
     */
    internal fun defaultCaseInsensitive(
        osName: String = System.getProperty("os.name").orEmpty(),
    ): Boolean {
        val lower = osName.lowercase()
        return lower.contains("win") || lower.contains("mac")
    }
}
