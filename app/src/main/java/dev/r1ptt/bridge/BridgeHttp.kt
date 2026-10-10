package dev.r1ptt.bridge

import dev.r1ptt.data.BridgeConfig
import dev.r1ptt.net.CallRegistry
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class BridgeError(val code: Int, val reason: String) : IOException("Relay request failed ($code)")

class BridgeHttp(private val client: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).callTimeout(25, TimeUnit.SECONDS)
    .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build()) {
    fun call(config: BridgeConfig, path: String, body: JSONObject?, calls: CallRegistry, countBytes: (Long) -> Unit): JSONObject {
        require(config.valid()) { "Invalid relay enrollment" }
        require(path.matches(Regex("/v1/(device|commands(?:/[0-9a-f-]{36}(?:/(freeze|grant|cancel|receipt))?)?|block)")))
        val raw = body?.toString()
        val request = Request.Builder().url(config.activeUrl.trimEnd('/') + path)
            .header("Authorization", "Bearer ${config.token}")
            .apply { if (raw != null) post(raw.toRequestBody("application/json".toMediaType())) }.build()
        // The fallback may need a LAN address while retaining the certificate's DNS name.
        // This is an explicit enrollment value; hostname verification and CA trust stay intact.
        val connection = if (config.useWireguard && config.wireguardAddress.isNotBlank()) client.newBuilder()
            .dns(object : okhttp3.Dns {
                override fun lookup(hostname: String): List<java.net.InetAddress> {
                    require(hostname == java.net.URI(config.wireguardUrl).host)
                    return listOf(java.net.InetAddress.getByAddress(config.wireguardAddress.split('.').map { it.toInt().toByte() }.toByteArray()))
                }
            }).build() else client
        val call = connection.newCall(request)
        calls.add(call)
        try {
            countBytes((raw?.toByteArray()?.size ?: 0).toLong() + 2048)
            return call.execute().use { reply ->
                val source = reply.body?.source() ?: throw IOException("Empty relay response")
                try { source.request(65_537) } finally { countBytes(source.buffer.size) }
                check(source.buffer.size <= 65_536) { "Relay response too large" }
                val result = JSONObject(source.readUtf8())
                if (!reply.isSuccessful) throw BridgeError(reply.code, result.optString("error").take(80))
                result
            }
        } finally { calls.remove(call) }
    }
}
