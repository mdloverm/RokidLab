package com.rokidlab.phone.store

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.TableChart
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rokidlab.phone.R
import com.rokidlab.phone.design.BrewBorder
import com.rokidlab.phone.design.BrewChat
import com.rokidlab.phone.design.BrewMuted
import com.rokidlab.phone.design.BrewPanel
import com.rokidlab.phone.design.BrewPanelAlt
import com.rokidlab.phone.design.BrewTextBright

/**
 * Markdown 表格卡：与代码卡/图片卡/文件卡同一套卡片语言（标题栏 + 内容区）。
 *
 * 为什么必须是**横向可滚动**而不是自适应列宽：Agent 输出的表格常见 6~10 列
 * （对比表、参数表），在手机宽度下若强行压缩，每列只剩两三个字、比不渲染还难读。
 * 列宽走"内容决定 + 设下限"，超宽就横向滚 —— 这是手机上看宽表的通行做法。
 */
@Composable
internal fun MarkdownTableCard(table: MdBlock.Table) {
    val scroll = rememberScrollState()
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
                .height(30.dp)
                .background(BrewChat.copy(alpha = 0.12f))
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.TableChart,
                contentDescription = null,
                tint = BrewChat,
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = stringResource(R.string.chat_table_label),
                color = BrewChat,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.8.sp,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.chat_table_shape, table.rows.size, table.header.size),
                color = BrewMuted,
                fontSize = 10.sp,
            )
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(BrewBorder),
        )
        Column(
            modifier = Modifier
                .horizontalScroll(scroll)
                .padding(vertical = 2.dp),
        ) {
            TableRow(cells = table.header, aligns = table.aligns, isHeader = true)
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(BrewBorder),
            )
            table.rows.forEachIndexed { idx, row ->
                // 斑马纹：宽表里行与行容易串行，交替底色是最省事的区分手段
                TableRow(
                    cells = row,
                    aligns = table.aligns,
                    isHeader = false,
                    striped = idx % 2 == 1,
                )
            }
        }
    }
}

/** 一行单元格；表头加粗、用强调色，正文用常规色 */
@Composable
private fun TableRow(
    cells: List<String>,
    aligns: List<Char>,
    isHeader: Boolean,
    striped: Boolean = false,
) {
    Row(
        modifier = Modifier
            .background(
                when {
                    isHeader -> BrewPanelAlt
                    striped -> BrewPanelAlt.copy(alpha = 0.45f)
                    else -> BrewPanel
                },
            )
            .padding(vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        cells.forEachIndexed { idx, cell ->
            Text(
                text = inlineAnnotated(cell, if (isHeader) BrewTextBright else BrewTextBright),
                color = if (isHeader) BrewTextBright else BrewTextBright,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                fontWeight = if (isHeader) FontWeight.SemiBold else FontWeight.Normal,
                textAlign = when (aligns.getOrNull(idx)) {
                    'c' -> TextAlign.Center
                    'r' -> TextAlign.End
                    else -> TextAlign.Start
                },
                maxLines = 4,
                // 单元格最小宽度：太窄会把"列"压成竖排文字；上限防止某一列吃掉全部宽度
                modifier = Modifier
                    .widthIn(min = 72.dp, max = 200.dp)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            )
            if (idx != cells.lastIndex) {
                Box(
                    Modifier
                        .width(1.dp)
                        .height(30.dp)
                        .background(BrewBorder),
                )
            }
        }
    }
}
