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
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PhotoCamera
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
@Composable
internal fun ChatHeader(
    onPhotoAsk: () -> Unit,
    onOpenKb: () -> Unit,
    onOpenSettings: () -> Unit,
    onClearChat: () -> Unit,
    /** 本机模式（不连眼镜也能聊）当前是否开启；见 [LabApplication.chatLocalOnlyEnabled] */
    localOnly: Boolean,
    onToggleLocalOnly: () -> Unit,
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
        // 会话列表入口就做在标题区上：标题栏实测可用宽仅 369dp，已被 5 个 IconButton
        // 占满（每个 40dp），再塞第 6 个必然让副标题折行。点标题开列表是零宽度成本的方案，
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
                // 本机模式是一句话就能说清的全局状态，直接写在副标题上 ——
                // 只靠一个换了的图标容易被忽略，而它会让「眼镜端不再收到回复」，必须显眼
                text = stringResource(
                    if (localOnly) R.string.chat_subtitle_local_only else R.string.chat_subtitle,
                ),
                color = if (localOnly) BrewAmber else BrewMuted,
                fontSize = 11.sp,
                // 单行硬约束：这一行宽度被右侧 5 个按钮挤得很紧，宁可省略号收尾也不许折成两行
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // 模式切换（放最前，紧邻说明当前模式的副标题）：本机模式=手机图标，眼镜模式=眼镜图标。
        // 图标本身就是状态指示器 —— 点了之后图标会换，不需要额外的开关图形。
        //
        // ⚠️ 眼镜用 `res/drawable/ic_glasses.xml`（官方 Material Symbols 字形换算，见
        // skills/rokidlab-chat-standalone-mode/scripts/symbols_to_vector.py）——
        // 自绘过一次线稿版，被判「跟同排图标风格不符」，官方字形才治本。
        // ⚠️ 手机**必须用 `Icons.Filled.PhoneAndroid` 这个"细框"形状，不要换"实心"版**：
        // 试过官方 `smartphone`(fill) 的实心方板（想解决"风格不符"），用户直接判成
        // 「跟内存卡一样，还不如之前的」—— 手机的实心字形在任何图标集里都读成存储卡/方块。
        // ⇒ 这条排的"实心感"不该由手机图标来承担；现在只有眼镜是自定义 drawable，手机用同库内置字形。
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
        IconButton(onClick = onPhotoAsk, modifier = Modifier.size(ICON_BUTTON_SIZE)) {
            Icon(
                imageVector = Icons.Filled.PhotoCamera,
                contentDescription = stringResource(R.string.chat_photo_ask),
                tint = BrewChat,
                modifier = Modifier.size(ICON_SIZE),
            )
        }
        IconButton(onClick = onOpenKb, modifier = Modifier.size(ICON_BUTTON_SIZE)) {
            Icon(
                imageVector = Icons.Filled.Folder,
                contentDescription = stringResource(R.string.chat_kb),
                tint = BrewChat,
                modifier = Modifier.size(ICON_SIZE),
            )
        }
        IconButton(onClick = onClearChat, modifier = Modifier.size(ICON_BUTTON_SIZE)) {
            Icon(
                imageVector = Icons.Filled.DeleteSweep,
                contentDescription = stringResource(R.string.chat_clear),
                tint = BrewChat,
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
