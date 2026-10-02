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
import androidx.compose.foundation.lazy.itemsIndexed
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
            val launched = runCatching { settingsLauncher.launch(forApp) }.isSuccess ||
                runCatching { settingsLauncher.launch(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }.isSuccess
            // На телевизорах и часах такого экрана настроек нет вовсе. Тогда
            // хотя бы обычные разрешения: до Android 13 они открывают медиа
            // и общие папки, и файловый менеджер не остаётся пустым.
            if (!launched) {
                permissionLauncher.launch(
                    arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE),
                )
            }
        } else {
            permissionLauncher.launch(
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE),
            )
        }
    }
}

// --- Вкладка «Диски» ----------------------------------------------------------
@Composable
internal fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
internal fun ListHint(text: String) {
    Text(
        text,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
internal fun DiskRow(
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
internal fun CenteredNote(text: String, spinner: Boolean = false) {
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
