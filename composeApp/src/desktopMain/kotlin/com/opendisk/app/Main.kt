package com.opendisk.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.opendisk.bridge.RcloneProcess

/**
 * Точка входа десктоп-приложения.
 *
 * На текущем этапе (см. ROADMAP.md, Этап 1-2) окно показывает, какой rclone
 * будет использован, — это первый практический шаг перед экраном "Список облаков".
 */
fun main() = application {
    Window(onCloseRequest = ::exitApplication, title = "OpenDisk") {
        MaterialTheme {
            AppRoot()
        }
    }
}

@Composable
fun AppRoot() {
    var rcloneStatus by remember { mutableStateOf("Проверка...") }

    remember {
        val located = RcloneProcess.locate()
        rcloneStatus = when (located?.source) {
            RcloneProcess.Source.BUNDLED ->
                "rclone: встроенный в OpenDisk\n${located.file}"

            RcloneProcess.Source.OVERRIDE ->
                "rclone: задан вручную через ${RcloneProcess.OVERRIDE_PROPERTY} / ${RcloneProcess.OVERRIDE_ENV}\n${located.file}"

            RcloneProcess.Source.SYSTEM_PATH ->
                "rclone: системный, найден в PATH\n${located.file}"

            null ->
                "rclone не найден.\nВ собранном дистрибутиве он идёт в комплекте; " +
                    "при запуске из исходников выполните ./gradlew :composeApp:run, " +
                    "чтобы сборка положила его в ресурсы приложения."
        }
        true
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("OpenDisk — открытый клиент облачных дисков", style = MaterialTheme.typography.headlineSmall)
            Text("Этап разработки: 1-2 (см. ROADMAP.md)")
            Text(rcloneStatus)
        }
    }
}
