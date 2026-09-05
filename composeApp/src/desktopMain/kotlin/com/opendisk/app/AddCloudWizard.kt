package com.opendisk.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opendisk.bridge.RcloneClient

/** Шаг мастера: сначала выбор сервиса, потом его поля. */
private sealed interface WizardStep {
    data object PickService : WizardStep
    data class FillPreset(val preset: CloudPreset) : WizardStep
    data object PickAdvanced : WizardStep
    data class FillAdvanced(val provider: RcloneClient.Provider) : WizardStep
}

/**
 * Мастер добавления облака.
 *
 * Начинается с плиток популярных сервисов: большинству нужно нажать одну и,
 * в худшем случае, ввести логин с паролем. Полный список бэкендов rclone
 * доступен через «другое подключение» — там же, где он и нужен: тем, кто знает,
 * что ищет. До этого список из семи десятков пунктов был единственным способом
 * добавить облако, и пользоваться им было нечем.
 */
@Composable
fun AddCloudWizard(
    providers: List<RcloneClient.Provider>,
    existingNames: Set<String>,
    oauthUrl: String?,
    onDismiss: () -> Unit,
    onCreate: (
        name: String,
        type: String,
        parameters: Map<String, String>,
        secretKeys: Set<String>,
        onResult: (String?) -> Unit,
    ) -> Unit,
) {
    val strings = LocalStrings.current
    var step by remember { mutableStateOf<WizardStep>(WizardStep.PickService) }

    when (val current = step) {
        WizardStep.PickService -> ServicePickerDialog(
            onDismiss = onDismiss,
            onPreset = { step = WizardStep.FillPreset(it) },
            onAdvanced = { step = WizardStep.PickAdvanced },
        )

        is WizardStep.FillPreset -> PresetFormDialog(
            preset = current.preset,
            existingNames = existingNames,
            oauthUrl = oauthUrl,
            onBack = { step = WizardStep.PickService },
            onDismiss = onDismiss,
            onCreate = onCreate,
        )

        WizardStep.PickAdvanced -> AdvancedPickerDialog(
            providers = providers,
            onBack = { step = WizardStep.PickService },
            onDismiss = onDismiss,
            onPick = { step = WizardStep.FillAdvanced(it) },
        )

        is WizardStep.FillAdvanced -> AdvancedFormDialog(
            provider = current.provider,
            existingNames = existingNames,
            onBack = { step = WizardStep.PickAdvanced },
            onDismiss = onDismiss,
            onCreate = onCreate,
        )
    }
}

@Composable
private fun ServicePickerDialog(
    onDismiss: () -> Unit,
    onPreset: (CloudPreset) -> Unit,
    onAdvanced: () -> Unit,
) {
    val strings = LocalStrings.current
    val presets = remember(strings) { cloudPresets(strings) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.whichCloud) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.heightIn(max = 360.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(presets, key = { it.id }) { preset ->
                        ServiceTile(preset = preset, onClick = { onPreset(preset) })
                    }
                }
                TextButton(onClick = onAdvanced) {
                    Text(strings.otherConnectionFull)
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(strings.cancel) } },
    )
}

