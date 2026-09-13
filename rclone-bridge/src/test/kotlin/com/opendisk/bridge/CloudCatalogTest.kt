package com.opendisk.bridge

import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.io.path.createTempDirectory
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Каталог сервисов.
 *
 * Главная опасность в каталоге — опечатка: поле `usre` вместо `user` или
 * провайдер `CloudFlare` вместо `Cloudflare` rclone молча проглотит, облако
 * создастся — и не будет работать. Поэтому самая ценная проверка здесь идёт
 * против настоящего rclone: каждый бэкенд, провайдер и ключ поля обязан
 * у него существовать. Без бинарника она пропускается, как и остальные
 * интеграционные тесты.
 */
class CloudCatalogTest {

    private var rclone: RcloneProcess? = null

    @AfterTest
    fun stopProcess() {
        rclone?.stop()
    }

    @Test
    fun `идентификаторы сервисов не повторяются`() {
        val ids = CloudCatalog.services.map { it.id }
        assertEquals(ids.size, ids.toSet().size, "повторы: ${ids.groupBy { it }.filter { it.value.size > 1 }.keys}")
    }

    @Test
    fun `у браузерного входа нет обязательного пароля`() {
        // Весь смысл входа через браузер — ничего не придумывать и не вводить.
        CloudCatalog.services.filter { it.oauth }.forEach { service ->
            assertTrue(
                service.fields.none { it.required },
                "${service.id}: у сервиса со входом через браузер обязательные поля " +
                    service.fields.filter { it.required }.map { it.key },
            )
        }
    }

    @Test
    fun `Google Диск есть и входит через браузер`() {
        val drive = CloudCatalog.services.single { it.backend == "drive" }
        assertTrue(drive.oauth)
        assertTrue(drive.asksWhichAccount)
    }

    @Test
    fun `поиск находит сервис по стране и по другому имени`() {
        assertTrue(CloudCatalog.services.any { it.matches("россия") })
        assertTrue(CloudCatalog.services.single { it.id == "onedrive" }.matches("microsoft"))
        assertTrue(CloudCatalog.services.single { it.id == "gdrive" }.matches("Google Диск"))
    }

    @Test
    fun `вариант бэкенда отбирает свои поля`() {
        val field = RcloneClient.Option(name = "region", provider = "AWS,Wasabi")
        assertTrue(field.appliesTo("AWS"))
        assertFalse(field.appliesTo("Cloudflare"))
        assertTrue(RcloneClient.Option(name = "endpoint", provider = "!AWS").appliesTo("Cloudflare"))
        assertFalse(RcloneClient.Option(name = "endpoint", provider = "!AWS").appliesTo("AWS"))
        assertTrue(RcloneClient.Option(name = "user").appliesTo("AWS"))
    }

    @Test
    fun `полный список не повторяет отобранные сервисы и не содержит обёрток`() {
        val providers = listOf(
            RcloneClient.Provider(name = "crypt", description = "Encrypt/Decrypt a remote"),
            RcloneClient.Provider(name = "drive", description = "Google Drive"),
            RcloneClient.Provider(name = "gofile", description = "Gofile"),
            RcloneClient.Provider(
                name = "s3",
                description = "Amazon S3 Compliant Storage Providers",
                options = listOf(
                    RcloneClient.Option(
                        name = "provider",
                        examples = listOf(
                            RcloneClient.Option.Example("AWS", "Amazon Web Services (AWS) S3"),
                            RcloneClient.Option.Example("Qiniu", "Qiniu Object Storage (Kodo)"),
                        ),
                    ),
                    RcloneClient.Option(name = "access_key_id"),
                    RcloneClient.Option(name = "region", provider = "AWS"),
                ),
            ),
        )

        val all = CloudCatalog.fromProviders(providers)
        val ids = all.map { it.id }

        assertFalse("rclone:crypt" in ids, "обёртка crypt попала в список облаков")
        assertFalse("rclone:drive" in ids, "Google Диск повторён: он уже есть среди отобранных")
        assertFalse("rclone:s3:AWS" in ids, "Amazon S3 повторён: он уже есть среди отобранных")
        assertTrue("rclone:gofile" in ids)

        val qiniu = all.single { it.id == "rclone:s3:Qiniu" }
        assertEquals("Qiniu Object Storage (Kodo)", qiniu.title.en)
        assertEquals(mapOf("provider" to "Qiniu"), qiniu.fixed)
        assertFalse(qiniu.fields.any { it.key == "region" }, "поле только для AWS показано у Qiniu")
    }

    @Test
    fun `каталог сходится с настоящим rclone`() {
        assumeTrue(RcloneProcess.locate() != null, "rclone не найден")

        val dir = createTempDirectory("catalog-config").toFile()
        val config = RcloneConfigFile(File(dir, "rclone.conf").apply { writeText("") })
        val process = RcloneProcess(
            rclonePath = requireNotNull(RcloneProcess.locate()).file.absolutePath,
            rcAddr = RcloneProcess.freeRcAddr(),
            config = config,
        )
        rclone = process
        process.start()
        runBlocking { process.awaitReady() }

        val providers = RcloneClient(process.rcBaseUrl).use { runBlocking { it.providers() } }
            .associateBy { it.type }

        val problems = CloudCatalog.services.flatMap { service ->
            val provider = providers[service.backend]
                ?: return@flatMap listOf("${service.id}: у rclone нет бэкенда «${service.backend}»")
            val options = provider.options.map { it.name }.toSet()
            val variant = service.fixed["provider"]
            val knownVariants = provider.options.firstOrNull { it.name == "provider" }
                ?.examples?.map { it.value }.orEmpty()

            buildList {
                (service.fixed.keys + service.fields.map { it.key })
                    .filter { it !in options }
                    .forEach { add("${service.id}: у «${service.backend}» нет поля «$it»") }
                if (variant != null && knownVariants.isNotEmpty() && variant !in knownVariants) {
                    add("${service.id}: у «${service.backend}» нет провайдера «$variant»")
                }
            }
        }
        assertTrue(problems.isEmpty(), problems.joinToString("\n"))

        // Полный список строится и действительно полный: у rclone 1.75 одних
        // провайдеров S3 полсотни.
        val all = CloudCatalog.fromProviders(providers.values.toList())
        assertTrue(all.size > 60, "в полном списке всего ${all.size} сервисов")
        assertTrue(all.none { it.backend in CloudCatalog.NOT_CLOUDS })
    }
}
