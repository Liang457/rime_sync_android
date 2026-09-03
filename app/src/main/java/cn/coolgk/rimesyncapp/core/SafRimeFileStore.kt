package cn.coolgk.rimesyncapp.core

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.IOException

/**
 * 基于 SAF 的 [RimeFileStore] 实现。
 * 使用用户通过 `ACTION_OPEN_DOCUMENT_TREE` 授权的目录 Uri。
 */
class SafRimeFileStore(private val treeUri: Uri) : RimeFileStore {

    private val rootDocument: DocumentFile by lazy { DocumentFile.fromTreeUri(context, treeUri)!! }

    companion object {
        lateinit var context: Context
            private set

        fun init(appContext: Context) {
            context = appContext.applicationContext
        }

        /** 校验持久化授权是否仍有效。 */
        fun hasPersistedPermission(uri: Uri): Boolean {
            return try {
                val flags = context.contentResolver.persistedUriPermissions
                    .any { it.uri == uri && it.isReadPermission && it.isWritePermission }
                flags
            } catch (e: Exception) {
                false
            }
        }
    }

    /** 沿路径逐段解析 DocumentFile。 */
    private fun resolve(relPath: String): DocumentFile? {
        val normalized = SafePath.normalize(relPath)
        if (normalized.isEmpty()) return rootDocument
        var current: DocumentFile = rootDocument
        for (segment in normalized.split('/')) {
            current = current.findFile(segment) ?: return null
        }
        return current
    }

    private fun resolveOrThrow(relPath: String): DocumentFile {
        return resolve(relPath) ?: throw IOException("路径不存在: $relPath")
    }

    override suspend fun listChildren(dirRelPath: String): List<FileEntry> {
        val dir = resolve(dirRelPath) ?: return emptyList()
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles().map {
            FileEntry(
                name = it.name ?: "",
                isDirectory = it.isDirectory,
                lastModified = it.lastModified(),
                size = it.length(),
            )
        }
    }

    override suspend fun exists(relPath: String): Boolean = resolve(relPath) != null

    override suspend fun isDirectory(relPath: String): Boolean =
        resolve(relPath)?.isDirectory == true

    override suspend fun readBytes(relPath: String): ByteArray {
        val doc = resolveOrThrow(relPath)
        if (doc.isDirectory) throw IOException("目标是目录: $relPath")
        return context.contentResolver.openInputStream(doc.uri)?.use { it.readBytes() }
            ?: throw IOException("无法打开文件: $relPath")
    }

    override suspend fun writeBytes(relPath: String, data: ByteArray) {
        val normalized = SafePath.normalize(relPath)
        val parts = normalized.split('/')
        val fileName = parts.last()
        SafePath.validateFileName(fileName)
        val parentPath = parts.dropLast(1).joinToString("/")

        var parent = if (parentPath.isEmpty()) rootDocument else resolve(parentPath)
            ?: mkdirs(parentPath).let { resolve(parentPath)!! }

        var doc = parent.findFile(fileName)
        if (doc == null) {
            doc = parent.createFile("application/octet-stream", fileName)
                ?: throw IOException("无法创建文件: $relPath")
        }
        context.contentResolver.openOutputStream(doc.uri, "wt")?.use { out ->
            out.write(data)
        } ?: throw IOException("无法写入文件: $relPath")
    }

    override suspend fun mkdirs(relPath: String) {
        if (relPath.isBlank()) return
        val normalized = SafePath.normalize(relPath)
        var current: DocumentFile = rootDocument
        for (segment in normalized.split('/')) {
            var next = current.findFile(segment)
            if (next == null) {
                next = current.createDirectory(segment)
                    ?: throw IOException("无法创建目录: $segment")
            }
            current = next
        }
    }

    override suspend fun delete(relPath: String) {
        val doc = resolve(relPath) ?: return
        doc.delete()
    }

    override suspend fun lastModified(relPath: String): Long? {
        return try {
            val doc = resolve(relPath) ?: return null
            val v = doc.lastModified()
            if (v > 0) v else null
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun size(relPath: String): Long? {
        return try {
            val doc = resolve(relPath) ?: return null
            val v = doc.length()
            if (v >= 0) v else null
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun readInstallationId(): String? {
        val file = resolve("installation.yaml") ?: return null
        val content = context.contentResolver.openInputStream(file.uri)?.use { it.readBytes() }
            ?: return null
        return try {
            @Suppress("UNCHECKED_CAST")
            val map = org.yaml.snakeyaml.Yaml().load<Any>(content.toString(Charsets.UTF_8)) as? Map<Any?, Any?>
            map?.get("installation_id")?.toString()
        } catch (e: Exception) {
            null
        }
    }
}