@Composable
private fun ServiceTile(preset: CloudPreset, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(
            modifier = Modifier.padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(
                modifier = Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(preset.accent),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    preset.glyph,
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            Text(
                preset.title,
                style = MaterialTheme.typography.labelLarge,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            Text(
                preset.subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}

@Composable
private fun PresetFormDialog(
    preset: CloudPreset,
    existingNames: Set<String>,
    oauthUrl: String?,
    onBack: () -> Unit,
    onDismiss: () -> Unit,
    onCreate: (String, String, Map<String, String>, Set<String>, (String?) -> Unit) -> Unit,
) {
    val strings = LocalStrings.current
    var name by remember { mutableStateOf(uniqueName(preset.id, existingNames)) }
    var values by remember { mutableStateOf(mapOf<String, String>()) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    val nameTaken = name in existingNames
    val missing = preset.fields.any { it.required && values[it.key].isNullOrBlank() }
    val canSubmit = name.isNotBlank() && !nameTaken && !missing && !busy

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(preset.title) },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (busy && preset.oauth) {
                    OauthWaiting(oauthUrl)
                } else {
                    preset.hint?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall)
                    }

                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text(strings.nameInList) },
                        singleLine = true,
                        isError = nameTaken,
                        supportingText = { if (nameTaken) Text(strings.nameTaken) },
                        modifier = Modifier.fillMaxWidth(),
                    )

                    preset.fields.forEach { field ->
                        OutlinedTextField(
                            value = values[field.key].orEmpty(),
                            onValueChange = { values = values + (field.key to it) },
                            label = { Text(field.label + if (field.required) " *" else "") },
                            singleLine = true,
                            visualTransformation = if (field.isPassword) {
                                PasswordVisualTransformation()
                            } else {
                                VisualTransformation.None
                            },
                            supportingText = { field.help?.let { Text(it) } },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    if (preset.oauth) {
                        Text(
                            strings.browserWillOpen,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        // Про это спотыкаются: страница открывается в аккаунте, в
                        // который браузер уже вошёл, и второй диск незаметно
                        // привязывается к тому же аккаунту.
                        Text(
                            strings.browserAccountWarning,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
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
                    onCreate(
                        name.trim(),
                        preset.backend,
                        preset.fixed + values,
                        preset.fields.filter { it.isPassword }.map { it.key }.toSet(),
                    ) { failure ->
                        busy = false
                        error = failure
                    }
                },
            ) {
                Text(if (busy) strings.connecting else strings.connect)
            }
        },
        dismissButton = {
            TextButton(onClick = onBack, enabled = !busy) { Text(strings.back) }
        },
    )
}

@Composable
private fun OauthWaiting(oauthUrl: String?) {
    val strings = LocalStrings.current

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp))
            Text(strings.waitingForBrowser)
        }
        Text(
            strings.waitingForBrowserHint,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        oauthUrl?.let {
            Text(strings.browserDidNotOpen, style = MaterialTheme.typography.bodySmall)
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun AdvancedPickerDialog(
    providers: List<RcloneClient.Provider>,
    onBack: () -> Unit,
    onDismiss: () -> Unit,
    onPick: (RcloneClient.Provider) -> Unit,
) {
    val strings = LocalStrings.current
    var query by remember { mutableStateOf("") }
    val filtered = providers.filter {
        query.isBlank() ||
            it.name.contains(query, ignoreCase = true) ||
            it.description.contains(query, ignoreCase = true)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.otherConnection) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text(strings.searchBackends(providers.size)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Column(
                    modifier = Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()),
                ) {
                    filtered.forEach { provider ->
                        Card(
                            modifier = Modifier.fillMaxWidth()
                                .padding(vertical = 3.dp)
                                .clickable { onPick(provider) },
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Text(provider.name, style = MaterialTheme.typography.labelLarge)
                                Text(
                                    provider.description,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    if (filtered.isEmpty()) {
                        Text(strings.nothingFound, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onBack) { Text(strings.back) } },
    )
}

@Composable
private fun AdvancedFormDialog(
    provider: RcloneClient.Provider,
    existingNames: Set<String>,
    onBack: () -> Unit,
    onDismiss: () -> Unit,
    onCreate: (String, String, Map<String, String>, Set<String>, (String?) -> Unit) -> Unit,
) {
    val strings = LocalStrings.current
    var name by remember { mutableStateOf(uniqueName(provider.name, existingNames)) }
    var values by remember { mutableStateOf(mapOf<String, String>()) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    val options = provider.formOptions()
    val nameTaken = name in existingNames
    val missing = options.any { it.required && values[it.name].isNullOrBlank() }
    val canSubmit = name.isNotBlank() && !nameTaken && !missing && !busy

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("${provider.name} — ${provider.description}") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(strings.nameInList) },
                    singleLine = true,
                    isError = nameTaken,
                    supportingText = { if (nameTaken) Text(strings.nameTaken) },
                    modifier = Modifier.fillMaxWidth(),
                )

                if (options.isEmpty()) {
                    Text(
                        strings.backendHasNoRequiredFields,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                options.forEach { option ->
                    OutlinedTextField(
                        value = values[option.name].orEmpty(),
                        onValueChange = { values = values + (option.name to it) },
                        label = { Text(option.name + if (option.required) " *" else "") },
                        singleLine = true,
                        visualTransformation = if (option.isPassword) {
                            PasswordVisualTransformation()
                        } else {
                            VisualTransformation.None
                        },
                        supportingText = { if (option.shortHelp.isNotEmpty()) Text(option.shortHelp) },
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
                    busy = true
                    error = null
                    onCreate(
                        name.trim(),
                        provider.name,
                        values,
                        options.filter { it.isPassword }.map { it.name }.toSet(),
                    ) { failure ->
                        busy = false
                        error = failure
                    }
                },
            ) {
                Text(if (busy) strings.connecting else strings.connect)
            }
        },
        dismissButton = { TextButton(onClick = onBack, enabled = !busy) { Text(strings.back) } },
    )
}

/**
 * Имя по умолчанию, свободное в конфиге: подставляем его, чтобы не заставлять
 * человека придумывать название на пустом месте.
 */
private fun uniqueName(base: String, taken: Set<String>): String {
    if (base !in taken) return base
    var index = 2
    while ("$base$index" in taken) index++
    return "$base$index"
}
