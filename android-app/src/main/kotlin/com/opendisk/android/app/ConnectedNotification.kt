package com.opendisk.android.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Значок в шторке, пока облака подключены.
 *
 * На телефоне у приложения нет ни окна в трее, ни значка рядом с часами:
 * шторка — единственное место, где видно, что оно вообще что-то делает.
 * А делает оно именно тогда, когда система через поставщика документов
 * ходит за файлами в облако — часто уже без открытого экрана.
 *
 * Уведомление не постоянное (`setOngoing`) намеренно: приложение не держит
 * службу, которую нельзя закрыть, и смахнуть значок человек вправе. Список
 * подключённых облаков от этого не меняется.
 */
object ConnectedNotification {

    fun update(context: Context, clouds: Collection<String>, strings: MobileStrings) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (clouds.isEmpty()) {
            manager.cancel(ID)
            return
        }
        createChannel(manager, strings)

        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val text = strings.notificationText(clouds.sorted())
        val notification = builder(context)
            // Иконка приложения, а не своя: рисованной мелкой иконки у проекта
            // пока нет, а пустой квадрат в шторке хуже узнаваемого значка.
            .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
            .setContentTitle(strings.notificationTitle)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .build()

        // Разрешения на уведомления с Android 13 может и не быть: тогда система
        // просто ничего не покажет. Подключение от этого не ломается, поэтому
        // и падать здесь не из-за чего.
        runCatching { manager.notify(ID, notification) }
    }

    @Suppress("DEPRECATION")
    private fun builder(context: Context): Notification.Builder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, CHANNEL)
        } else {
            Notification.Builder(context)
        }

    private fun createChannel(manager: NotificationManager, strings: MobileStrings) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL,
            strings.notificationChannel,
            // Тихо и без звука: это состояние, а не событие.
            NotificationManager.IMPORTANCE_LOW,
        )
        channel.setShowBadge(false)
        manager.createNotificationChannel(channel)
    }

    private const val CHANNEL = "connected-clouds"
    private const val ID = 1
}
