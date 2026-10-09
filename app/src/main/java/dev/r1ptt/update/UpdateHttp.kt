package dev.r1ptt.update

import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit

/** No API/provider credentials, cookies, automatic retries, cleartext or arbitrary redirects. */
class UpdateHttp {
    private val client = OkHttpClient.Builder()
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(3, TimeUnit.MINUTES).build()
    @Volatile private var stopped = false
    @Volatile private var call: Call? = null

    fun cancel() { stopped = true; call?.cancel() }

    fun metadata(): ByteArray? = get(ReleaseManifest.METADATA_URL, 45).use { response ->
        if (response.code == 404) return null
        if (!response.isSuccessful) throw UpdateException("Release server unavailable. Try again later.")
        val body = response.body ?: throw UpdateException("Empty update metadata.")
        if (body.contentLength() > ReleaseManifest.MAX_METADATA) throw UpdateException("Update metadata is too large.")
        body.byteStream().use { input ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            while (true) {
                if (stopped) throw UpdateException("Update check cancelled.")
                val n = input.read(buffer)
                if (n < 0) break
                if (out.size() + n > ReleaseManifest.MAX_METADATA) throw UpdateException("Update metadata is too large.")
                out.write(buffer, 0, n)
            }
            out.toByteArray()
        }
    }

    fun download(release: ReleaseManifest, directory: File): File = get(release.apkUrl).use { response ->
        if (!response.isSuccessful) throw UpdateException("Could not download the release. Try again later.")
        val body = response.body ?: throw UpdateException("Empty update download.")
        val length = body.contentLength()
        if (length != -1L && length != release.apkSize) throw UpdateException("Update size does not match signed metadata.")
        body.byteStream().use { UpdateFiles.receive(it, directory, release) { stopped } }
    }

    private fun get(address: String, seconds: Long = 180): Response {
        var url = address.toHttpUrl()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
        repeat(6) { hop ->
            if (stopped || !allowed(url)) throw UpdateException("Untrusted update download address.")
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) throw UpdateException("Update request timed out. Try again later.")
            val next = client.newCall(Request.Builder().url(url).header("User-Agent", "robotOS-updater")
                .header("Accept-Encoding", "identity").build())
            next.timeout().timeout(remaining, TimeUnit.NANOSECONDS)
            call = next
            if (stopped) next.cancel()
            val response = next.execute()
            if (response.code !in setOf(301, 302, 303, 307, 308)) return response
            val location = response.header("Location")
            response.close()
            if (hop == 5 || location == null) throw UpdateException("Too many or invalid update redirects.")
            url = url.resolve(location) ?: throw UpdateException("Invalid update redirect.")
        }
        throw UpdateException("Invalid update redirect.")
    }

    companion object {
        fun allowed(url: HttpUrl): Boolean = url.scheme == "https" && url.port == 443 &&
            url.username.isEmpty() && url.password.isEmpty() && url.fragment == null && when (url.host) {
                "github.com" -> url.encodedPath.startsWith("/${ReleaseManifest.REPOSITORY}/releases/")
                "release-assets.githubusercontent.com", "objects.githubusercontent.com" -> true
                else -> false
            }
    }
}
