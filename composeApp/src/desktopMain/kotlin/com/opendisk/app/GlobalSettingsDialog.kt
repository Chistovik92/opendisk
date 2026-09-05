package com.opendisk.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Общие настройки приложения: автозапуск и ограничение скорости.
 *
 * Ограничение общее, а не по облакам, потому что rclone умеет ограничивать
 * только глобально — на все переносы сразу.
 */
@Composable
fun GlobalSettingsDialog(
    current: GlobalSettings,
    onDismiss: () -> Unit,
    onSave: (GlobalSettings) -> Unit,
) {
    var autostart by remember { mutableStateOf(current.autostart) }
    var unlimited by remember {
        mutableStateOf(current.bandwidthLimit == GlobalSettings.BANDWIDTH_UNLIMITED)
    }
    var limit by remember {
        mutableStateOf(
            current.bandwidthLimit.takeIf { it != GlobalSettings.BANDWIDTH_UNLIMITED }.orEmpty(),
        )
    }

    val autostartSupported = remember { Autostart.isSupported() }
    val limitLooksValid = unlimited || limit.isBlank() || BANDWIDTH_PATTERN.matches(limit.trim())

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Настройки приложения") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Column {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Checkbox(
                            checked = autostart,
                            onCheckedChange = { autostart = it },
                            enabled = autostartSupported,
                        )
                        Text("Запускать при входе в систему")
                    }
                    Text(
                        if (autostartSupported) {
                            "Приложение запустится свёрнутым в трей и подключит облака, " +
                                "отмеченные как автоподключаемые."
                        } else {
                            "На этой системе автозапуск пока не поддерживается."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Ограничение скорости", style = MaterialTheme.typography.titleSmall)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Checkbox(checked = unlimited, onCheckedChange = { unlimited = it })
                        Text("Без ограничения")
                    }
                    if (!unlimited) {
                        OutlinedTextField(
                            value = limit,
                            onValueChange = { limit = it },
                            label = { Text("Скорость") },
                            singleLine = true,
                            isError = !limitLooksValid,
                            placeholder = { Text("например, 2M") },
                            supportingText = {
                                Text(
                                    if (limitLooksValid) {
                                        "Килобайты, мегабайты или гигабайты в секунду: 500k, 2M, 1G."
                                    } else {
                                        "Не разобрать. Ожидается число и единица: 500k, 2M, 1G."
                                    },
                                )
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Text(
                        "Ограничение действует сразу на все облака — rclone умеет " +
                            "ограничивать только так.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                enabled = limitLooksValid,
                onClick = {
                    val rate = if (unlimited || limit.isBlank()) {
                        GlobalSettings.BANDWIDTH_UNLIMITED
                    } else {
                        limit.trim()
                    }
                    onSave(GlobalSettings(autostart = autostart, bandwidthLimit = rate))
                },
            ) {
                Text("Сохранить")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

/**
 * Проверяем формат до отправки: rclone на непонятное значение отвечает
 * невнятным «bad bwlimit», и разбираться в нём пользователю незачем.
 */
private val BANDWIDTH_PATTERN = Regex("""^\d+(\.\d+)?\s*[kKmMgGtT]?[iI]?[bB]?$""")
