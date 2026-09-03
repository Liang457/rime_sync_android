package com.rimesync.android.core

import org.junit.Assert.assertEquals
import org.junit.Test

/** 验证 BouncyCastle 回退路径（平台不支持 SHA3 时的 fallback）产出与标准一致的哈希。 */
class HashUtilsFallbackTest {

    private fun withBcFallback(block: () -> Unit) {
        HashUtils.forceBcFallback = true
        try {
            block()
        } finally {
            HashUtils.forceBcFallback = false
        }
    }

    @Test
    fun emptyVector() = withBcFallback {
        val hash = HashUtils.computeFileHash(ByteArray(0))
        assertEquals("sha3-256:a7ffc6f8bf1ed76651c14756a061d662f580ff4de43b49fa82d80a4b80f8434a", hash)
    }

    @Test
    fun abcVector() = withBcFallback {
        val hash = HashUtils.computeFileHash("abc".toByteArray())
        assertEquals(
            "sha3-256:3a985da74fe225b2045c172d6bd390bd855f086e3e9d525b46bfe24511431532",
            hash,
        )
    }

    @Test
    fun matchesPlatformForLargeInput() {
        val data = ByteArray(20000) { (it % 251).toByte() }
        val platform = HashUtils.computeFileHash(data)
        withBcFallback {
            assertEquals(platform, HashUtils.computeFileHash(data))
        }
    }

    @Test
    fun streamMatchesWithFallback() = withBcFallback {
        val data = ByteArray(20000) { (it % 251).toByte() }
        var offset = 0
        val streamed = HashUtils.computeFileHash { buffer ->
            if (offset >= data.size) {
                -1
            } else {
                val count = minOf(buffer.size, data.size - offset)
                System.arraycopy(data, offset, buffer, 0, count)
                offset += count
                count
            }
        }
        assertEquals(HashUtils.computeFileHash(data), streamed)
    }
}