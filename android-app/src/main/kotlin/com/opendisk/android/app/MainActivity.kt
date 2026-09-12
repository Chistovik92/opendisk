package com.opendisk.android.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
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
            val model: OpenDiskModel = viewModel()
            val state by model.state.collectAsState()
            // Тема выбирается снаружи приложения: её задаёт система, а на
            // Android 12 и новее оттуда же приходит и вся палитра.
            OpenDiskTheme(state.preferences.theme) {
                OpenDiskApp(model)
            }
        }
    }
}

@Composable
fun OpenDiskApp(model: OpenDiskModel = viewModel()) {
    val state by model.state.collectAsState()
    val strings = model.strings
    var cloudToDelete by remember { mutableStateOf<String?>(null) }
    val askForNotifications = rememberNotificationPermission()

    Scaffold(
        topBar = { AppBar(state, model) },
        floatingActionButton = {
            if (state.browsing == null && !state.starting && !state.settings) {
                FloatingActionButton(onClick = model::startAdding) { Text("+") }
            }
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                state.starting -> Centered(strings.starting, spinner = true)
                state.settings -> SettingsScreen(state, model)
                state.browsing != null -> FolderList(state.browsing!!, model)
                else -> CloudList(
                    state = state,
                    model = model,
                    onDelete = { cloudToDelete = it },
                    onConnect = { cloud, connected ->
                        // Разрешение спрашиваем ровно там, где значок и
                        // появится, а не при первом запуске: вопрос без
                        // повода одинаково раздражает и остаётся без ответа.
                        if (connected) askForNotifications()
                        model.setConnected(cloud, connected)
                    },
                )
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

    if (state.settings) BackHandler { model.closeSettings() }

    state.link?.let { LinkDialog(it, model) }
    if (state.adding) AddCloudDialog(model)

    cloudToDelete?.let { name ->
        ConfirmDeleteDialog(
            cloud = name,
            strings = strings,
            onDismiss = { cloudToDelete = null },
            onConfirm = {
                model.deleteCloud(name)
                cloudToDelete = null
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppBar(state: MobileState, model: OpenDiskModel) {
    val open = state.browsing
    val strings = model.strings
    TopAppBar(
        title = {
            Text(
                text = when {
                    state.settings -> strings.settings
                    open == null -> "OpenDisk"
                    open.path.isEmpty() -> open.cloud
                    else -> open.path.substringAfterLast('/')
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        navigationIcon = {
            when {
                state.settings -> TextButton(onClick = model::closeSettings) { Text(strings.back) }
                open != null -> TextButton(onClick = {
                    val parent = open.parent
                    if (parent == null) model.closeBrowser() else model.open(open.cloud, parent)
                }) { Text(strings.back) }
            }
        },
        actions = {
            // Настройки — на всех экранах, кроме них самих: искать их
            // приходится редко, а найти нужно сразу.
            if (!state.settings && !state.starting) {
                TextButton(onClick = model::openSettings) { Text(strings.settings) }
            }
        },
    )
}

/**
 * Разрешение на уведомления с Android 13.
 *
 * Возвращает то, что нужно вызвать в момент подключения: спросить, если ещё
 * не спрашивали. Отказ ничего не ломает — подключение работает, значка в
 * шторке просто не будет.
 */
@Composable
private fun rememberNotificationPermission(): () -> Unit {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return {}
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }
    return {
        val granted = ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) launcher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
    }
}

@Composable
private fun CloudList(
    state: MobileState,
    model: OpenDiskModel,
    onDelete: (String) -> Unit,
    onConnect: (String, Boolean) -> Unit,
) {
    val strings = model.strings

    state.error?.let { error ->
        Card(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Text(error, modifier = Modifier.padding(12.dp), color = MaterialTheme.colorScheme.error)
        }
        return
    }

    if (state.clouds.isEmpty()) {
        Centered(strings.noClouds)
        return
    }

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            // Что вообще значит «подключить» на телефоне — объяснение стоит
            // над списком, а не прячется в справке: диска, как на компьютере,
            // здесь не будет, и ждать его не надо.
            Text(
                strings.connectExplanation,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HorizontalDivider()
        }
        items(state.clouds, key = { it.name }) { cloud ->
            val connected = cloud.name in state.preferences.connected
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { model.open(cloud.name) }
                    .padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(cloud.name, style = MaterialTheme.typography.titleMedium)
                    val space = cloud.about?.describe(strings).orEmpty()
                    Text(
                        listOf(
                            if (connected) strings.connected else strings.notConnected,
                            space,
                        ).filter { it.isNotEmpty() }.joinToString("  ·  "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = connected,
                    onCheckedChange = { onConnect(cloud.name, it) },
                )
                // Кнопкой, а не долгим нажатием: долгое нажатие никто не
                // находит, а в 0.5.0 удалить облако было нельзя вовсе —
                // функция в модели была, а до неё не вело ничего.
                TextButton(onClick = { onDelete(cloud.name) }) { Text(strings.delete) }
            }
            HorizontalDivider()
        }
    }
}

/**
 * Настройки: оформление, язык и сведения о приложении.
 *
 * Отдельным экраном, а не диалогом: на телефоне диалог с четырьмя разделами
 * упирается в края экрана, и нижняя часть обрезается — ровно то, из-за чего
 * переделан диалог настроек на компьютере.
 */
@Composable
private fun SettingsScreen(state: MobileState, model: OpenDiskModel) {
    val strings = model.strings
    val context = LocalContext.current
    val appVersion = remember(context) {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        SettingsSection(strings.theme) {
            MobileTheme.entries.forEach { option ->
                ChoiceRow(
                    selected = state.preferences.theme == option,
                    label = when (option) {
                        MobileTheme.AUTO -> strings.themeAuto
                        MobileTheme.LIGHT -> strings.themeLight
                        MobileTheme.DARK -> strings.themeDark
                    },
                    onClick = { model.setTheme(option) },
                )
            }
            Hint(strings.themeHint)
        }

        SettingsSection(strings.language) {
            MobileLanguage.entries.forEach { option ->
                ChoiceRow(
                    selected = state.preferences.language == option,
                    label = when (option) {
                        MobileLanguage.AUTO -> strings.languageAuto
                        MobileLanguage.RUSSIAN -> "Русский"
                        MobileLanguage.ENGLISH -> "English"
                    },
                    onClick = { model.setLanguage(option) },
                )
            }
            Hint(strings.languageChangeHint)
        }

        SettingsSection(strings.notificationChannel) {
            Hint(strings.notificationHint)
        }

        SettingsSection(strings.about) {
            Text("${strings.version}: ${appVersion ?: "—"}")
            state.rcloneVersion?.let { Text("${strings.builtOnRclone} $it") }
            Text("${strings.projectPage}: $PROJECT_URL")
        }
    }
}

@Composable
private fun SettingsSection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        content()
    }
}

@Composable
private fun ChoiceRow(selected: Boolean, label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label)
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

private const val PROJECT_URL = "https://github.com/Chistovik92/opendisk"

@Composable
private fun FolderList(open: Browsing, model: OpenDiskModel) {
    val strings = model.strings
    when {
        open.loading -> Centered(strings.readingFolder, spinner = true)
        open.error != null -> Centered(open.error)
        open.entries.isEmpty() -> Centered(strings.emptyFolder)
        else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(open.entries, key = { it.path }) { entry ->
                EntryRow(entry, strings) {
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
private fun EntryRow(entry: RcloneClient.Entry, strings: MobileStrings, onClick: () -> Unit) {
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
                    formatBytes(entry.size, strings),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (!entry.isDir) Text(strings.linkHint, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun LinkDialog(link: LinkState, model: OpenDiskModel) {
    val strings = model.strings
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = model::dismissLink,
        title = { Text(strings.linkTitle) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    link.remotePath,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                when {
                    link.busy -> Text(strings.linkAsking)
                    link.error != null -> Text(link.error, color = MaterialTheme.colorScheme.error)
                    link.url != null -> {
                        OutlinedTextField(
                            value = link.url,
                            onValueChange = {},
                            readOnly = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            strings.linkExplanation,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (copied) Text(strings.copied, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = {
            link.url?.let { url ->
                Button(onClick = {
                    copyToClipboard(context, url)
                    copied = true
                }) { Text(strings.copy) }
            }
        },
        dismissButton = { TextButton(onClick = model::dismissLink) { Text(strings.close) } },
    )
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    clipboard?.setPrimaryClip(ClipData.newPlainText("OpenDisk", text))
}

@Composable
private fun ConfirmDeleteDialog(
    cloud: String,
    strings: MobileStrings,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.deleteTitle(cloud)) },
        text = { Text(strings.deleteExplanation) },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
            ) { Text(strings.delete) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(strings.cancel) } },
    )
}

/**
 * Добавление облака: сначала выбор сервиса, потом его поля.
 *
 * До этого выпуска здесь был список из трёх слов — `webdav`, `sftp`, `ftp`, —
 * и человек должен был сам знать, что Яндекс.Диск подключается по WebDAV,
 * по какому адресу и с каким паролем. Теперь знает приложение, как и на
 * компьютере.
 *
 * Сервисы с подтверждением доступа в браузере (Google Диск, Dropbox,
 * OneDrive) сюда по-прежнему не попали: их поток должен вернуть человека из
 * браузера обратно в приложение, а это отдельная работа. Об этом сказано
 * прямо в списке, а не оставлено на догадки.
 */
@Composable
private fun AddCloudDialog(model: OpenDiskModel) {
    val strings = model.strings
    val presets = remember(strings) { mobilePresets(strings) }
    var chosen by remember { mutableStateOf<MobilePreset?>(null) }

    val preset = chosen
    if (preset == null) {
        ChooseServiceDialog(strings, presets, onChoose = { chosen = it }, onDismiss = model::cancelAdding)
    } else {
        PresetFormDialog(
            preset = preset,
            model = model,
            onBack = { chosen = null },
        )
    }
}

@Composable
private fun ChooseServiceDialog(
    strings: MobileStrings,
    presets: List<MobilePreset>,
    onChoose: (MobilePreset) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.chooseService) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                presets.forEach { preset ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onChoose(preset) }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        // Буква на цветном квадрате, а не логотип сервиса:
                        // чужие товарные знаки в приложение мы не кладём.
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .background(preset.accent, RoundedCornerShape(8.dp)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(preset.glyph, color = Color.White)
                        }
                        Column {
                            Text(preset.title, style = MaterialTheme.typography.titleSmall)
                            Text(
                                preset.subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                HorizontalDivider()
                Text(
                    strings.browserServicesMissing,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(strings.cancel) } },
    )
}

@Composable
private fun PresetFormDialog(preset: MobilePreset, model: OpenDiskModel, onBack: () -> Unit) {
    val strings = model.strings
    var name by remember(preset.id) { mutableStateOf(preset.id) }
    val values = remember(preset.id) { mutableStateMapOf<String, String>() }
    var error by remember(preset.id) { mutableStateOf<String?>(null) }
    var busy by remember(preset.id) { mutableStateOf(false) }

    val filled = preset.fields.filter { it.required }.all { values[it.key].orEmpty().isNotBlank() }

    AlertDialog(
        onDismissRequest = model::cancelAdding,
        title = { Text(preset.title) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                preset.hint?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(strings.name) },
                    singleLine = true,
                )
                preset.fields.forEach { field ->
                    OutlinedTextField(
                        value = values[field.key].orEmpty(),
                        onValueChange = { values[field.key] = it },
                        label = { Text(field.label) },
                        singleLine = true,
                        // Пароль под маской: в 0.5.0 он печатался открытым
                        // текстом — на экране телефона, который видно
                        // из-за плеча.
                        visualTransformation = if (field.isPassword) {
                            PasswordVisualTransformation()
                        } else {
                            VisualTransformation.None
                        },
                        keyboardOptions = if (field.isPassword) {
                            KeyboardOptions(keyboardType = KeyboardType.Password)
                        } else {
                            KeyboardOptions.Default
                        },
                    )
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(
                enabled = !busy && name.isNotBlank() && filled,
                onClick = {
                    busy = true
                    error = null
                    val secrets = preset.fields.filter { it.isPassword }.map { it.key }.toSet()
                    model.addCloud(
                        name = name,
                        type = preset.backend,
                        parameters = preset.fixed + values,
                        secretKeys = secrets,
                    ) { failure ->
                        busy = false
                        error = failure
                    }
                },
            ) { Text(if (busy) strings.adding else strings.add) }
        },
        dismissButton = { TextButton(onClick = onBack) { Text(strings.back) } },
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
