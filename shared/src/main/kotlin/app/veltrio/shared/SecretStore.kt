package app.veltrio.shared

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** API keys and tokens, encrypted with a key kept in the Android Keystore
 *  (the role the Keychain plays on macOS, DPAPI on Windows) in secrets.dat. */
object SecretStore {
    private const val FILE = "secrets.dat"
    private const val ALIAS = "veltrio.secrets"
    private val serializer = MapSerializer(String.serializer(), String.serializer())
    private val values: MutableMap<String, String> by lazy { load() }

    fun get(name: String): String? = synchronized(this) { values[name] }

    fun set(name: String, value: String?) = synchronized(this) {
        if (value == null) values.remove(name) else values[name] = value
        save()
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
        }.generateKey()
    }

    private fun load(): MutableMap<String, String> = try {
        val bytes = File(Storage.folder, FILE).takeIf { it.exists() }?.readBytes()
        if (bytes == null) mutableMapOf() else {
            // 12-byte IV, then the ciphertext.
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, 12))
            Storage.json.decodeFromString(serializer, cipher.doFinal(bytes, 12, bytes.size - 12).decodeToString()).toMutableMap()
        }
    } catch (_: Exception) {
        // Unreadable (key gone after a restore, corrupted): start over.
        mutableMapOf()
    }

    private fun save() {
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val sealed = cipher.doFinal(Storage.json.encodeToString(serializer, values).encodeToByteArray())
            File(Storage.folder, FILE).writeBytes(cipher.iv + sealed)
        } catch (_: Exception) {
            // Keys then live for this session only.
        }
    }
}
