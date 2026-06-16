package com.rokidlab.phone.glasses

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
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 引导界面 - 指导用户完成前置条件
 */
@Composable
internal fun GuideScreen(
    currentStep: GuideStep,
    selectedHostApp: RokidHostApp?,
    authorized: Boolean,
    onSelectHostApp: (RokidHostApp) -> Unit,
    onSelectMirrorSource: () -> Unit,
    onAuthorize: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BrewBg)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // 标题
        Text(
            text = "欢迎使用 Rokid Lab",
            color = BrewGreen,
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 2.sp,
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        Text(
            text = "by DLOVER",
            color = BrewMuted,
            fontSize = 14.sp,
        )
        
        Spacer(modifier = Modifier.height(48.dp))
        
        // 进度指示器
        GuideProgressIndicator(currentStep = currentStep)
        
        Spacer(modifier = Modifier.height(48.dp))
        
        // 根据当前步骤显示不同的引导内容
        when (currentStep) {
            GuideStep.SELECT_HOST_APP -> SelectHostAppStep(
                selectedHostApp = selectedHostApp,
                onSelectHostApp = onSelectHostApp,
            )
            GuideStep.SELECT_MIRROR_SOURCE -> SelectMirrorSourceStep(
                onSelectMirrorSource = onSelectMirrorSource,
            )
            GuideStep.AUTHORIZE -> AuthorizeStep(
                authorized = authorized,
                onAuthorize = onAuthorize,
            )
            GuideStep.READY -> {
                // 已完成所有步骤，不应该显示此界面
            }
        }
        
        Spacer(modifier = Modifier.height(32.dp))
        
        // 步骤说明
        StepInstructions(currentStep = currentStep)
    }
}

@Composable
private fun GuideProgressIndicator(currentStep: GuideStep) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GuideStep.values().forEachIndexed { index, step ->
                val isCompleted = index < currentStep.ordinal
                val isCurrent = index == currentStep.ordinal
                val stepDotColor = when {
                    isCompleted -> BrewSuccess
                    isCurrent -> when (step) {
                        GuideStep.SELECT_HOST_APP -> BrewGreen
                        GuideStep.SELECT_MIRROR_SOURCE -> BrewCyan
                        GuideStep.AUTHORIZE -> BrewMagenta
                        GuideStep.READY -> BrewSuccess
                    }
                    else -> BrewPanel
                }
                
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(
                            color = stepDotColor,
                            shape = RoundedCornerShape(20.dp),
                        )
                        .border(
                            width = 2.dp,
                            color = when {
                                isCompleted -> BrewSuccess
                                isCurrent -> stepDotColor
                                else -> BrewBorder
                            },
                            shape = RoundedCornerShape(20.dp),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "${index + 1}",
                        color = when {
                            isCompleted || isCurrent -> BrewBg
                            else -> BrewMuted
                        },
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
                
                if (index < GuideStep.values().size - 1) {
                    Box(
                        modifier = Modifier
                            .width(32.dp)
                            .height(2.dp)
                            .background(
                                if (isCompleted) BrewSuccess else BrewBorder,
                            ),
                    )
                }
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Row(
            horizontalArrangement = Arrangement.spacedBy(32.dp),
        ) {
            GuideStep.values().forEach { step ->
                Text(
                    text = when (step) {
                        GuideStep.SELECT_HOST_APP -> "选择版本"
                        GuideStep.SELECT_MIRROR_SOURCE -> "选择源"
                        GuideStep.AUTHORIZE -> "授权"
                        GuideStep.READY -> "完成"
                    },
                    color = BrewMuted,
                    fontSize = 12.sp,
                )
            }
        }
    }
}

@Composable
private fun SelectHostAppStep(
    selectedHostApp: RokidHostApp?,
    onSelectHostApp: (RokidHostApp) -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "第一步：选择 Rokid AI 版本",
            color = BrewText,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        Text(
            text = "请选择您使用的 Rokid 眼镜型号",
            color = BrewMuted,
            fontSize = 14.sp,
        )
        
        Spacer(modifier = Modifier.height(32.dp))
        
        RokidHostApp.values().forEach { hostApp ->
            val isSelected = selectedHostApp == hostApp
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        color = if (isSelected) BrewGreen else BrewPanel,
                        shape = RoundedCornerShape(12.dp),
                    )
                    .border(
                        width = 1.dp,
                        color = if (isSelected) BrewGreen else BrewBorder,
                        shape = RoundedCornerShape(12.dp),
                    )
                    .clickable { onSelectHostApp(hostApp) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = hostApp.displayName,
                    color = if (isSelected) BrewBg else BrewText,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

@Composable
private fun SelectMirrorSourceStep(
    onSelectMirrorSource: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "第二步：选择商店源",
            color = BrewText,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        Text(
            text = "选择应用商店的镜像源",
            color = BrewMuted,
            fontSize = 14.sp,
        )
        
        Spacer(modifier = Modifier.height(32.dp))
        
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(BrewGreen, shape = RoundedCornerShape(12.dp))
                .clickable { onSelectMirrorSource() },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "选择商店源",
                color = BrewBg,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun AuthorizeStep(
    authorized: Boolean,
    onAuthorize: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "第三步：授权眼镜",
            color = BrewText,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        Text(
            text = "需要在眼镜端授权此应用",
            color = BrewMuted,
            fontSize = 14.sp,
        )
        
        Spacer(modifier = Modifier.height(32.dp))
        
        if (authorized) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .background(BrewSuccess, shape = RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "已授权 ✓",
                    color = BrewBg,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(BrewGreen, shape = RoundedCornerShape(12.dp))
                    .clickable { onAuthorize() },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "点击授权",
                    color = BrewBg,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Text(
            text = "请确保眼镜已开启并运行 Rokid Space/Vision",
            color = BrewMuted,
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun StepInstructions(currentStep: GuideStep) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(BrewPanel, shape = RoundedCornerShape(12.dp))
            .border(
                width = 1.dp,
                color = BrewBorder,
                shape = RoundedCornerShape(12.dp),
            )
            .padding(16.dp),
    ) {
        Text(
            text = when (currentStep) {
                GuideStep.SELECT_HOST_APP -> "提示：选择正确的眼镜型号可以确保应用兼容性"
                GuideStep.SELECT_MIRROR_SOURCE -> "提示：如果默认源加载缓慢，可以尝试切换其他源"
                GuideStep.AUTHORIZE -> "提示：授权后，应用将能够管理眼镜上的应用安装"
                GuideStep.READY -> ""
            },
            color = BrewMuted,
            fontSize = 12.sp,
            lineHeight = 20.sp,
        )
    }
}
