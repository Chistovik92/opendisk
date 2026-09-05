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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.opendisk.bridge.RcloneClient

/**
 * Запрос пароля от зашифрованного конфига. Не диалог, а часть экрана: без пароля
 * приложению всё равно нечего показывать, а модальное окно поверх пустоты только мешает.
 */
@Composable
fun PasswordPrompt(wrongAttempt: Boolean, onSubmit: (String) -> Unit) {
    var password by remember { mutableStateOf("") }

    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Конфигурация rclone зашифрована", style = MaterialTheme.typography.titleMedium)
        Text(
            "Введите пароль конфига — он нужен, чтобы прочитать список облаков.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Пароль") },
            singleLine = true,
            isError = wrongAttempt,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )

        if (wrongAttempt) {
            Text(
                "Пароль не подошёл. Попробуйте ещё раз.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        Button(onClick = { onSubmit(password) }, enabled = password.isNotEmpty()) {
            Text("Разблокировать")
        }
    }
}

/**
 * Переименование облака.
 *
 * В rclone у облака нет отдельного «отображаемого имени» — имя и есть ключ
 * секции в конфиге, поэтому переименование пересоздаёт запись. Про отключение
 * смонтированного диска предупреждаем заранее, чтобы это не стало сюрпризом.
 */
@Composable
fun RenameCloudDialog(
    cloudName: String,
    isMounted: Boolean,
    existingNames: Set<String>,
    onDismiss: () -> Unit,
    onRename: (String, (String?) -> Unit) -> Unit,
) {
    var newName by remember { mutableStateOf(cloudName) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    val taken = newName != cloudName && newName in existingNames
    val canSubmit = newName.isNotBlank() && newName != cloudName && !taken && !busy

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Переименовать облако") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("Новое название") },
                    singleLine = true,
                    isError = taken,
                    supportingText = { if (taken) Text("Такое название уже занято") },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (isMounted) {
                    Text(
                        "Облако сейчас подключено как диск — при переименовании оно будет " +
                            "отключено, подключить его нужно будет заново.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Button(
                enabled = canSubmit,
                onClick = {
                    busy = true
                    error = null
                    onRename(newName.trim()) { failure ->
                        busy = false
                        error = failure
                    }
                },
            ) {
                Text(if (busy) "Переименовываю..." else "Переименовать")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Отмена") } },
    )
}

@Composable
fun ConfirmDeleteDialog(cloudName: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Удалить облако?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Облако «$cloudName» будет удалено из конфигурации rclone.")
                Text(
                    "Файлы в самом облаке не тронутся — удаляется только подключение.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { Button(onClick = onConfirm) { Text("Удалить") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}
