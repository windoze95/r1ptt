package dev.r1ptt.update

import org.json.JSONObject
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Signature
import java.util.Base64

class UpdateException(message: String) : Exception(message)

data class ReleaseManifest(
    val versionCode: Long,
    val versionName: String,
    val tag: String,
    val apkSha256: String,
    val apkSize: Long,
    val certificateSha256: String,
    val minSdk: Int,
    val commitSha: String,
) {
    val apkUrl get() = "https://github.com/$REPOSITORY/releases/download/$tag/robotOS.apk"

    companion object {
        const val REPOSITORY = "windoze95/robotOS"
        const val PACKAGE = "dev.r1ptt"
        const val METADATA_URL = "https://github.com/$REPOSITORY/releases/latest/download/update.json"
        const val MAX_METADATA = 32 * 1024
        const val MAX_APK = 64L * 1024 * 1024
        private val hex = Regex("[0-9a-f]{64}")
        private val fields = setOf("schema", "repository", "packageName", "versionCode", "versionName",
            "tag", "apkName", "apkSha256", "apkSize", "certificateSha256", "minSdk", "commitSha")

        /** Authenticate the exact payload bytes BEFORE interpreting any release-controlled field. */
        fun verify(bytes: ByteArray, key: PublicKey, installedCertificate: String): ReleaseManifest = try {
            require(bytes.size in 1..MAX_METADATA)
            val envelope = JSONObject(String(bytes, Charsets.UTF_8))
            require(envelope.keys().asSequence().toSet() == setOf("payload", "signature"))
            val payload = Base64.getDecoder().decode(envelope.getString("payload"))
            val signature = Base64.getDecoder().decode(envelope.getString("signature"))
            require(payload.size in 1..8192 && signature.size in 1..1024)
            val algorithm = when (key.algorithm) {
                "RSA" -> "SHA256withRSA"
                "EC" -> "SHA256withECDSA"
                else -> throw IllegalArgumentException("Unsupported signing key")
            }
            require(Signature.getInstance(algorithm).run {
                initVerify(key)
                update(payload)
                verify(signature)
            })
            val j = JSONObject(String(payload, Charsets.UTF_8))
            require(j.keys().asSequence().toSet() == fields)
            fun number(name: String): Long {
                val v = j.get(name)
                require(v is Int || v is Long) // no string coercion, fractions or overflowing doubles
                return (v as Number).toLong()
            }
            fun string(name: String): String = (j.get(name) as? String) ?: error("Expected string")
            require(number("schema") == 1L && string("repository") == REPOSITORY)
            require(string("packageName") == PACKAGE && string("apkName") == "robotOS.apk")
            val tag = string("tag")
            val code = releaseVersionCode(tag)
            require(number("versionCode") == code && string("versionName") == tag.drop(1))
            val digest = string("apkSha256")
            val cert = string("certificateSha256")
            val commit = string("commitSha")
            val size = number("apkSize")
            val minSdk = number("minSdk")
            require(hex.matches(digest) && hex.matches(cert) && cert == installedCertificate)
            require(Regex("[0-9a-f]{40}").matches(commit))
            require(size in 1..MAX_APK && minSdk in 33..Int.MAX_VALUE.toLong())
            ReleaseManifest(code, tag.drop(1), tag, digest, size, cert, minSdk.toInt(), commit)
        } catch (_: Exception) {
            throw UpdateException("Update metadata could not be authenticated. Nothing was installed.")
        }

        fun releaseVersionCode(tag: String): Long {
            val m = Regex("v(0|[1-9][0-9]{0,3})\\.(0|[1-9][0-9]{0,2})\\.(0|[1-9][0-9]{0,2})").matchEntire(tag)
                ?: throw IllegalArgumentException("Expected vMAJOR.MINOR.PATCH")
            val (major, minor, patch) = m.destructured
            require(major.toInt() <= 2099)
            return (major.toLong() * 1_000_000 + minor.toLong() * 1_000 + patch.toLong()).also {
                require(it in 2..2_099_999_999)
            }
        }

        fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}

/** Shared pure checks, applied after metadata authentication and again immediately before staging. */
object UpdatePolicy {
    fun newer(release: ReleaseManifest, installed: Long, highestSeen: Long, sdk: Int): Boolean {
        if (release.versionCode < maxOf(installed, highestSeen))
            throw UpdateException("An older release was returned. Downgrades are blocked.")
        if (release.minSdk > sdk) throw UpdateException("This update needs a newer Android version.")
        return release.versionCode > installed
    }

    fun apkMatches(release: ReleaseManifest, packageName: String, code: Long, name: String?,
                   certificateDigests: List<String>, minSdk: Int) {
        if (packageName != ReleaseManifest.PACKAGE || code != release.versionCode || name != release.versionName ||
            certificateDigests != listOf(release.certificateSha256) || minSdk != release.minSdk)
            throw UpdateException("The APK identity or signature does not match this app and release.")
    }
}
