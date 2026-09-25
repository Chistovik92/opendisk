package com.opendisk.android.app

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.opendisk.bridge.AppReleases
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.io.File

/**
 * Обновление приложения на телефоне, приставке и часах.
 *
 * До 0.5.12 на Android его не было вовсе: новую версию нужно было узнать
 * самому, найти на GitHub и поставить руками. Теперь — как на компьютере:
 * проверка по списку выпусков, apk под процессор устройства, сверка
 * SHA-256 (общий с десктопом код, [com.opendisk.bridge.UpdateDownloader])
 * и установка системным установщиком.
 *
 * Поставить apk молча Android не даёт — и правильно. Первый раз он просит
 * разрешить OpenDisk «установку неизвестных приложений», потом при каждой
 * установке показывает своё окно подтверждения. Подпись новой версии
 * система сверяет сама: apk с другим ключом поверх не встанет.
 */
object AppUpdate {

    const val CHECKSUMS = "SHA256SUMS-Android"

    /**
     * apk под процессор устройства.
     *
     * Раздельные apk вдвое меньше универсального (librclone — копия rclone
     * на каждую архитектуру). Первый ABI в списке — родной для устройства;
     * для x86-64 раздельного apk в выпуске нет, остаётся универсальный.
     */
    fun assetFor(assets: List<AppReleases.Asset>, abis: List<String>): AppReleases.Asset? {
        val suffix = when (abis.firstOrNull()) {
            "arm64-v8a" -> "-arm64.apk"
            "armeabi-v7a" -> "-arm32.apk"
            else -> null
        }
        return suffix?.let { s -> assets.firstOrNull { it.name.endsWith(s) } }
            ?: assets.firstOrNull { it.name.endsWith("-universal.apk") }
    }

    fun deviceAbis(): List<String> = Build.SUPPORTED_ABIS.toList()

    /**
     * Разрешено ли OpenDisk ставить приложения. До Android 8 это общий
     * переключатель «Неизвестные источники», а не разрешение приложению, —
     * спросить его отсюда нельзя, установщик сам скажет, если он выключен.
     */
    fun canInstall(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    /** Экран, где разрешают установку из OpenDisk. */
    fun requestInstallPermission(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    /**
     * Отдаёт скачанный и сверенный apk системному установщику.
     *
     * Через сессию PackageInstaller, а не ACTION_VIEW на файл: так не нужен
     * FileProvider для apk, и итог установки приходит к нам
     * ([InstallResultReceiver]), а не теряется.
     */
    fun install(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            .apply { setAppPackageName(context.packageName) }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("OpenDisk.apk", 0, apk.length()).use { output ->
                apk.inputStream().use { it.copyTo(output) }
                session.fsync(output)
            }
            // Изменяемый: система дописывает в него итог установки.
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            val callback = PendingIntent.getBroadcast(
                context,
                sessionId,
                Intent(context, InstallResultReceiver::class.java),
                flags,
            )
            session.commit(callback.intentSender)
        }
    }

    /** Чем закончилась установка — для сообщения на экране. */
    sealed interface Outcome {
        data object Installed : Outcome

        data class Failed(val message: String?) : Outcome
    }

    private val _outcomes = MutableSharedFlow<Outcome>(extraBufferCapacity = 4)
    val outcomes: SharedFlow<Outcome> = _outcomes.asSharedFlow()

    internal fun report(outcome: Outcome) {
        _outcomes.tryEmit(outcome)
    }
}

/**
 * Итог сессии установки.
 *
 * Сначала система отвечает «нужно подтверждение» и даёт окно — его и
 * показываем. Успешная установка заменяет процесс приложения, так что об
 * успехе чаще всего сообщать уже некому; отказ или ошибку показываем.
 */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            PackageInstaller.STATUS_SUCCESS -> AppUpdate.report(AppUpdate.Outcome.Installed)
            // Нажали «Отмена» в окне установщика — это не ошибка.
            PackageInstaller.STATUS_FAILURE_ABORTED -> Unit
            else -> AppUpdate.report(
                AppUpdate.Outcome.Failed(intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)),
            )
        }
    }
}
