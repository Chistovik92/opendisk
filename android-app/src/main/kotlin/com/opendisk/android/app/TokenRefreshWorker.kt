package com.opendisk.android.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.opendisk.android.LibrcloneTransport
import com.opendisk.bridge.AuthErrors
import com.opendisk.bridge.RcloneClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Раз в сутки обращается к каждому облаку — ради токенов.
 *
 * rclone обновляет токен доступа сам, но только когда им пользуются. Если
 * облако не открывали неделями, срок мог выйти: у OneDrive и ряда других
 * сервисов токен живёт, пока им пользуются, и протухает после долгого
 * простоя, а человек узнавал об этом, только когда файл не открывался.
 * Лёгкий запрос раз в сутки держит токен живым.
 *
 * Это не лечит Google в режиме тестирования: там токен умирает через
 * неделю, как им ни пользуйся. Зато об этом человек узнаёт сразу, а не по
 * сломанному файлу: облако помечается «нужно войти заново» и приходит
 * уведомление.
 *
 * Работу в фоне выдаёт система (WorkManager), без службы и значка в шторке.
 */
class TokenRefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val settings = MobileSettings(applicationContext)
        LibrcloneTransport.useConfig(File(applicationContext.filesDir, "rclone.conf"))
        val client = RcloneClient(LibrcloneTransport.get())

        val expired = mutableSetOf<String>()
        val unreachable = mutableSetOf<String>()
        val remotes = runCatching { client.listRemotes() }.getOrElse { return@withContext Result.retry() }
        for (name in remotes) {
            when (probe(client, name)) {
                Probe.EXPIRED -> expired += name
                Probe.OTHER -> unreachable += name
                Probe.OK -> Unit
            }
        }

        val before = settings.needsSignIn
        settings.needsSignIn = merge(before, remotes.toSet(), expired, unreachable)
        // Уведомляем только о новых: напоминать каждые сутки о том же облаке — докучать.
        (expired - before).forEach { notify(applicationContext, it, MobileStrings.of(settings.read().language)) }
        Result.success()
    }

    private enum class Probe { OK, EXPIRED, OTHER }

    /**
     * Лёгкий запрос к облаку. `about` у части бэкендов не поддерживается —
     * тогда читаем корень: ошибка про токен приходит и на нём.
     */
    private suspend fun probe(client: RcloneClient, name: String): Probe {
        val result = withTimeoutOrNull(PROBE_TIMEOUT_MILLIS) {
            runCatching { client.about(name) }.recoverCatching { client.listFs(RcloneClient.cloudFs(name)) }
        } ?: return Probe.OTHER
        val error = result.exceptionOrNull() ?: return Probe.OK
        val message = (error as? com.opendisk.bridge.RcloneRcException)?.rcloneError ?: error.message.orEmpty()
        return if (AuthErrors.isExpired(message)) Probe.EXPIRED else Probe.OTHER
    }

    companion object {
        private const val NAME = "opendisk-token-refresh"
        private const val CHANNEL = "cloud-access"
        private const val PROBE_TIMEOUT_MILLIS = 60_000L

        /** Раз в сутки, и только при сети: без неё проверять нечего. Повторный вызов ничего не дублирует. */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<TokenRefreshWorker>(1, TimeUnit.DAYS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        /** Уведомление «нужно войти заново». Отказ человека от уведомлений не ошибка — просто молчим. */
        private fun notify(context: Context, cloud: String, strings: MobileStrings) {
            if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                manager.createNotificationChannel(
                    NotificationChannel(CHANNEL, strings.tokenChannel, NotificationManager.IMPORTANCE_DEFAULT),
                )
            }
            val open = PendingIntent.getActivity(
                context,
                cloud.hashCode(),
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            @Suppress("DEPRECATION")
            val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(context, CHANNEL)
            } else {
                Notification.Builder(context)
            }
            val notification = builder
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle(strings.reauthNotificationTitle(cloud))
                .setContentText(strings.reauthNotificationText)
                .setStyle(Notification.BigTextStyle().bigText(strings.reauthNotificationText))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            // Своё уведомление у каждого облака: два протухших не должны стереть друг друга.
            manager.notify(NOTIFICATION_BASE + (cloud.hashCode() and 0xFFFF), notification)
        }

        private const val NOTIFICATION_BASE = 41_000

        /**
         * Новый список «нужно войти заново»: проверенные и отозвавшиеся из него
         * убираются, протухшие добавляются. Облака, которых проверка не
         * достигла (нет сети, долго не отвечали), остаются как были — по
         * неудачной проверке судить о токене нельзя. Удалённые облака
         * пропадают из списка вовсе.
         */
        internal fun merge(before: Set<String>, existing: Set<String>, expired: Set<String>, unreachable: Set<String> = emptySet()): Set<String> =
            (before.filter { it in existing && it in unreachable }.toSet()) + expired
    }
}
