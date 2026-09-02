package com.rimesync.android.core

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.File
import java.time.Instant

data class FileState(
    val hash: String,
    val size: Long,
    val modified: String,
)

object TimeUtils {
    fun epochToIso(epochMillis: Long): String = Instant.ofEpochMilli(epochMillis).toString()

    /** 安全解析 ISO 时间，无法解析返回 null。统一按 UTC 比较相对先后。 */
    fun safeParseIso(iso: String?): Instant? = try {
        iso?.trim()?.let { Instant.parse(it) }
    } catch (e: Exception) {
        null
    }
}

/** 用户词库增量同步引擎，行为与 CLI 的 core/sync.py 对齐。 */
object SyncEngine {

    /** 计算本地设备 sync 目录状态。 */
    suspend fun computeLocalState(store: RimeFileStore, device: String): Map<String, FileState> {
        val dir = "sync/$device"
        if (!store.isDirectory(dir)) return emptyMap()
        val files = store.walkFiles(dir)
        val state = HashMap<String, FileState>()
        val prefix = "$dir/"
        for (rel in files) {
            val name = rel.removePrefix(prefix)
            if (name == "_manifest.json") continue
            try {
                val size = store.size(rel) ?: 0L
                val mtime = store.lastModified(rel) ?: 0L
                val data = store.readBytes(rel)
                state[name] = FileState(
                    hash = HashUtils.computeFileHash(data),
                    size = size,
                    modified = TimeUtils.epochToIso(mtime),
                )
            } catch (e: Exception) {
                continue
            }
        }
        return state
    }

    /** 对比本地与远端状态，返回 (需上传, 需下载)。哈希相同跳过；不同时较新的 mtime 胜出。 */
    fun diffSyncState(
        local: Map<String, FileState>,
        remote: Map<String, FileState>,
    ): Pair<List<String>, List<String>> {
        val toUpload = ArrayList<String>()
        val toDownload = ArrayList<String>()

        val localFiles = local.keys
        val remoteFiles = remote.keys

        for (fname in localFiles - remoteFiles) toUpload.add(fname)
        for (fname in remoteFiles - localFiles) toDownload.add(fname)

        for (fname in localFiles intersect remoteFiles) {
            val lh = local[fname]?.hash ?: ""
            val rh = remote[fname]?.hash ?: ""
            if (lh == rh) continue

            val localMtime = TimeUtils.safeParseIso(local[fname]?.modified)
            val remoteMtime = TimeUtils.safeParseIso(remote[fname]?.modified)

            when {
                localMtime == null || remoteMtime == null -> {
                    toUpload.add(fname)
                    toDownload.add(fname)
                }
                localMtime > remoteMtime -> toUpload.add(fname)
                localMtime < remoteMtime -> toDownload.add(fname)
                else -> {
                    toUpload.add(fname)
                    toDownload.add(fname)
                }
            }
        }
        return toUpload to toDownload
    }

    private fun remoteStateFromInfo(data: JsonObject?): Map<String, FileState> {
        val state = HashMap<String, FileState>()
        val files = data?.arr("files") ?: return state
        for (element in files) {
            val obj = element as? JsonObject ?: continue
            val name = obj.str("name") ?: continue
            state[name] = FileState(
                hash = obj.str("hash") ?: "",
                size = obj.lng("size") ?: 0L,
                modified = obj.str("modified") ?: "",
            )
        }
        return state
    }

    private suspend fun deviceNames(api: ApiClient): List<String> {
        val result = api.getDeviceList()
        val devices = result.obj("data")?.arr("devices")
            ?: return emptyList()
        val names = ArrayList<String>()
        for (element in devices) {
            when (element) {
                is JsonObject -> element.str("name")?.let { names.add(it) }
                is JsonPrimitive -> element.contentOrNull?.let { names.add(it) }
                else -> {}
            }
        }
        return names
    }

