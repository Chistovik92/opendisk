package com.opendisk.android.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.opendisk.bridge.CatalogService
import com.opendisk.bridge.CloudCatalog
import com.opendisk.bridge.RcloneClient

/**
 * Единственное окно приложения: две вкладки внизу — «Диски» и «Добавить».
 *
 * «Диски» — всё, что уже есть: память телефона, карты, root и облака; тап
 * по диску открывает встроенный файловый менеджер. «Добавить» — каталог
 * сервисов. До 0.5.6 это был один список облаков с кнопкой «+», а внутрь
 * облака можно было заглянуть только за ссылкой на файл.
 *
 * Отдельной библиотеки навигации по-прежнему нет: экранов немного, и все
 * они — состояния одной модели.
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
    val context = LocalContext.current
    var cloudToDelete by remember { mutableStateOf<String?>(null) }
    var creatingFolder by remember { mutableStateOf(false) }
    val askForNotifications = rememberNotificationPermission()
    var storageGranted by remember { mutableStateOf(StorageAccess.granted(context)) }
    val requestStorage = rememberStorageAccessRequest { granted ->
        storageGranted = granted
        model.refreshVolumes()
    }
    val snackbar = remember { SnackbarHostState() }
    val device = remember { DeviceKind.of(context) }
    val roundScreen = LocalConfiguration.current.isScreenRound

    // Разрешения — сразу после запуска: значок в шторке и доступ ко всем
    // файлам. Без второго файловый менеджер не видит память телефона, и
    // скачать файл из облака было бы некуда. Настройки системы открываем
    // один раз; дальше — кнопкой на вкладке «Диски».
    LaunchedEffect(state.starting) {
        if (state.starting) return@LaunchedEffect
        askForNotifications()
        model.refreshVolumes()
        if (!storageGranted && !model.storageAccessAsked()) {
            model.markStorageAccessAsked()
            requestStorage()
        }
    }

    // Файл готов — отдаём его выбранному приложению.
    LaunchedEffect(state.openRequest) {
        val request = state.openRequest ?: return@LaunchedEffect
        launchOpen(context, request, strings)?.let(model::showNotice)
        model.openRequestHandled()
    }

    LaunchedEffect(state.notice) {
        val notice = state.notice ?: return@LaunchedEffect
        snackbar.showSnackbar(notice)
        model.dismissNotice()
    }

    Scaffold(
        modifier = Modifier.padding(device.safePadding(roundScreen)),
        topBar = { AppBar(state, model, onNewFolder = { creatingFolder = true }) },
        bottomBar = {
            Column {
                state.operation?.let { OperationBar(it) }
                if (!state.starting && !state.settings && device == DeviceKind.WATCH) {
                    // На часах полноразмерная панель вкладок заняла бы треть
                    // экрана — две кнопки в строку делают то же самое.
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                    ) {
                        TextButton(onClick = {
                            if (state.tab == MainTab.DISKS) model.closeBrowser()
                            model.selectTab(MainTab.DISKS)
                        }) { Text(strings.tabDisks) }
                        TextButton(onClick = { model.selectTab(MainTab.ADD) }) { Text(strings.tabAdd) }
                    }
                } else if (!state.starting && !state.settings) {
                    NavigationBar {
                        NavigationBarItem(
                            selected = state.tab == MainTab.DISKS,
                            onClick = {
                                // Повторное нажатие на «Диски» из папки — к списку дисков.
                                if (state.tab == MainTab.DISKS) model.closeBrowser()
                                model.selectTab(MainTab.DISKS)
                            },
                            icon = { Text("🗂") },
                            label = { Text(strings.tabDisks) },
                        )
                        NavigationBarItem(
                            selected = state.tab == MainTab.ADD,
                            onClick = { model.selectTab(MainTab.ADD) },
                            icon = { Text("＋") },
                            label = { Text(strings.tabAdd) },
                        )
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                state.starting -> Centered(strings.starting, spinner = true)
                state.settings -> SettingsScreen(state, model)
                state.tab == MainTab.ADD -> AddCloudScreen(state, model)
                state.browsing != null -> FileBrowser(state, state.browsing!!, model)
                else -> DisksScreen(
                    state = state,
                    model = model,
                    storageGranted = storageGranted,
                    onRequestStorage = requestStorage,
                    onDelete = { cloudToDelete = it },
                    onConnect = { cloud, connected ->
                        // Ещё раз — на случай, если при запуске человек отмахнулся:
                        // при подключении повод для значка очевиден.
                        if (connected) askForNotifications()
                        model.setConnected(cloud, connected)
                    },
                )
            }
        }
    }

    // Кнопка «назад» ведёт вверх по папкам, а не выкидывает из приложения:
    // иначе из глубокой папки выйти можно было бы только целиком.
    val browsing = state.browsing
    when {
        state.settings -> BackHandler { model.closeSettings() }
        state.tab == MainTab.ADD -> BackHandler { model.selectTab(MainTab.DISKS) }
        browsing != null -> BackHandler {
            val parent = browsing.parent
            if (parent == null) model.closeBrowser() else model.open(browsing.disk, parent)
        }
    }

    state.link?.let { LinkDialog(it, model) }
    state.signIn?.let { SignInDialog(it, model) }

    if (creatingFolder) {
        NameDialog(
            title = strings.newFolder,
            initial = "",
            strings = strings,
            onDismiss = { creatingFolder = false },
            onConfirm = { name ->
                model.createFolder(name)
                creatingFolder = false
            },
        )
    }

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
private fun AppBar(state: MobileState, model: OpenDiskModel, onNewFolder: () -> Unit) {
    val open = state.browsing.takeIf { state.tab == MainTab.DISKS && !state.settings }
    val strings = model.strings
    TopAppBar(
        title = {
            Text(
                text = when {
                    state.settings -> strings.settings
                    state.tab == MainTab.ADD -> strings.chooseService
                    open == null -> "OpenDisk"
                    open.path.isEmpty() -> diskTitle(open.disk, strings)
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
                    if (parent == null) model.closeBrowser() else model.open(open.disk, parent)
                }) { Text(strings.back) }
            }
        },
        actions = {
            if (open != null) {
                var menu by remember { mutableStateOf(false) }
                Box {
                    TextButton(onClick = { menu = true }) { Text("⋮", style = MaterialTheme.typography.titleLarge) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text(strings.newFolder) }, onClick = {
                            menu = false
                            onNewFolder()
                        })
                        DropdownMenuItem(text = { Text(strings.refresh) }, onClick = {
                            menu = false
                            model.refreshFolder()
                        })
                    }
                }
            } else if (!state.settings && !state.starting) {
                // Настройки — на всех экранах, кроме них самих: искать их
                // приходится редко, а найти нужно сразу.
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
 * Добавление облака: весь каталог сервисов с поиском.
 *
 * Отдельным экраном, а не диалогом: сервисов больше полутора сотен, и
 * диалог с ними упирался бы в края телефона. Сверху — отобранные вручную
 * с готовыми адресами, по разделам; снизу — всё, что знает встроенный
 * rclone, с каждым провайдером S3 отдельной строкой.
 *
 * До 0.5.4 здесь было пять сервисов, и все по паролю: Google Диск, Dropbox
 * и OneDrive подтверждают доступ в браузере, а входа через браузер на
 * телефоне не было.
 */
