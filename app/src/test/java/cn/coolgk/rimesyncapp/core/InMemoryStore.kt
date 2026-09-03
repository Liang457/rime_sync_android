package cn.coolgk.rimesyncapp.core

/** 内存版 [RimeFileStore]，用于 JVM 单元测试。 */
open class InMemoryStore : RimeFileStore {

    val files = HashMap<String, ByteArray>()

    /** 可选的 mtime 覆盖表；未设置时 lastModified 返回 0。 */
    val mtimes = HashMap<String, Long>()

    override suspend fun listChildren(dirRelPath: String): List<FileEntry> {
        val prefix = SafePath.normalize(dirRelPath)
        val prefixPath = if (prefix.isEmpty()) "" else "$prefix/"
        val children = HashMap<String, FileEntry>()
        for ((key, data) in files) {
            if (!key.startsWith(prefixPath)) continue
            val rest = key.removePrefix(prefixPath)
            if (rest.isEmpty()) continue
            val first = rest.substringBefore('/')
            val isDir = '/' in rest
            children[first] = FileEntry(
                name = first,
                isDirectory = isDir,
                lastModified = mtimes[key] ?: 0L,
                size = if (isDir) 0 else data.size.toLong(),
            )
        }
        return children.values.toList()
    }

    override suspend fun exists(relPath: String): Boolean = files.containsKey(SafePath.normalize(relPath))

    override suspend fun isDirectory(relPath: String): Boolean {
        val prefix = SafePath.normalize(relPath)
        val prefixPath = if (prefix.isEmpty()) "" else "$prefix/"
        return files.keys.any { it.startsWith(prefixPath) }
    }

    override suspend fun readBytes(relPath: String): ByteArray {
        val normalized = SafePath.normalize(relPath)
        return files[normalized] ?: throw java.io.IOException("不存在: $relPath")
    }

    override suspend fun writeBytes(relPath: String, data: ByteArray) {
        files[SafePath.normalize(relPath)] = data
    }

    override suspend fun mkdirs(relPath: String) = Unit

    override suspend fun delete(relPath: String) {
        files.remove(SafePath.normalize(relPath))
    }

    override suspend fun lastModified(relPath: String): Long? = mtimes[SafePath.normalize(relPath)] ?: 0L

    override suspend fun size(relPath: String): Long? = files[SafePath.normalize(relPath)]?.size?.toLong()

    override suspend fun readInstallationId(): String? = null
}