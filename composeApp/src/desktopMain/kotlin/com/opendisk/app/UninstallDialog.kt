package com.opendisk.app

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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Полное удаление приложения.
 *
 * Смысл диалога — спросить ровно про то, о чём нельзя решать за пользователя.
 * Свои следы приложение убирает всегда и молча; конфиг с доступами к облакам
 * и драйвер WinFsp — только по явной галочке, потому что первое это данные
 * пользователя, а второй может быть нужен другим программам.
 */
@Composable
fun UninstallDialog(
    controller: RcloneController,
    onDismiss: () -> Unit,
    onQuit: () -> Unit,
) {
    val strings = LocalStrings.current

    var removeConfig by remember { mutableStateOf(false) }
    var removeWinFsp by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    // Ищем WinFsp один раз при открытии: показывать галочку для того, чего
    // в системе нет, значит обещать несделанное.
    var winFspUninstall by remember { mutableStateOf<String?>(null) }
    var winFspChecked by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        winFspUninstall = withContext(Dispatchers.IO) { Cleanup.findWinFspUninstall() }
        winFspChecked = true
    }

    val installed = AppVersion.current != null

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(strings.removeAppTitle) },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (!installed) {
                    Text(strings.removeNotInstalled, style = MaterialTheme.typography.bodySmall)
                } else {
                    Text(strings.removeAppIntro, style = MaterialTheme.typography.bodySmall)

                    OptionRow(
                        checked = removeConfig,
                        onCheckedChange = { removeConfig = it },
                        enabled = !busy,
                        title = strings.removeCloudConfig,
                        hint = strings.removeCloudConfigHint,
                    )

                    if (winFspChecked) {
                        if (winFspUninstall != null) {
                            OptionRow(
                                checked = removeWinFsp,
                                onCheckedChange = { removeWinFsp = it },
                                enabled = !busy,
                                title = strings.removeWinFsp,
                                hint = strings.removeWinFspHint,
                            )
                        } else {
                            Text(
                                strings.removeWinFspNotFound,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                message?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Button(
                enabled = installed && !busy,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
                onClick = {
                    busy = true
                    message = strings.removeWorking
                    // Порядок важен: сначала отключить диски и погасить rclone,
                    // иначе установщик упрётся в занятые файлы, а диски
                    // останутся в системе без того, кто ими управляет.
                    controller.prepareForRemoval(alsoCloudConfig = removeConfig) {
                        val winFsp = winFspUninstall
                        if (removeWinFsp && winFsp != null) {
                            Cleanup.uninstallWinFsp(winFsp)
                        }
                        message = strings.removeStarted
                        // Само приложение удаляет установщик: он же уберёт
                        // ярлыки и запись в «Программах и компонентах», чего
                        // приложение о себе сделать не может.
                        Cleanup.startSelfUninstall()
                        onQuit()
                    }
                },
            ) {
                Text(strings.removeProceed)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(strings.cancel) } },
    )
}

@Composable
private fun OptionRow(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean,
    title: String,
    hint: String,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Checkbox(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
            Text(title)
        }
        Text(
            hint,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 12.dp),
        )
    }
}
