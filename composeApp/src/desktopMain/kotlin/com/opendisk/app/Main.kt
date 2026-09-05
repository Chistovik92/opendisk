package com.opendisk.app

import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.window.Tray
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
fun main() {
    // Окно прячется в трей, поэтому ярлык нажимают повторно — и без этой
    // проверки получали вторую копию приложения со своим процессом rclone.
    if (!SingleInstance.acquire { activationSignal.tryEmit(Unit) }) {
        return
    }
    runApplication()
}

private fun runApplication() = application {
    val controller = remember { RcloneController() }
    val state by controller.state.collectAsState()
    val traySupported = remember { runCatching { SystemTray.isSupported() }.getOrDefault(false) }
    var windowVisible by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) { controller.start() }

    // Повторный запуск ярлыка просит показать окно вместо второй копии.
    LaunchedEffect(Unit) {
        activationSignal.collect {
            windowVisible = true
        }
    }

    fun quit() {
        controller.shutdown()
        exitApplication()
    }

    if (traySupported) {
        Tray(
            icon = OpenDiskIcon,
            tooltip = "OpenDisk",
            onAction = { windowVisible = true },
            menu = {
                Item("Показать окно", onClick = { windowVisible = true })
                Item("Выход", onClick = ::quit)
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
            AppScreen(state, controller)
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
