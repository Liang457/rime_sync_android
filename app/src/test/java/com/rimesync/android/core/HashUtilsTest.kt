package com.rimesync.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HashUtilsTest {

    @Test
    fun computeFileHash_empty() {
        val hash = HashUtils.computeFileHash(ByteArray(0))
        assertTrue(hash.startsWith("sha3-256:"))
        // SHA3-256 空输入标准摘要
        assertEquals("sha3-256:a7ffc6f8bf1ed76651c14756a061d662f580ff4de43b49fa82d80a4b80f8434a", hash)
    }

    @Test
    fun computeFileHash_knownValue() {
        // "abc"
        val hash = HashUtils.computeFileHash("abc".toByteArray())
        assertEquals(
            "sha3-256:3a985da74fe225b2045c172d6bd390bd855f086e3e9d525b46bfe24511431532",
            hash,
        )
    }

    @Test
    fun computeFileHash_streamMatches() {
        val data = ByteArray(20000) { (it % 251).toByte() }
        val oneShot = HashUtils.computeFileHash(data)
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
        assertEquals(oneShot, streamed)
    }
}