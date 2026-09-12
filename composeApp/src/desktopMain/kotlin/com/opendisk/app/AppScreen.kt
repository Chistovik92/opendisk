package com.opendisk.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.opendisk.bridge.MountSupport

/**
 * Главный экран.
 *
 * Видимость настроек хранится снаружи: открыть их можно не только кнопкой на
 * экране, но и пунктом меню в трее, а окно при этом может быть спрятано.
 */
@Composable
fun AppScreen(
    state: UiState,
    controller: RcloneController,
    onQuit: () -> Unit,
    showingAppSettings: Boolean,
    onShowAppSettings: (Boolean) -> Unit,
) {
    val strings = LocalStrings.current
    var addingCloud by remember { mutableStateOf(false) }
    var cloudToDelete by remember { mutableStateOf<String?>(null) }
    var cloudToRename by remember { mutableStateOf<String?>(null) }
    var cloudToConfigure by remember { mutableStateOf<String?>(null) }
    var cloudToEdit by remember { mutableStateOf<String?>(null) }
    var showingAbout by remember { mutableStateOf(false) }
    var removingApp by remember { mutableStateOf(false) }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.fillMaxSize().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Header(state)

            when (val session = state.session) {
                SessionState.Starting -> CenteredMessage(strings.startingRclone, showSpinner = true)

                is SessionState.NeedPassword -> PasswordPrompt(
                    wrongAttempt = session.wrongAttempt,
                    onSubmit = controller::submitPassword,
                )

                is SessionState.Failed -> FailureMessage(session)

                SessionState.Ready -> ReadyContent(
                    state = state,
                    controller = controller,
                    onAddCloud = { addingCloud = true },
                    onRequestDelete = { cloudToDelete = it },
                    onRequestRename = { cloudToRename = it },
                    onRequestSettings = { cloudToConfigure = it },
                    onAppSettings = { onShowAppSettings(true) },
                    onAbout = { showingAbout = true },
                    onQuit = onQuit,
                )
            }
        }
    }

    if (addingCloud) {
        AddCloudWizard(
            providers = state.providers,
            existingNames = state.clouds.map { it.name }.toSet(),
            oauthUrl = state.oauthUrl,
            onDismiss = { addingCloud = false },
            onCancel = { controller.cancelAddCloud() },
            onCreate = { name, type, parameters, secrets, onResult ->
                controller.addCloud(name, type, parameters, secrets) { error ->
                    if (error == null) addingCloud = false
                    onResult(error)
                }
            },
        )
    }

    state.fileLink?.let { link ->
        FileLinkDialog(
            link = link,
            controller = controller,
            onDismiss = controller::dismissLink,
        )
    }

    if (showingAbout) {
        AboutDialog(state = state, onDismiss = { showingAbout = false })
    }

    if (removingApp) {
        UninstallDialog(
            controller = controller,
            onDismiss = { removingApp = false },
            onQuit = onQuit,
        )
    }

    if (showingAppSettings) {
        GlobalSettingsDialog(
            current = state.globalSettings,
            onDismiss = { onShowAppSettings(false) },
            onSave = { updated ->
                controller.updateGlobalSettings(updated)
                onShowAppSettings(false)
            },
            onCheckUpdates = {
                onShowAppSettings(false)
                controller.checkForUpdates(manual = true)
            },
            onRemoveApp = {
                onShowAppSettings(false)
                removingApp = true
            },
        )
    }

    cloudToConfigure?.let { name ->
        CloudSettingsDialog(
            cloudName = name,
            current = state.settings[name] ?: CloudSettings(),
            isMounted = state.clouds.firstOrNull { it.name == name }?.isMounted == true,
            onDismiss = { cloudToConfigure = null },
            onSave = { updated ->
                controller.updateCloudSettings(name, updated)
                cloudToConfigure = null
            },
            onEditConnection = {
                cloudToConfigure = null
                cloudToEdit = name
            },
        )
    }

    cloudToEdit?.let { name ->
        EditCloudDialog(
            cloudName = name,
            providers = state.providers,
            loadConfig = controller::loadCloudConfig,
            onSave = { parameters, secrets, onResult ->
                controller.editCloud(name, parameters, secrets, onResult)
            },
            onDismiss = { cloudToEdit = null },
        )
    }

    cloudToRename?.let { name ->
        RenameCloudDialog(
            cloudName = name,
            isMounted = state.clouds.firstOrNull { it.name == name }?.isMounted == true,
            existingNames = state.clouds.map { it.name }.toSet(),
            onDismiss = { cloudToRename = null },
            onRename = { newName, onResult ->
                controller.renameCloud(name, newName) { error ->
                    if (error == null) cloudToRename = null
                    onResult(error)
                }
            },
        )
    }

    cloudToDelete?.let { name ->
        ConfirmDeleteDialog(
            cloudName = name,
            onDismiss = { cloudToDelete = null },
            onConfirm = {
                controller.deleteCloud(name)
                cloudToDelete = null
            },
        )
    }
}

