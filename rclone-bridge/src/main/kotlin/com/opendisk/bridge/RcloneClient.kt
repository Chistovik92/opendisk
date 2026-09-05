package com.opendisk.bridge

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Типобезопасная обёртка над rclone RC (Remote Control) HTTP API.
 *
 * Список используемых эндпоинтов и их назначение — в docs/ARCHITECTURE.md.
 * Официальная документация rclone RC: https://rclone.org/rc/
 */
class RcloneClient(
    private val baseUrl: String,
    private val httpClient: HttpClient = defaultHttpClient(),
) {

    @Serializable
    data class RemoteInfo(val name: String, val type: String)

    @Serializable
    private data class ListRemotesResponse(val remotes: List<String> = emptyList())

    suspend fun listRemotes(): List<String> {
        val response: ListRemotesResponse = call("config/listremotes")
        return response.remotes
    }

    suspend fun createRemote(name: String, type: String, parameters: Map<String, String>) {
        call<JsonObject>(
            "config/create",
            buildMap {
                put("name", name)
                put("type", type)
                put("parameters", parameters)
            },
        )
    }

    suspend fun deleteRemote(name: String) {
        call<JsonObject>("config/delete", mapOf("name" to name))
    }

    suspend fun mount(remoteName: String, mountPoint: String, vfsCacheMode: String = "writes") {
        call<JsonObject>(
            "mount/mount",
            mapOf(
                "fs" to "$remoteName:",
                "mountPoint" to mountPoint,
                "vfsOpt" to mapOf("CacheMode" to vfsCacheMode),
            ),
        )
    }

    suspend fun unmount(mountPoint: String) {
        call<JsonObject>("mount/unmount", mapOf("mountPoint" to mountPoint))
    }

    @Serializable
    data class MountInfo(val Fs: String, val MountPoint: String)

    @Serializable
    private data class ListMountsResponse(val mountPoints: List<MountInfo> = emptyList())

    suspend fun listMounts(): List<MountInfo> {
        val response: ListMountsResponse = call("mount/listmounts")
        return response.mountPoints
    }

    private suspend inline fun <reified T> call(
        endpoint: String,
        body: Map<String, Any?> = emptyMap(),
    ): T {
        return httpClient.post("$baseUrl/$endpoint") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }.body()
    }

    companion object {
        fun defaultHttpClient(): HttpClient = HttpClient(CIO) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
        }
    }
}
