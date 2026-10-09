package dev.r1ptt.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

class AtomicConfigFileTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun missingFileIsAnEmptyStore() {
        assertNull(AtomicConfigFile(File(temporary.root, "config.enc")).read())
    }

    @Test
    fun committedWritesCanBeReadByANewInstance() {
        val file = File(temporary.root, "config.enc")
        val store = AtomicConfigFile(file)

        store.write("v1:old-iv:old-encrypted-data")
        assertEquals("v1:old-iv:old-encrypted-data", AtomicConfigFile(file).read())
        store.write("v1:new-iv:new-encrypted-data")
        assertEquals("v1:new-iv:new-encrypted-data", AtomicConfigFile(file).read())
        assertEquals(listOf("config.enc"), temporary.root.list()!!.toList())
    }

    @Test
    fun unreadableExistingPathThrowsInsteadOfReportingEmpty() {
        val file = temporary.newFolder("config.enc")

        expectIoFailure { AtomicConfigFile(file).read() }

        assertTrue(file.isDirectory)
    }

    @Test
    fun failedAtomicRenamePreservesExistingDestinationAndRemovesPendingFile() {
        val file = temporary.newFolder("config.enc")
        val original = File(file, "previous-encrypted-data").apply { writeText("keep") }

        expectIoFailure { AtomicConfigFile(file).write("v1:new-iv:new-data") }

        assertEquals("keep", original.readText())
        assertEquals(listOf("config.enc"), temporary.root.list()!!.toList())
    }

    @Test
    fun failedReplacementKeepsTheOldEncryptedFileAndCanBeRetried() {
        val file = File(temporary.root, "config.enc")
        AtomicConfigFile(file).write("v1:old-iv:old-encrypted-data")
        var fail = true
        val store = AtomicConfigFile(file) { pending, destination ->
            if (fail) throw IOException("Simulated atomic replacement failure")
            Files.move(pending, destination, ATOMIC_MOVE, REPLACE_EXISTING)
        }

        expectIoFailure { store.write("v1:new-iv:new-encrypted-data") }

        assertEquals("v1:old-iv:old-encrypted-data", AtomicConfigFile(file).read())
        assertEquals(listOf("config.enc"), temporary.root.list()!!.toList())
        fail = false
        store.write("v1:new-iv:new-encrypted-data")
        assertEquals("v1:new-iv:new-encrypted-data", AtomicConfigFile(file).read())
    }

    @Test
    fun blockedParentPathFailsBeforeReplacingAnything() {
        val parent = temporary.newFile("blocked").apply { writeText("keep") }

        expectIoFailure { AtomicConfigFile(File(parent, "config.enc")).write("v1:new-iv:new-data") }

        assertEquals("keep", parent.readText())
    }

    private fun expectIoFailure(action: () -> Unit) {
        try {
            action()
        } catch (_: IOException) {
            return
        }
        throw AssertionError("Expected a filesystem failure")
    }
}
