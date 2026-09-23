package com.opendisk.android.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/**
 * Разрешение телефона работать в фоне.
 *
 * Пока OpenDisk не на экране, Android вправе усыпить его и отрезать сеть.
 * Для облаков это смертельно: «Файлы» читают их, когда наше окно давно
 * закрыто, а вход через браузер вообще идёт, пока впереди чужая вкладка —
 * на настоящем телефоне он падал с «lookup oauth.yandex.com: no such host».
 *
 * Раньше это обходилось значком в шторке: приложение со службой переднего
 * плана телефон считает активным. Значок сутками висел ни за чем, поэтому
 * с 0.5.10 спрашиваем разрешение прямо — системным окном Android, — а значок
 * остаётся выключателем в настройках для тех, кому и разрешения мало.
 *
 * Отдельная беда — прошивки Xiaomi, Oppo, Realme, vivo, Huawei и Samsung: у
 * них поверх Android свой список автозапуска, и системное разрешение для них
 * ничего не значит. Экран этого списка у каждой свой, найти его человеку
 * почти невозможно, поэтому открываем сами — а если такого экрана нет,
 * ведём в обычные настройки приложения.
 */
object BackgroundWork {

    /** Разрешено ли приложению не спать в фоне. */
    fun allowed(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val power = context.getSystemService(PowerManager::class.java) ?: return true
        return power.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * Системное окно «разрешить работу в фоне».
     *
     * Показывает его сам Android, и решение принимает человек — приложение
     * ничего не выключает за него. Окна может не быть (сборки без
     * оптимизации батареи): тогда ведём в общий список, где OpenDisk нужно
     * найти самому.
     */
    @Suppress("BatteryLife")
    fun request(context: Context): Boolean {
        if (allowed(context)) return true
        val direct = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:" + context.packageName),
        )
        if (start(context, direct)) return true
        return start(context, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    }

    /** Есть ли у прошивки свой список автозапуска. */
    fun hasVendorSettings(context: Context): Boolean = vendorIntents().any { resolves(context, it) }

    /**
     * Открывает список автозапуска прошивки, а если его нет — настройки
     * приложения: там у большинства прошивок те же ограничения фона, просто
     * глубже.
     */
    fun openVendorSettings(context: Context): Boolean {
        vendorIntents().firstOrNull { resolves(context, it) }?.let { return start(context, it) }
        return start(
            context,
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + context.packageName),
            ),
        )
    }

    /**
     * Экраны автозапуска известных прошивок.
     *
     * Список открытый и заведомо неполный: у каждой прошивки свой экран, и
     * они меняются от версии к версии. Поэтому ни одного из них может не
     * оказаться — это не ошибка, а обычное дело, и тогда работает запасной
     * путь через настройки приложения.
     */
    private fun vendorIntents(): List<Intent> = listOf(
        // Xiaomi, Redmi, POCO
        component("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
        // Oppo, Realme, OnePlus
        component("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
        component("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
        component("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"),
        // vivo
        component("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
        // Huawei, Honor
        component("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
        component("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"),
        // Samsung
        component("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity"),
        // Asus
        component("com.asus.mobilemanager", "com.asus.mobilemanager.autostart.AutoStartActivity"),
    )

    private fun component(packageName: String, activity: String): Intent =
        Intent().setComponent(ComponentName(packageName, activity))

    private fun resolves(context: Context, intent: Intent): Boolean =
        context.packageManager.resolveActivity(intent, 0) != null

    /**
     * Запускает экран настроек. Ошибку глотаем: прошивка может объявить
     * экран и не пустить на него, и падать из-за этого приложение не должно.
     */
    private fun start(context: Context, intent: Intent): Boolean = runCatching {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.isSuccess
}
