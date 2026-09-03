package cn.coolgk.rimesyncapp.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File

/**
 * 本地文件哈希缓存：以 (size, mtime) 为失效依据，命中时跳过重新读取与哈希。
 * 持久化为 JSON 文件，损坏时自动重置。仅当 size 与 mtime 同时匹配且 mtime>0 时命中（保守失效）。
 */
class HashCache(private val file: File, private val maxEntries: Int = 2000) {

    private data class Entry(val size: Long, val mtime: Long, val hash: String)

    private val entries = HashMap<String, Entry>()
    private val json = Json { ignoreUnknownKeys = true }

    init {
        load()
    }

    /** 命中返回缓存的哈希；size 或 mtime 变化、或 mtime<=0 时返回 null。 */
    @Synchronized
    fun get(path: String, size: Long, mtime: Long): String? {
        if (mtime <= 0) return null
        val cached = entries[path] ?: return null
        if (cached.size != size || cached.mtime != mtime) {
            entries.remove(path)
            return null
        }
        return cached.hash
    }

    @Synchronized
    fun put(path: String, size: Long, mtime: Long, hash: String) {
        if (mtime <= 0) return
        entries[path] = Entry(size, mtime, hash)
        if (entries.size > maxEntries) {
            // 超限时裁剪一半条目（HashMap 无序，仅作容量控制）
            val toDrop = entries.keys.take(entries.size - maxEntries / 2)
            for (k in toDrop) entries.remove(k)
        }
    }

    fun load() {
        entries.clear()
        try {
            if (!file.exists()) return
            val root = json.parseToJsonElement(file.readText(Charsets.UTF_8)).jsonObject
            for ((key, value) in root) {
                val obj = value.jsonObject
                entries[key] = Entry(
                    size = obj["size"]?.jsonPrimitive?.longOrNull ?: 0L,
                    mtime = obj["mtime"]?.jsonPrimitive?.longOrNull ?: 0L,
                    hash = obj["hash"]?.jsonPrimitive?.contentOrNull ?: "",
                )
            }
        } catch (e: Exception) {
            entries.clear()
        }
    }

    /** 原子写入（tmp + rename），失败不影响同步主流程。 */
    @Synchronized
    fun save() {
        try {
            file.parentFile?.mkdirs()
            val root = buildJsonObject {
                for ((k, v) in entries) {
                    put(k, buildJsonObject {
                        put("size", v.size)
                        put("mtime", v.mtime)
                        put("hash", v.hash)
                    })
                }
            }
            val text = root.toString()
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(text, Charsets.UTF_8)
            file.delete()
            if (!tmp.renameTo(file)) {
                file.writeText(text, Charsets.UTF_8)
            }
        } catch (e: Exception) {
            // 缓存写入失败不影响同步
        }
    }

    @Synchronized
    fun clear() {
        entries.clear()
        file.delete()
    }
}