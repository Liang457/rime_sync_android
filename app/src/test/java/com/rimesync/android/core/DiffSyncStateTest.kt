package com.rimesync.android.core

import org.junit.Assert.assertEquals
import org.junit.Test

class DiffSyncStateTest {

    private fun state(vararg names: String): Map<String, FileState> =
        names.associateWith { FileState(hash = "h-$it", size = 1, modified = "2026-01-01T00:00:00Z") }

    @Test
    fun onlyLocalFiles_upload() {
        val local = state("a.txt", "b.txt")
        val remote = state("a.txt")
        val (toUpload, toDownload) = SyncEngine.diffSyncState(local, remote)
        assertEquals(listOf("b.txt"), toUpload)
        assertEquals(emptyList<String>(), toDownload)
    }

    @Test
    fun onlyRemoteFiles_download() {
        val local = state("a.txt")
        val remote = state("a.txt", "b.txt")
        val (toUpload, toDownload) = SyncEngine.diffSyncState(local, remote)
        assertEquals(emptyList<String>(), toUpload)
        assertEquals(listOf("b.txt"), toDownload)
    }

    @Test
    fun sameHash_noAction() {
        val local = mapOf("a.txt" to FileState("sha3-256:abc", 1, "2026-01-01T00:00:00Z"))
        val remote = mapOf("a.txt" to FileState("sha3-256:abc", 1, "2026-01-01T00:00:01Z"))
        val (toUpload, toDownload) = SyncEngine.diffSyncState(local, remote)
        assertEquals(emptyList<String>(), toUpload)
        assertEquals(emptyList<String>(), toDownload)
    }

    @Test
    fun differentHash_localNewer_upload() {
        val local = mapOf("a.txt" to FileState("h1", 1, "2026-01-02T00:00:00Z"))
        val remote = mapOf("a.txt" to FileState("h2", 1, "2026-01-01T00:00:00Z"))
        val (toUpload, toDownload) = SyncEngine.diffSyncState(local, remote)
        assertEquals(listOf("a.txt"), toUpload)
        assertEquals(emptyList<String>(), toDownload)
    }

    @Test
    fun differentHash_remoteNewer_download() {
        val local = mapOf("a.txt" to FileState("h1", 1, "2026-01-01T00:00:00Z"))
        val remote = mapOf("a.txt" to FileState("h2", 1, "2026-01-02T00:00:00Z"))
        val (toUpload, toDownload) = SyncEngine.diffSyncState(local, remote)
        assertEquals(emptyList<String>(), toUpload)
        assertEquals(listOf("a.txt"), toDownload)
    }

    @Test
    fun sameMtime_bothWays() {
        val local = mapOf("a.txt" to FileState("h1", 1, "2026-01-01T00:00:00Z"))
        val remote = mapOf("a.txt" to FileState("h2", 1, "2026-01-01T00:00:00Z"))
        val (toUpload, toDownload) = SyncEngine.diffSyncState(local, remote)
        assertEquals(listOf("a.txt"), toUpload)
        assertEquals(listOf("a.txt"), toDownload)
    }

    @Test
    fun unparseableMtime_bothWays() {
        val local = mapOf("a.txt" to FileState("h1", 1, "not-a-date"))
        val remote = mapOf("a.txt" to FileState("h2", 1, "2026-01-01T00:00:00Z"))
        val (toUpload, toDownload) = SyncEngine.diffSyncState(local, remote)
        assertEquals(listOf("a.txt"), toUpload)
        assertEquals(listOf("a.txt"), toDownload)
    }
}

class TimeUtilsTest {

    @Test
    fun parseIsoWithZ() {
        val instant = TimeUtils.safeParseIso("2026-01-01T00:00:00Z")
        assertEquals("2026-01-01T00:00:00Z", instant.toString())
    }

    @Test
    fun parseIsoWithOffset() {
        val instant = TimeUtils.safeParseIso("2026-01-01T08:00:00+08:00")
        assertEquals("2026-01-01T00:00:00Z", instant.toString())
    }

    @Test
    fun parseIsoInvalid() {
        assertEquals(null, TimeUtils.safeParseIso("garbage"))
        assertEquals(null, TimeUtils.safeParseIso(null))
    }
}