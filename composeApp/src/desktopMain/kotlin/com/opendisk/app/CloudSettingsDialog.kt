package com.opendisk.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
 * Настройки одного облака: как кэшировать файлы и куда монтировать.
 *
 * Режим кэширования — ответ на главный вопрос про такие приложения:
 * «скачивать всё или только то, с чем работаю». Поэтому объяснения написаны
 * про последствия для пользователя, а не про устройство VFS.
 */
@Composable
fun CloudSettingsDialog(
    cloudName: String,
    current: CloudSettings,
    isMounted: Boolean,
    onDismiss: () -> Unit,
    onSave: (CloudSettings) -> Unit,
) {
    val strings = LocalStrings.current
    var cacheMode by remember { mutableStateOf(current.cacheMode) }
    var mountPoint by remember { mutableStateOf(current.mountPoint.orEmpty()) }
    var mountOnStartup by remember { mutableStateOf(current.mountOnStartup) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.cloudSettingsTitle(cloudName)) },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(strings.whatToKeepOnDisk, style = MaterialTheme.typography.titleSmall)

                strings.cacheModes.forEach { option ->
                    CacheModeRow(
                        option = option,
                        selected = cacheMode == option.value,
                        onSelect = { cacheMode = option.value },
                    )
                }

                Text(strings.whereToMount, style = MaterialTheme.typography.titleSmall)
                OutlinedTextField(
                    value = mountPoint,
                    onValueChange = { mountPoint = it },
                    label = { Text(strings.mountPoint) },
                    singleLine = true,
                    placeholder = { Text(RcloneController.defaultMountPoint(cloudName)) },
                    supportingText = {
                        Text(strings.mountPointHint)
                    },
                    modifier = Modifier.fillMaxWidth(),
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Checkbox(checked = mountOnStartup, onCheckedChange = { mountOnStartup = it })
                    Text(strings.mountOnStartup)
                }

                if (isMounted) {
                    Text(
                        strings.settingsApplyOnReconnect,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(
                        CloudSettings(
                            cacheMode = cacheMode,
                            mountPoint = mountPoint.trim().takeIf { it.isNotEmpty() },
                            mountOnStartup = mountOnStartup,
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

@Composable
private fun CacheModeRow(
    option: CacheModeOption,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onSelect)) {
        Row(
            modifier = Modifier.padding(10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Top,
        ) {
            RadioButton(selected = selected, onClick = onSelect)
            Column {
                Text(option.title, style = MaterialTheme.typography.labelLarge)
                Text(
                    option.explanation,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
