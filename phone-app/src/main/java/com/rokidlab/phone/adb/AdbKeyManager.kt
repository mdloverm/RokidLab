package com.rokidlab.phone.adb

import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator

/**
 * ADB RSA 密钥持久化管理器
 * 所有 ADB Client 共享同一对密钥，避免每次连接都弹 RSA 确认对话框。
 * 密钥文件存储在 [baseDir]/adbkey 和 [baseDir]/adbkey.pub。
 */
object AdbKeyManager {
    private const val TAG = "AdbKeyManager"
    private const val KEY_FILE_NAME = "adbkey"
    private const val PUB_KEY_FILE_NAME = "adbkey.pub"

    @Volatile
    private var cachedKeyPair: KeyPair? = null

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
                val keyFactory = KeyFactory.getInstance("RSA")
                val privKey = keyFactory.generatePrivate(java.security.spec.PKCS8EncodedKeySpec(privBytes))
                val pubKey = keyFactory.generatePublic(java.security.spec.X509EncodedKeySpec(pubBytes))
                val kp = KeyPair(pubKey, privKey)
                cachedKeyPair = kp
                Log.i(TAG, "ADB 密钥从磁盘加载成功")
                return kp
            } catch (e: Exception) {
                Log.w(TAG, "加载 ADB 密钥失败，将重新生成: ${e.message}")
            }
        }

        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(2048)
        val kp = kpg.genKeyPair()

        try {
            FileOutputStream(privKeyFile).use { it.write(kp.private.encoded) }
            FileOutputStream(pubKeyFile).use { it.write(kp.public.encoded) }
            Log.i(TAG, "ADB 密钥已生成并持久化")
        } catch (e: Exception) {
            Log.w(TAG, "ADB 密钥持久化失败（不影响本次连接）: ${e.message}")
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
