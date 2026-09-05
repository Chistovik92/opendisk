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
 * На текущем этапе (см. ROADMAP.md, Этап 1-2) окно только проверяет,
 * найден ли rclone в системе, — это первый практический шаг перед
 * экраном "Список облаков".
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
        val binary = RcloneProcess.findRcloneBinary()
        rcloneStatus = "Ожидаемый путь к rclone: $binary\n" +
            "(проверка существования файла будет добавлена вместе с экраном настроек)"
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
