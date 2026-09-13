package com.opendisk.bridge

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

/**
 * Каталог сервисов в JSON — для приложения на iPhone.
 *
 * Kotlin на iOS не работает, а переписанная вручную копия каталога на Swift
 * расходилась бы с этой ровно так, как до 0.5.4 расходились плитки на
 * компьютере и на Android. Поэтому каталог один, здесь, а iOS получает его
 * файлом при сборке: `./gradlew :rclone-bridge:exportCatalog`.
 */
object CatalogExport {

    fun toJson(): String = Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), catalog())

    private fun catalog() = buildJsonObject {
        put(
            "groups",
            buildJsonArray {
                CatalogGroup.entries.forEach { group ->
                    add(buildJsonObject {
                        put("key", group.name)
                        put("title", localized(group.title))
                    })
                }
            },
        )
        put("services", JsonArray(CloudCatalog.services.map(::service)))
        put("notClouds", JsonArray(CloudCatalog.NOT_CLOUDS.sorted().map(::JsonPrimitive)))
        put("commonOptionalFields", JsonArray(CloudCatalog.COMMON_OPTIONAL_FIELDS.sorted().map(::JsonPrimitive)))
    }

    private fun service(service: CatalogService) = buildJsonObject {
        put("id", service.id)
        put("title", localized(service.title))
        put("subtitle", localized(service.subtitle))
        put("backend", service.backend)
        put("fixed", JsonObject(service.fixed.mapValues { JsonPrimitive(it.value) }))
        put(
            "fields",
            JsonArray(
                service.fields.map { field ->
                    buildJsonObject {
                        put("key", field.key)
                        put("label", localized(field.label))
                        put("isPassword", field.isPassword)
                        put("required", field.required)
                        field.help?.let { put("help", localized(it)) }
                    }
                },
            ),
        )
        put("oauth", service.oauth)
        service.hint?.let { put("hint", localized(it)) }
        put("accent", service.accent)
        put("glyph", service.glyph)
        put("group", service.group.name)
        put("keywords", JsonArray(service.keywords.map(::JsonPrimitive)))
    }

    private fun localized(text: Localized) = buildJsonObject {
        put("ru", text.ru)
        put("en", text.en)
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val target = File(requireNotNull(args.firstOrNull()) { "укажите файл для каталога" })
        target.parentFile?.mkdirs()
        target.writeText(toJson())
        println("Каталог: ${CloudCatalog.services.size} сервисов → $target")
    }
}
