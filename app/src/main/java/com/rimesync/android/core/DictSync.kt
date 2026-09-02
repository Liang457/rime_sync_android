package com.rimesync.android.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.time.Instant

/** 各类别配置的前缀映射。 */
object DictCategories {
    val PREFIXES = mapOf(
        "cn" to "cn_dicts/",
        "en" to "en_dicts/",
        "lua" to "lua/",
        "opencc" to "opencc/",
    )
}

/** 同步状态跟踪（`.sync_state.json`），使用服务器时钟。 */
class SyncState(private val store: RimeFileStore) {

    private suspend fun load(): Map<String, String> {
        return try {
            val bytes = store.readBytes(".sync_state.json")
            val json = org.json.JSONObject(bytes.toString(Charsets.UTF_8))
            val keys = json.keys()
            val result = HashMap<String, String>()
            while (keys.hasNext()) {
                val key = keys.next()
                result[key] = json.getString(key)
            }
            result
        } catch (e: Exception) {
            emptyMap()
        }
    }

    private suspend fun save(state: Map<String, String>) {
        val json = org.json.JSONObject()
        for ((k, v) in state) json.put(k, v)
        store.writeBytes(".sync_state.json", json.toString(2).toByteArray(Charsets.UTF_8))
    }

    suspend fun getLastSync(key: String = "all"): String? = load()[key]

    suspend fun setLastSync(key: String, timestamp: String) {
        val state = load().toMutableMap()
        state[key] = timestamp
        save(state)
    }
}

/** 词库/配置增量同步引擎，行为与 CLI 的 core/dicts.py 对齐。 */
object DictSync {

    private suspend fun filterFiles(files: JsonArray, category: String?): List<JsonObject> {
        if (category == null || category == "all") return files.mapNotNull { it as? JsonObject }
        val prefix = DictCategories.PREFIXES[category] ?: return files.mapNotNull { it as? JsonObject }
        return files.mapNotNull { it as? JsonObject }
            .filter { (it.str("path") ?: "").startsWith(prefix) }
    }

    private suspend fun checkChanges(store: RimeFileStore, serverFiles: List<JsonObject>): List<String> {
        val changed = ArrayList<String>()
        for (remote in serverFiles) {
            val relPath = remote.str("path") ?: continue
            val remoteHash = remote.str("hash") ?: continue
            if (!store.exists(relPath)) {
                changed.add(relPath)
                continue
            }
            try {
                val localHash = HashUtils.computeFileHash(store.readBytes(relPath))
                if (localHash != remoteHash) changed.add(relPath)
            } catch (e: Exception) {
                changed.add(relPath)
            }
        }
        return changed
    }

    suspend fun syncDicts(
        store: RimeFileStore,
        api: ApiClient,
        category: String? = null,
        since: String? = null,
        tempDir: File,
    ): Map<String, Any> {
        val state = SyncState(store)
        val stateKey = category ?: "all"

        val effectiveSince = since ?: state.getLastSync(stateKey)

        val info = api.getFullSyncInfo(since = effectiveSince)
        val data = info.obj("data")
        val serverFiles = filterFiles(data?.arr("files") ?: JsonArray(emptyList()), category)

        if (serverFiles.isEmpty()) {
            return mapOf("files" to 0, "changed" to 0)
        }

        val changedFiles = checkChanges(store, serverFiles)
        if (changedFiles.isEmpty()) {
            return mapOf("files" to serverFiles.size, "changed" to 0)
        }

        val tarFile = File(tempDir, "runtime_sync.tar")
        api.downloadFullSyncTar(since = effectiveSince, target = tarFile)
        try {
            val extracted = TarUtils.extractTar(tarFile, store, "")
            val serverTs = data?.str("timestamp") ?: Instant.now().toString()
            state.setLastSync(stateKey, serverTs)
            return mapOf(
                "files" to serverFiles.size,
                "changed" to changedFiles.size,
                "extracted" to extracted.size,
            )
        } finally {
            tarFile.delete()
        }
    }
}

/** 完整同步引擎，行为与 CLI 的 core/fullsync.py 对齐。 */
object FullSync {

    suspend fun downloadFullSync(
        store: RimeFileStore,
        api: ApiClient,
        exclude: String? = null,
        since: String? = null,
        tempDir: File,
    ): List<String> {
        val tarFile = File(tempDir, "full_sync.tar")
        api.downloadFullSyncTar(exclude, since, tarFile)
        try {
            return TarUtils.extractTar(tarFile, store, "")
        } finally {
            tarFile.delete()
        }
    }

    suspend fun uploadFullSync(api: ApiClient, file: File, overwrite: Boolean): JsonObject {
        return api.uploadFullSync(file, overwrite)
    }
}