package com.opendisk.android

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File

/**
 * Путь к конфигу rclone для проверок.
 *
 * Задавать обязательно до первого [LibrcloneTransport.get] — ровно как это
 * делает приложение. Без пути rclone не сохранит ни одного облака, и на
 * телефоне это выглядело как «настройки сбросились сами».
 */
object TestConfig {
    fun use(): File {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.filesDir, "rclone.conf")
        LibrcloneTransport.useConfig(file)
        return file
    }
}