@Composable
private fun Header(state: UiState) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        // Версия прямо в заголовке: по скриншоту от пользователя должно быть
        // сразу видно, что у него стоит. Раньше это выяснялось только через
        // «О приложении» или реестр.
        Text(
            AppVersion.current?.let { "OpenDisk $it" } ?: "OpenDisk",
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            listOf(state.rcloneDescription, state.configDescription)
                .filter { it.isNotEmpty() }
                .joinToString("  ·  "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ReadyContent(
    state: UiState,
    controller: RcloneController,
    onAddCloud: () -> Unit,
    onRequestDelete: (String) -> Unit,
    onRequestRename: (String) -> Unit,
    onRequestSettings: (String) -> Unit,
    onAppSettings: () -> Unit,
    onAbout: () -> Unit,
    onQuit: () -> Unit,
) {
    val strings = LocalStrings.current

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Button(onClick = onAddCloud) { Text(strings.addCloud) }
        OutlinedButton(onClick = controller::refresh) { Text(strings.refresh) }
        OutlinedButton(onClick = onAppSettings) { Text(strings.appSettings) }
        OutlinedButton(onClick = onAbout) { Text(strings.about) }
    }

    (state.mount as? MountSupport.Status.Missing)?.let { missing ->
        MountDriverBanner(
            missing = missing,
            installing = state.installingMountDriver,
            onInstall = controller::installMountDriver,
        )
    }
    state.availableUpdate?.let { update ->
        UpdateBanner(
            update = update,
            downloading = state.updateInProgress,
            // После запуска сценария обновления приложение выходит само: пока
            // оно работает, установщик не может удалить файлы прошлой версии.
            onInstall = { controller.installUpdate(onFinished = onQuit) },
        )
    }
    state.updateMessage?.let { Banner(it) }
    state.globalError?.let { Banner(strings.errorPrefix(it)) }

    HorizontalDivider()

    if (state.clouds.isEmpty()) {
        CenteredMessage(strings.noCloudsYet)
    } else {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.clouds, key = { it.name }) { cloud ->
                CloudRow(
                    cloud = cloud,
                    mountAvailable = state.mountAvailable,
                    cacheMode = state.settings[cloud.name]?.cacheMode
                        ?: CloudSettings.DEFAULT_CACHE_MODE,
                    asNetworkDrive = RcloneController.mountOptionsFor(
                        name = cloud.name,
                        mountPoint = cloud.mountPoint.orEmpty(),
                        settings = state.settings[cloud.name] ?: CloudSettings(),
                    ).networkMode,
                    onMount = { controller.mount(cloud.name) },
                    onUnmount = { controller.unmount(cloud.name) },
                    onRename = { onRequestRename(cloud.name) },
                    onSettings = { onRequestSettings(cloud.name) },
                    onDelete = { onRequestDelete(cloud.name) },
                    onLink = {
                        // Диалог открывается на диске этого облака, но выбрать
                        // можно и файл с соседнего подключённого — облако
                        // определится по самому пути.
                        chooseFileOnDrive(
                            startIn = controller.mountPointOf(cloud.name),
                            title = strings.linkChooseFile,
                        )?.let(controller::createLink)
                    },
                )
            }
        }
    }
}

