package com.opendisk.android.app

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.webkit.MimeTypeMap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.opendisk.bridge.RcloneClient

// --- Доступ ко всем файлам ----------------------------------------------------

/**
 * Доступ к памяти телефона.
 *
 * С Android 11 обычные разрешения на хранилище дают только медиафайлы, а
 * файловому менеджеру нужно всё: документы, архивы, папки других программ.
 * Для этого есть отдельное «доступ ко всем файлам», которое выдаётся не
 * окном, а переключателем в настройках системы, — туда мы и отправляем.
 * До Android 11 хватает обычных разрешений на чтение и запись.
 */
object StorageAccess {
    fun granted(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
        }
}

/**
 * Запрос доступа ко всем файлам. Возвращает то, что нужно вызвать, чтобы
 * спросить; [onResult] зовётся, когда человек вернулся из настроек или
 * ответил на системное окно.
 */
@Composable
fun rememberStorageAccessRequest(onResult: (Boolean) -> Unit): () -> Unit {
    val context = LocalContext.current
    val settingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { onResult(StorageAccess.granted(context)) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { onResult(StorageAccess.granted(context)) }

    return {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val forApp = Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:${context.packageName}"),
            )
            // Часть прошивок не знает экрана «для этого приложения» — тогда общий список.
            val launched = runCatching { settingsLauncher.launch(forApp) }.isSuccess
            if (!launched) {
                runCatching { settingsLauncher.launch(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
            }
        } else {
            permissionLauncher.launch(
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE),
            )
        }
    }
}

// --- Вкладка «Диски» ----------------------------------------------------------

/**
 * Все диски разом: память телефона, карты, root и облака.
 *
 * Облако здесь в первую очередь папка, которую открывают, — как в любом
 * файловом менеджере. Показать его в системных «Файлах» и удалить — в «⋮».
 */
