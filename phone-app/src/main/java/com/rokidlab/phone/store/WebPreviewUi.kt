package com.rokidlab.phone.store

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Language
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rokidlab.phone.R
import com.rokidlab.phone.design.BrewChat
import com.rokidlab.phone.design.BrewMuted
import com.rokidlab.phone.design.BrewPanel
import com.rokidlab.phone.design.BrewBorder
import com.rokidlab.phone.design.BrewTextBright
import com.rokidlab.phone.platform.WebPreviewManager

/**
 * 本机网页预览的聊天侧 UI（只剩预览条）。
 *
 * 数据源是 [WebPreviewManager.previews]（StateFlow）：容器里有常驻网页服务就显示预览条，
 * 没有就整体不渲染 —— 预览是**持续状态**，离开聊天页再回来状态照常恢复。
 * 点击预览条拉起 [WebPreviewActivity] 看页面。
 *
 * ## 全屏预览层的宿主演进（2026-10-13 排查实录，别改回去）
 *  - v1 Compose `Dialog`：WebView 在独立 window 的 surface 上内容不合成（黑/白屏）；
 *  - v2 Compose `AndroidView` 挂 Activity 主窗口：仍然不出内容 —— ViewFactoryHolder
 *    深埋巨型 Compose 树里，WebView 从未完成一次 draw（PFLAG_DRAWN 不置位），
 *    渲染进程视口高度卡死 0（html/body computed height=0px，overflow:hidden ⇒ 全裁掉），
 *    compositor 停止产帧，DevTools 报 `"visible":false`；
 *  - v3 纯 View overlay 挂 `android.R.id.content`：能显示，但属于「在宿主 Activity 上打补丁」；
 *  - v4（现行）：**独立 [WebPreviewActivity]**（与 BrowserActivity 同款架构）——
 *    WebView 在独立轻量 Activity 的 onCreate 里创建，窗口 bounds 从一开始就正确，
 *    不经过巨型 Compose 树。BrowserActivity 多年验证同形态稳定。
 *
 * 缓存口径：预览 WebView 一律 `LOAD_NO_CACHE`——本地 127.0.0.1 服务返回的就是最新内容，
 * 走磁盘缓存既没收益，还会出现「改了页面但预览不更新」的诡异路径。
 *
 * 明文 http 的合规性：`network_security_config` 已把 127.0.0.1 白名单（明文只放回环），
 * 预览地址恰好在白名单内；WebView 加载外部 http 站点依然被系统拦 —— 边界没变宽。
 */
@Composable
internal fun WebPreviewBar() {
    val previews by WebPreviewManager.previews.collectAsState()
    if (previews.isEmpty()) return

    val ctx = LocalContext.current

    Column(modifier = Modifier.fillMaxWidth()) {
        previews.forEach { info ->
            Row(
                modifier = Modifier
                    // ⚠️ 不能写 `padding(horizontal = …, top = …)` —— 没有这个重载
                    .padding(start = 12.dp, end = 12.dp, top = 6.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(BrewPanel)
                    .border(1.dp, BrewBorder, RoundedCornerShape(10.dp))
                    .clickable { ctx.startActivity(WebPreviewActivity.intent(ctx, info)) }
                    .padding(start = 12.dp, end = 4.dp, top = 7.dp, bottom = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Filled.Language,
                    contentDescription = null,
                    tint = BrewChat,
                    modifier = Modifier.size(15.dp),
                )
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.chat_preview_bar_label),
                        color = BrewTextBright,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = info.url,
                        color = BrewMuted,
                        fontSize = 11.sp,
                        maxLines = 1,
                    )
                }
                TextButton(onClick = { ctx.startActivity(WebPreviewActivity.intent(ctx, info)) }) {
                    Text(
                        text = stringResource(R.string.chat_preview_bar_open),
                        color = BrewChat,
                        fontSize = 12.sp,
                    )
                }
            }
        }
    }
}
