package com.opendisk.android.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Главный экран: хранилища, категории, закладки и облака.
 *
 * Устроен по образцу главного экрана ES File Explorer и описан в
 * docs/MOBILE-UI.md — на iPhone экран такой же, порядок разделов и названия
 * совпадают.
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
    var adding by remember { mutableStateOf(false) }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        // Первый запуск: приглашение добавить облако — самым верхом, а не после
        // хранилищ и категорий. С него начинается любой новый человек, и под
        // экраном оно оставалось незамеченным.
        if (state.clouds.isEmpty() && state.error == null) {
            item {
                Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(strings.noClouds)
                        Button(onClick = { model.selectTab(MainTab.ADD) }) { Text(strings.addFirstCloud) }
                    }
                }
            }
        }
        item { SectionTitle(strings.sectionDevice) }
        item {
            val phone = remember { LocalVolumes.primary() }
            StorageCard(
                glyph = "📱",
                title = strings.phoneStorage,
                subtitle = if (storageGranted) null else strings.storageAccessNeeded,
                space = if (storageGranted) phone.space() else null,
                strings = strings,
                onClick = { if (storageGranted) model.open(phone) else onRequestStorage() },
            ) {
                if (!storageGranted) {
                    OutlinedButton(onClick = onRequestStorage) { Text(strings.grantStorageAccess) }
                }
            }
        }
        items(state.removableVolumes, key = { it.key }) { volume ->
            StorageCard(
                glyph = "💾",
                title = strings.sdCard,
                subtitle = null,
                space = volume.space(),
                strings = strings,
                onClick = { if (storageGranted) model.open(volume) else onRequestStorage() },
            )
        }
        items(state.preferences.customDisks, key = { "folder:$it" }) { root ->
            val disk = remember(root) { Disk.Local(root, removable = false) }
            var menu by remember { mutableStateOf(false) }
            StorageCard(
                glyph = "📁",
                title = diskTitle(disk, strings),
                subtitle = root,
                space = disk.space(),
                strings = strings,
                onClick = { model.open(disk) },
                menu = {
                    Box {
                        TextButton(onClick = { menu = true }) { Text("⋮", style = MaterialTheme.typography.titleLarge) }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text(strings.removeDisk, color = MaterialTheme.colorScheme.error) },
                                onClick = {
                                    menu = false
                                    model.removeCustomDisk(root)
                                },
                            )
                        }
                    }
                },
            )
        }
        if (state.rootAvailable) {
            item {
                // Root — такой же диск, как память: карточка, а не строка списка.
                // Пока доступ не выдан, на ней кнопка запроса: окно менеджера
                // root должно появиться в ответ на нажатие, а не само.
                StorageCard(
                    glyph = "#",
                    title = strings.rootFs,
                    subtitle = when (state.rootGranted) {
                        true -> strings.rootGrantedHint
                        false -> strings.rootDenied
                        null -> strings.rootHint
                    },
                    space = null,
                    strings = strings,
                    onClick = { if (state.rootGranted == true) model.open(Disk.Root) else model.requestRoot(openAfter = true) },
                ) {
                    if (state.rootGranted != true) {
                        OutlinedButton(onClick = { model.requestRoot(openAfter = true) }) {
                            Text(if (state.rootGranted == false) strings.rootRetry else strings.rootRequest)
                        }
                    }
                }
            }
        }
        item { SectionTitle(strings.sectionTools) }
        item {
            Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                Row(modifier = Modifier.padding(8.dp)) {
                    ToolTile("☁", Color(0xFF1E88E5), strings.toolClouds, Modifier.weight(1f)) { model.selectTab(MainTab.ADD) }
                    ToolTile("＋", Color(0xFF00897B), strings.addDisk, Modifier.weight(1f)) { adding = true }
                    ToolTile("#", Color(0xFF6D4C41), strings.rootFs.substringBefore(" ("), Modifier.weight(1f)) {
                        if (state.rootGranted == true) model.open(Disk.Root) else model.requestRoot(openAfter = true)
                    }
                    ToolTile("⚙", Color(0xFF757575), strings.settings, Modifier.weight(1f)) { model.openSettings() }
                }
            }
        }
        item {
            OutlinedButton(
                onClick = { adding = true },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            ) { Text("＋  " + strings.addDisk) }
        }

        item { SectionTitle(strings.sectionCategories) }
        item {
            Column(
                modifier = Modifier.padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FileCategory.entries.chunked(3).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { category ->
                            CategoryTile(
                                category = category,
                                strings = strings,
                                modifier = Modifier.weight(1f),
                                onClick = { if (storageGranted) model.openCategory(category) else onRequestStorage() },
                            )
                        }
                    }
                }
            }
        }

        item { SectionTitle(strings.sectionBookmarks) }
        if (state.preferences.bookmarks.isEmpty()) item { ListHint(strings.noBookmarks) }
        items(state.preferences.bookmarks, key = { "bookmark:" + it.key }) { bookmark ->
            var menu by remember { mutableStateOf(false) }
            DiskRow(
                glyph = "🔖",
                title = bookmark.path.substringAfterLast('/').ifEmpty { diskTitle(bookmark.disk, strings) },
                subtitle = listOf(diskTitle(bookmark.disk, strings), bookmark.path).filter { it.isNotEmpty() }.joinToString(" / "),
                onClick = { model.open(bookmark.disk, bookmark.path) },
            ) {
                Box {
                    TextButton(onClick = { menu = true }) { Text("⋮", style = MaterialTheme.typography.titleLarge) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text(strings.removeBookmark) }, onClick = {
                            menu = false
                            model.removeBookmark(bookmark)
                        })
                    }
                }
            }
            HorizontalDivider()
        }

        item { SectionTitle(strings.sectionClouds) }
        state.error?.let { error ->
            item {
                Text(error, modifier = Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
            }
        }
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
                    strings.needsSignIn.takeIf { cloud.needsSignIn },
                    cloud.about?.describe(strings)?.takeIf { it.isNotEmpty() },
                    strings.visibleInFiles.takeIf { connected },
                ).joinToString("  ·  "),
                onClick = { model.open(Disk.Cloud(cloud.name)) },
            ) {
                Box {
                    TextButton(onClick = { menu = true }) { Text("⋮", style = MaterialTheme.typography.titleLarge) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    strings.signInAgain,
                                    color = if (cloud.needsSignIn) MaterialTheme.colorScheme.error else Color.Unspecified,
                                )
                            },
                            onClick = {
                                menu = false
                                model.signInAgain(cloud.name)
                            },
                        )
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

    if (adding) {
        AddDiskDialog(
            strings = strings,
            rootGranted = state.rootGranted == true,
            onDismiss = { adding = false },
            onCloud = {
                adding = false
                model.selectTab(MainTab.ADD)
            },
            onRoot = {
                adding = false
                model.requestRoot()
            },
            onFolder = model::addCustomDisk,
        )
    }
}

