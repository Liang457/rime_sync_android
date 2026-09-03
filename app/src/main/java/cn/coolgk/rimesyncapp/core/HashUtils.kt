package cn.coolgk.rimesyncapp.core

import org.bouncycastle.crypto.digests.SHA3Digest
import java.security.MessageDigest

object HashUtils {

    const val HASH_ALGORITHM = "sha3-256"
    private const val CHUNK_SIZE = 8192

    private val HEX_CHARS = "0123456789abcdef".toCharArray()

    /** 测试用：强制走 BouncyCastle 回退路径。 */
    @Volatile
    internal var forceBcFallback: Boolean = false

    /** 优先使用平台 SHA3 提供者；部分设备（精简 ROM）不支持时回退 BouncyCastle。 */
    private fun newDigest(): MessageDigest? = if (forceBcFallback) {
        null
    } else {
        try {
            MessageDigest.getInstance("SHA3-256")
        } catch (e: Exception) {
            null
        }
    }

    /** 计算文件 SHA3-256 哈希，格式为 `sha3-256:<hex>`，与 CLI 端一致。 */
    fun computeFileHash(data: ByteArray): String {
        val md = newDigest()
        val digest = if (md != null) {
            md.digest(data)
        } else {
            val sha = SHA3Digest(256)
            sha.update(data, 0, data.size)
            val out = ByteArray(sha.digestSize)
            sha.doFinal(out, 0)
            out
        }
        return "$HASH_ALGORITHM:${digest.toHex()}"
    }

    /** 流式计算哈希，适用于大文件。 */
    fun computeFileHash(block: (ByteArray) -> Int): String {
        val md = newDigest()
        val digest = if (md != null) {
            val buffer = ByteArray(CHUNK_SIZE)
            while (true) {
                val read = block(buffer)
                if (read < 0) break
                if (read > 0) md.update(buffer, 0, read)
            }
            md.digest()
        } else {
            val sha = SHA3Digest(256)
            val buffer = ByteArray(CHUNK_SIZE)
            while (true) {
                val read = block(buffer)
                if (read < 0) break
                if (read > 0) sha.update(buffer, 0, read)
            }
            val out = ByteArray(sha.digestSize)
            sha.doFinal(out, 0)
            out
        }
        return "$HASH_ALGORITHM:${digest.toHex()}"
    }

    /** 查表法十六进制编码，避免逐字节 String.format 的开销（约 18x 提速）。 */
    private fun ByteArray.toHex(): String {
        val chars = CharArray(size * 2)
        for (i in indices) {
            val b = this[i].toInt() and 0xFF
            chars[i * 2] = HEX_CHARS[b ushr 4]
            chars[i * 2 + 1] = HEX_CHARS[b and 0x0F]
        }
        return String(chars)
    }
}