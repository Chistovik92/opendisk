package com.opendisk.android.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.opendisk.bridge.RcloneClient

/**
 * Единственный экран приложения — точнее, три состояния одного экрана: список
 * облаков, содержимое папки и диалоги поверх них.
 *
 * Отдельной навигации нет намеренно: экранов пока три, и библиотека навигации
 * ради них — лишняя зависимость и лишний слой. Появится четвёртый — станет
 * видно, что пора.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                OpenDiskApp()
            }
        }
    }
}

@Composable
fun OpenDiskApp(model: OpenDiskModel = viewModel()) {
    val state by model.state.collectAsState()

    Scaffold(
        topBar = { AppBar(state, model) },
        floatingActionButton = {
            if (state.browsing == null && !state.starting) {
                FloatingActionButton(onClick = model::startAdding) { Text("+") }
            }
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                state.starting -> Centered("Запускаю rclone…", spinner = true)
                state.browsing != null -> FolderList(state.browsing!!, model)
                else -> CloudList(state, model)
            }
        }
    }

    // Кнопка «назад» ведёт вверх по папкам, а не выкидывает из приложения:
    // иначе из глубокой папки выйти можно было бы только целиком.
    state.browsing?.let { open ->
        BackHandler {
            val parent = open.parent
            if (parent == null) model.closeBrowser() else model.open(open.cloud, parent)
        }
    }

    state.link?.let { LinkDialog(it, model) }
    if (state.adding) AddCloudDialog(model)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppBar(state: MobileState, model: OpenDiskModel) {
    val open = state.browsing
    TopAppBar(
        title = {
            Text(
                text = when {
                    open == null -> "OpenDisk"
                    open.path.isEmpty() -> open.cloud
                    else -> open.path.substringAfterLast('/')
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        navigationIcon = {
            if (open != null) {
                TextButton(onClick = {
                    val parent = open.parent
                    if (parent == null) model.closeBrowser() else model.open(open.cloud, parent)
                }) { Text("Назад") }
            }
        },
    )
}

@Composable
private fun CloudList(state: MobileState, model: OpenDiskModel) {
    state.error?.let { error ->
        Card(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Text(error, modifier = Modifier.padding(12.dp), color = MaterialTheme.colorScheme.error)
        }
        return
    }

    if (state.clouds.isEmpty()) {
        Centered("Облаков пока нет.\nДобавьте первое кнопкой «+».")
        return
    }

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(state.clouds, key = { it.name }) { cloud ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { model.open(cloud.name) }
                    .padding(16.dp),
            ) {
                Text(cloud.name, style = MaterialTheme.typography.titleMedium)
                val space = cloud.about?.describe().orEmpty()
                if (space.isNotEmpty()) {
                    Text(
                        space,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            HorizontalDivider()
        }
    }
}

@Composable
private fun FolderList(open: Browsing, model: OpenDiskModel) {
    when {
        open.loading -> Centered("Читаю папку…", spinner = true)
        open.error != null -> Centered(open.error)
        open.entries.isEmpty() -> Centered("Папка пуста")
        else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(open.entries, key = { it.path }) { entry ->
                EntryRow(entry) {
                    if (entry.isDir) {
                        model.open(open.cloud, entry.path)
                    } else {
                        model.requestLink(open.cloud, entry)
                    }
                }
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun EntryRow(entry: RcloneClient.Entry, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                // Папку видно по названию со слэшем: значков в первой версии
                // нет, а отличать надо сразу.
                text = if (entry.isDir) "${entry.name}/" else entry.name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!entry.isDir) {
                Text(
                    formatBytes(entry.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (!entry.isDir) Text("ссылка", style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun LinkDialog(link: LinkState, model: OpenDiskModel) {
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = model::dismissLink,
        title = { Text("Ссылка на скачивание") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    link.remotePath,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                when {
                    link.busy -> Text("Спрашиваю ссылку у облака…")
                    link.error != null -> Text(link.error, color = MaterialTheme.colorScheme.error)
                    link.url != null -> {
                        OutlinedTextField(
                            value = link.url,
                            onValueChange = {},
                            readOnly = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            "Ссылку выдал сам сервис. Файл по ней скачает любой, " +
                                "у кого она есть, без входа в аккаунт.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (copied) Text("Скопировано", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = {
            link.url?.let { url ->
                Button(onClick = {
                    copyToClipboard(context, url)
                    copied = true
                }) { Text("Копировать") }
            }
        },
        dismissButton = { TextButton(onClick = model::dismissLink) { Text("Закрыть") } },
    )
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    clipboard?.setPrimaryClip(ClipData.newPlainText("OpenDisk", text))
}

/**
 * Добавление облака.
 *
 * Только те бэкенды, что настраиваются логином и паролем. Сервисы с
 * подтверждением в браузере (Google Диск, Dropbox) сюда сознательно не
 * попали: их поток требует вернуть пользователя из браузера обратно
 * в приложение, а это отдельная работа, и делать её наполовину нельзя.
 */
@Composable
private fun AddCloudDialog(model: OpenDiskModel) {
    var name by remember { mutableStateOf("") }
    var type by remember { mutableStateOf("webdav") }
    var url by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = model::cancelAdding,
        title = { Text("Новое облако") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Название") },
                    singleLine = true,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("webdav", "sftp", "ftp").forEach { option ->
                        TextButton(onClick = { type = option }) {
                            Text(if (type == option) "• $option" else option)
                        }
                    }
                }
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text(if (type == "webdav") "Адрес сервера" else "Хост") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = user,
                    onValueChange = { user = it },
                    label = { Text("Логин") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = pass,
                    onValueChange = { pass = it },
                    label = { Text("Пароль") },
                    singleLine = true,
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(
                enabled = !busy && name.isNotBlank() && url.isNotBlank(),
                onClick = {
                    busy = true
                    error = null
                    val parameters = mapOf(
                        // У webdav адрес называется url, у sftp и ftp — host.
                        if (type == "webdav") "url" to url else "host" to url,
                        "user" to user,
                        "pass" to pass,
                    )
                    model.addCloud(name, type, parameters, setOf("pass")) { failure ->
                        busy = false
                        error = failure
                    }
                },
            ) { Text(if (busy) "Добавляю…" else "Добавить") }
        },
        dismissButton = { TextButton(onClick = model::cancelAdding) { Text("Отмена") } },
    )
}

@Composable
private fun Centered(text: String, spinner: Boolean = false) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (spinner) CircularProgressIndicator()
            Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
