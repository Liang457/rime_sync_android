package cn.coolgk.rimesyncapp.core

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.File
import java.time.Instant
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

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

    private const val PARALLEL_CONCURRENCY = 4
    private const val PARALLEL_BYTES_LIMIT = 64L * 1024 * 1024

    /** 计算本地设备 sync 目录状态；传入 [cache] 时可命中 (size, mtime) 不变的哈希，跳过重读。
     * 逐文件并行计算，缓解 SAF 逐文件 IPC 的开销。 */
    suspend fun computeLocalState(
        store: RimeFileStore,
        device: String,
        cache: HashCache? = null,
    ): Map<String, FileState> {
        val dir = "sync/$device"
        if (!store.isDirectory(dir)) return emptyMap()
        val prefix = "$dir/"
        val files = store.walkFilesWithStats(dir)
        if (files.isEmpty()) return emptyMap()

        val state = Collections.synchronizedMap(HashMap<String, FileState>())
        val semaphore = Semaphore(PARALLEL_CONCURRENCY)
        coroutineScope {
            files.map { stat ->
                async {
                    semaphore.withPermit {
                        val rel = stat.relPath
                        val name = rel.removePrefix(prefix)
                        if (name == "_manifest.json") return@withPermit
                        try {
                            val size = stat.size
                            val mtime = stat.lastModified
                            val hash = cache?.get(rel, size, mtime)
                                ?: HashUtils.computeFileHash(store.readBytes(rel))
                                    .also { cache?.put(rel, size, mtime, it) }
                            state[name] = FileState(
                                hash = hash,
                                size = size,
                                modified = TimeUtils.epochToIso(mtime),
                            )
                        } catch (e: Exception) {
                            CoreLog.warn("无法计算文件哈希: $rel: ${e.message}")
                        }
                    }
                }
            }.awaitAll()
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

    /** 增量上传当前设备的用户词库；并行上传，单个文件失败不影响整体。 */
    suspend fun syncUserdbUpload(
        store: RimeFileStore,
        api: ApiClient,
        device: String,
        tempDir: File,
        cache: HashCache? = null,
    ): Map<String, Any> {
        val start = System.currentTimeMillis()
        CoreLog.info("增量上传用户词库（设备: $device）...")
        val remoteState = try {
            val result = api.getSyncInfo(device = device)
            remoteStateFromInfo(result.obj("data"))
        } catch (e: Exception) {
            CoreLog.warn("无法获取服务端同步信息，回退到全量tar上传: ${e.message}")
            uploadSyncTar(store, api, device, tempDir)
            return mapOf("uploaded" to -1, "fallback_tar" to true)
        }

        val localState = computeLocalState(store, device, cache)
        if (localState.isEmpty()) {
            CoreLog.info("本地无用户词库文件")
            return mapOf("uploaded" to 0, "skipped" to 0)
        }

        val toUpload = diffSyncState(localState, remoteState).first
        if (toUpload.isEmpty()) {
            CoreLog.info("所有文件已是最新 (${localState.size} 个文件)，无需上传")
            return mapOf("uploaded" to 0, "skipped" to localState.size)
        }

        CoreLog.info("发现 ${toUpload.size} 个文件需要上传（共 ${localState.size} 个本地文件）")
        val totalBytes = toUpload.sumOf { localState[it]?.size ?: 0L }
        val parallel = toUpload.size > 1 && totalBytes <= PARALLEL_BYTES_LIMIT
        val (success, failed) = uploadFiles(store, api, device, toUpload, parallel)
        CoreLog.info("增量上传完成: $success/${toUpload.size} 成功, ${failed.size} 失败, 耗时 ${elapsedMs(start)}")
        return mapOf("uploaded" to success, "failed" to failed.size, "total" to toUpload.size)
    }

    /** 逐个读取并上传文件；并行上传时读取一次内存中直接提交，免去临时文件往返。 */
    private suspend fun uploadFiles(
        store: RimeFileStore,
        api: ApiClient,
        device: String,
        names: List<String>,
        parallel: Boolean,
    ): Pair<Int, List<String>> {
        val success = AtomicInteger(0)
        val failed = Collections.synchronizedList(ArrayList<String>())
        val semaphore = Semaphore(if (parallel) PARALLEL_CONCURRENCY else 1)
        coroutineScope {
            names.map { fname ->
                async {
                    semaphore.withPermit {
                        try {
                            val data = store.readBytes("sync/$device/$fname")
                            api.uploadSyncFileBytes(fname, device, data)
                            success.incrementAndGet()
                        } catch (e: Exception) {
                            CoreLog.warn("上传文件 $fname 失败: ${e.message}")
                            failed.add(fname)
                        }
                    }
                }
            }.awaitAll()
        }
        return success.get() to failed.toList()
    }

    /** 增量下载其他设备的用户词库；文件间并行下载，单文件/单设备失败不影响整体。 */
    suspend fun syncUserdbDownload(
        store: RimeFileStore,
        api: ApiClient,
        device: String,
        cache: HashCache? = null,
    ): Map<String, Any> {
        val start = System.currentTimeMillis()
        CoreLog.info("增量下载其他设备的用户词库（当前设备: $device）...")
        val allDevices = deviceNames(api)
        if (allDevices.isEmpty()) throw RimeSyncException("无法获取设备列表")

        val otherDevices = allDevices.filter { it != device }
        if (otherDevices.isEmpty()) {
            CoreLog.info("没有其他设备需要同步")
            return mapOf("devices" to 0, "downloaded" to 0, "skipped" to 0)
        }

        CoreLog.info("发现 ${otherDevices.size} 个其他设备: ${otherDevices.joinToString()}")
        val totalDownloaded = AtomicInteger(0)
        for (other in otherDevices) {
            try {
                CoreLog.info("增量同步设备 $other...")
                val result = api.getSyncInfo(device = other)
                val remoteState = remoteStateFromInfo(result.obj("data"))
                val localState = computeLocalState(store, other, cache)
                val toDownload = diffSyncState(localState, remoteState).second
                if (toDownload.isEmpty()) {
                    CoreLog.info("设备 $other: 所有文件已是最新")
                    continue
                }
                CoreLog.info("设备 $other: ${toDownload.size} 个文件需要下载")
                val semaphore = Semaphore(PARALLEL_CONCURRENCY)
                coroutineScope {
                    toDownload.map { fname ->
                        async {
                            semaphore.withPermit {
                                try {
                                    val data = api.downloadSyncFile(fname, other)
                                    store.writeBytes("sync/$other/$fname", data)
                                    totalDownloaded.incrementAndGet()
                                } catch (e: Exception) {
                                    CoreLog.warn("下载文件 $other/$fname 失败: ${e.message}")
                                }
                            }
                        }
                    }.awaitAll()
                }
            } catch (e: Exception) {
                CoreLog.warn("处理设备 $other 时出错: ${e.message}，跳过此设备")
            }
        }
        CoreLog.info("增量下载完成: 共 ${totalDownloaded.get()} 个文件, 耗时 ${elapsedMs(start)}")
        return mapOf("downloaded" to totalDownloaded.get())
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
                CoreLog.warn("tar上传失败，回退到逐个文件上传模式...")
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

    private fun elapsedMs(start: Long): Long = System.currentTimeMillis() - start
}