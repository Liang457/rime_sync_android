package com.rimesync.android.core

data class FileEntry(
    val name: String,
    val isDirectory: Boolean,
    val lastModified: Long,
    val size: Long,
)

/**
 * Rime 配置目录的文件存取抽象。CLI 端直接操作本地文件系统，
 * Android 端通过 SAF (Storage Access Framework) 访问用户授权的目录。
 */
interface RimeFileStore {

    /** 列出某目录下的直接子项；空字符串表示根目录。 */
    suspend fun listChildren(dirRelPath: String): List<FileEntry>

    suspend fun exists(relPath: String): Boolean

    suspend fun isDirectory(relPath: String): Boolean

    suspend fun readBytes(relPath: String): ByteArray

    suspend fun writeBytes(relPath: String, data: ByteArray)

    suspend fun mkdirs(relPath: String)

    suspend fun delete(relPath: String)

    /** 返回毫秒时间戳，无法获取时返回 null。 */
    suspend fun lastModified(relPath: String): Long?

    suspend fun size(relPath: String): Long?

    /** 读取指定目录下的 installation.yaml，返回 installation_id（设备标识）。 */
    suspend fun readInstallationId(): String?
}

/** 递归收集某目录下的全部文件相对路径（含子目录）。 */
suspend fun RimeFileStore.walkFiles(dirRelPath: String): List<String> {
    val result = ArrayList<String>()
    val pending = ArrayDeque<String>()
    pending.addLast(dirRelPath)
    while (pending.isNotEmpty()) {
        val dir = pending.removeFirst()
        for (child in listChildren(dir)) {
            val childPath = if (dir.isEmpty()) child.name else "$dir/${child.name}"
            if (child.isDirectory) {
                pending.addLast(childPath)
            } else {
                result.add(childPath)
            }
        }
    }
    return result
}

/** 目录下文件的相对路径与大小/修改时间；listChildren 已返回这些信息，避免重复 SAF 调用。 */
data class FileStat(
    val relPath: String,
    val size: Long,
    val lastModified: Long,
)

/** 递归收集某目录下的全部文件（含 size 与 lastModified，来自一次 listChildren）。 */
suspend fun RimeFileStore.walkFilesWithStats(dirRelPath: String): List<FileStat> {
    val result = ArrayList<FileStat>()
    val pending = ArrayDeque<String>()
    pending.addLast(dirRelPath)
    while (pending.isNotEmpty()) {
        val dir = pending.removeFirst()
        for (child in listChildren(dir)) {
            val childPath = if (dir.isEmpty()) child.name else "$dir/${child.name}"
            if (child.isDirectory) {
                pending.addLast(childPath)
            } else {
                result.add(FileStat(childPath, child.size, child.lastModified))
            }
        }
    }
    return result
}