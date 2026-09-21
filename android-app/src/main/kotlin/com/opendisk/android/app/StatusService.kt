package com.opendisk.android.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/** Что показывать в шторке. */
data class StatusSnapshot(
    val clouds: List<CloudStatus> = emptyList(),
    /** Облако, в которое сейчас идёт вход через браузер; null — вход не идёт. */
    val signingIn: String? = null,
) {
    data class CloudStatus(val name: String, val connected: Boolean, val space: String)

    /** Работа, ради которой нужна служба: отдаём облака системе или ждём браузера. */
    val active: Boolean get() = signingIn != null || clouds.any { it.connected }
}

/**
 * Значок OpenDisk в шторке — сводка по подключениям — и служба переднего
 * плана, которой он принадлежит.
 *
 * Значок — единственное место на телефоне, где видно, что приложение
 * работает: какие облака подключены, сколько в них занято, идёт ли вход.
 *
 * Служба нужна не ради значка. Подключённое облако система читает, когда
 * человеку нужен файл, — через «Файлы», пока OpenDisk в фоне. Ровно в фоне
 * идёт и обмен кода на токен при входе через браузер: впереди вкладка.
 * Фоновому приложению многие телефоны отрезают сеть — экономия трафика,
 * ограничения батареи у производителя, — и на настоящем телефоне вход в
 * Яндекс.Диск падал на «lookup oauth.yandex.com: no such host». Приложение со
 * службой переднего плана система считает активным и сеть ему оставляет.
 *
 * Поэтому служба работает, пока есть подключённые облака или идёт вход, и
 * останавливается, когда отключены все: держать её без дела незачем.
 */
class StatusService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Система подняла службу сама после того, как выгрузила приложение:
        // экрана нет, и сводку взять неоткуда, кроме настроек. Подключённые
        // облака — там; места в них не знаем, и это не страшно.
        if (snapshot.clouds.isEmpty() && snapshot.signingIn == null) {
            snapshot = StatusSnapshot(
                clouds = MobileSettings(this).read().connected.sorted()
                    .map { StatusSnapshot.CloudStatus(name = it, connected = true, space = "") },
            )
        }
        val notification = build(this, snapshot)
        // С Android 12 выйти на передний план из фона система разрешает не
        // всегда — например, при её собственном перезапуске службы. Тогда
        // тихо сдаёмся: падение приложения из-за значка хуже, чем без значка.
        val foreground = runCatching {
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE ->
                    startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
                    startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
                else -> startForeground(ID, notification)
            }
        }.isSuccess
        if (!foreground) {
            stopSelf()
            return START_NOT_STICKY
        }
        running = true
        if (!snapshot.active) {
            stopSelf()
            return START_NOT_STICKY
        }
        // Поднимать заново, если система выгрузила приложение. До 0.5.8 здесь
        // было «не поднимать»: считалось, что без экрана отдавать облака
        // некому. Но «Файлы» читают облако через поставщика документов, и
        // экран для этого не нужен, а служба — нужна: без неё свёрнутому
        // приложению телефон отрезает сеть, и облако в «Файлах» переставало
        // открываться, как будто пропало.
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "status"
        private const val ID = 1

        @Volatile
        private var snapshot = StatusSnapshot()

        @Volatile
        private var running = false

        /** Приводит значок и службу в соответствие с состоянием приложения. */
        fun sync(context: Context, next: StatusSnapshot) {
            val app = context.applicationContext
            snapshot = next
            val manager = app.getSystemService(NotificationManager::class.java) ?: return

            if (!next.active) {
                if (running) runCatching { app.stopService(Intent(app, StatusService::class.java)) }
                manager.cancel(ID)
                return
            }

            if (running) {
                // Служба уже есть — достаточно обновить её значок.
                runCatching { manager.notify(ID, build(app, next)) }
                return
            }

            val started = runCatching {
                val intent = Intent(app, StatusService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    app.startForegroundService(intent)
                } else {
                    app.startService(intent)
                }
            }.isSuccess
            // Запустить службу из фона Android 12 и новее может не дать. Тогда
            // хотя бы значок: сводка всё равно нужна человеку.
            if (!started) runCatching { manager.notify(ID, build(app, next)) }
        }

        private fun build(context: Context, snapshot: StatusSnapshot): Notification {
            val strings = MobileStrings.of(MobileSettings(context).read().language)
            val manager = context.getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                manager?.createNotificationChannel(
                    NotificationChannel(CHANNEL, strings.notificationChannel, NotificationManager.IMPORTANCE_LOW)
                        .apply { setShowBadge(false) },
                )
            }

            val open = PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

            val connected = snapshot.clouds.filter { it.connected }
            val title = snapshot.signingIn?.let(strings::signInNotificationTitle)
                ?: strings.statusTitle(connected.size, snapshot.clouds.size)
            val summary = if (snapshot.signingIn != null) {
                strings.signInNotificationText
            } else {
                connected.joinToString(", ") { it.name }
            }
            // Развёрнутый вид — по строке на облако: подключено ли и сколько занято.
            val details = buildString {
                if (snapshot.signingIn != null) appendLine(strings.signInNotificationText)
                snapshot.clouds.forEach { cloud ->
                    append(if (cloud.connected) "● " else "○ ")
                    append(cloud.name).append(" — ")
                    append(if (cloud.connected) strings.connected else strings.notConnected)
                    if (cloud.space.isNotEmpty()) append(" · ").append(cloud.space)
                    appendLine()
                }
            }.trim()

            @Suppress("DEPRECATION")
            val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(context, CHANNEL)
            } else {
                Notification.Builder(context)
            }
            return builder
                .setSmallIcon(
                    if (snapshot.signingIn != null) {
                        android.R.drawable.stat_notify_sync
                    } else {
                        android.R.drawable.stat_notify_sync_noanim
                    },
                )
                .setContentTitle(title)
                .setContentText(summary)
                .setStyle(Notification.BigTextStyle().bigText(details))
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .build()
        }
    }
}
