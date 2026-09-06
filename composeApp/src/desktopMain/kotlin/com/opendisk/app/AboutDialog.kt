package com.opendisk.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * «О приложении»: версия, версия встроенного rclone, пути к файлам и лицензии.
 *
 * Пути показываем не для красоты — это первое, что спрашивают при разборе
 * проблемы, и искать их по документации неудобно. Содержимое можно выделить
 * и скопировать, чтобы приложить к сообщению об ошибке.
 */
@Composable
fun AboutDialog(
    state: UiState,
    onDismiss: () -> Unit,
    onCheckUpdates: () -> Unit,
    onRemoveApp: () -> Unit,
) {
    val strings = LocalStrings.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.about) },
        text = {
            SelectionContainer {
                Column(
                    modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("OpenDisk", style = MaterialTheme.typography.titleMedium)
                    Text(strings.aboutDescription, style = MaterialTheme.typography.bodySmall)

                    Section(strings.version) {
                        Text(AppVersion.current ?: strings.versionUnknown)
                        state.rcloneVersion?.let {
                            Text("${strings.builtOnRclone} $it")
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = onCheckUpdates) { Text(strings.checkNow) }
                            TextButton(onClick = onRemoveApp) { Text(strings.removeApp) }
                        }
                    }

                    Section(strings.whereFilesAre) {
                        Text("${strings.cloudsList}: ${state.configFilePath}")
                        Text("${strings.appSettingsFile}: ${state.settingsFilePath}")
                    }

                    Section(strings.licenses) {
                        Text(strings.licensesText)
                    }

                    Section(strings.projectPage) {
                        Text(PROJECT_URL)
                        Text("${strings.reportProblem}: $ISSUES_URL")
                    }
                }
            }
        },
        confirmButton = { Button(onClick = onDismiss) { Text(strings.close) } },
    )
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        MaterialTheme.typography.bodySmall.let { content() }
    }
}

private const val PROJECT_URL = "https://github.com/Chistovik92/opendisk"
private const val ISSUES_URL = "https://github.com/Chistovik92/opendisk/issues"
