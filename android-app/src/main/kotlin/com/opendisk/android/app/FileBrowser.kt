package com.opendisk.android.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.opendisk.bridge.RcloneClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Файловый менеджер 0.6.0.
 *
 * Устроен так же, как привычные проводники: путь ступенями сверху, список или
 * плитки, долгое нажатие включает выбор нескольких файлов, а действия над
 * выбранным — в нижней панели. Раньше у каждой строки было своё меню «⋮»,
 * и удалить или перенести десять файлов значило десять раз пройти по нему.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileBrowser(state: MobileState, open: Browsing, model: OpenDiskModel) {
    val strings = model.strings
    val options = state.preferences.listing
    val shown = remember(open.entries, options, open.query) { arrange(open.entries, options, open.query.orEmpty()) }
    val selected = remember(open.entries, open.selected) { open.entries.filter { it.path in open.selected } }
    val supportsLinks = (open.disk as? Disk.Cloud)?.let { disk ->
        state.clouds.firstOrNull { it.name == disk.name }?.supportsLinks
    } == true

    var toRename by remember { mutableStateOf<RcloneClient.Entry?>(null) }
    var toDelete by remember { mutableStateOf<List<RcloneClient.Entry>?>(null) }
    var properties by remember { mutableStateOf<RcloneClient.Entry?>(null) }
    var creating by remember { mutableStateOf<Creating?>(null) }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            when {
                open.selecting -> SelectionBar(open, shown, model)
                open.query != null -> SearchBar(open.query, model)
                else -> BrowserBar(open, options, model, onCreate = { creating = it })
            }
            Crumbs(open, model)
            HorizontalDivider()

            state.clip?.let { clip ->
                Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                    Row(
                        modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            strings.inClipboard(clip.entries, clip.move),
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
                    shown.isEmpty() -> CenteredNote(strings.nothingFound)
                    options.view == ViewMode.GRID -> FileGrid(shown, open, strings, model)
                    else -> FileList(shown, open, strings, model)
                }
            }

            if (open.selecting) {
                ActionBar(
                    selected = selected,
                    open = open,
                    supportsLinks = supportsLinks,
                    strings = strings,
                    model = model,
                    onDelete = { toDelete = selected },
                    onRename = { toRename = selected.single() },
                    onProperties = { properties = selected.single() },
                )
            }
        }

        if (!open.selecting && open.query == null && !open.loading && open.error == null) {
            AddButton(
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                strings = strings,
                onCreate = { creating = it },
            )
        }
    }

    creating?.let { kind ->
        NameDialog(
            title = if (kind == Creating.FOLDER) strings.newFolder else strings.newFile,
            initial = "",
            strings = strings,
            onDismiss = { creating = null },
            onConfirm = { name ->
                if (kind == Creating.FOLDER) model.createFolder(name) else model.createFile(name)
                creating = null
            },
        )
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
                model.clearSelection()
            },
        )
    }

    toDelete?.let { entries ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = {
                Text(if (entries.size == 1) strings.deleteFileTitle(entries.first().name) else strings.deleteManyTitle(entries.size))
            },
            text = { Text(if (entries.size == 1) strings.deleteFileExplanation else strings.deleteManyExplanation) },
            confirmButton = {
                Button(
                    onClick = {
                        model.delete(entries)
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

    properties?.let { entry ->
        PropertiesDialog(entry, open, strings) { properties = null }
    }
}

private enum class Creating { FOLDER, FILE }

// --- Верхние панели ------------------------------------------------------------

@Composable
private fun BrowserBar(open: Browsing, options: ListingOptions, model: OpenDiskModel, onCreate: (Creating) -> Unit) {
    val strings = model.strings
    var menu by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = {
            val parent = open.parent
            if (parent == null) model.closeBrowser() else model.open(open.disk, parent)
        }) { Text("←", style = MaterialTheme.typography.titleLarge) }
        Text(
            if (open.path.isEmpty()) diskTitle(open.disk, strings) else open.path.substringAfterLast('/'),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        TextButton(onClick = { model.setQuery("") }) { Text("🔍") }
        TextButton(onClick = {
            model.setListing(options.copy(view = if (options.view == ViewMode.LIST) ViewMode.GRID else ViewMode.LIST))
        }) { Text(if (options.view == ViewMode.LIST) "▦" else "☰", style = MaterialTheme.typography.titleLarge) }
        Box {
            TextButton(onClick = { menu = true }) { Text("⋮", style = MaterialTheme.typography.titleLarge) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                Text(
                    strings.sortBy,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                listOf(
                    SortOrder.NAME to strings.sortName,
                    SortOrder.SIZE to strings.sortSize,
                    SortOrder.DATE to strings.sortDate,
                    SortOrder.TYPE to strings.sortType,
                ).forEach { (order, label) ->
                    DropdownMenuItem(
                        text = { Text(label) },
                        trailingIcon = { Checkbox(checked = options.sort == order, onCheckedChange = null) },
                        onClick = { model.setListing(options.copy(sort = order)) },
                    )
                }
                DropdownMenuItem(
                    text = { Text(strings.sortReverse) },
                    trailingIcon = { Checkbox(checked = options.descending, onCheckedChange = null) },
                    onClick = { model.setListing(options.copy(descending = !options.descending)) },
                )
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text(strings.showHidden) },
                    trailingIcon = { Checkbox(checked = options.showHidden, onCheckedChange = null) },
                    onClick = { model.setListing(options.copy(showHidden = !options.showHidden)) },
                )
                HorizontalDivider()
                DropdownMenuItem(text = { Text(strings.newFolder) }, onClick = {
                    menu = false
                    onCreate(Creating.FOLDER)
                })
                DropdownMenuItem(text = { Text(strings.newFile) }, onClick = {
                    menu = false
                    onCreate(Creating.FILE)
                })
                DropdownMenuItem(text = { Text(strings.refresh) }, onClick = {
                    menu = false
                    model.refreshFolder()
                })
            }
        }
    }
}

@Composable
private fun SelectionBar(open: Browsing, shown: List<RcloneClient.Entry>, model: OpenDiskModel) {
    val strings = model.strings
    val all = shown.isNotEmpty() && shown.all { it.path in open.selected }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = model::clearSelection) { Text("✕", style = MaterialTheme.typography.titleLarge) }
        Text(
            strings.selectedCount(open.selected.size),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleMedium,
        )
        TextButton(onClick = { if (all) model.clearSelection() else model.selectAll(shown) }) {
            Text(if (all) strings.clearSelection else strings.selectAll)
        }
    }
}

