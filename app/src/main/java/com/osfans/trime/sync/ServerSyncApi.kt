/*
 * SPDX-FileCopyrightText: 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

/**
 * rime_server (v2 protocol) sync API client.
 *
 * All endpoints require the `X-Api-Token` header except `/api/health`.
 * See server `app/api.py` for the authoritative contract.
 */
class ServerSyncApi(
    serverUrl: String,
    private val token: String,
) {
    @Serializable
    data class ManifestEntry(
        val sha3: String = "",
        val size: Long = 0,
        val mtime: Double = 0.0,
        val version: String = "",
        val source: String = "",
    )

    @Serializable
    data class Manifest(
        val revision: Long = -1,
        @SerialName("updated_at") val updatedAt: String = "",
        val files: Map<String, ManifestEntry> = emptyMap(),
    )

    private val baseUrl = serverUrl.trim().trimEnd('/')

    private val json = Json { ignoreUnknownKeys = true }

    private val client =
        OkHttpClient
            .Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .build()

    init {
        require(baseUrl.toHttpUrlOrNull() != null) { "Invalid server URL: $serverUrl" }
    }

    private fun request(
        path: String,
        query: List<Pair<String, String>> = emptyList(),
        builder: Request.Builder.() -> Unit = {},
    ): Request {
        val base = baseUrl.toHttpUrlOrNull() ?: throw IOException("Invalid server URL: $baseUrl")
        val urlBuilder = base.newBuilder().addPathSegments(path.trimStart('/'))
        query.forEach { (k, v) -> urlBuilder.addQueryParameter(k, v) }
        return Request.Builder().url(urlBuilder.build()).header("X-Api-Token", token).apply(builder).build()
    }

    private fun parseError(body: okhttp3.ResponseBody?): String = body?.string()?.let { raw ->
        runCatching {
            json.decodeFromString<JsonObject>(raw)["error"]?.toString()?.trim('"')
        }.getOrNull() ?: raw.take(200)
    } ?: "HTTP error"

    private inline fun <reified T> executeForJson(request: Request): T = client.newCall(request).execute().use { resp ->
        val body = resp.body
        if (!resp.isSuccessful || body == null) {
            throw IOException("HTTP ${resp.code}: ${parseError(body)}")
        }
        json.decodeFromString<T>(body.string())
    }

    private inline fun executeForStream(request: Request, block: (InputStream, okhttp3.Response) -> Unit) {
        client.newCall(request).execute().use { resp ->
            val body = resp.body
            if (!resp.isSuccessful || body == null) {
                throw IOException("HTTP ${resp.code}: ${parseError(body)}")
            }
            body.byteStream().use { block(it, resp) }
        }
    }

    /** Throws if the server is not a reachable rime_server. */
    fun checkHealth() {
        client.newCall(request("api/health")).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}: server unreachable")
        }
    }

    fun fetchManifest(): Manifest = executeForJson(request("api/manifest"))

    fun downloadFile(
        rel: String,
        dest: File,
    ) {
        val request = request("api/files/get/$rel")
        executeForStream(request) { input, _ ->
            dest.parentFile?.mkdirs()
            dest.outputStream().buffered().use { input.copyTo(it) }
        }
    }

    /**
     * Download several files as one tar.gz (`?paths=a,b,c`), writing each entry to
     * `root/<arcname>`. Returns the number of files written.
     */
    fun downloadFilesTar(
        rels: List<String>,
        root: File,
    ): Int {
        val request =
            request(
                "api/files/get",
                query = rels.chunked(100).map { chunk -> "paths" to chunk.joinToString(",") },
            )
        var count = 0
        executeForStream(request) { input, resp ->
            if (resp.header("X-File-Count")?.toIntOrNull() == 0) return@executeForStream
            extractTarGz(input, root) { count++ }
        }
        return count
    }

    /** Push this device's user db snapshot tar (files at archive-relative paths). */
    fun pushUserdb(
        device: String,
        tar: File,
    ): JsonObject {
        val requestBody =
            MultipartBody
                .Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("device", device)
                .addFormDataPart(
                    "tar",
                    tar.name,
                    tar.asRequestBody("application/gzip".toMediaType()),
                ).build()
        val request = request("api/userdb/push") { post(requestBody) }
        return executeForJson<JsonObject>(request).jsonObject
    }

    /**
     * Pull other devices' snapshots as tar.gz, extracting `<device>/<file>` entries under
     * [root] (= the rime `sync/` dir). Returns the server-reported file count.
     */
    fun pullUserdb(
        device: String,
        root: File,
    ): Int {
        val request = request("api/userdb/pull", query = listOf("device" to device))
        var count = 0
        executeForStream(request) { input, resp ->
            count = resp.header("X-Sync-File-Count")?.toIntOrNull() ?: 0
            if (count <= 0) return@executeForStream
            extractTarGz(input, root)
        }
        return count
    }

    private fun extractTarGz(
        input: InputStream,
        root: File,
        onEach: () -> Unit = {},
    ) {
        GZIPInputStream(input.buffered()).use { gz ->
            org.apache.commons.compress.archivers.tar.TarArchiveInputStream(gz).use { tar ->
                while (true) {
                    val entry = tar.nextTarEntry ?: break
                    if (!entry.isFile) continue
                    val target = ServerSyncPaths.resolve(root, entry.name)
                    target.parentFile?.mkdirs()
                    target.outputStream().buffered().use { tar.copyTo(it) }
                    onEach()
                }
            }
        }
    }
}

/** Path safety shared by tar extraction and direct downloads. */
object ServerSyncPaths {
    /** Resolve [rel] under [root], rejecting traversal outside the root. */
    fun resolve(
        root: File,
        rel: String,
    ): File {
        val normalized = rel.trim().replace('\\', '/').trimStart('/').removePrefix("./")
        require(normalized.isNotEmpty() && normalized.split('/').none { it.isEmpty() || it == ".." || it == "." }) {
            "Unsafe sync path: $rel"
        }
        val canonicalRoot = root.canonicalFile
        val resolved = File(canonicalRoot, normalized).canonicalFile
        require(resolved.path.startsWith(canonicalRoot.path + File.separator)) {
            "Sync path escapes root: $rel"
        }
        return resolved
    }
}

/** All [ServerSyncApi] calls are blocking; run them off the main thread. */
suspend fun <T> ServerSyncApi.call(block: ServerSyncApi.() -> T): T = withContext(Dispatchers.IO) { block() }
