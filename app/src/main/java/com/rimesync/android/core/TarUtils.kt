package com.rimesync.android.core

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

object TarUtils {

    private const val BUFFER_SIZE = 64 * 1024

    /** 将 store 中某目录下的全部文件打包为 tar，写入 [targetFile]。 */
    suspend fun createTar(
        store: RimeFileStore,
        dirRelPath: String,
        targetFile: File,
        excludePrefixes: Set<String> = emptySet(),
        excludeDotFiles: Boolean = false,
    ): Int {
        val files = store.walkFiles(dirRelPath).filter { rel ->
            val relPath = rel.removePrefix(if (dirRelPath.isEmpty()) "" else "$dirRelPath/")
            if (excludeDotFiles && relPath.startsWith(".")) return@filter false
            excludePrefixes.none { prefix -> relPath == prefix || relPath.startsWith("$prefix/") }
        }
        if (files.isEmpty()) return 0
        val rootPrefix = if (dirRelPath.isEmpty()) "" else "$dirRelPath/"

        TarArchiveOutputStream(BufferedOutputStream(FileOutputStream(targetFile))).use { tarOut ->
            for (file in files) {
                val arcName = file.removePrefix(rootPrefix)
                val data = store.readBytes(file)
                val entry = TarArchiveEntry(arcName)
                entry.size = data.size.toLong()
                tarOut.putArchiveEntry(entry)
                tarOut.write(data)
                tarOut.closeArchiveEntry()
            }
        }
        return files.size
    }

    /**
     * 将 tar 文件安全解压到 store 的指定目录下。
     * 仅解压普通文件，跳过目录/符号链接/设备等；拒绝绝对路径与 `..` 穿越。
     */
    suspend fun extractTar(tarFile: File, store: RimeFileStore, dirRelPath: String): List<String> {
        val extracted = ArrayList<String>()
        val base = SafePath.normalize(dirRelPath)

        try {
            TarArchiveInputStream(BufferedInputStream(FileInputStream(tarFile))).use { tarIn ->
                var entry: TarArchiveEntry? = tarIn.nextEntry
                while (entry != null) {
                    val name = entry.name
                    if (entry.isFile) {
                        val normalized = SafePath.normalize(name)
                        val target = if (base.isEmpty()) normalized else "$base/$normalized"
                        val data = tarIn.readBytes()
                        store.writeBytes(target, data)
                        extracted.add(target)
                    }
                    entry = tarIn.nextEntry
                }
            }
            return extracted
        } catch (e: PathTraversalException) {
            throw e
        } catch (e: Exception) {
            throw RimeSyncException("解压tar文件失败: ${e.message}", e)
        }
    }

    /** 将字节安全地保存到 store 的指定目录下，返回最终相对路径。 */
    suspend fun saveBytes(store: RimeFileStore, baseDir: String, fileName: String, data: ByteArray): String {
        SafePath.validateFileName(fileName)
        val base = SafePath.normalize(baseDir)
        val target = if (base.isEmpty()) fileName else "$base/$fileName"
        store.writeBytes(target, data)
        return target
    }
}