package com.rokidlab.phone.util

import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import java.util.Collections
import java.util.WeakHashMap
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 敏感字符串（API Key / 第三方令牌）落盘封装。
 *
 * 密文格式：`enc:v1:` + Base64(iv ‖ ciphertext)。密钥是 Android Keystore 里的
 * AES-256-GCM 密钥（不可导出，驻 TEE），因此 prefs 文件被读取也拿不到明文。
 *
 * 为什么不用 androidx.security:security-crypto（EncryptedSharedPreferences）：
 * 本工程要求 `--offline` 可构建，该依赖不在本地 Gradle 缓存内，引入即断构建；
 * 本实现的密钥保护方式（Keystore AES-GCM + 随机 iv）与之等价。
 *
 * 兼容与降级：
 * - 读到无前缀的值 = 老版本遗留明文，原样返回并就地加密回写（一次性迁移）；
 * - Keystore 不可用（个别 ROM/降级）时加密失败 → 退回明文并告警，不让配置功能中断。
 *
 * 注意：只在 prefs 里存密文；内存中仍是明文（调用方拿到的就是明文）。
 */
internal object SecretStore {

    private const val TAG = "SecretStore"
    private const val KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "rokidlab_secret_aes_v1"
    private const val TRANSFORM = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128
    private const val IV_BYTES = 12
    private const val PREFIX = "enc:v1:"

    /**
     * 明文缓存：SharedPreferences 实例 → (键 → 明文)。
     * Keystore 解密有 TEE 往返成本，而设置弹窗在组合阶段就调 getOnlineAiConfig()
     * （每次重组都会读），不缓存会让输入明显卡顿。ContextImpl 按名缓存 prefs 实例，
     * 故用弱引用做键是稳定的，实例回收即自动失效。
     */
    private val plainCache: MutableMap<SharedPreferences, MutableMap<String, String>> =
        Collections.synchronizedMap(WeakHashMap())

    fun put(prefs: SharedPreferences, key: String, value: String?) {
        if (value == null) {
            prefs.edit().remove(key).apply()
            cache(prefs).remove(key)
            return
        }
        prefs.edit().putString(key, encrypt(value)).apply()
        cache(prefs)[key] = value
    }

    /** 未存/解密失败返回 null（避免把坏数据当成空串掩盖问题） */
    fun get(prefs: SharedPreferences, key: String): String? {
        cache(prefs)[key]?.let { return it }
        val raw = prefs.getString(key, null) ?: return null
        if (!raw.startsWith(PREFIX)) {
            // 老版本遗留明文：加密回写后返回
            Log.i(TAG, "$key stored as plaintext, migrating to encrypted")
            prefs.edit().putString(key, encrypt(raw)).apply()
            cache(prefs)[key] = raw
            return raw
        }
        val plain = decrypt(raw) ?: return null
        cache(prefs)[key] = plain
        return plain
    }

    fun clearCache() = plainCache.clear()

    private fun cache(prefs: SharedPreferences): MutableMap<String, String> =
        synchronized(plainCache) {
            plainCache.getOrPut(prefs) { HashMap() }
        }

    private fun encrypt(plain: String): String = try {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val body = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        PREFIX + Base64.encodeToString(cipher.iv + body, Base64.NO_WRAP)
    } catch (e: Exception) {
        Log.e(TAG, "encrypt failed, fallback to plaintext: ${e.message}")
        plain
    }

    private fun decrypt(stored: String): String? = try {
        val all = Base64.decode(stored.removePrefix(PREFIX), Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(
            Cipher.DECRYPT_MODE,
            secretKey(),
            GCMParameterSpec(GCM_TAG_BITS, all, 0, IV_BYTES),
        )
        String(cipher.doFinal(all, IV_BYTES, all.size - IV_BYTES), Charsets.UTF_8)
    } catch (e: Exception) {
        Log.e(TAG, "decrypt failed (keystore reset or corrupt data): ${e.message}")
        null
    }

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }
}
