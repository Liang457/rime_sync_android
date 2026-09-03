package cn.coolgk.rimesyncapp.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TarUtilsTest {

    @Test
    fun tarRoundTrip() {
        val store = InMemoryStore()
        store.files["dicts/cn.txt"] = "中文".toByteArray()
        store.files["dicts/sub/en.txt"] = "english".toByteArray()

        val tarFile = File.createTempFile("test", ".tar")
        tarFile.deleteOnExit()
        kotlinx.coroutines.runBlocking {
            val count = TarUtils.createTar(store, "dicts", tarFile)
            assertEquals(2, count)

            val target = InMemoryStore()
            val extracted = TarUtils.extractTar(tarFile, target, "")
            assertEquals(2, extracted.size)
            assertEquals("中文", String(target.files["cn.txt"]!!))
            assertEquals("english", String(target.files["sub/en.txt"]!!))
        }
    }

    @Test
    fun extractRejectsPathTraversal() {
        val store = InMemoryStore()
        store.files["a.txt"] = "x".toByteArray()
        val tarFile = File.createTempFile("test", ".tar")
        tarFile.deleteOnExit()
        kotlinx.coroutines.runBlocking { TarUtils.createTar(store, "", tarFile) }

        // 构造带 .. 的恶意 tar
        val evil = File.createTempFile("evil", ".tar")
        evil.deleteOnExit()
        org.apache.commons.compress.archivers.tar.TarArchiveOutputStream(
            java.io.BufferedOutputStream(java.io.FileOutputStream(evil))
        ).use { out ->
            val entry = org.apache.commons.compress.archivers.tar.TarArchiveEntry("../../evil.txt")
            entry.size = 1
            out.putArchiveEntry(entry)
            out.write("x".toByteArray())
            out.closeArchiveEntry()
        }

        val target = InMemoryStore()
        val thrown = try {
            kotlinx.coroutines.runBlocking { TarUtils.extractTar(evil, target, "") }
            null
        } catch (e: Exception) {
            e
        }
        assertTrue("应拒绝路径遍历", thrown is PathTraversalException)
    }
}