@Composable
private fun AddCloudScreen(state: MobileState, model: OpenDiskModel) {
    val strings = model.strings
    var query by remember { mutableStateOf("") }
    var chosen by remember { mutableStateOf<CatalogService?>(null) }

    val curated = remember(query) { CloudCatalog.services.filter { it.matches(query) } }
    val all = state.allServices.filter { it.matches(query) }

    Column(modifier = Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text(strings.searchServices) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            (curated + all).groupBy { it.group }.forEach { (group, services) ->
                item(key = group.name) {
                    Text(
                        group.title.pick(strings.russian),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 16.dp, top = 14.dp, bottom = 4.dp),
                    )
                }
                items(services, key = { it.id }) { service ->
                    ServiceRow(service, strings) { chosen = service }
                }
            }
            if (state.allServices.isEmpty()) {
                item { ListNote(strings.loadingAllServices, spinner = true) }
            } else if (curated.isEmpty() && all.isEmpty()) {
                item { ListNote(strings.nothingFound) }
            }
        }
    }

    chosen?.let { service ->
        ServiceFormDialog(
            service = service,
            model = model,
            existingNames = state.clouds.map { it.name }.toSet(),
            onDismiss = { chosen = null },
        )
    }
}

/** Строка-пояснение внутри списка: у элемента ленивого списка нет высоты, которую можно заполнить. */
@Composable
private fun ListNote(text: String, spinner: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (spinner) CircularProgressIndicator(modifier = Modifier.size(18.dp))
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ServiceRow(service: CatalogService, strings: MobileStrings, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Буква на цветном квадрате, а не логотип сервиса: чужие товарные
        // знаки в приложение мы не кладём.
        Box(
            modifier = Modifier.size(36.dp).background(Color(service.accent), RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(service.glyph, color = Color.White, maxLines = 1)
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(service.title.pick(strings.russian), style = MaterialTheme.typography.titleSmall)
            Text(
                service.subtitle.pick(strings.russian),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ServiceFormDialog(
    service: CatalogService,
    model: OpenDiskModel,
    existingNames: Set<String>,
    onDismiss: () -> Unit,
) {
    val strings = model.strings
    // Имя по умолчанию — от идентификатора сервиса без служебной приставки:
    // «rclone:s3:Wasabi» превращается в «Wasabi».
    val baseName = service.id.split(":").last()
    var name by remember(service.id) { mutableStateOf(uniqueName(baseName, existingNames)) }
    val values = remember(service.id) { mutableStateMapOf<String, String>() }
    var error by remember(service.id) { mutableStateOf<String?>(null) }
    var busy by remember(service.id) { mutableStateOf(false) }

    val nameTaken = name.trim() in existingNames
    val filled = service.fields.filter { it.required }.all { values[it.key].orEmpty().isNotBlank() }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(service.title.pick(strings.russian)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                service.hint?.let {
                    Text(
                        it.pick(strings.russian),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = oneLine(it) },
                    label = { Text(strings.name) },
                    singleLine = true,
                    isError = nameTaken,
                )
                service.fields.forEach { field ->
                    OutlinedTextField(
                        value = values[field.key].orEmpty(),
                        onValueChange = { values[field.key] = oneLine(it) },
                        label = { Text(field.label.pick(strings.russian) + if (field.required) " *" else "") },
                        supportingText = field.help?.let { help -> { Text(help.pick(strings.russian)) } },
                        singleLine = true,
                        // Пароль под маской: на экране телефона, который видно
                        // из-за плеча, открытым текстом ему не место.
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
                if (service.oauth) {
                    Text(
                        strings.browserWillOpen,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(
                enabled = !busy && name.isNotBlank() && !nameTaken && filled,
                onClick = {
                    busy = true
                    error = null
                    model.addCloud(service, name, values.toMap()) { failure ->
                        busy = false
                        error = failure
                        if (failure == null) onDismiss()
                    }
                },
            ) {
                Text(
                    when {
                        busy -> strings.adding
                        service.oauth -> strings.signInWithBrowser
                        else -> strings.add
                    },
                )
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(strings.back) } },
    )
}

/**
 * Ожидание подтверждения в браузере.
 *
 * Страница открывается во вкладке браузера поверх приложения — Custom Tabs,
 * а не встроенным окном: Google отказывается пускать на вход из встроенных
 * браузеров («disallowed_useragent»), и правильно делает — встроенное окно
 * видит пароль. Вернуть человека обратно само приложение не может: Android
 * не даёт открывать свои окна из фона. Поэтому подсказка просит закрыть
 * вкладку, когда страница скажет «Success», — облако к этому моменту уже
 * в списке.
 */
@Composable
private fun SignInDialog(signIn: SignInState, model: OpenDiskModel) {
    val strings = model.strings
    val context = LocalContext.current
    var opened by remember(signIn.cloud) { mutableStateOf<String?>(null) }

    val link = signIn.link
    LaunchedEffect(link) {
        if (link != null && opened != link) {
            if (!openInBrowser(context, link)) model.showNotice(strings.noBrowser)
            opened = link
        }
    }

    AlertDialog(
        onDismissRequest = {},
        title = { Text(strings.waitingForBrowser) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp))
                    Text(if (link == null) strings.preparingSignIn else signIn.cloud)
                }
                Text(
                    strings.waitingForBrowserHint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            if (link != null) {
                TextButton(onClick = {
                    if (!openInBrowser(context, link)) model.showNotice(strings.noBrowser)
                }) { Text(strings.openBrowserAgain) }
            }
        },
        dismissButton = {
            TextButton(onClick = model::cancelSignIn, enabled = !signIn.cancelled) { Text(strings.cancel) }
        },
    )
}

/**
 * Открывает страницу входа. false — открыть нечем: на приставках и часах
 * браузера часто нет вовсе, и молча ничего не делать тут хуже всего.
 */
private fun openInBrowser(context: Context, link: String): Boolean = runCatching {
    CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(context, Uri.parse(link))
}.isSuccess

/**
 * Строка без переносов — то, что можно отдать rclone.
 *
 * Поле помечено singleLine, но это про клавиатуру: вставка из буфера обмена
 * переносы приносит. Идентификатор приложения Google, скопированный из
 * консоли Google дважды, приезжал двумя строками, и rclone отвечал
 * «invalid key or value contains \\n or \\r» — понять из этого, что не так
 * с полем, человеку невозможно. Пробелы по краям убираем там же: в
 * скопированном секрете они не значат ничего, а ломают вход.
 */
fun oneLine(value: String): String = value.replace(NEWLINES, "").trim()

private val NEWLINES = Regex("[\r\n]+")

/**
 * Имя по умолчанию, свободное в списке: подставляем его, чтобы не заставлять
 * человека придумывать название на пустом месте.
 */
private fun uniqueName(base: String, taken: Set<String>): String {
    if (base !in taken) return base
    var index = 2
    while ("$base$index" in taken) index++
    return "$base$index"
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
