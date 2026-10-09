package dev.r1ptt.data

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

/** Private encrypted file, replaced only after the complete new contents have been synced. */
internal class AtomicConfigFile(
    private val file: File,
    private val replace: (Path, Path) -> Unit = { pending, destination ->
        Files.move(pending, destination, ATOMIC_MOVE, REPLACE_EXISTING)
    },
) : ConfigBlobStorage {
    @Synchronized
    override fun read(): String? = try {
        String(Files.readAllBytes(file.toPath()), Charsets.UTF_8)
    } catch (_: NoSuchFileException) {
        null
    }

    @Synchronized
    override fun write(blob: String) {
        val destination = file.toPath()
        val directory = requireNotNull(destination.parent) { "Config file requires a parent directory" }
        Files.createDirectories(directory)
        val pending = Files.createTempFile(directory, ".config-", ".tmp")
        try {
            FileOutputStream(pending.toFile()).use { stream ->
                stream.write(blob.toByteArray(Charsets.UTF_8))
                stream.fd.sync()
            }
            // There is no fallback to a non-atomic replacement. A filesystem that cannot provide
            // atomic renames must report a failed save while keeping the prior file recoverable.
            replace(pending, destination)
        } finally {
            // Cleanup failure must not report a committed rename as a failed transaction.
            runCatching { Files.deleteIfExists(pending) }
        }
    }
}
