package dev.r1ptt.update

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.io.InputStream

class UpdateFilesTest {
    @get:Rule val temp = TemporaryFolder()
    private val bytes = "APK fixture only".toByteArray()
    private fun release() = ReleaseManifest(2000, "0.2.0", "v0.2.0", ReleaseManifest.sha256(bytes),
        bytes.size.toLong(), "a".repeat(64), 33, "b".repeat(40))
    private fun noStagedFiles(dir: File) {
        assertFalse(File(dir, "download.part").exists())
        assertFalse(File(dir, "verified.apk").exists())
    }
    @Test fun exactSignedTransferBecomesReadyAndCanBeRechecked() {
        val dir = temp.newFolder()
        val file = UpdateFiles.receive(bytes.inputStream(), dir, release()) { false }
        assertArrayEquals(bytes, file.readBytes())
        assertFalse(File(dir, "download.part").exists())
        UpdateFiles.verify(file, release())
    }
    @Test fun truncationAndWrongHashNeverLeaveAnInstallableFile() {
        for (input in listOf(bytes.dropLast(1).toByteArray(), ByteArray(bytes.size))) {
            val dir = temp.newFolder()
            assertThrows(UpdateException::class.java) { UpdateFiles.receive(input.inputStream(), dir, release()) { false } }
            noStagedFiles(dir)
        }
    }
    @Test fun oversizedStreamIsStoppedAtSignedLimit() {
        val dir = temp.newFolder()
        assertThrows(UpdateException::class.java) { UpdateFiles.receive((bytes + 0).inputStream(), dir, release()) { false } }
        noStagedFiles(dir)
    }
    @Test fun offlineOrInterruptedStreamCleansPartialAndOldReadyFile() {
        val dir = temp.newFolder()
        File(dir, "verified.apk").writeText("old")
        val input = object : InputStream() {
            override fun read(): Int = throw IOException("offline fixture")
        }
        assertThrows(IOException::class.java) { UpdateFiles.receive(input, dir, release()) { false } }
        noStagedFiles(dir)
    }
    @Test fun cancellationDuringTransferRemovesPartial() {
        val dir = temp.newFolder()
        var cancelled = false
        val input = object : java.io.ByteArrayInputStream(bytes) {
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                val result = super.read(b, off, len)
                cancelled = true
                return result
            }
        }
        assertThrows(UpdateException::class.java) { UpdateFiles.receive(input, dir, release()) { cancelled } }
        noStagedFiles(dir)
    }
    @Test fun changedOrMissingFileIsRejectedBeforeStaging() {
        val dir = temp.newFolder()
        val file = UpdateFiles.receive(bytes.inputStream(), dir, release()) { false }
        file.writeBytes(ByteArray(bytes.size))
        assertThrows(UpdateException::class.java) { UpdateFiles.verify(file, release()) }
        file.delete()
        assertThrows(UpdateException::class.java) { UpdateFiles.verify(file, release()) }
    }
}
