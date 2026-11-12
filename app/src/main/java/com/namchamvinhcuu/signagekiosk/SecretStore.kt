package com.namchamvinhcuu.signagekiosk

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.InvalidKeyException
import java.security.KeyStore
import java.security.UnrecoverableKeyException
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Holds the paired player URL (it carries the token) AES-GCM encrypted with a non-exportable Android Keystore
 * key; only the ciphertext and the IV reach SharedPreferences. This runs on an unattended TV, so no Keystore
 * failure may crash the app (some OEM builds throw ProviderException, a RuntimeException): it is logged WITHOUT
 * the value and the caller falls back to pairing again. A key that cannot be used is deleted and created once more.
 */
class SecretStore(context: Context) {
    private val prefs = context.getSharedPreferences("kiosk_secret", Context.MODE_PRIVATE)

    /** commit(): a process restart may follow right away. False = not stored (the TV pairs again after a restart). */
    fun save(plain: String): Boolean = try {
        encrypt(plain, key())
    } catch (e: Exception) {
        // A key that cannot encrypt (invalidated, damaged keystore entry): replace it and try once more.
        KLog.w("secret store: save failed, recreating the key", e)
        try {
            deleteKey()
            encrypt(plain, key())
        } catch (e2: Exception) {
            KLog.e("secret store: save failed", e2)
            false
        }
    }

    fun load(): String? {
        val iv = prefs.getString(K_IV, null) ?: return null
        val ct = prefs.getString(K_CT, null) ?: return null
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, Base64.decode(iv, Base64.NO_WRAP)))
            String(cipher.doFinal(Base64.decode(ct, Base64.NO_WRAP)), Charsets.UTF_8)
        } catch (e: AEADBadTagException) {
            dropUnreadable(e)
        } catch (e: UnrecoverableKeyException) {
            dropUnreadable(e)
        } catch (e: InvalidKeyException) {
            dropUnreadable(e)
        } catch (e: IllegalArgumentException) {
            // Base64.decode on a damaged blob.
            dropUnreadable(e)
        } catch (e: Exception) {
            // Anything else (Keystore not ready right after boot, ProviderException...) may be transient: keep the
            // blob, pair again for now; the next save replaces it.
            KLog.e("secret store: load failed, keeping the stored value", e)
            null
        }
    }

    fun clear() {
        prefs.edit().remove(K_IV).remove(K_CT).commit()
    }

    private fun encrypt(plain: String, key: SecretKey): Boolean {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return prefs.edit()
            .putString(K_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString(K_CT, Base64.encodeToString(ct, Base64.NO_WRAP))
            .commit()
    }

    private fun dropUnreadable(e: Exception): String? {
        KLog.e("secret store: stored value unreadable, dropped (the TV will pair again)", e)
        clear()
        return null
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private fun deleteKey() {
        try {
            keyStore().deleteEntry(ALIAS)
        } catch (e: GeneralSecurityException) {
            KLog.w("secret store: deleting the key failed", e)
        } catch (e: IOException) {
            KLog.w("secret store: deleting the key failed", e)
        }
    }

    private fun key(): SecretKey {
        (keyStore().getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "signage_kiosk_paired_url"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
        const val K_IV = "iv"
        const val K_CT = "ct"
    }
}