    /** 增量上传当前设备的用户词库。 */
    suspend fun syncUserdbUpload(
        store: RimeFileStore,
        api: ApiClient,
        device: String,
        tempDir: File,
    ): Map<String, Any> {
        val remoteState = try {
            val result = api.getSyncInfo(device = device)
            remoteStateFromInfo(result.obj("data"))
        } catch (e: Exception) {
            uploadSyncTar(store, api, device, tempDir)
            return mapOf("uploaded" to -1, "fallback_tar" to true)
        }

        val localState = computeLocalState(store, device)
        if (localState.isEmpty()) {
            return mapOf("uploaded" to 0, "skipped" to 0)
        }

        val toUpload = diffSyncState(localState, remoteState).first
        if (toUpload.isEmpty()) {
            return mapOf("uploaded" to 0, "skipped" to localState.size)
        }

        var success = 0
        val failed = ArrayList<String>()
        for (fname in toUpload) {
            try {
                val data = store.readBytes("sync/$device/$fname")
                val tempFile = writeTemp(tempDir, fname, data)
                api.uploadSyncFile(tempFile, fname, device)
                tempFile.delete()
                success++
            } catch (e: Exception) {
                failed.add(fname)
            }
        }
        return mapOf("uploaded" to success, "failed" to failed.size, "total" to toUpload.size)
    }

    /** 增量下载其他设备的用户词库。 */
    suspend fun syncUserdbDownload(
        store: RimeFileStore,
        api: ApiClient,
        device: String,
    ): Map<String, Any> {
        val allDevices = deviceNames(api)
        if (allDevices.isEmpty()) throw RimeSyncException("无法获取设备列表")

        val otherDevices = allDevices.filter { it != device }
        if (otherDevices.isEmpty()) {
            return mapOf("devices" to 0, "downloaded" to 0, "skipped" to 0)
        }

        var totalDownloaded = 0
        for (other in otherDevices) {
            try {
                val result = api.getSyncInfo(device = other)
                val remoteState = remoteStateFromInfo(result.obj("data"))
                val localState = computeLocalState(store, other)
                val toDownload = diffSyncState(localState, remoteState).second
                if (toDownload.isEmpty()) continue

                for (fname in toDownload) {
                    try {
                        val data = api.downloadSyncFile(fname, other)
                        SafePath.normalize("sync/$other/$fname")
                        store.writeBytes("sync/$other/$fname", data)
                        totalDownloaded++
                    } catch (e: Exception) {
                        // 单文件失败不影响整体
                    }
                }
            } catch (e: Exception) {
                // 设备处理失败则跳过
            }
        }
        return mapOf("downloaded" to totalDownloaded)
    }

    /** 全量 tar 上传，失败回退逐个文件。 */
    suspend fun uploadSyncTar(
        store: RimeFileStore,
        api: ApiClient,
        device: String,
        tempDir: File,
    ): JsonObject {
        val dir = "sync/$device"
        val files = store.walkFiles(dir)
        if (files.isEmpty()) {
            throw RimeSyncException("sync文件夹不存在或为空: $dir")
        }
        val tarFile = File(tempDir, "sync_${device}_${System.currentTimeMillis()}.tar")
        TarUtils.createTar(store, dir, tarFile, excludePrefixes = setOf("_manifest.json"))

        return try {
            val result = api.uploadSyncTar(tarFile, device)
            if (result.bool("success") != true) {
                fallbackUploadFiles(store, api, device, files, tempDir)
                JsonObject(mapOf("success" to JsonPrimitive(true), "message" to JsonPrimitive("通过逐个文件上传完成")))
            } else {
                result
            }
        } finally {
            tarFile.delete()
        }
    }

    private suspend fun fallbackUploadFiles(
        store: RimeFileStore,
        api: ApiClient,
        device: String,
        files: List<String>,
        tempDir: File,
    ) {
        val prefix = "sync/$device/"
        for (rel in files) {
            val name = rel.removePrefix(prefix)
            try {
                val data = store.readBytes(rel)
                val temp = writeTemp(tempDir, name, data)
                api.uploadSyncFile(temp, name, device)
                temp.delete()
            } catch (e: Exception) {
                // 忽略单个失败
            }
        }
    }

    private fun writeTemp(tempDir: File, relPath: String, data: ByteArray): File {
        val file = File(tempDir, SafePath.normalize(relPath))
        file.parentFile?.mkdirs()
        file.writeBytes(data)
        return file
    }
}