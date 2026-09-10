package com.vstokke.memos.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import com.vstokke.memos.domain.Account
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class CredentialStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun load(): Account? {
        val baseUrl = preferences.getString(KEY_URL, null) ?: return null
        val userName = preferences.getString(KEY_USER, null) ?: return null
        val displayName = preferences.getString(KEY_DISPLAY, "") ?: ""
        val encrypted = preferences.getString(KEY_TOKEN, null) ?: return null
        return try {
            val bytes = Base64.decode(encrypted, Base64.NO_WRAP)
            if (bytes.size <= IV_SIZE) return null
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                key(),
                GCMParameterSpec(TAG_BITS, bytes.copyOfRange(0, IV_SIZE)),
            )
            val token = cipher.doFinal(bytes.copyOfRange(IV_SIZE, bytes.size)).decodeToString()
            if (token.isBlank()) null else Account(
                baseUrl = baseUrl,
                token = token,
                userName = userName,
                displayName = displayName,
                supportsMemoReminderTime = preferences.getBoolean(KEY_MEMO_REMINDERS, false),
            )
        } catch (_: Exception) {
            null
        }
    }

    fun save(account: Account) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.iv + cipher.doFinal(account.token.encodeToByteArray())
        preferences.edit {
            putString(KEY_URL, account.baseUrl)
            putString(KEY_USER, account.userName)
            putString(KEY_DISPLAY, account.displayName)
            putBoolean(KEY_MEMO_REMINDERS, account.supportsMemoReminderTime)
            putString(KEY_TOKEN, Base64.encodeToString(encrypted, Base64.NO_WRAP))
        }
    }

    fun clear() {
        preferences.edit { clear() }
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
            generateKey()
        }
    }

    private companion object {
        const val PREFERENCES = "credentials"
        const val KEY_ALIAS = "memos-pocket-token"
        const val KEY_URL = "base_url"
        const val KEY_USER = "user_name"
        const val KEY_DISPLAY = "display_name"
        const val KEY_MEMO_REMINDERS = "memo_reminder_time_supported"
        const val KEY_TOKEN = "token_ciphertext"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_SIZE = 12
        const val TAG_BITS = 128
    }
}
