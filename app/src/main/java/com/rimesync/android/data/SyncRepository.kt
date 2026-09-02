package com.rimesync.android.data

import android.content.Context
import com.rimesync.android.core.ApiClient
import com.rimesync.android.core.ConfigRepository
import com.rimesync.android.core.DictSync
import com.rimesync.android.core.FullSync
import com.rimesync.android.core.RimeFileStore
import com.rimesync.android.core.RimeSyncConfig
import com.rimesync.android.core.SafRimeFileStore
import com.rimesync.android.core.SyncEngine
import com.rimesync.android.core.TarUtils
import kotlinx.serialization.json.JsonObject
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 同步操作仓库：加载配置、构建 API 客户端与 rime 目录 store，
 * 提供保留的同步操作（用户词库、更新词库、完整同步），并在执行过程中写日志。
 */
class SyncRepository(private val context: Context) {

    private val configRepo = ConfigRepository(context)

    suspend fun loadConfig(): RimeSyncConfig = configRepo.load()

    suspend fun updateConfig(transform: (RimeSyncConfig) -> RimeSyncConfig) {
        configRepo.update(transform)
    }

    suspend fun getRimeStore(): SafRimeFileStore? = configRepo.getRimeStore()

    /** 解析设备名（配置优先，其次 installation.yaml）。 */
    suspend fun resolveDeviceName(): String {
        val store = getRimeStore()
        val name = configRepo.resolveDeviceName(store)
        logInfo("当前设备: $name")
        return name
    }

    private fun tempDir(): File = File(context.cacheDir, "sync").apply { mkdirs() }

    private suspend fun <T> run(block: suspend (ApiClient, RimeFileStore) -> T): T =
        withContext(Dispatchers.IO) {
            val config = configRepo.load()
            val api = ApiClient(config)
            val store = configRepo.getRimeStore()
                ?: throw IllegalStateException("未选择 Rime 配置目录，请在设置中选择")
            block(api, store)
        }

    // ---- 用户词库同步 ----

    suspend fun syncUserdbUpload(): Map<String, Any> = run { api, store ->
        logInfo("增量上传用户词库...")
        val device = configRepo.resolveDeviceName(store)
        SyncEngine.syncUserdbUpload(store, api, device, tempDir())
    }

    suspend fun syncUserdbDownload(): Map<String, Any> = run { api, store ->
        logInfo("增量下载其他设备的用户词库...")
        val device = configRepo.resolveDeviceName(store)
        SyncEngine.syncUserdbDownload(store, api, device)
    }

    /** 快速同步：先下载其他设备变更，再上传本机用户词库。 */
    suspend fun syncUserdbQuick(): Pair<Map<String, Any>, Map<String, Any>> = run { api, store ->
        logInfo("快速同步开始（下载 → 上传）...")
        val device = configRepo.resolveDeviceName(store)
        val download = SyncEngine.syncUserdbDownload(store, api, device)
        logInfo("快速同步下载完成: $download")
        val upload = SyncEngine.syncUserdbUpload(store, api, device, tempDir())
        logInfo("快速同步上传完成: $upload")
        download to upload
    }

    // ---- 词库更新 ----

    suspend fun syncDicts(category: String? = null): Map<String, Any> = run { api, store ->
        val key = category ?: "all"
        logInfo("检查配置更新（类别: $key）...")
        DictSync.syncDicts(store, api, category, null, tempDir())
    }

    // ---- 完整同步 ----

    suspend fun downloadFullSync(exclude: String? = null, since: String? = null): List<String> =
        run { api, store ->
            logInfo("下载完整配置包...")
            FullSync.downloadFullSync(store, api, exclude, since, tempDir())
        }

    /** 将本地 Rime 目录打包为 tar 并上传（覆盖服务器配置）。 */
    suspend fun uploadFullSyncFromLocal(overwrite: Boolean): JsonObject = run { api, store ->
        logWarn("此操作会覆盖服务器现有配置，请谨慎操作！")
        val tarFile = File(tempDir(), "full_sync_upload.tar")
        val count = TarUtils.createTar(
            store, "", tarFile,
            excludePrefixes = setOf("sync", "full_sync.tar", "runtime_sync.tar", "dicts_update.tar"),
            excludeDotFiles = true,
        )
        if (count == 0) throw IllegalStateException("Rime 目录中没有可上传的文件")
        logInfo("打包完成，共 $count 个文件")
        try {
            FullSync.uploadFullSync(api, tarFile, overwrite)
        } finally {
            tarFile.delete()
        }
    }
}