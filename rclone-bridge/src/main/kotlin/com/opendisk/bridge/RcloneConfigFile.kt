package com.opendisk.bridge

import java.io.File

/**
 * Родной `rclone.conf`.
 *
 * OpenDisk не изобретает свой формат хранения облаков и не дублирует конфиг —
 * работаем с тем же файлом, что и консольный rclone, чтобы настройки были общими
 * и пользователь мог править их привычным способом. См. docs/ARCHITECTURE.md.
 */
class RcloneConfigFile(val path: File) {

    fun exists(): Boolean = path.isFile

    /**
     * Зашифрован ли конфиг паролем.
     *
     * rclone помечает такой файл маркером [ENCRYPTED_MARKER] в первых строках,
     * дальше идёт base64. Читаем только начало: конфиг может быть большим,
     * а решение принимается по заголовку.
     */
    fun isEncrypted(): Boolean {
        if (!exists()) return false
        return runCatching {
            path.useLines { lines ->
                lines.take(HEADER_LINES_TO_SCAN).any { it.trim() == ENCRYPTED_MARKER }
            }
        }.getOrDefault(false)
    }

    override fun toString(): String = path.absolutePath

    companion object {
        /** Маркер, которым rclone открывает зашифрованный конфиг. */
        const val ENCRYPTED_MARKER = "RCLONE_ENCRYPT_V0:"

        /** Переменная окружения, которой rclone передаётся пароль конфига. */
        const val PASSWORD_ENV = "RCLONE_CONFIG_PASS"

        /** Переменная окружения, которой rclone переопределяют путь к конфигу. */
        const val CONFIG_PATH_ENV = "RCLONE_CONFIG"

        private const val HEADER_LINES_TO_SCAN = 5

        /**
         * Определяет путь к конфигу по тем же правилам, что и сам rclone:
         *
         * 1. переменная `RCLONE_CONFIG`, если задана;
         * 2. Windows — `%APPDATA%\rclone\rclone.conf`;
         * 3. остальные ОС — `$XDG_CONFIG_HOME/rclone/rclone.conf`,
         *    иначе `~/.config/rclone/rclone.conf`;
         * 4. если ничего из этого не существует, но есть старый `~/.rclone.conf` —
         *    берём его: rclone поддерживает эту раскладку для старых установок.
         *
         * Зависимости от окружения передаются параметрами, чтобы правила можно
         * было проверить тестами для всех ОС, а не только для текущей.
         */
        fun default(
            env: (String) -> String? = System::getenv,
            userHome: String = System.getProperty("user.home"),
            osName: String = System.getProperty("os.name"),
        ): RcloneConfigFile {
            env(CONFIG_PATH_ENV)?.takeIf { it.isNotBlank() }?.let {
                return RcloneConfigFile(File(it))
            }

            val home = File(userHome)
            val primary = if (osName.lowercase().contains("win")) {
                val appData = env("APPDATA")?.takeIf { it.isNotBlank() }
                    ?: File(home, "AppData/Roaming").path
                File(File(appData), "rclone/rclone.conf")
            } else {
                val xdg = env("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() }
                    ?: File(home, ".config").path
                File(File(xdg), "rclone/rclone.conf")
            }

            val legacy = File(home, ".rclone.conf")
            return RcloneConfigFile(if (!primary.isFile && legacy.isFile) legacy else primary)
        }
    }
}