@Composable
fun DisksScreen(
    state: MobileState,
    model: OpenDiskModel,
    storageGranted: Boolean,
    onRequestStorage: () -> Unit,
    onDelete: (String) -> Unit,
    onConnect: (String, Boolean) -> Unit,
) {
    val strings = model.strings
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item { SectionTitle(strings.sectionDevice) }
        item {
            DiskRow(
                glyph = "📱",
                title = strings.phoneStorage,
                subtitle = if (storageGranted) LocalVolumes.primary().root else strings.storageAccessNeeded,
                onClick = { if (storageGranted) model.open(LocalVolumes.primary()) else onRequestStorage() },
            ) {
                if (!storageGranted) {
                    OutlinedButton(onClick = onRequestStorage) { Text(strings.grantStorageAccess) }
                }
            }
            HorizontalDivider()
        }
        items(state.removableVolumes, key = { it.key }) { volume ->
            DiskRow(
                glyph = "💾",
                title = strings.sdCard,
                subtitle = volume.root,
                onClick = { if (storageGranted) model.open(volume) else onRequestStorage() },
            )
            HorizontalDivider()
        }
        if (state.rootAvailable) {
            item {
                DiskRow(
                    glyph = "#",
                    title = strings.rootFs,
                    subtitle = if (state.rootGranted == false) strings.rootDenied else strings.rootHint,
                    onClick = { model.open(Disk.Root) },
                )
                HorizontalDivider()
            }
        }

        item { SectionTitle(strings.sectionClouds) }
        state.error?.let { error ->
            item {
                Text(error, modifier = Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
            }
        }
        // Пояснение про «Файлы» над списком убрано: теперь оно в самом пункте
        // меню, там, где человек решает, включать или нет.
        if (state.clouds.isEmpty()) item { ListHint(strings.noClouds) }
        items(state.clouds, key = { "cloud:" + it.name }) { cloud ->
            val connected = cloud.name in state.preferences.connected
            var menu by remember { mutableStateOf(false) }
            // Нажатие на строку — открыть диск, и больше ничего. В 0.5.6 рядом
            // стояли переключатель и «Удалить»: переключатель с подписью
            // «В «Файлах»» было непонятно зачем трогать, а промахнуться мимо
            // строки и удалить облако — легко. Всё это теперь в «⋮».
            DiskRow(
                glyph = "☁",
                title = cloud.name,
                subtitle = listOfNotNull(
                    cloud.about?.describe(strings)?.takeIf { it.isNotEmpty() },
                    strings.visibleInFiles.takeIf { connected },
                ).joinToString("  ·  "),
                onClick = { model.open(Disk.Cloud(cloud.name)) },
            ) {
                Box {
                    TextButton(onClick = { menu = true }) { Text("⋮", style = MaterialTheme.typography.titleLarge) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text(strings.showInFilesAction) },
                            trailingIcon = { Checkbox(checked = connected, onCheckedChange = null) },
                            onClick = {
                                menu = false
                                onConnect(cloud.name, !connected)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(strings.delete, color = MaterialTheme.colorScheme.error) },
                            onClick = {
                                menu = false
                                onDelete(cloud.name)
                            },
                        )
                    }
                }
            }
            HorizontalDivider()
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun ListHint(text: String) {
    Text(
        text,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun DiskRow(
    glyph: String,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    trailing: @Composable () -> Unit = {},
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 16.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(glyph, style = MaterialTheme.typography.titleLarge, modifier = Modifier.width(32.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotEmpty()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        trailing()
    }
}

/** Как диск называется на экране. */
fun diskTitle(disk: Disk, strings: MobileStrings): String = when (disk) {
    is Disk.Cloud -> disk.name
    is Disk.Local -> if (disk.removable) "${strings.sdCard} (${disk.root.substringAfterLast('/')})" else strings.phoneStorage
    Disk.Root -> strings.rootFs
}

// --- Файловый браузер ---------------------------------------------------------

@Composable
fun FileBrowser(state: MobileState, open: Browsing, model: OpenDiskModel) {
    val strings = model.strings
    var toRename by remember { mutableStateOf<RcloneClient.Entry?>(null) }
    var toDelete by remember { mutableStateOf<RcloneClient.Entry?>(null) }
    val supportsLinks = (open.disk as? Disk.Cloud)?.let { disk ->
        state.clouds.firstOrNull { it.name == disk.name }?.supportsLinks
    } == true

    Column(modifier = Modifier.fillMaxSize()) {
        // Где мы: диск и путь. Заголовок сверху показывает только последнюю
        // папку, а в глубине облака важно видеть и начало пути.
        Text(
            listOf(diskTitle(open.disk, strings), open.path).filter { it.isNotEmpty() }.joinToString(" / "),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )

        state.clip?.let { clip ->
            Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                Row(
                    modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        strings.inClipboard(clip.entry.name, clip.move),
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    TextButton(onClick = model::cancelClip) { Text(strings.cancel) }
                    Button(onClick = model::paste, enabled = state.operation == null) { Text(strings.pasteHere) }
                }
            }
        }

        Box(modifier = Modifier.weight(1f)) {
            when {
                open.loading -> CenteredNote(strings.readingFolder, spinner = true)
                open.error != null -> CenteredNote(open.error)
                open.entries.isEmpty() -> CenteredNote(strings.emptyFolder)
                else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(open.entries, key = { it.path }) { entry ->
                        FileRow(
                            entry = entry,
                            disk = open.disk,
                            supportsLinks = supportsLinks,
                            strings = strings,
                            onOpen = {
                                if (entry.isDir) model.open(open.disk, entry.path) else model.openFile(entry)
                            },
                            onShare = { model.openFile(entry, share = true) },
                            onDownload = { model.download(entry) },
                            onCopy = { model.clip(entry, move = false) },
                            onCut = { model.clip(entry, move = true) },
                            onRename = { toRename = entry },
                            onLink = { (open.disk as? Disk.Cloud)?.let { model.requestLink(it.name, entry) } },
                            onDelete = { toDelete = entry },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    toRename?.let { entry ->
        NameDialog(
            title = strings.rename,
            initial = entry.name,
            strings = strings,
            onDismiss = { toRename = null },
            onConfirm = { name ->
                model.rename(entry, name)
                toRename = null
            },
        )
    }

    toDelete?.let { entry ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text(strings.deleteFileTitle(entry.name)) },
            text = { Text(strings.deleteFileExplanation) },
            confirmButton = {
                Button(
                    onClick = {
                        model.delete(entry)
                        toDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) { Text(strings.delete) }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text(strings.cancel) } },
        )
    }
}

@Composable
private fun FileRow(
    entry: RcloneClient.Entry,
    disk: Disk,
    supportsLinks: Boolean,
    strings: MobileStrings,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onDownload: () -> Unit,
    onCopy: () -> Unit,
    onCut: () -> Unit,
    onRename: () -> Unit,
    onLink: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(if (entry.isDir) "📁" else "📄", modifier = Modifier.width(28.dp))
        Column(modifier = Modifier.weight(1f).padding(vertical = 12.dp)) {
            Text(entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!entry.isDir) {
                Text(
                    formatBytes(entry.size, strings),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Box {
            TextButton(onClick = { menu = true }) { Text("⋮", style = MaterialTheme.typography.titleLarge) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                val actions = buildList {
                    if (!entry.isDir) {
                        add(strings.open to onOpen)
                        add(strings.share to onShare)
                    }
                    // Из памяти телефона скачивать некуда — файл уже там.
                    if (disk !is Disk.Local || disk.removable) add(strings.downloadToPhone to onDownload)
                    add(strings.copyAction to onCopy)
                    add(strings.cutAction to onCut)
                    add(strings.rename to onRename)
                    if (supportsLinks && !entry.isDir) add(strings.getLink to onLink)
                    add(strings.delete to onDelete)
                }
                actions.forEach { (label, action) ->
                    DropdownMenuItem(
                        text = { Text(label) },
                        onClick = {
                            menu = false
                            action()
                        },
                    )
                }
            }
        }
    }
}

/** Имя папки или новое имя файла. */
@Composable
fun NameDialog(
    title: String,
    initial: String,
    strings: MobileStrings,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember { mutableStateOf(initial) }
    val valid = name.isNotBlank() && '/' !in name
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(strings.name) },
                singleLine = true,
                isError = !valid,
            )
        },
        confirmButton = { Button(onClick = { onConfirm(name) }, enabled = valid) { Text(strings.ok) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(strings.cancel) } },
    )
}

/** Полоса внизу, пока идёт копирование или удаление. */
@Composable
fun OperationBar(text: String) {
    Card(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
        Row(
            modifier = Modifier.padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp))
            Text(text, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun CenteredNote(text: String, spinner: Boolean = false) {
    Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (spinner) CircularProgressIndicator()
            Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// --- Открыть в другом приложении ---------------------------------------------

/**
 * Отдаёт файл другому приложению через FileProvider: прямой путь чужое
 * приложение прочитать не сможет, а адрес вида `content://` — сможет, пока
 * у него есть выданное нами разрешение.
 */
fun launchOpen(context: Context, request: OpenRequest, strings: MobileStrings): String? {
    val uri = runCatching {
        FileProvider.getUriForFile(context, "${context.packageName}.files", request.file)
    }.getOrElse { return it.message }
    val extension = request.name.substringAfterLast('.', "").lowercase()
    val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "*/*"
    val intent = if (request.share) {
        Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri)
    } else {
        Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
    }.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    return try {
        context.startActivity(Intent.createChooser(intent, request.name))
        null
    } catch (_: ActivityNotFoundException) {
        strings.noAppToOpen
    }
}
