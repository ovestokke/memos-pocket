package com.vstokke.memos.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import com.vstokke.memos.domain.Account
import com.vstokke.memos.domain.AuthMethod
import com.vstokke.memos.domain.PendingOAuth
import org.json.JSONObject
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
        val credential = decrypt(preferences.getString(KEY_TOKEN, null) ?: return null) ?: return null
        val authMethod = preferences.getString(KEY_AUTH_METHOD, null)
            ?.let { runCatching { AuthMethod.valueOf(it) }.getOrNull() }
            ?: AuthMethod.PERSONAL_ACCESS_TOKEN
        if (credential.isBlank()) return null
        return Account(
            baseUrl = baseUrl,
            token = if (authMethod == AuthMethod.PERSONAL_ACCESS_TOKEN) credential else "",
            userName = userName,
            displayName = displayName,
            supportsMemoReminderTime = preferences.getBoolean(KEY_MEMO_REMINDERS, false),
            authMethod = authMethod,
            refreshToken = if (authMethod == AuthMethod.SESSION) credential else null,
        )
    }

    fun save(account: Account) {
        val credential = when (account.authMethod) {
            AuthMethod.PERSONAL_ACCESS_TOKEN -> account.token
            AuthMethod.SESSION -> account.refreshToken.orEmpty()
        }
        require(credential.isNotBlank())
        preferences.edit {
            putString(KEY_URL, account.baseUrl)
            putString(KEY_USER, account.userName)
            putString(KEY_DISPLAY, account.displayName)
            putBoolean(KEY_MEMO_REMINDERS, account.supportsMemoReminderTime)
            putString(KEY_AUTH_METHOD, account.authMethod.name)
            putString(KEY_TOKEN, encrypt(credential))
        }
    }

    fun savePendingOAuth(pending: PendingOAuth) {
        val value = JSONObject()
            .put("baseUrl", pending.baseUrl)
            .put("providerName", pending.providerName)
            .put("state", pending.state)
            .put("codeVerifier", pending.codeVerifier)
            .put("createdAt", pending.createdAtEpochMillis)
        preferences.edit { putString(KEY_PENDING_OAUTH, encrypt(value.toString())) }
    }

    fun pendingOAuth(): PendingOAuth? {
        val plaintext = decrypt(preferences.getString(KEY_PENDING_OAUTH, null) ?: return null) ?: return null
        return try {
            val value = JSONObject(plaintext)
            PendingOAuth(
                baseUrl = value.getString("baseUrl"),
                providerName = value.getString("providerName"),
                state = value.getString("state"),
                codeVerifier = value.getString("codeVerifier"),
                createdAtEpochMillis = value.getLong("createdAt"),
            )
        } catch (_: Exception) {
            null
        }
    }

    fun clearPendingOAuth() {
        preferences.edit { remove(KEY_PENDING_OAUTH) }
    }

    fun selectedSpace(): String? = preferences.getString(KEY_SELECTED_SPACE, null)

    fun saveSelectedSpace(name: String?) {
        preferences.edit {
            if (name == null) remove(KEY_SELECTED_SPACE) else putString(KEY_SELECTED_SPACE, name)
        }
    }

    fun clear() {
        preferences.edit { clear() }
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.iv + cipher.doFinal(value.encodeToByteArray())
        return Base64.encodeToString(encrypted, Base64.NO_WRAP)
    }

    private fun decrypt(value: String): String? {
        return try {
            val bytes = Base64.decode(value, Base64.NO_WRAP)
            if (bytes.size <= IV_SIZE) null else {
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(
                    Cipher.DECRYPT_MODE,
                    key(),
                    GCMParameterSpec(TAG_BITS, bytes.copyOfRange(0, IV_SIZE)),
                )
                cipher.doFinal(bytes.copyOfRange(IV_SIZE, bytes.size)).decodeToString()
            }
        } catch (_: Exception) {
            null
        }
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
        const val KEY_SELECTED_SPACE = "selected_space"
        const val KEY_AUTH_METHOD = "auth_method"
        const val KEY_TOKEN = "token_ciphertext"
        const val KEY_PENDING_OAUTH = "pending_oauth_ciphertext"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_SIZE = 12
        const val TAG_BITS = 128
    }
}
