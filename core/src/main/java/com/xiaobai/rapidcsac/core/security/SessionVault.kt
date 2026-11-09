package com.xiaobai.rapidcsac.core.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONArray
import org.json.JSONObject

data class Account(val origin: String, val uid: Long, val name: String, val token: String) {
    val key: String
        get() = "$origin/$uid"
}

/** Android Keystore protects all saved session tokens. Passwords are never saved. */
class SessionVault(context: Context) {
    private val preferences = context.getSharedPreferences("sessions", Context.MODE_PRIVATE)
    private val alias = "csac.sessions.v1"
    private val key: SecretKey by lazy {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)
            ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
                init(
                    KeyGenParameterSpec.Builder(
                            alias,
                            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                        )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .build()
                )
                generateKey()
            }
    }

    @Synchronized
    fun accounts(): List<Account> {
        val encoded = preferences.getString("accounts", null) ?: return emptyList()
        return try {
            val bytes = Base64.decode(encoded, Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            val data =
                JSONArray(String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8))
            (0 until data.length()).map { index ->
                val account = data.getJSONObject(index)
                Account(
                    account.getString("origin"),
                    account.getLong("uid"),
                    account.getString("name"),
                    account.getString("token"),
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun active(): Account? =
        accounts().firstOrNull { it.key == preferences.getString("active", "") }

    @Synchronized
    fun save(account: Account) {
        persist(accounts().filterNot { it.key == account.key } + account)
        preferences.edit().putString("active", account.key).commit()
    }

    @Synchronized
    fun remove(account: Account) {
        val wasActive = active()?.key == account.key
        persist(accounts().filterNot { it.key == account.key })
        if (wasActive) preferences.edit().remove("active").commit()
    }

    fun deselect() {
        preferences.edit().remove("active").commit()
    }

    private fun persist(accounts: List<Account>) {
        val array = JSONArray()
        accounts.forEach { account ->
            array.put(
                JSONObject().apply {
                    put("origin", account.origin)
                    put("uid", account.uid)
                    put("name", account.name)
                    put("token", account.token)
                }
            )
        }
        val cipher =
            Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
        val encrypted = cipher.iv + cipher.doFinal(array.toString().toByteArray(Charsets.UTF_8))
        preferences
            .edit()
            .putString("accounts", Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .commit()
    }
}