@Composable
private fun CloudRow(
    cloud: CloudUi,
    mountAvailable: Boolean,
    cacheMode: String,
    asNetworkDrive: Boolean,
    onMount: () -> Unit,
    onUnmount: () -> Unit,
    onRename: () -> Unit,
    onSettings: () -> Unit,
    onDelete: () -> Unit,
    onLink: () -> Unit,
) {
    val strings = LocalStrings.current

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(cloud.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = cloudStatusLine(cloud, cacheMode, strings, asNetworkDrive),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (cloud.busy) {
                        CircularProgressIndicator(modifier = Modifier.width(24.dp))
                    } else if (cloud.isMounted) {
                        OutlinedButton(onClick = onUnmount) { Text(strings.disconnect) }
                    } else {
                        Button(onClick = onMount, enabled = mountAvailable) {
                            Text(strings.connect)
                        }
                    }
                    // Только у подключённого диска и только там, где сервис
                    // ссылки вообще умеет: кнопка, которая заведомо приведёт
                    // к отказу, хуже отсутствующей.
                    if (cloud.isMounted && cloud.supportsLinks) {
                        TextButton(onClick = onLink, enabled = !cloud.busy) {
                            Text(strings.linkFile)
                        }
                    }
                    TextButton(onClick = onSettings, enabled = !cloud.busy) {
                        Text(strings.settings)
                    }
                    TextButton(onClick = onRename, enabled = !cloud.busy) { Text(strings.rename) }
                    TextButton(onClick = onDelete, enabled = !cloud.busy) { Text(strings.delete) }
                }
            }

            cloud.error?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            // Не ошибка: облако работает, но плохо и по исправимой причине.
            // Иначе о такой причине узнать неоткуда — снаружи это выглядит
            // просто как «тормозит».
            cloud.warning?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun cloudStatusLine(
    cloud: CloudUi,
    cacheMode: String,
    strings: Strings,
    asNetworkDrive: Boolean = false,
): String {
    // Про сетевой диск говорим прямо в строке состояния.
    //
    // Иначе получается так: приложение пишет «подключено к F:», а человек
    // открывает «Этот компьютер», среди дисков ничего не находит и решает,
    // что диск не смонтировался. На деле сетевые диски Windows показывает
    // отдельным разделом, ниже обычных.
    val status = cloud.mountPoint?.let {
        if (asNetworkDrive) strings.connectedToNetwork(it) else strings.connectedTo(it)
    } ?: strings.notConnected
    val space = cloud.about?.describe(strings)
    // Режим кэширования показываем прямо в строке: это то, чем облака между
    // собой отличаются на практике, и лезть в настройки ради проверки неудобно.
    val cache = strings.cacheModes.firstOrNull { it.value == cacheMode }
        ?.title
        ?.substringBefore(" —")
        ?.substringBefore(" -")
    return listOfNotNull(status, space, cache?.let { strings.cacheLabel(it.lowercase()) })
        .joinToString("  ·  ")
}

/**
 * Не установлен драйвер монтирования. Если установщик едет внутри дистрибутива,
 * предлагаем поставить его прямо отсюда — искать и качать что-то руками
 * пользователь не должен.
 *
 * Текст объяснения берётся здесь, а не из моста: мост сообщает, чего не хватает,
 * а как это рассказать человеку и на каком языке — дело интерфейса.
 */
@Composable
private fun MountDriverBanner(
    missing: MountSupport.Status.Missing,
    installing: Boolean,
    onInstall: () -> Unit,
) {
    val strings = LocalStrings.current
    val explanation = when (missing.kind) {
        MountSupport.Status.Kind.WINFSP -> strings.winFspExplanation
        MountSupport.Status.Kind.FUSE -> strings.fuseExplanation
        MountSupport.Status.Kind.MACFUSE -> strings.macFuseExplanation
    }
    val downloadUrl = missing.downloadUrl

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(strings.mountUnavailable, style = MaterialTheme.typography.titleSmall)
            Text(explanation, style = MaterialTheme.typography.bodySmall)

            when {
                installing -> Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(modifier = Modifier.width(20.dp))
                    Text(
                        strings.installingDriver(missing.what),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                missing.bundledInstaller != null ->
                    Button(onClick = onInstall) { Text(strings.installDriver(missing.what)) }

                downloadUrl != null -> Text(
                    strings.downloadFrom(downloadUrl),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun Banner(text: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Text(text, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
    }
}

/**
 * Сообщение о новой версии.
 *
 * Автоматически ничего не скачивается и не ставится: обновление меняет
 * программу на компьютере, и запускать это без нажатия неправильно. Кнопка
 * есть только там, где обновление можно поставить целиком — то есть на
 * Windows; иначе предлагается открыть страницу выпуска.
 */
@Composable
private fun UpdateBanner(
    update: UpdateChecker.Update,
    downloading: Boolean,
    onInstall: () -> Unit,
) {
    val strings = LocalStrings.current

    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(strings.updateAvailable, style = MaterialTheme.typography.titleSmall)
                Text(
                    strings.updateVersion(update.version),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (downloading) {
                CircularProgressIndicator(modifier = Modifier.width(24.dp))
                Text(strings.updateDownloading, style = MaterialTheme.typography.bodySmall)
            } else if (update.assetUrl != null) {
                Button(onClick = onInstall) { Text(strings.updateInstall) }
            } else {
                OutlinedButton(onClick = { openInBrowser(update.pageUrl) }) {
                    Text(strings.updateOpenPage)
                }
            }
        }
    }
}

/** Открывает ссылку в браузере по умолчанию. Ошибку глотаем: не критично. */
private fun openInBrowser(url: String) {
    runCatching { java.awt.Desktop.getDesktop().browse(java.net.URI(url)) }
}

@Composable
private fun CenteredMessage(text: String, showSpinner: Boolean = false) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (showSpinner) CircularProgressIndicator()
            Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun FailureMessage(session: SessionState.Failed) {
    val strings = LocalStrings.current

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(session.message, color = MaterialTheme.colorScheme.error)
        if (session.details.isNotEmpty()) {
            Text(strings.rcloneOutput, style = MaterialTheme.typography.labelMedium)
            LazyColumn {
                items(session.details) { line ->
                    Text(line, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
