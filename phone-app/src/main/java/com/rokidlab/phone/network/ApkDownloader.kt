package com.rokidlab.phone.network

import com.rokidlab.phone.app.*
import com.rokidlab.phone.adb.*
import com.rokidlab.phone.design.*
import com.rokidlab.phone.filemanager.*
import com.rokidlab.phone.glasses.*
import com.rokidlab.phone.mirror.*
import com.rokidlab.phone.model.*
import com.rokidlab.phone.network.*
import com.rokidlab.phone.settings.*
import com.rokidlab.phone.store.*
import com.rokidlab.phone.util.*
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

class ApkDownloader(private val context: Context) {
    suspend fun download(url: String, label: String, expectedSha256: String? = null, onProgress: (Int) -> Unit): File =
        withContext(Dispatchers.IO) {
            val safeName = label.replace(Regex("[^A-Za-z0-9._-]"), "_")
            val target = File(context.cacheDir, safeName)
            var lastError: Exception? = null
            // 最多重试 2 次（首次 + 1 次重试）
            repeat(2) { attempt ->
                try {
                    HttpClient.downloadWithPercent(url, target, onPercent = onProgress)
                    expectedSha256?.takeIf { it.isNotBlank() }?.let { expected ->
                        val actual = target.sha256()
                        require(actual.equals(expected, ignoreCase = true)) {
                            "校验和不匹配：$safeName"
                        }
                    }
                    return@withContext target // 成功则返回
                } catch (e: Exception) {
                    target.delete()
                    lastError = e
                    if (attempt == 0) {
                        android.util.Log.w("ApkDownloader", "第${attempt + 1}次下载失败，重试: ${e.message}")
                    }
                }
            }
            throw lastError ?: IllegalStateException("下载失败：$safeName")
        }

    private fun File.sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
