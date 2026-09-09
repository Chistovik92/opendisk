package com.opendisk.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.ui.unit.dp
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.File
import javax.swing.JFileChooser

/**
 * Ссылка на скачивание файла.
 *
 * Показывает и путь, и облако: диск мог оказаться не тем, с чьей кнопки начали,
 * — в файловом диалоге можно уйти на соседний подключённый диск, и ссылку тогда
 * выдаёт то облако, которое файл действительно держит. Молча подменить облако
 * было бы неправильно, поэтому оно названо прямо.
 */
@Composable
fun FileLinkDialog(link: FileLinkState, controller: RcloneController, onDismiss: () -> Unit) {
    val strings = LocalStrings.current
    var copied by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.linkDialogTitle) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    link.localPath,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                if (link.cloud.isNotEmpty()) {
                    Text(
                        strings.linkFromCloud(link.cloud, link.remotePath.ifEmpty { "/" }),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                when {
                    link.busy -> Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.width(20.dp))
                        Text(strings.linkAsking, style = MaterialTheme.typography.bodySmall)
                    }

                    link.error != null -> Text(
                        link.error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )

                    link.revoked -> Text(
                        strings.linkRevoked,
                        style = MaterialTheme.typography.bodySmall,
                    )

                    link.url != null -> {
                        // Поле, а не просто текст: адрес нужно уметь выделить и
                        // прочитать целиком, а длинные ссылки в Text обрезаются.
                        // Только для чтения — менять выданный сервисом адрес
                        // бессмысленно.
                        OutlinedTextField(
                            value = link.url,
                            onValueChange = {},
                            readOnly = true,
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            strings.linkExplanation,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (copied) {
                            Text(
                                strings.linkCopied,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            link.url?.let { url ->
                Button(
                    onClick = {
                        copyToClipboard(url)
                        copied = true
                    },
                    enabled = !link.busy,
                ) {
                    Text(strings.linkCopy)
                }
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Отзыв рядом с копированием намеренно: сделать файл публичным
                // легко, и закрыть доступ должно быть так же легко — иначе
                // остаётся только идти на сайт сервиса.
                if (link.url != null && !link.busy) {
                    OutlinedButton(onClick = controller::revokeLink) { Text(strings.linkRevoke) }
                }
                TextButton(onClick = onDismiss) { Text(strings.close) }
            }
        },
    )
}

/**
 * Кладёт адрес в буфер обмена. Ошибку глотаем: буфера может не быть в headless-среде,
 * а ссылка при этом всё равно видна в поле и выделяется мышью.
 */
private fun copyToClipboard(text: String) {
    runCatching {
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
    }
}

/**
 * Выбор файла на подключённом диске.
 *
 * `JFileChooser`, а не `java.awt.FileDialog`: последний не умеет открыться в
 * заданном каталоге на Windows достаточно надёжно, а начинать надо именно на
 * диске облака — искать его среди «Сетевых расположений» руками каждый раз
 * никто не станет.
 *
 * Диалог модальный и работает на потоке интерфейса — у Compose Desktop это
 * тот же поток событий AWT, так что вызывать его прямо из обработчика нажатия
 * можно и нужно.
 */
fun chooseFileOnDrive(startIn: String?, title: String): String? {
    val chooser = JFileChooser().apply {
        dialogTitle = title
        fileSelectionMode = JFileChooser.FILES_ONLY
        isMultiSelectionEnabled = false
        startIn?.let { point ->
            // Точка монтирования может ещё не отвечать (диск поднимается) —
            // тогда просто открываемся там, где откроется.
            runCatching { currentDirectory = File(point) }
        }
    }
    if (chooser.showOpenDialog(null) != JFileChooser.APPROVE_OPTION) return null
    return chooser.selectedFile?.absolutePath
}
