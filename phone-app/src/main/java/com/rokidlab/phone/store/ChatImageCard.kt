package com.rokidlab.phone.store

import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.OpenInFull
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.rokidlab.phone.R
import com.rokidlab.phone.design.BrewBg
import com.rokidlab.phone.design.BrewBorder
import com.rokidlab.phone.design.BrewChat
import com.rokidlab.phone.design.BrewMuted
import com.rokidlab.phone.design.BrewPanel
import com.rokidlab.phone.design.BrewPanelAlt
import com.rokidlab.phone.design.BrewTextBright
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 聊天图片卡片（替代旧的裸 [BubbleImage]）：与 [CodeBlockCard] 同一套卡片语言。
 *
 * - 标题栏左侧：图片图标 + 格式标签（JPEG/PNG/WEBP/GIF，data URL 按 MIME、直链按扩展名，
 *   认不出来标 IMAGE）；
 * - 标题栏右侧：**纯图标** —— 全屏预览、下载到手机（保存的是**原图字节**，不是气泡里
 *   600px 的采样图，见 [ChatImageCache.fetchBytes]）；
 * - 点图片本体也能进全屏预览；加载中/失败沿用原占位态。
 */
@Composable
internal fun ChatImageCard(url: String) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var bitmap by remember(url) { mutableStateOf<Bitmap?>(null) }
    var failed by remember(url) { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf(false) }

    LaunchedEffectImage(url) { bmp ->
        bitmap = bmp
        failed = bmp == null
    }

    val format = remember(url) { ImageFormat.of(url) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(BrewPanel)
            .border(1.dp, BrewBorder, RoundedCornerShape(10.dp)),
    ) {
        // ── 标题栏（聊天主题色低透明底）──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(34.dp)
                .background(BrewChat.copy(alpha = 0.12f))
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.Image,
                contentDescription = null,
                tint = BrewChat,
                modifier = Modifier.size(15.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = format.label,
                color = BrewChat,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.8.sp,
            )
            Spacer(Modifier.weight(1f))
            HeaderGlyph(
                enabled = bitmap != null,
                onClick = { preview = true },
                icon = Icons.Outlined.OpenInFull,
                description = stringResource(R.string.chat_image_preview),
            )
            HeaderGlyph(
                enabled = bitmap != null && !saving,
                onClick = {
                    saving = true
                    scope.launch(Dispatchers.IO) {
                        val outcome = runCatching {
                            val bytes = ChatImageCache.fetchBytes(url)
                                ?: error(ctx.getString(R.string.chat_image_failed))
                            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                            ChatMediaSaver.saveImage(ctx, "leqi-$stamp.${format.ext}", format.mime, bytes)
                        }.fold(
                            onSuccess = { ctx.getString(R.string.chat_image_saved, it) },
                            onFailure = {
                                ctx.getString(R.string.chat_image_save_failed, it.message ?: it.javaClass.simpleName)
                            },
                        )
                        withContext(Dispatchers.Main) {
                            saving = false
                            Toast.makeText(ctx, outcome, Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                icon = if (saving) null else Icons.Outlined.Download,
                description = stringResource(R.string.chat_image_download),
                saving = saving,
            )
        }

        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(BrewBorder),
        )

        // ── 图片本体 ──
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 80.dp, max = 260.dp)
                .background(BrewPanelAlt)
                .let { m -> if (bitmap != null) m.clickable { preview = true } else m },
            contentAlignment = Alignment.Center,
        ) {
            val bmp = bitmap
            when {
                bmp != null -> androidx.compose.foundation.Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(6.dp)
                        .clip(RoundedCornerShape(6.dp)),
                    contentScale = ContentScale.Fit,
                )

                failed -> Text(
                    text = stringResource(R.string.chat_image_failed),
                    color = BrewMuted,
                    fontSize = 12.sp,
                )

                else -> CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp,
                    color = BrewChat,
                )
            }
        }
    }

    // 只有图已加载才能进预览（两个入口都在 bitmap != null 时才可点）
    val previewBmp = bitmap
    if (preview && previewBmp != null) {
        ImageFullscreenDialog(previewBmp) { preview = false }
    }
}

/** 标题栏 30dp 图标位；[saving] 时显示小转圈替代图标。 */
@Composable
private fun HeaderGlyph(
    enabled: Boolean,
    onClick: () -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector?,
    description: String,
    saving: Boolean = false,
) {
    Box(
        modifier = Modifier
            .padding(start = 2.dp)
            .size(30.dp)
            .clip(RoundedCornerShape(8.dp))
            .let { m -> if (enabled) m.clickable(onClick = onClick) else m },
        contentAlignment = Alignment.Center,
    ) {
        when {
            saving -> CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                strokeWidth = 1.6.dp,
                color = BrewChat,
            )

            icon != null -> Icon(
                imageVector = icon,
                contentDescription = description,
                tint = if (enabled) BrewMuted else BrewMuted.copy(alpha = 0.35f),
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/** 全屏查看：近黑遮罩 + Fit 大图，点任意处/返回关闭（全屏覆盖层，不走 BrewDialog）。 */
@Composable
private fun ImageFullscreenDialog(bitmap: Bitmap, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(BrewBg.copy(alpha = 0.97f))
                .clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center,
        ) {
            androidx.compose.foundation.Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp, vertical = 44.dp),
                contentScale = ContentScale.Fit,
            )
            Icon(
                imageVector = Icons.Outlined.Close,
                contentDescription = stringResource(R.string.chat_image_preview),
                tint = BrewTextBright,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(top = 12.dp, end = 14.dp)
                    .size(22.dp),
            )
        }
    }
}

/** 抽出加载副作用，保持与旧 BubbleImage 相同的缓存语义。 */
@Composable
private fun LaunchedEffectImage(url: String, onResult: (Bitmap?) -> Unit) {
    androidx.compose.runtime.LaunchedEffect(url) {
        ChatImageCache.load(url) { onResult(it) }
    }
}

/** 图片格式：标题栏标签 + 保存落盘的扩展名/MIME。 */
internal enum class ImageFormat(val label: String, val ext: String, val mime: String) {
    JPEG("JPEG", "jpg", "image/jpeg"),
    PNG("PNG", "png", "image/png"),
    WEBP("WEBP", "webp", "image/webp"),
    GIF("GIF", "gif", "image/gif"),
    BMP("BMP", "bmp", "image/bmp"),
    UNKNOWN("IMAGE", "jpg", "image/jpeg"),
    ;

    companion object {
        fun of(url: String): ImageFormat {
            val lower = url.lowercase()
            // data:image/png;base64, …
            if (lower.startsWith("data:")) {
                val mime = lower.substringAfter("data:").substringBefore(';').substringBefore(',')
                return when (mime.substringAfter('/')) {
                    "png" -> PNG
                    "webp" -> WEBP
                    "gif" -> GIF
                    "bmp" -> BMP
                    "jpeg", "jpg" -> JPEG
                    else -> UNKNOWN
                }
            }
            return when (lower.substringBefore('?').substringBefore('#').substringAfterLast('.', "")) {
                "jpg", "jpeg" -> JPEG
                "png" -> PNG
                "webp" -> WEBP
                "gif" -> GIF
                "bmp" -> BMP
                else -> UNKNOWN
            }
        }
    }
}
