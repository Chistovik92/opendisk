package com.opendisk.app

import com.opendisk.bridge.MountSupport
import com.opendisk.bridge.RcloneProcess
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Установка драйвера монтирования встроенным установщиком.
 *
 * Тест меняет состояние системы (ставит WinFsp) и требует подтверждения UAC,
 * поэтому по умолчанию пропускается. Запускать осознанно:
 *
 * ```
 * ./gradlew :composeApp:test -Dopendisk.test.driverInstall=true \
 *     -Dopendisk.rclone.path=... -Dcompose.application.resources.dir=...
 * ```
 *
 * `compose.application.resources.dir` нужен, потому что встроенный установщик
 * ищется там же, где встроенный rclone; в собранном приложении это свойство
 * проставляет сам Compose.
 */
class MountDriverInstallTest {

    @Test
    fun `installs the bundled mount driver`() {
        assumeTrue(
            System.getProperty("opendisk.test.driverInstall") == "true",
            "тест меняет состояние системы — запускается только явно",
        )
        assumeTrue(RcloneProcess.locate() != null, "rclone не найден")

        val status = MountSupport.check()
        assumeTrue(
            status is MountSupport.Status.Missing,
            "драйвер уже установлен — сначала удалите его, иначе проверять нечего",
        )

        val missing = status as MountSupport.Status.Missing
        val installer: File? = missing.bundledInstaller
        assertNotNull(
            installer,
            "установщик не найден в ресурсах приложения — проверьте " +
                "compose.application.resources.dir и задачу downloadWinFsp",
        )

        assertTrue(MountSupport.installBundled(installer), "установка не завершилась успехом")
        assertTrue(
            MountSupport.check() is MountSupport.Status.Available,
            "после установки система всё ещё считает монтирование недоступным",
        )
    }
}
