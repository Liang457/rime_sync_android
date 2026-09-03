package com.rimesync.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class HashCacheTest {

    private fun tempFile(): File = File.createTempFile("hash_cache_test", ".json")

    @Test
    fun get_returnsCachedHash() {
        val cache = HashCache(tempFile())
        cache.put("sync/dev/a.txt", 10, 1000, "h1")
        assertEquals("h1", cache.get("sync/dev/a.txt", 10, 1000))
    }

    @Test
    fun get_missWhenSizeChanges() {
        val cache = HashCache(tempFile())
        cache.put("sync/dev/a.txt", 10, 1000, "h1")
        assertNull(cache.get("sync/dev/a.txt", 11, 1000))
    }

    @Test
    fun get_missWhenMtimeChanges() {
        val cache = HashCache(tempFile())
        cache.put("sync/dev/a.txt", 10, 1000, "h1")
        assertNull(cache.get("sync/dev/a.txt", 10, 2000))
    }

    @Test
    fun get_missWhenUnknown() {
        val cache = HashCache(tempFile())
        assertNull(cache.get("sync/dev/a.txt", 10, 1000))
    }

    @Test
    fun put_ignoresZeroMtime() {
        val cache = HashCache(tempFile())
        cache.put("sync/dev/a.txt", 10, 0, "h1")
        assertNull(cache.get("sync/dev/a.txt", 10, 0))
    }

    @Test
    fun persistence_roundTrip() {
        val file = tempFile()
        val cache = HashCache(file)
        cache.put("sync/dev/a.txt", 10, 1000, "sha3-256:abc")
        cache.save()

        val reloaded = HashCache(file)
        assertEquals("sha3-256:abc", reloaded.get("sync/dev/a.txt", 10, 1000))
    }

    @Test
    fun corruptedJson_resetsEmpty() {
        val file = tempFile()
        file.writeText("{ not valid json !!")
        val cache = HashCache(file)
        assertNull(cache.get("sync/dev/a.txt", 10, 1000))
    }

    @Test
    fun clear_removesEntries() {
        val cache = HashCache(tempFile())
        cache.put("sync/dev/a.txt", 10, 1000, "h1")
        cache.clear()
        assertNull(cache.get("sync/dev/a.txt", 10, 1000))
    }
}