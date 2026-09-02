package com.rimesync.android.core

import java.security.MessageDigest

object HashUtils {

    const val HASH_ALGORITHM = "sha3-256"
    private const val CHUNK_SIZE = 8192

    /** 计算文件 SHA3-256 哈希，格式为 `sha3-256:<hex>`，与 CLI 端一致。 */
    fun computeFileHash(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA3-256")
        return "$HASH_ALGORITHM:${digest.digest(data).toHex()}"
    }

    /** 流式计算哈希，适用于大文件。 */
    fun computeFileHash(block: (ByteArray) -> Int): String {
        val digest = MessageDigest.getInstance("SHA3-256")
        val buffer = ByteArray(CHUNK_SIZE)
        while (true) {
            val read = block(buffer)
            if (read < 0) break
            if (read > 0) digest.update(buffer, 0, read)
        }
        return "$HASH_ALGORITHM:${digest.digest().toHex()}"
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}