/** Карточка хранилища с полосой заполнения — как у ES File Explorer. */
@Composable
private fun StorageCard(
    glyph: String,
    title: String,
    subtitle: String?,
    space: Space?,
    strings: MobileStrings,
    onClick: () -> Unit,
    menu: @Composable () -> Unit = {},
    extra: @Composable () -> Unit = {},
) {
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).clickable(onClick = onClick)) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (space != null && space.total > 0) {
                    UsageRing(space)
                } else {
                    Text(glyph, style = MaterialTheme.typography.headlineSmall)
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    val line = subtitle ?: space?.let { strings.used(formatBytes(it.used, strings), formatBytes(it.total, strings)) }
                    if (line != null) {
                        Text(line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                menu()
            }
            extra()
        }
    }
}

private fun categoryGlyph(category: FileCategory) = when (category) {
    FileCategory.IMAGES -> "🖼"
    FileCategory.VIDEO -> "🎞"
    FileCategory.AUDIO -> "🎵"
    FileCategory.DOCUMENTS -> "📄"
    FileCategory.DOWNLOADS -> "⬇"
    FileCategory.NEW -> "✨"
}

private fun categoryColor(category: FileCategory) = when (category) {
    FileCategory.IMAGES -> Color(0xFF43A047)
    FileCategory.VIDEO -> Color(0xFFE53935)
    FileCategory.AUDIO -> Color(0xFF8E24AA)
    FileCategory.DOCUMENTS -> Color(0xFF1E88E5)
    FileCategory.DOWNLOADS -> Color(0xFF00897B)
    FileCategory.NEW -> Color(0xFFF2B01E)
}

