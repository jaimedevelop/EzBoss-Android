package pro.ezboss.mobile.auth

import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Ciphertext-only, separate namespace; Android Keystore keys are non-exportable. */
internal class SecureCredentialStore(private val context: Context) {
    private val preferences = context.getSharedPreferences("better_auth_device_session", Context.MODE_PRIVATE)
    private val alias = context.packageName + ".better-auth.session"
    private fun requireUnlocked() {
        if ((context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isDeviceLocked) throw NativeAuthException(503)
    }
    private fun key(): SecretKey {
        requireUnlocked()
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
        if (Build.VERSION.SDK_INT >= 28) spec.setUnlockedDeviceRequired(true)
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply { init(spec.build()) }.generateKey()
    }
    fun read(): String? {
        requireUnlocked()
        val value = preferences.getString("credential", null) ?: return null
        val pieces = value.split(':'); check(pieces.size == 2)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(pieces[0], Base64.NO_WRAP)))
        val data = JSONObject(String(cipher.doFinal(Base64.decode(pieces[1], Base64.NO_WRAP)), Charsets.UTF_8))
        check(data.getString("credentialKind") == "session")
        return data.getString("token").takeIf { it.isNotBlank() }
    }
    fun write(token: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, key())
        val plaintext = JSONObject().put("credentialKind", "session").put("token", token).toString().toByteArray(Charsets.UTF_8)
        val value = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(cipher.doFinal(plaintext), Base64.NO_WRAP)
        check(preferences.edit().putString("credential", value).commit()) { "Secure session save failed." }
    }
    fun clear() { check(preferences.edit().clear().commit()) { "Secure session removal failed." } }
}
