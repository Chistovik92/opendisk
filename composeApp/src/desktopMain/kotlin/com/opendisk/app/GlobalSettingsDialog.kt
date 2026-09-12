package com.opendisk.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
 * Настройки приложения: язык, оформление, автозапуск, обновления, ограничение
 * скорости — и здесь же действия над самой установкой.
 *
 * «Проверить обновления» и «Удалить OpenDisk» переехали сюда из «О приложении»:
 * там они были справкой среди путей к файлам и лицензий, а это единственные
 * во всём окне кнопки, которые меняют программу на компьютере. Искать их
 * логично там же, где галочка «проверять обновления».
 *
 * Содержимое прокручивается. Без этого нижний раздел обрезался прямо по
 * галочке «без ограничения»: на экране оставался включённый переключатель
 * без подписи, и понять, что он делает, было нельзя.
 *
 * Ограничение скорости общее, а не по облакам, потому что rclone умеет
 * ограничивать только глобально — на все переносы сразу.
 */
@Composable
fun GlobalSettingsDialog(
    current: GlobalSettings,
    onDismiss: () -> Unit,
    onSave: (GlobalSettings) -> Unit,
    onCheckUpdates: () -> Unit,
    onRemoveApp: () -> Unit,
) {
    val strings = LocalStrings.current
    var language by remember { mutableStateOf(Language.fromCode(current.language)) }
    var theme by remember { mutableStateOf(ThemeChoice.fromCode(current.theme)) }
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
            Column(
                modifier = Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                SettingsSection(strings.settingsGeneral) {
                    Choice(strings.language) {
                        Language.entries.forEach { option ->
                            Option(
                                selected = language == option,
                                label = when (option) {
                                    Language.AUTO -> strings.languageAuto
                                    Language.RUSSIAN -> "Русский"
                                    Language.ENGLISH -> "English"
                                },
                                onClick = { language = option },
                            )
                        }
                        Hint(strings.languageChangeHint)
                    }

                    Switch(
                        checked = autostart,
                        onCheckedChange = { autostart = it },
                        label = strings.runAtLogin,
                        enabled = autostartSupported,
                        hint = when (autostartUnavailable) {
                            null -> strings.runAtLoginHint
                            Autostart.Unavailable.OPERATING_SYSTEM -> strings.autostartUnsupported
                            Autostart.Unavailable.NOT_INSTALLED -> strings.autostartNeedsInstall
                        },
                    )
                }

                SettingsSection(strings.settingsAppearance) {
                    Choice(strings.theme) {
                        ThemeChoice.entries.forEach { option ->
                            Option(
                                selected = theme == option,
                                label = when (option) {
                                    ThemeChoice.AUTO -> strings.themeAuto
                                    ThemeChoice.LIGHT -> strings.themeLight
                                    ThemeChoice.DARK -> strings.themeDark
                                },
                                onClick = { theme = option },
                            )
                        }
                        Hint(strings.themeHint)
                    }
                }

                SettingsSection(strings.settingsUpdatesSection) {
                    Switch(
                        checked = checkUpdates,
                        onCheckedChange = { checkUpdates = it },
                        label = strings.checkUpdates,
                        hint = strings.checkUpdatesHint,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onCheckUpdates) { Text(strings.checkNow) }
                        OutlinedButton(onClick = onRemoveApp) { Text(strings.removeApp) }
                    }
                    Hint(strings.removeAppHint)
                }

                SettingsSection(strings.settingsTransfer) {
                    Choice(strings.speedLimit) {
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
                                    Text(
                                        if (limitLooksValid) strings.speedHint else strings.speedInvalid,
                                    )
                                },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        Hint(strings.speedIsGlobal)
                    }
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
                            theme = theme.code,
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

/** Раздел с заголовком: разделов стало столько, что без них диалог читается сплошняком. */
@Composable
private fun SettingsSection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        content()
        HorizontalDivider()
    }
}

@Composable
private fun Choice(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        content()
    }
}

@Composable
private fun Option(selected: Boolean, label: String, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label)
    }
}

/**
 * Галочка с подписью и пояснением. Подпись всегда рядом с самой галочкой:
 * переключатель без подписи — это ровно та ошибка, из-за которой диалог
 * и переделан.
 */
@Composable
private fun Switch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    label: String,
    hint: String,
    enabled: Boolean = true,
) {
    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Checkbox(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
            Text(label)
        }
        Hint(hint)
    }
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * Проверяем формат до отправки: rclone на непонятное значение отвечает
 * невнятным «bad bwlimit», и разбираться в нём пользователю незачем.
 */
private val BANDWIDTH_PATTERN = Regex("""^\d+(\.\d+)?\s*[kKmMgGtT]?[iI]?[bB]?$""")
