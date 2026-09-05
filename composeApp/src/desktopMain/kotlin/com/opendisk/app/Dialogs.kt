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
 * Мастер добавления облака.
 *
 * Список бэкендов и их настройки берутся из самого rclone (`config/providers`),
 * а не зашиты в код: так мастер не расходится с установленной версией rclone.
 */
@Composable
fun AddCloudDialog(
    providers: List<RcloneClient.Provider>,
    existingNames: Set<String>,
    onDismiss: () -> Unit,
    onCreate: (
        name: String,
        type: String,
        parameters: Map<String, String>,
        secretKeys: Set<String>,
        onResult: (String?) -> Unit,
    ) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(providers.firstOrNull()) }
    var expanded by remember { mutableStateOf(false) }
    var values by remember { mutableStateOf(mapOf<String, String>()) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    val formOptions = selected?.formOptions().orEmpty()
    val nameTaken = name in existingNames
    val missingRequired = formOptions.any { it.required && values[it.name].isNullOrBlank() }
    val canSubmit = name.isNotBlank() && !nameTaken && selected != null && !missingRequired && !busy

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Новое облако") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Название") },
                    singleLine = true,
                    isError = nameTaken,
                    supportingText = {
                        if (nameTaken) Text("Облако с таким названием уже есть")
                    },
                    modifier = Modifier.fillMaxWidth(),
                )

                Column {
                    OutlinedButton(
                        onClick = { expanded = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(selected?.let { "${it.name} — ${it.description}" } ?: "Выберите тип")
                    }
                    DropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false },
                        modifier = Modifier.heightIn(max = 320.dp),
                    ) {
                        providers.forEach { provider ->
                            DropdownMenuItem(
                                text = { Text("${provider.name} — ${provider.description}") },
                                onClick = {
                                    selected = provider
                                    // Значения от прошлого бэкенда к новому не относятся.
                                    values = emptyMap()
                                    expanded = false
                                },
                            )
                        }
                    }
                }

                formOptions.forEach { option ->
                    OutlinedTextField(
                        value = values[option.name].orEmpty(),
                        onValueChange = { values = values + (option.name to it) },
                        label = { Text(option.name + if (option.required) " *" else "") },
                        singleLine = true,
                        visualTransformation = if (option.isPassword) {
                            PasswordVisualTransformation()
                        } else {
                            androidx.compose.ui.text.input.VisualTransformation.None
                        },
                        supportingText = {
                            if (option.shortHelp.isNotEmpty()) Text(option.shortHelp)
                        },
                        modifier = Modifier.fillMaxWidth(),
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
                    val provider = selected ?: return@Button
                    busy = true
                    error = null
                    onCreate(
                        name.trim(),
                        provider.name,
                        values,
                        formOptions.filter { it.isPassword }.map { it.name }.toSet(),
                    ) { failure ->
                        busy = false
                        error = failure
                    }
                },
            ) {
                Text(if (busy) "Создаю..." else "Создать")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text("Отмена") }
        },
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

/** Строка кнопок в диалогах — вынесена, чтобы не дублировать выравнивание. */
@Composable
fun DialogButtons(content: @Composable () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { content() }
}
