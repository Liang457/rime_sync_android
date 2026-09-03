package com.rimesync.android.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SyncEngineCacheTest {

    /** 统计 readBytes 调用次数的内存 store，用于验证缓存命中时跳过重读。 */
    private class CountingStore : InMemoryStore() {
        var readCount = 0
        override suspend fun readBytes(relPath: String): ByteArray {
            readCount++
            return super.readBytes(relPath)
        }
    }

    private fun tempCacheFile(): File = File.createTempFile("sync_cache", ".json")

    @Test
    fun computeLocalState_cacheHit_skipsRead() = runBlocking {
        val store = CountingStore()
        val rel = "sync/dev/a.txt"
        store.writeBytes(rel, "hello".toByteArray())
        store.mtimes[rel] = 1000L
        val cache = HashCache(tempCacheFile())

        val first = SyncEngine.computeLocalState(store, "dev", cache)
        val readsAfterFirst = store.readCount
        assertTrue(readsAfterFirst > 0)
        assertEquals("sha3-256:", first["a.txt"]?.hash?.substring(0, 9))

        val second = SyncEngine.computeLocalState(store, "dev", cache)
        assertEquals(readsAfterFirst, store.readCount)
        assertEquals(first, second)
    }

    @Test
    fun computeLocalState_cacheMiss_whenMtimeChanges() = runBlocking {
        val store = CountingStore()
        val rel = "sync/dev/a.txt"
        store.writeBytes(rel, "hello".toByteArray())
        store.mtimes[rel] = 1000L
        val cache = HashCache(tempCacheFile())

        SyncEngine.computeLocalState(store, "dev", cache)
        val readsAfterFirst = store.readCount

        store.mtimes[rel] = 2000L
        SyncEngine.computeLocalState(store, "dev", cache)
        assertTrue(store.readCount > readsAfterFirst)
    }

    @Test
    fun computeLocalState_zeroMtime_neverCached() = runBlocking {
        val store = CountingStore()
        store.writeBytes("sync/dev/a.txt", "hello".toByteArray())
        val cache = HashCache(tempCacheFile())

        SyncEngine.computeLocalState(store, "dev", cache)
        val readsAfterFirst = store.readCount

        SyncEngine.computeLocalState(store, "dev", cache)
        assertEquals(readsAfterFirst * 2, store.readCount)
    }
}