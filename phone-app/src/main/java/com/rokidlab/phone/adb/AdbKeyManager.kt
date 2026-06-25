package com.rokidlab.phone.adb

import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec

/**
 * ADB RSA 密钥持久化管理器
 * 所有 ADB Client 共享同一对密钥，避免每次连接都弹 RSA 确认对话框。
 * 密钥文件存储在 [baseDir]/adbkey 和 [baseDir]/adbkey.pub。
 *
 * BouncyCastle Provider 由 LabApplication.onCreate() 提前注册，此处直接优先使用 BC。
 */
object AdbKeyManager {
    private const val TAG = "AdbKeyManager"
    private const val KEY_FILE_NAME = "adbkey"
    private const val PUB_KEY_FILE_NAME = "adbkey.pub"

    @Volatile
    private var cachedKeyPair: KeyPair? = null

    /** 获取 KeyFactory（优先 BC Provider，兼容 HarmonyOS） */
    private fun getKeyFactory(): KeyFactory {
        return try { KeyFactory.getInstance("RSA", "BC") }
        catch (_: Exception) { KeyFactory.getInstance("RSA") }
    }

    /** 获取 KeyPairGenerator（优先 BC Provider，兼容 HarmonyOS） */
    private fun getKeyPairGenerator(): KeyPairGenerator {
        return try { KeyPairGenerator.getInstance("RSA", "BC") }
        catch (_: Exception) { KeyPairGenerator.getInstance("RSA") }
    }

    /** 获取 SHA1withRSA Signature（优先 BC Provider，兼容 HarmonyOS） */
    fun getSignature(): Signature {
        return try { Signature.getInstance("SHA1withRSA", "BC") }
        catch (_: Exception) { Signature.getInstance("SHA1withRSA") }
    }

    /**
     * 获取或生成 RSA 密钥对。
     * 优先从磁盘加载已有密钥，失败则生成新密钥并持久化。
     * 所有 ADB Client 共用同一对密钥。
     */
    @Synchronized
    fun getOrCreateKeyPair(baseDir: String): KeyPair {
        cachedKeyPair?.let { return it }

        val privKeyFile = File(baseDir, KEY_FILE_NAME)
        val pubKeyFile = File(baseDir, PUB_KEY_FILE_NAME)

        if (privKeyFile.exists() && pubKeyFile.exists()) {
            try {
                val privBytes = FileInputStream(privKeyFile).use { it.readBytes() }
                val pubBytes = FileInputStream(pubKeyFile).use { it.readBytes() }
                val keyFactory = getKeyFactory()
                val privKey = keyFactory.generatePrivate(PKCS8EncodedKeySpec(privBytes))
                val pubKey = keyFactory.generatePublic(X509EncodedKeySpec(pubBytes))
                val kp = KeyPair(pubKey, privKey)
                cachedKeyPair = kp
                Log.i(TAG, "ADB key loaded from disk successfully")
                return kp
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load ADB key, regenerating: ${e.message}")
            }
        }

        val kpg = getKeyPairGenerator()
        kpg.initialize(2048)
        val kp = kpg.genKeyPair()

        try {
            FileOutputStream(privKeyFile).use { it.write(kp.private.encoded) }
            FileOutputStream(pubKeyFile).use { it.write(kp.public.encoded) }
            // 设置私钥文件权限：仅所有者可读写
            privKeyFile.setReadable(false, false)
            privKeyFile.setReadable(true, true)
            privKeyFile.setWritable(false, false)
            privKeyFile.setWritable(true, true)
            // 设置公钥文件权限：所有者可读写，其他用户只读
            pubKeyFile.setReadable(true, false)
            pubKeyFile.setWritable(false, false)
            pubKeyFile.setWritable(true, true)
            Log.i(TAG, "ADB key generated and persisted with secure permissions")
        } catch (e: Exception) {
            Log.w(TAG, "ADB key persistence failed (does not affect current connection): ${e.message}")
        }

        cachedKeyPair = kp
        return kp
    }

    /** 清除缓存（用于测试或密钥重置） */
    @Synchronized
    fun reset() {
        cachedKeyPair = null
    }
}
