package dev.r1ptt.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** The current [Config], persisted encrypted (it holds API keys) and observable. */
class ConfigStore(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("config", Context.MODE_PRIVATE)
    private val state = MutableStateFlow(load())

    val flow: StateFlow<Config> = state
    val value: Config get() = state.value

    @Synchronized
    fun update(transform: (Config) -> Config): Config {
        val next = transform(state.value)
        prefs.edit().putString(KEY, Sealed.seal(ConfigJson.toJson(next).toString())).apply()
        state.value = next
        return next
    }

    /** Applies a (partial) JSON document; throws on malformed JSON so a bad import changes nothing. */
    fun import(json: String): Config = update { ConfigJson.merge(it, JSONObject(json)) }

    private fun load(): Config {
        val blob = prefs.getString(KEY, null) ?: return Config()
        return runCatching { ConfigJson.merge(Config(), JSONObject(Sealed.open(blob))) }
            .onFailure { Log.w(TAG, "stored config unreadable, using defaults", it) }
            .getOrDefault(Config())
    }

    private companion object {
        const val KEY = "config"
        const val TAG = "r1ptt"
    }
}

/** AES-GCM with a non-exportable Android Keystore key, so the keys aren't sitting in plain XML. */
private object Sealed {
    private const val ALIAS = "r1ptt-config"

    fun seal(plain: String): String = try {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        "v1:" + b64(c.iv) + ":" + b64(c.doFinal(plain.toByteArray()))
    } catch (e: Exception) {
        // A broken keystore (it happens on GSIs) must not cost the user their settings.
        Log.w("r1ptt", "keystore unavailable, storing config unencrypted", e)
        "plain:" + b64(plain.toByteArray())
    }

    fun open(blob: String): String = when {
        blob.startsWith("v1:") -> {
            val (_, iv, data) = blob.split(":", limit = 3)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, unb64(iv)))
            String(c.doFinal(unb64(data)))
        }
        blob.startsWith("plain:") -> String(unb64(blob.removePrefix("plain:")))
        else -> blob
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return gen.generateKey()
    }

    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)
    private fun unb64(s: String) = Base64.decode(s, Base64.NO_WRAP)
}
