package com.opendisk.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Notification
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.rememberTrayState
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.flow.MutableSharedFlow
import java.awt.SystemTray

/**
 * Сигнал «покажи окно» от повторного запуска приложения. Живёт вне композиции,
 * потому что приходит из сокета ещё до того, как окно создано.
 */
private val activationSignal = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

/**
 * Точка входа десктоп-приложения.
 *
 * Окно живёт вместе с иконкой в трее: закрытие окна прячет его, а выход —
 * пункт меню трея. Если трей системой не поддерживается, закрытие окна
 * завершает приложение: иначе его стало бы нечем закрыть.
 */
fun main(args: Array<String>) {
    // Уборка за собой при удалении. Запускается установщиком от имени
    // пользователя — только так видны его настройки и запись автозапуска,
    // которые лежат в профиле, а не в каталоге программы. Окно при этом
    // не показывается и проверка единственной копии не нужна: работы на
    // доли секунды, и делать её нужно в любом случае.
    if (args.contains(CLEANUP_FLAG)) {
        Cleanup.remove(Cleanup.ownFiles())
        Cleanup.removeAutostart()
        return
    }

    // Окно прячется в трей, поэтому ярлык нажимают повторно — и без этой
    // проверки получали вторую копию приложения со своим процессом rclone.
    if (!SingleInstance.acquire { activationSignal.tryEmit(Unit) }) {
        return
    }
    // При автозапуске окно показывать не нужно: приложение поднимается
    // вместе с системой, чтобы подключить диски, а не чтобы мешать.
    runApplication(startHidden = args.contains(HIDDEN_FLAG))
}

/** Флаг запуска свёрнутым — его добавляет автозапуск. */
const val HIDDEN_FLAG = "--hidden"

/** Флаг уборки за собой — его передаёт установщик при удалении. */
const val CLEANUP_FLAG = "--cleanup"

private fun runApplication(startHidden: Boolean) = application {
    val controller = remember { RcloneController() }
    val state by controller.state.collectAsState()
    val traySupported = remember { runCatching { SystemTray.isSupported() }.getOrDefault(false) }
    val trayState = rememberTrayState()
    val strings = Strings.of(Language.fromCode(state.globalSettings.language))
    // Свёрнутым можно стартовать только с треем: иначе окно не вернуть.
    var windowVisible by remember { mutableStateOf(!(startHidden && traySupported)) }

    LaunchedEffect(Unit) { controller.start() }

    // Повторный запуск ярлыка просит показать окно вместо второй копии.
    LaunchedEffect(Unit) {
        activationSignal.collect {
            windowVisible = true
        }
    }

    // Об оборвавшемся подключении пользователь иначе не узнает: окно живёт
    // свёрнутым, а в трее уведомление видно сразу.
    LaunchedEffect(Unit) {
        controller.notifications.collect { event ->
            trayState.sendNotification(
                Notification(
                    title = event.title,
                    message = event.message,
                    type = Notification.Type.Error,
                ),
            )
        }
    }

    fun quit() {
        controller.shutdown()
        exitApplication()
    }

    if (traySupported) {
        Tray(
            state = trayState,
            icon = OpenDiskIcon,
            tooltip = "OpenDisk",
            onAction = { windowVisible = true },
            menu = {
                Item(strings.showWindow, onClick = { windowVisible = true })
                Item(strings.quit, onClick = ::quit)
            },
        )
    }

    Window(
        onCloseRequest = { if (traySupported) windowVisible = false else quit() },
        visible = windowVisible,
        state = rememberWindowState(size = DpSize(720.dp, 560.dp)),
        title = "OpenDisk",
        icon = OpenDiskIcon,
    ) {
        MaterialTheme {
            // Язык подаётся сверху: строки нужны и вне композиции — например,
            // контроллеру для сообщений об ошибках.
            CompositionLocalProvider(LocalStrings provides strings) {
                AppScreen(state, controller, onQuit = ::quit)
            }
        }
    }
}

/**
 * Иконка приложения и трея. Рисуется кодом, а не берётся из файла: на этом этапе
 * дизайна ещё нет, а тащить в репозиторий заглушку-картинку смысла мало.
 */
private object OpenDiskIcon : Painter() {

    override val intrinsicSize: Size = Size(32f, 32f)

    override fun DrawScope.onDraw() {
        val accent = Color(0xFF3B6FD4)
        val light = Color(0xFF8FB4F5)

        // Схематичное облако: крупная капля плюс два «пузыря» слева и справа.
        drawCircle(light, radius = size.minDimension * 0.24f, center = Offset(size.width * 0.34f, size.height * 0.55f))
        drawCircle(light, radius = size.minDimension * 0.20f, center = Offset(size.width * 0.68f, size.height * 0.58f))
        drawCircle(accent, radius = size.minDimension * 0.28f, center = Offset(size.width * 0.50f, size.height * 0.44f))
    }
}
