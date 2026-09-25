package com.rokidlab.phone.store

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rokidlab.phone.R
import com.rokidlab.phone.ai.llm.ProviderCatalog
import com.rokidlab.phone.design.BrewAmber
import com.rokidlab.phone.design.BrewChat
import com.rokidlab.phone.design.BrewCoral
import com.rokidlab.phone.design.BrewCyan
import com.rokidlab.phone.design.BrewMagenta
import com.rokidlab.phone.design.BrewPink
import com.rokidlab.phone.design.BrewPurple
import com.rokidlab.phone.design.BrewTeal

/**
 * 供应商品牌图标：**内置矢量图标优先，匹配不到回落首字母文字头像**。
 *
 * 做法照搬 rikkahub 的 `AutoAIIcon`（有图标渲染图标、没有就 `TextAvatar` 取名字首字），
 * 图标同样取自 lobehub icons 图标库（rikkahub 的 AIIconMatcher.kt 里就写着
 * `// https://lobehub.com/zh/icons`）—— 但**渲染方式不同**：
 * 那边用 Coil3 运行时渲染 SVG + 注入 CSS 着色；Lab 没有图片/SVG 依赖，于是把那些图标
 * （都是「单色 currentColor + 纯 path」，结构极简）**离线转成 VectorDrawable**
 * （`res/drawable/provider_icon_*.xml`，脚本见项目记忆），体积 1-4KB/个，
 * 用 Compose `Icon(tint = accent)` 着色 —— 零依赖、矢量清晰，也和现有头像视觉一致。
 *
 * 维护：新增品牌 = ① 下载 lobe 单色 svg 转 VectorDrawable；② 在 [ProviderIcons.resOf] 加一行。
 */

internal object ProviderIcons {

    /** 品牌 id → 矢量图标资源（0 = 没有图标，调用方回落首字母） */
    fun resOf(id: String): Int = when (id) {
        "deepseek" -> R.drawable.provider_icon_deepseek
        "moonshot" -> R.drawable.provider_icon_moonshot
        "dashscope" -> R.drawable.provider_icon_dashscope
        "zhipu" -> R.drawable.provider_icon_zhipu
        "volces" -> R.drawable.provider_icon_volces
        "siliconflow" -> R.drawable.provider_icon_siliconflow
        "openai" -> R.drawable.provider_icon_openai
        "anthropic" -> R.drawable.provider_icon_anthropic
        "gemini" -> R.drawable.provider_icon_gemini
        "xai" -> R.drawable.provider_icon_xai
        "openrouter" -> R.drawable.provider_icon_openrouter
        // custom（自定义 OpenAI 兼容端点）没有品牌，走首字母
        else -> 0
    }
}

/**
 * 品牌强调色：按 [ProviderCatalog.PRESETS] 的顺序轮换 Lab 主题色板。
 *
 * ⚠️ 每次调用现取（不缓存在顶层 val 里）：`Brew*` 是主题色 getter，
 * 顶层 val 会把首次访问时的颜色冻住，换主题后不跟随。
 */
internal fun providerAccentOf(id: String): Color {
    val palette = listOf(BrewChat, BrewCyan, BrewPurple, BrewAmber, BrewTeal, BrewPink, BrewMagenta, BrewCoral)
    val index = ProviderCatalog.PRESETS.indexOfFirst { it.id == id }
    return palette[if (index < 0) 0 else index % palette.size]
}

/**
 * 供应商头像：圆形品牌色底 + 品牌图标（无图标时用名字首字母）。
 *
 * @param accent 品牌强调色（调用方按 index 轮换 Lab 色板）
 * @param circleSize 圆底直径；@param iconSize 图标边长（首字母时字号自动按比例）
 */
@Composable
internal fun ProviderAvatar(
    id: String,
    name: String,
    accent: Color,
    circleSize: Dp,
    iconSize: Dp,
    modifier: Modifier = Modifier,
) {
    val res = ProviderIcons.resOf(id)
    Box(
        modifier = modifier
            .size(circleSize)
            .clip(CircleShape)
            .background(accent.copy(alpha = 0.18f))
            .border(1.dp, accent.copy(alpha = 0.5f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (res != 0) {
            Icon(
                painter = painterResource(res),
                contentDescription = name,
                tint = accent,
                modifier = Modifier.size(iconSize),
            )
        } else {
            Text(
                text = name.firstOrNull()?.uppercase() ?: "?",
                color = accent,
                fontSize = (iconSize.value * 0.78f).sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}
