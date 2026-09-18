package com.opendisk.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
    /** Настройки всех облаков — чтобы не дать выбрать букву, закреплённую за другим. */
    allSettings: Map<String, CloudSettings>,
    /** Где облако подключено сейчас: эта буква занята им самим, а не кем-то ещё. */
    mountedAt: String?,
    onDismiss: () -> Unit,
    onSave: (CloudSettings) -> Unit,
    /**
     * Переход к параметрам самого подключения — адресу сервера, логину, паролю.
     * Здесь только то, что касается диска на этой машине; настройки связи
     * с сервисом живут отдельно, потому что меняются они через rclone,
     * а не через файл настроек приложения.
     */
    onEditConnection: () -> Unit,
) {
    val strings = LocalStrings.current
    val windows = remember { System.getProperty("os.name").lowercase().contains("win") }
    val letterChoices = remember(allSettings, mountedAt) {
        DriveLetters.choices(
            cloud = cloudName,
            settings = allSettings,
            systemTaken = DriveLetters.systemTaken(),
            ownMounted = DriveLetters.letterOf(mountedAt),
        )
    }
    var cacheMode by remember { mutableStateOf(current.cacheMode) }
    var mountPoint by remember { mutableStateOf(current.mountPoint.orEmpty()) }
    // Своя папка вместо буквы — только если человек её уже выбрал раньше.
    var intoFolder by remember {
        mutableStateOf(current.mountPoint?.isNotBlank() == true && DriveLetters.letterOf(current.mountPoint) == null)
    }
    var mountOnStartup by remember { mutableStateOf(current.mountOnStartup) }
    var showAsLocalDrive by remember { mutableStateOf(current.showAsLocalDrive) }

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
                if (windows && !intoFolder) {
                    DriveLetterPicker(
                        choices = letterChoices,
                        selected = DriveLetters.letterOf(mountPoint),
                        onSelect = { mountPoint = DriveLetters.mountPointOf(it) },
                    )
                    Text(
                        strings.driveLetterHint,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
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
                }
                if (windows) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Checkbox(
                            checked = intoFolder,
                            onCheckedChange = { checked ->
                                intoFolder = checked
                                // Переключились обратно на букву — вернём закреплённую
                                // или первую свободную, а не путь к папке.
                                if (!checked && DriveLetters.letterOf(mountPoint) == null) {
                                    mountPoint = DriveLetters.letterOf(current.mountPoint)
                                        ?.let(DriveLetters::mountPointOf)
                                        ?: letterChoices.firstOrNull { it is DriveLetters.Choice.Free }
                                            ?.letter?.let(DriveLetters::mountPointOf)
                                        ?: ""
                                }
                            },
                        )
                        Text(strings.driveLetterFolderInstead)
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Checkbox(checked = mountOnStartup, onCheckedChange = { mountOnStartup = it })
                    Text(strings.mountOnStartup)
                }

                // Только Windows: на Linux и macOS такого разделения нет.
                if (System.getProperty("os.name").lowercase().contains("win")) {
                    Column {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Checkbox(
                                checked = showAsLocalDrive,
                                onCheckedChange = { showAsLocalDrive = it },
                            )
                            Text(strings.showAsLocalDrive)
                        }
                        Text(
                            strings.showAsLocalDriveHint,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                if (isMounted) {
                    Text(
                        strings.settingsApplyOnReconnect,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                TextButton(
                    onClick = onEditConnection,
                    modifier = Modifier.align(Alignment.Start),
                ) {
                    Text(strings.editConnection)
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
                            showAsLocalDrive = showAsLocalDrive,
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
 * Выбор буквы из списка, а не ввод руками: так видно, какие буквы свободны,
 * какие заняты системой и какие уже закреплены за другими облаками, — и
 * выбрать две одинаковые буквы для двух облаков просто нельзя.
 */
@Composable
private fun DriveLetterPicker(
    choices: List<DriveLetters.Choice>,
    selected: Char?,
    onSelect: (Char) -> Unit,
) {
    val strings = LocalStrings.current
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }) {
            Text(
                selected?.let { "${strings.driveLetter}: $it:" }
                    ?: "${strings.driveLetter}: —",
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.heightIn(max = 320.dp),
        ) {
            choices.forEach { choice ->
                val label = when (choice) {
                    is DriveLetters.Choice.Free -> "${choice.letter}:"
                    is DriveLetters.Choice.OtherCloud -> strings.driveLetterOfCloud(choice.letter, choice.cloud)
                    is DriveLetters.Choice.System -> strings.driveLetterInSystem(choice.letter)
                }
                DropdownMenuItem(
                    text = { Text(label) },
                    enabled = choice is DriveLetters.Choice.Free,
                    onClick = {
                        onSelect(choice.letter)
                        expanded = false
                    },
                    trailingIcon = if (choice.letter == selected) {
                        { Text("✓") }
                    } else {
                        null
                    },
                )
            }
        }
    }
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
