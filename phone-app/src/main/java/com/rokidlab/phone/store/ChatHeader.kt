package com.rokidlab.phone.store

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rokidlab.phone.R
import com.rokidlab.phone.app.LabApplication
import com.rokidlab.phone.design.BrewAmber
import com.rokidlab.phone.design.BrewChat
import com.rokidlab.phone.design.BrewCoral
import com.rokidlab.phone.design.BrewMuted
import com.rokidlab.phone.design.BrewPanel

/**
 * 标题栏图标的统一尺寸。
 *
 * 两个都必须显式指定，不能靠默认值：
 *  - [ICON_SIZE] —— Material3 的 `Icon` 默认取「画笔自带的固有尺寸」（矢量图为 24dp），
 *    自绘 drawable 若 width/height 写得不一样就会与内置图标不同大小，显式写死才是硬保证。
 *  - [ICON_BUTTON_SIZE] —— `IconButton` 默认 48dp，五个并排要吃掉 240dp；标题栏实测可用宽
 *    仅 369dp（1200px @ density 520），留给左侧标题栏只剩 105dp，副标题必然换行。
 *    收到 40dp 后字形仍是 24dp（视觉大小不变），只压缩了按钮内边距，给文字让出 40dp。
 */
private val ICON_BUTTON_SIZE = 40.dp
private val ICON_SIZE = 24.dp

// ===== 顶部标题栏 =====
//
// 构成：标题（点开会话列表） + 右侧三个按钮［本机模式 · 清空对话 · 设置］。
//
// ⚠️ 2026-09-24 来回挪过一次，结论记在这里免得后人再试：
//   当天先把「本机模式 / 拍照 / 知识库 / 清空」四个一起下移到输入栏，理由是想给标题腾宽度；
//   但用户随后指出**本机模式与清空属于"全局状态类"动作**（一个是全局开关、一个清整段对话），
//   跟"发消息顺手点"的拍照/知识库不是一类，放输入栏反而难找 ⇒ 只把这两个挪回顶栏。
//   拍照 / 知识库留在输入栏（它们确实是发消息前的取材动作）。
@Composable
internal fun ChatHeader(
    onOpenSettings: () -> Unit,
    /** 本机模式开关（手机/眼镜） */
    onToggleLocalOnly: () -> Unit,
    /** 清空当前对话 */
    onClearChat: () -> Unit,
    /** 环境音持续录入开关：点击开启/关闭（默认关，状态不持久） */
    onToggleAmbient: () -> Unit,
    /** 环境音持续录入当前是否开启 */
    ambientActive: Boolean,
    /** 本机模式（不连眼镜也能聊）当前是否开启；见 [LabApplication.chatLocalOnlyEnabled] */
    localOnly: Boolean,
    /** 当前会话标题（取代固定的「乐奇聊天」：多会话下用户需要知道自己在哪个对话里） */
    sessionTitle: String,
    onOpenSessions: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(BrewPanel)
            // end 只留 4dp：图标按钮自带 12dp 内边距，实际右边距 ≈16dp，与 start 对齐
            .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 会话列表入口就做在标题区上：点标题开列表是零宽度成本的方案，
        // 且「标题 = 当前会话名」本身就是用户找列表的心理入口。
        Column(
            modifier = Modifier
                .weight(1f)
                .clickable { onOpenSessions() }
                .padding(vertical = 2.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = sessionTitle,
                    color = BrewChat,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Icon(
                    imageVector = Icons.Filled.ExpandMore,
                    contentDescription = stringResource(R.string.chat_sessions_title),
                    tint = BrewMuted,
                    modifier = Modifier.size(16.dp),
                )
            }
            Text(
                // 副标题一句话说清当前全局态：本机模式 > 聆听模式（环境音持续录入）> 默认；
                // 前两者都是「眼镜端行为被改变了」的状态，必须显眼（琥珀色）
                text = stringResource(
                    when {
                        localOnly -> R.string.chat_subtitle_local_only
                        ambientActive -> R.string.chat_subtitle_ambient
                        else -> R.string.chat_subtitle
                    },
                ),
                color = if (localOnly || ambientActive) BrewAmber else BrewMuted,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // 本机模式：图标本身就是状态（眼镜=走眼镜播报 / 手机=只在本机跑，不连眼镜也能聊）
        IconButton(onClick = onToggleLocalOnly, modifier = Modifier.size(ICON_BUTTON_SIZE)) {
            if (localOnly) {
                Icon(
                    imageVector = Icons.Filled.PhoneAndroid,
                    contentDescription = stringResource(R.string.chat_local_only_on),
                    tint = BrewAmber,
                    modifier = Modifier.size(ICON_SIZE),
                )
            } else {
                Icon(
                    painter = painterResource(R.drawable.ic_glasses),
                    contentDescription = stringResource(R.string.chat_local_only_off),
                    tint = BrewChat,
                    modifier = Modifier.size(ICON_SIZE),
                )
            }
        }
        // 环境音持续录入：图标本身就是状态（开=琥珀「聆听模式」/ 关=与其他三钮同色同大小）。
        // Hearing 字形比自绘 ic_glasses 视觉偏大，用 20dp 与另外三钮视觉平衡。
        // 开启后眼镜远场麦听到的每句话自动作为新消息进对话；默认关、不持久（App 重启回到关闭态）。
        IconButton(onClick = onToggleAmbient, modifier = Modifier.size(ICON_BUTTON_SIZE)) {
            Icon(
                imageVector = Icons.Filled.Hearing,
                contentDescription = stringResource(R.string.chat_ambient_listen),
                tint = if (ambientActive) BrewAmber else BrewChat,
                modifier = Modifier.size(20.dp),
            )
        }
        IconButton(onClick = onClearChat, modifier = Modifier.size(ICON_BUTTON_SIZE)) {
            Icon(
                imageVector = Icons.Filled.DeleteSweep,
                contentDescription = stringResource(R.string.chat_clear),
                tint = BrewCoral,
                modifier = Modifier.size(ICON_SIZE),
            )
        }
        IconButton(onClick = onOpenSettings, modifier = Modifier.size(ICON_BUTTON_SIZE)) {
            Icon(
                imageVector = Icons.Filled.Settings,
                contentDescription = stringResource(R.string.chat_settings),
                tint = BrewChat,
                modifier = Modifier.size(ICON_SIZE),
            )
        }
    }
}