fun categoryTitle(category: FileCategory, strings: MobileStrings): String = when (category) {
    FileCategory.IMAGES -> strings.catImages
    FileCategory.VIDEO -> strings.catVideo
    FileCategory.AUDIO -> strings.catAudio
    FileCategory.DOCUMENTS -> strings.catDocuments
    FileCategory.DOWNLOADS -> strings.catDownloads
    FileCategory.NEW -> strings.catNew
}

@Composable
private fun CategoryTile(category: FileCategory, strings: MobileStrings, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        RoundIcon(categoryGlyph(category), categoryColor(category))
        Text(
            categoryTitle(category, strings),
            style = MaterialTheme.typography.labelMedium,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Что можно добавить на вкладку «Диски»: облако, папку или флешку, root.
 *
 * Три пути в одном окне, а не три кнопки на экране: добавление — редкое
 * действие, и держать его постоянно на виду значило бы отнимать место у
 * самих дисков. Папка вводится путём — системный выбор папки отдаёт
 * `content://`, а файловому менеджеру нужен настоящий путь.
 */
@Composable
private fun AddDiskDialog(
    strings: MobileStrings,
    rootGranted: Boolean,
    onDismiss: () -> Unit,
    onCloud: () -> Unit,
    onRoot: () -> Unit,
    onFolder: (String) -> String?,
) {
    var folderMode by remember { mutableStateOf(false) }
    var path by remember { mutableStateOf("/storage/") }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.addDisk) },
        text = {
            if (folderMode) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = path,
                        onValueChange = {
                            path = it
                            error = null
                        },
                        label = { Text(strings.folderPath) },
                        singleLine = true,
                        isError = error != null,
                        supportingText = error?.let { message -> { Text(message) } },
                    )
                }
            } else {
                Column {
                    AddDiskOption("☁", strings.addDiskCloud, strings.addDiskCloudHint, onCloud)
                    AddDiskOption("📁", strings.addDiskFolder, strings.addDiskFolderHint) { folderMode = true }
                    if (!rootGranted) AddDiskOption("#", strings.addDiskRoot, strings.addDiskRootHint, onRoot)
                }
            }
        },
        confirmButton = {
            if (folderMode) {
                Button(onClick = {
                    val problem = onFolder(path)
                    if (problem == null) onDismiss() else error = problem
                }) { Text(strings.add) }
            }
        },
        dismissButton = {
            TextButton(onClick = { if (folderMode) folderMode = false else onDismiss() }) {
                Text(if (folderMode) strings.back else strings.cancel)
            }
        },
    )
}

@Composable
private fun AddDiskOption(glyph: String, title: String, hint: String, onClick: () -> Unit) {
    DiskRow(glyph = glyph, title = title, subtitle = hint, onClick = onClick)
}

/** Круглый цветной значок с глифом — как у категорий и инструментов ES. */
@Composable
private fun RoundIcon(glyph: String, color: Color, size: Int = 52) {
    Box(
        modifier = Modifier.size(size.dp).clip(CircleShape).background(color.copy(alpha = 0.22f)),
        contentAlignment = Alignment.Center,
    ) {
        // Тональный круг, а не сплошная заливка: так значки держатся в палитре
        // Material You и не спорят с цветом обоев.
        Text(glyph, style = MaterialTheme.typography.titleLarge, color = color)
    }
}

/** Кольцо заполнения с процентом внутри: «42%», как у Internal Storage в ES. */
@Composable
private fun UsageRing(space: Space) {
    Box(modifier = Modifier.size(52.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(
            progress = { space.fraction },
            modifier = Modifier.size(52.dp),
            strokeWidth = 5.dp,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
        )
        Text("${(space.fraction * 100).toInt()}%", style = MaterialTheme.typography.labelMedium)
    }
}

/** Плитка инструмента: круглый значок и подпись. */
@Composable
private fun ToolTile(glyph: String, color: Color, title: String, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier = modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        RoundIcon(glyph, color, size = 44)
        Text(title, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}
