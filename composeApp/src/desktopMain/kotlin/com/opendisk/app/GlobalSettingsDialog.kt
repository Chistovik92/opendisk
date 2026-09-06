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
import androidx.compose.material3.RadioButton
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
    val strings = LocalStrings.current
    var language by remember { mutableStateOf(Language.fromCode(current.language)) }
    var autostart by remember { mutableStateOf(current.autostart) }
    var checkUpdates by remember { mutableStateOf(current.checkUpdates) }
    var unlimited by remember {
        mutableStateOf(current.bandwidthLimit == GlobalSettings.BANDWIDTH_UNLIMITED)
    }
    var limit by remember {
        mutableStateOf(
            current.bandwidthLimit.takeIf { it != GlobalSettings.BANDWIDTH_UNLIMITED }.orEmpty(),
        )
    }

    val autostartUnavailable = remember { Autostart.unavailableReason() }
    val autostartSupported = autostartUnavailable == null
    val limitLooksValid = unlimited || limit.isBlank() || BANDWIDTH_PATTERN.matches(limit.trim())

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.appSettings) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(strings.language, style = MaterialTheme.typography.titleSmall)
                    Language.entries.forEach { option ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            RadioButton(
                                selected = language == option,
                                onClick = { language = option },
                            )
                            Text(
                                when (option) {
                                    Language.AUTO -> strings.languageAuto
                                    Language.RUSSIAN -> "Русский"
                                    Language.ENGLISH -> "English"
                                },
                            )
                        }
                    }
                    Text(
                        strings.languageChangeHint,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

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
                        Text(strings.runAtLogin)
                    }
                    Text(
                        when (autostartUnavailable) {
                            null -> strings.runAtLoginHint
                            Autostart.Unavailable.OPERATING_SYSTEM -> strings.autostartUnsupported
                            Autostart.Unavailable.NOT_INSTALLED -> strings.autostartNeedsInstall
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Column {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Checkbox(checked = checkUpdates, onCheckedChange = { checkUpdates = it })
                        Text(strings.checkUpdates)
                    }
                    Text(
                        strings.checkUpdatesHint,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(strings.speedLimit, style = MaterialTheme.typography.titleSmall)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Checkbox(checked = unlimited, onCheckedChange = { unlimited = it })
                        Text(strings.unlimited)
                    }
                    if (!unlimited) {
                        OutlinedTextField(
                            value = limit,
                            onValueChange = { limit = it },
                            label = { Text(strings.speed) },
                            singleLine = true,
                            isError = !limitLooksValid,
                            placeholder = { Text(strings.speedExample) },
                            supportingText = {
                                Text(if (limitLooksValid) strings.speedHint else strings.speedInvalid)
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Text(
                        strings.speedIsGlobal,
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
                    onSave(
                        GlobalSettings(
                            autostart = autostart,
                            bandwidthLimit = rate,
                            checkUpdates = checkUpdates,
                            language = language.code,
                        ),
                    )
                },
            ) {
                Text(strings.save)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(strings.cancel) } },
    )
}

/**
 * Проверяем формат до отправки: rclone на непонятное значение отвечает
 * невнятным «bad bwlimit», и разбираться в нём пользователю незачем.
 */
private val BANDWIDTH_PATTERN = Regex("""^\d+(\.\d+)?\s*[kKmMgGtT]?[iI]?[bB]?$""")
