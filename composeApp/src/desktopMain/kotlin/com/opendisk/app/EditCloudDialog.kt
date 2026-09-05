package com.opendisk.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.opendisk.bridge.RcloneClient

/**
 * Изменение параметров уже добавленного облака.
 *
 * До этого поменять адрес сервера или логин можно было только удалив облако и
 * заведя заново — а для сервисов с подтверждением доступа в браузере это ещё и
 * означало проходить авторизацию повторно: токен лежит в тех же параметрах.
 * Здесь меняются только те поля, которые пользователь действительно тронул.
 *
 * Секреты не показываются. Показать их нечем — в конфиге они «затемнённые», —
 * а подставить затемнённое значение в поле пароля значило бы при сохранении
 * записать его обратно уже как открытый текст.
 */
@Composable
fun EditCloudDialog(
    cloudName: String,
    providers: List<RcloneClient.Provider>,
    loadConfig: (String, (Map<String, String>?, String?) -> Unit) -> Unit,
    onSave: (Map<String, String>, Set<String>, (String?) -> Unit) -> Unit,
    onDismiss: () -> Unit,
) {
    val strings = LocalStrings.current

    var current by remember { mutableStateOf<Map<String, String>?>(null) }
    var values by remember { mutableStateOf(mapOf<String, String>()) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(cloudName) {
        loadConfig(cloudName) { config, failure ->
            current = config.orEmpty()
            error = failure
        }
    }

    val loaded = current
    val provider = loaded?.get("type")?.let { type ->
        providers.firstOrNull { it.name == type }
    }
    val options = provider?.formOptions().orEmpty()

    // Отправляем только непустые поля: пустое означает «не трогать», а не
    // «стереть». Пустой ключ в config/update затёр бы настоящее значение.
    val changed = values.filterValues { it.isNotBlank() }
    val canSave = loaded != null && changed.isNotEmpty() && !busy

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(strings.connectionOf(cloudName)) },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (loaded == null && error == null) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp))
                        Text(strings.loadingCurrentSettings)
                    }
                }

                if (loaded != null) {
                    Text(
                        strings.editKeepsAuthorization,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    if (options.isEmpty()) {
                        Text(
                            strings.nothingToChange,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    options.forEach { option ->
                        val stored = loaded[option.name].orEmpty()
                        OutlinedTextField(
                            value = values[option.name] ?: if (option.isPassword) "" else stored,
                            onValueChange = { values = values + (option.name to it) },
                            label = { Text(option.name) },
                            singleLine = true,
                            visualTransformation = if (option.isPassword) {
                                PasswordVisualTransformation()
                            } else {
                                VisualTransformation.None
                            },
                            supportingText = {
                                Text(
                                    if (option.isPassword) {
                                        strings.leaveEmptyToKeep
                                    } else {
                                        option.shortHelp
                                    },
                                )
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }

                error?.let {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                enabled = canSave,
                onClick = {
                    busy = true
                    error = null
                    onSave(
                        changed,
                        options.filter { it.isPassword }.map { it.name }.toSet(),
                    ) { failure ->
                        busy = false
                        error = failure
                        if (failure == null) onDismiss()
                    }
                },
            ) {
                Text(if (busy) strings.saving else strings.save)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(strings.cancel) } },
    )
}