@Composable
private fun SearchBar(query: String, model: OpenDiskModel) {
    val strings = model.strings
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = { model.setQuery(null) }) { Text("←", style = MaterialTheme.typography.titleLarge) }
        OutlinedTextField(
            value = query,
            onValueChange = { model.setQuery(it) },
            modifier = Modifier.weight(1f).focusRequester(focus),
            placeholder = { Text(strings.searchHint) },
            singleLine = true,
        )
    }
}

/** Путь ступенями; нажатие на ступень открывает эту папку. */
@Composable
private fun Crumbs(open: Browsing, model: OpenDiskModel) {
    val strings = model.strings
    val crumbs = remember(open.disk, open.path) { breadcrumbs(diskTitle(open.disk, strings), open.path) }
    val list = rememberLazyListState()
    // Глубокий путь не помещается — показываем его конец, где человек и находится.
    LaunchedEffect(crumbs.size) { list.scrollToItem(crumbs.lastIndex) }
    LazyRow(
        state = list,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        itemsIndexed(crumbs) { index, crumb ->
            val last = index == crumbs.lastIndex
            Text(
                crumb.label,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(enabled = !last) { model.open(open.disk, crumb.path) }
                    .padding(horizontal = 6.dp, vertical = 6.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = if (last) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.primary,
                maxLines = 1,
            )
            if (!last) Text("›", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// --- Список и плитки ---------------------------------------------------------------

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileList(shown: List<RcloneClient.Entry>, open: Browsing, strings: MobileStrings, model: OpenDiskModel) {
    // Ключ с номером строки, а не один путь: Google Диск разрешает два файла
    // с одинаковым именем в одной папке, и на таком списке приложение падало
    // при прокрутке («Key … was already used»).
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        itemsIndexed(shown, key = { index, entry -> "$index:" + entry.path }) { _, entry ->
            val checked = entry.path in open.selected
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(if (checked) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                    .combinedClickable(
                        onClick = { activate(entry, open, model) },
                        onLongClick = { model.toggleSelected(entry) },
                    )
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                KindIcon(entry, open.disk, size = 44)
                Column(modifier = Modifier.weight(1f)) {
                    Text(entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val details = listOf(
                        if (entry.isDir) "" else formatBytes(entry.size, strings),
                        formatModTime(entry.modTime),
                    ).filter { it.isNotEmpty() }.joinToString("  ·  ")
                    if (details.isNotEmpty()) {
                        Text(
                            details,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (open.selecting) Checkbox(checked = checked, onCheckedChange = { model.toggleSelected(entry) })
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileGrid(shown: List<RcloneClient.Entry>, open: Browsing, strings: MobileStrings, model: OpenDiskModel) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(104.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        gridItemsIndexed(shown, key = { index, entry -> "$index:" + entry.path }) { _, entry ->
            val checked = entry.path in open.selected
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (checked) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                    .combinedClickable(
                        onClick = { activate(entry, open, model) },
                        onLongClick = { model.toggleSelected(entry) },
                    )
                    .padding(8.dp),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    KindIcon(entry, open.disk, size = 72)
                    Text(
                        entry.name,
                        modifier = Modifier.padding(top = 6.dp),
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (checked) {
                    Text("✔", modifier = Modifier.align(Alignment.TopEnd), color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

/** Нажатие: в режиме выбора — отметить, иначе открыть папку или файл. */
private fun activate(entry: RcloneClient.Entry, open: Browsing, model: OpenDiskModel) {
    when {
        open.selecting -> model.toggleSelected(entry)
        entry.isDir -> model.open(open.disk, entry.path)
        else -> model.openFile(entry)
    }
}

// --- Значки и превью ---------------------------------------------------------------

private fun kindColor(kind: FileKind): Color = when (kind) {
    FileKind.FOLDER -> Color(0xFFF2B01E)
    FileKind.IMAGE -> Color(0xFF43A047)
    FileKind.VIDEO -> Color(0xFFE53935)
    FileKind.AUDIO -> Color(0xFF8E24AA)
    FileKind.DOCUMENT -> Color(0xFF1E88E5)
    FileKind.ARCHIVE -> Color(0xFF6D4C41)
    FileKind.APK -> Color(0xFF00897B)
    FileKind.OTHER -> Color(0xFF757575)
}

/**
 * Значок типа файла; у картинок на самом телефоне — миниатюра.
 *
 * Миниатюры облачных файлов не делаем: для них пришлось бы скачать файл
 * целиком ради значка — на мобильной сети это дороже, чем пользы.
 */
@Composable
private fun KindIcon(entry: RcloneClient.Entry, disk: Disk, size: Int) {
    val kind = FileKind.of(entry)
    val shape = RoundedCornerShape(10.dp)
    val thumbnail = if (kind == FileKind.IMAGE && disk is Disk.Local) {
        val path = disk.absolutePath(entry.path)
        path?.let { produceState<Bitmap?>(null, it) { value = Thumbnails.load(it, size * 3) }.value }
    } else {
        null
    }
    Box(
        modifier = Modifier.size(size.dp).clip(shape).background(kindColor(kind).copy(alpha = 0.18f)),
        contentAlignment = Alignment.Center,
    ) {
        if (thumbnail != null) {
            Image(
                bitmap = thumbnail.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Text(kind.glyph, style = if (size > 56) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.titleLarge)
        }
    }
}

/**
 * Миниатюры с телефона: уменьшенная копия через `inSampleSize`, а не полная
 * загрузка 12-мегапиксельного снимка ради квадрата в 44 точки. Кэш держит
 * последние — прокрутка вверх-вниз не пересчитывает их заново.
 */
private object Thumbnails {
    private val cache = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    suspend fun load(path: String, targetPx: Int): Bitmap? {
        cache.get(path)?.let { return it }
        return withContext(Dispatchers.IO) {
            runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(path, bounds)
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= targetPx && bounds.outHeight / (sample * 2) >= targetPx) sample *= 2
                BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
            }.getOrNull()?.also { cache.put(path, it) }
        }
    }
}

// --- Кнопка «+», нижняя панель, свойства -------------------------------------------------

@Composable
private fun AddButton(modifier: Modifier, strings: MobileStrings, onCreate: (Creating) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        FloatingActionButton(onClick = { menu = true }) { Text("＋", style = MaterialTheme.typography.headlineSmall) }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text(strings.newFolder) }, onClick = {
                menu = false
                onCreate(Creating.FOLDER)
            })
            DropdownMenuItem(text = { Text(strings.newFile) }, onClick = {
                menu = false
                onCreate(Creating.FILE)
            })
        }
    }
}

@Composable
private fun ActionBar(
    selected: List<RcloneClient.Entry>,
    open: Browsing,
    supportsLinks: Boolean,
    strings: MobileStrings,
    model: OpenDiskModel,
    onDelete: () -> Unit,
    onRename: () -> Unit,
    onProperties: () -> Unit,
) {
    var more by remember { mutableStateOf(false) }
    val single = selected.singleOrNull()
    Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            ActionButton("⧉", strings.copyAction) { model.clip(selected, move = false) }
            ActionButton("✂", strings.cutAction) { model.clip(selected, move = true) }
            ActionButton("🗑", strings.delete) { onDelete() }
            ActionButton("⇪", strings.share) {
                if (single != null && !single.isDir) model.openFile(single, share = true) else model.showNotice(strings.shareOneOnly)
            }
            Box {
                ActionButton("⋮", strings.more) { more = true }
                DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                    if (single != null) {
                        DropdownMenuItem(text = { Text(strings.rename) }, onClick = {
                            more = false
                            onRename()
                        })
                        DropdownMenuItem(text = { Text(strings.properties) }, onClick = {
                            more = false
                            onProperties()
                        })
                        if (supportsLinks && !single.isDir) {
                            DropdownMenuItem(text = { Text(strings.getLink) }, onClick = {
                                more = false
                                (open.disk as? Disk.Cloud)?.let { model.requestLink(it.name, single) }
                            })
                        }
                    }
                    // Из памяти телефона скачивать некуда — файл уже там.
                    if (open.disk !is Disk.Local || open.disk.removable) {
                        DropdownMenuItem(text = { Text(strings.downloadToPhone) }, onClick = {
                            more = false
                            model.download(selected)
                        })
                    }
                }
            }
        }
    }
}

@Composable
private fun ActionButton(glyph: String, label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(glyph, style = MaterialTheme.typography.titleLarge)
            Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1)
        }
    }
}

@Composable
private fun PropertiesDialog(entry: RcloneClient.Entry, open: Browsing, strings: MobileStrings, onDismiss: () -> Unit) {
    val kind = FileKind.of(entry)
    val kindLabel = when (kind) {
        FileKind.FOLDER -> strings.kindFolder
        FileKind.IMAGE -> strings.kindImage
        FileKind.VIDEO -> strings.kindVideo
        FileKind.AUDIO -> strings.kindAudio
        FileKind.DOCUMENT -> strings.kindDocument
        FileKind.ARCHIVE -> strings.kindArchive
        FileKind.APK -> strings.kindApk
        FileKind.OTHER -> strings.kindOther
    }
    val where = listOf(diskTitle(open.disk, strings), entry.path.substringBeforeLast('/', "")).filter { it.isNotEmpty() }.joinToString(" / ")
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.properties) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Property(strings.propertyName, entry.name)
                Property(strings.propertyLocation, where)
                Property(strings.propertyType, kindLabel)
                if (!entry.isDir) Property(strings.propertySize, formatBytes(entry.size, strings))
                formatModTime(entry.modTime).takeIf { it.isNotEmpty() }?.let { Property(strings.propertyModified, it) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(strings.ok) } },
    )
}

@Composable
private fun Property(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value)
    }
}
