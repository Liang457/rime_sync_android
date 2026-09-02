package com.rimesync.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SafePathTest {

    @Test
    fun normalize_simple() {
        assertEquals("a/b/c.txt", SafePath.normalize("a/b/c.txt"))
    }

    @Test
    fun normalize_backslash() {
        assertEquals("a/b", SafePath.normalize("a\\b"))
    }

    @Test
    fun normalize_leadingSlashRejected() {
        assertThrows(PathTraversalException::class.java) {
            SafePath.normalize("/etc/passwd")
        }
    }

    @Test
    fun normalize_dotDotRejected() {
        assertThrows(PathTraversalException::class.java) {
            SafePath.normalize("a/../../b")
        }
    }

    @Test
    fun normalize_driveLetterRejected() {
        assertThrows(PathTraversalException::class.java) {
            SafePath.normalize("C:/Windows")
        }
    }

    @Test
    fun validateFileName_normal() {
        SafePath.validateFileName("userdict.txt")
    }

    @Test
    fun validateFileName_withSlashRejected() {
        assertThrows(PathTraversalException::class.java) {
            SafePath.validateFileName("a/b.txt")
        }
    }

    @Test
    fun validateFileName_dotDotRejected() {
        assertThrows(PathTraversalException::class.java) {
            SafePath.validateFileName("..")
        }
    }
}