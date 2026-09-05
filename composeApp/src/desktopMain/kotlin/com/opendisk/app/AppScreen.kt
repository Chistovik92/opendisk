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
import androidx.compose.material3.Divider
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

@Composable
fun AppScreen(state: UiState, controller: RcloneController) {
    var addingCloud by remember { mutableStateOf(false) }
    var cloudToDelete by remember { mutableStateOf<String?>(null) }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.fillMaxSize().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Header(state)

            when (val session = state.session) {
                SessionState.Starting -> CenteredMessage("Запускаю rclone...", showSpinner = true)

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
            onCreate = { name, type, parameters, secrets, onResult ->
                controller.addCloud(name, type, parameters, secrets) { error ->
                    if (error == null) addingCloud = false
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
        Text("OpenDisk", style = MaterialTheme.typography.headlineSmall)
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
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Button(onClick = onAddCloud) { Text("Добавить облако") }
        OutlinedButton(onClick = controller::refresh) { Text("Обновить") }
    }

    (state.mount as? MountSupport.Status.Missing)?.let { missing ->
        MountDriverBanner(
            missing = missing,
            installing = state.installingMountDriver,
            onInstall = controller::installMountDriver,
        )
    }
    state.globalError?.let { Banner("Ошибка: $it") }

    Divider()

    if (state.clouds.isEmpty()) {
        CenteredMessage("Облаков пока нет. Начните с кнопки «Добавить облако».")
    } else {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.clouds, key = { it.name }) { cloud ->
                CloudRow(
                    cloud = cloud,
                    mountAvailable = state.mountAvailable,
                    onMount = { controller.mount(cloud.name, RcloneController.defaultMountPoint(cloud.name)) },
                    onUnmount = { controller.unmount(cloud.name) },
                    onDelete = { onRequestDelete(cloud.name) },
                )
            }
        }
    }
}

@Composable
private fun CloudRow(
    cloud: CloudUi,
    mountAvailable: Boolean,
    onMount: () -> Unit,
    onUnmount: () -> Unit,
    onDelete: () -> Unit,
) {
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
                        text = cloudStatusLine(cloud),
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
                        OutlinedButton(onClick = onUnmount) { Text("Отключить") }
                    } else {
                        Button(onClick = onMount, enabled = mountAvailable) { Text("Подключить") }
                    }
                    TextButton(onClick = onDelete, enabled = !cloud.busy) { Text("Удалить") }
                }
            }

            cloud.error?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

private fun cloudStatusLine(cloud: CloudUi): String {
    val status = if (cloud.isMounted) "подключено к ${cloud.mountPoint}" else "не подключено"
    val space = cloud.about?.describe()
    return listOfNotNull(status, space).joinToString("  ·  ")
}

/**
 * Не установлен драйвер монтирования. Если установщик едет внутри дистрибутива,
 * предлагаем поставить его прямо отсюда — искать и качать что-то руками
 * пользователь не должен.
 */
@Composable
private fun MountDriverBanner(
    missing: MountSupport.Status.Missing,
    installing: Boolean,
    onInstall: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Подключение дисков недоступно", style = MaterialTheme.typography.titleSmall)
            Text(missing.explanation, style = MaterialTheme.typography.bodySmall)

            when {
                installing -> Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(modifier = Modifier.width(20.dp))
                    Text("Устанавливаю ${missing.what}...", style = MaterialTheme.typography.bodySmall)
                }

                missing.bundledInstaller != null ->
                    Button(onClick = onInstall) { Text("Установить ${missing.what}") }

                missing.downloadUrl != null -> Text(
                    "Скачать: ${missing.downloadUrl}",
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
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(session.message, color = MaterialTheme.colorScheme.error)
        if (session.details.isNotEmpty()) {
            Text("Вывод rclone:", style = MaterialTheme.typography.labelMedium)
            LazyColumn {
                items(session.details) { line ->
                    Text(line, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
