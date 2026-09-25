package com.rokidlab.phone.store

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import android.widget.MediaController
import android.widget.VideoView
import com.rokidlab.phone.R
import com.rokidlab.phone.design.BrewBorder
import com.rokidlab.phone.design.BrewChat
import com.rokidlab.phone.design.BrewMuted
import com.rokidlab.phone.design.BrewPanel
import com.rokidlab.phone.design.BrewPink
import com.rokidlab.phone.design.BrewTextBright

/** 音视频扩展名（文件卡据此分流到播放卡） */
private val VIDEO_EXTS = setOf("mp4", "webm", "mkv", "mov", "3gp")
private val AUDIO_EXTS = setOf("mp3", "wav", "m4a", "aac", "ogg", "flac", "opus")

/** 这个文件是不是媒体（决定「文件卡」还是「播放卡」） */
internal fun isMediaFile(name: String): Boolean {
    val ext = ChatAttachments.extOf(name)
    return VIDEO_EXTS.contains(ext) || AUDIO_EXTS.contains(ext)
}

/**
 * 音视频卡：**内置播放器**（`VideoView`，系统自带解码器，不引 ExoPlayer 这类依赖）。
 *
 * 出现的场合：AI 在容器里用 ffmpeg 之类生成音视频、或用户把音视频放进共享目录后由 diff 识别。
 * 之前这些产出**根本不出卡**（非文本被过滤掉了），用户只能在文件管理器里自己找。
 *
 * 取舍：
 *  - 用 `VideoView` + 系统 `MediaController`：零依赖、自带进度条/暂停，代价是样式朴素；
 *  - **点一下才开始播**（不自动播放）：对话列表里自动出声是最招人烦的交互之一；
 *  - 音频没有画面，用一个大图标占位 + 同一个播放器（VideoView 播音频时画面是黑的，
 *    所以给音频单开一条"图标 + 时长"的形态，避免出现一块莫名其妙的黑矩形）。
 */
@Composable
internal fun ChatMediaCard(path: String, name: String) {
    val ctx = LocalContext.current
    val isVideo = VIDEO_EXTS.contains(ChatAttachments.extOf(name))
    val uri = remember(path) { Uri.fromFile(java.io.File(path)) }
    var videoView by remember(path) { mutableStateOf<VideoView?>(null) }
    var playing by remember(path) { mutableStateOf(false) }
    var prepared by remember(path) { mutableStateOf(false) }

    // 离开屏幕就释放播放器（不释放会一直占着解码器，列表里滑几下就一堆实例）
    DisposableEffect(path) {
        onDispose {
            runCatching {
                videoView?.stopPlayback()
                videoView?.setMediaController(null as MediaController?)
            }
            videoView = null
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(BrewPanel)
            .border(1.dp, BrewBorder, RoundedCornerShape(10.dp)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(34.dp)
                .background(BrewPink.copy(alpha = 0.12f))
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (isVideo) Icons.Outlined.Movie else Icons.Filled.MusicNote,
                contentDescription = null,
                tint = BrewPink,
                modifier = Modifier.size(15.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = stringResource(
                    if (isVideo) R.string.chat_media_video else R.string.chat_media_audio,
                ),
                color = BrewPink,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.8.sp,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = name,
                color = BrewMuted,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(BrewBorder),
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .background(BrewPanel),
            contentAlignment = Alignment.Center,
        ) {
            AndroidView(
                factory = { c ->
                    VideoView(c).apply {
                        setVideoURI(uri)
                        setOnPreparedListener { mp ->
                            prepared = true
                            // 不自动播：等用户点
                            mp.isLooping = false
                        }
                        setOnCompletionListener { playing = false }
                        // 系统控制器：进度条 + 暂停都自带（音频时它也有用）
                        setMediaController(MediaController(c).also { it.setAnchorView(this) })
                        videoView = this
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(if (isVideo) 200.dp else 1.dp),
            )
            if (!isVideo) {
                // 音频：播放/暂停 + 状态文案（VideoView 的画面区在音频时是黑的，这里盖掉）
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            val v = videoView ?: return@clickable
                            if (playing) {
                                v.pause()
                                playing = false
                            } else {
                                v.start()
                                playing = true
                            }
                        }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    Icon(
                        imageVector = if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = stringResource(
                            if (playing) R.string.chat_media_pause else R.string.chat_media_play,
                        ),
                        tint = BrewChat,
                        modifier = Modifier.size(22.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = stringResource(
                            when {
                                playing -> R.string.chat_media_playing
                                prepared -> R.string.chat_media_play
                                else -> R.string.chat_media_loading
                            },
                        ),
                        color = BrewTextBright,
                        fontSize = 13.sp,
                    )
                }
            }
        }
    }
}
