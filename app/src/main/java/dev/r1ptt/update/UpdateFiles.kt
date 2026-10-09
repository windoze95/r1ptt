package dev.r1ptt.update

import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest

/** A partial transfer is never exposed as an installable APK; every exit removes the partial file. */
object UpdateFiles {
    fun receive(input: InputStream, directory: File, release: ReleaseManifest, cancelled: () -> Boolean): File {
        directory.mkdirs()
        val partial = File(directory, "download.part")
        val ready = File(directory, "verified.apk")
        ready.delete()
        try {
            val hash = MessageDigest.getInstance("SHA-256")
            var count = 0L
            FileOutputStream(partial).use { out ->
                val buffer = ByteArray(32 * 1024)
                while (true) {
                    if (cancelled()) throw UpdateException("Update download cancelled.")
                    val n = input.read(buffer)
                    if (n < 0) break
                    count += n
                    if (count > release.apkSize || count > ReleaseManifest.MAX_APK)
                        throw UpdateException("Update download exceeded its signed size.")
                    hash.update(buffer, 0, n)
                    out.write(buffer, 0, n)
                }
                out.fd.sync()
            }
            val digest = hash.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
            if (cancelled() || count != release.apkSize || digest != release.apkSha256)
                throw UpdateException("Update download was incomplete or failed its checksum.")
            if (!partial.renameTo(ready)) throw UpdateException("Could not save the verified update.")
            return ready
        } finally {
            partial.delete()
        }
    }

    fun verify(file: File, release: ReleaseManifest) {
        if (!file.isFile || file.length() != release.apkSize) throw UpdateException("Downloaded update is missing or incomplete.")
        val hash = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(32 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                hash.update(buffer, 0, n)
            }
        }
        val actual = hash.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        if (actual != release.apkSha256) throw UpdateException("Downloaded update failed its checksum.")
    }
}
