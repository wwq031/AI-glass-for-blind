package com.leqi.experiment.phonel

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** 运行时凭据在手机上输入，AES 密钥由 Android Keystore 保管。 */
internal class AgentModelSettings(
    private val preferences: SharedPreferences,
    private val encryptionKey: () -> SecretKey,
) {
    constructor(context: Context) : this(context.getSharedPreferences("agent_model", Context.MODE_PRIVATE), ::deviceKey)

    val cloudEnabled: Boolean get() = preferences.getBoolean("cloud", false)
    val model: String get() = preferences.getString("model", FLASH)!!
    val hasKey: Boolean get() = preferences.contains("credential")
    val totalTokens: Long get() = preferences.getLong("tokens", 0)
    fun recordUsage(tokens: Int) { preferences.edit().putLong("tokens", totalTokens + tokens).apply() }

    fun apiKey(): String {
        val saved = preferences.getString("credential", null) ?: return ""
        val iv = Base64.decode(preferences.getString("iv", null), Base64.NO_WRAP)
        return Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, encryptionKey(), GCMParameterSpec(128, iv))
            String(doFinal(Base64.decode(saved, Base64.NO_WRAP)), Charsets.UTF_8)
        }
    }

    /** 空输入保留已存密钥，切换本地模式不需要解密密钥。 */
    fun save(cloud: Boolean, selectedModel: String, replacementKey: String) {
        require(selectedModel in listOf(FLASH, PRO))
        val key = replacementKey.trim()
        require(key.none { it.isWhitespace() || it.isISOControl() })
        require(!cloud || key.isNotEmpty() || hasKey)
        val edit = preferences.edit().putBoolean("cloud", cloud).putString("model", selectedModel)
        if (key.isNotEmpty()) {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, encryptionKey())
            edit.putString("credential", Base64.encodeToString(cipher.doFinal(key.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP))
                .putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
        }
        check(edit.commit())
    }

    fun clearKey() { check(preferences.edit().remove("credential").remove("iv").putBoolean("cloud", false).commit()) }

    companion object {
        const val FLASH = "deepseek-flash"
        const val PRO = "deepseek-v4-pro"
        private fun deviceKey(): SecretKey {
            val alias = "leqi.deepseek.credential"
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (store.getKey(alias, null) as? SecretKey)?.let { return it }
            return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
                init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
                generateKey()
            }
        }
    }
}
