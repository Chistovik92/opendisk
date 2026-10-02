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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        // Первый запуск: приглашение добавить облако — самым верхом, а не после
        // хранилищ и категорий. С него начинается любой новый человек, и под
        // экраном оно оставалось незамеченным.
        if (state.clouds.isEmpty() && state.error == null) {
            item {
                Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(strings.noClouds)
                        Button(onClick = { model.selectTab(MainTab.ADD) }) { Text(strings.tabAdd) }
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
    extra: @Composable () -> Unit = {},
) {
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).clickable(onClick = onClick)) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(glyph, style = MaterialTheme.typography.headlineSmall)
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    val line = subtitle ?: space?.let { strings.used(formatBytes(it.used, strings), formatBytes(it.total, strings)) }
                    if (line != null) {
                        Text(line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            space?.let {
                LinearProgressIndicator(progress = { it.fraction }, modifier = Modifier.fillMaxWidth())
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
            .background(categoryColor(category).copy(alpha = 0.16f))
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(categoryGlyph(category), style = MaterialTheme.typography.headlineMedium)
        Text(
            categoryTitle(category, strings),
            style = MaterialTheme.typography.labelMedium,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
