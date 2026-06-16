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
import com.rokidlab.phone.R
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
import androidx.compose.ui.platform.LocalContext
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
    val ctx = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BrewBg)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = ctx.getString(R.string.guide_welcome),
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
        
        GuideProgressIndicator(currentStep = currentStep, ctx = ctx)
        
        Spacer(modifier = Modifier.height(48.dp))
        
        when (currentStep) {
            GuideStep.SELECT_HOST_APP -> SelectHostAppStep(
                selectedHostApp = selectedHostApp,
                onSelectHostApp = onSelectHostApp,
                ctx = ctx,
            )
            GuideStep.SELECT_MIRROR_SOURCE -> SelectMirrorSourceStep(
                onSelectMirrorSource = onSelectMirrorSource,
                ctx = ctx,
            )
            GuideStep.AUTHORIZE -> AuthorizeStep(
                authorized = authorized,
                onAuthorize = onAuthorize,
                ctx = ctx,
            )
            GuideStep.READY -> { }
        }
        
        Spacer(modifier = Modifier.height(32.dp))
        
        StepInstructions(currentStep = currentStep, ctx = ctx)
    }
}

@Composable
private fun GuideProgressIndicator(currentStep: GuideStep, ctx: android.content.Context) {
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
                        GuideStep.SELECT_HOST_APP -> ctx.getString(R.string.guide_select_version_step)
                        GuideStep.SELECT_MIRROR_SOURCE -> ctx.getString(R.string.guide_select_source_step)
                        GuideStep.AUTHORIZE -> ctx.getString(R.string.guide_authorize_step)
                        GuideStep.READY -> ctx.getString(R.string.guide_ready_step)
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
    ctx: android.content.Context,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = ctx.getString(R.string.guide_step1_title_local),
            color = BrewText,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        Text(
            text = ctx.getString(R.string.guide_step1_desc),
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
    ctx: android.content.Context,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = ctx.getString(R.string.guide_step2_title_local),
            color = BrewText,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        Text(
            text = ctx.getString(R.string.guide_step2_desc),
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
                text = ctx.getString(R.string.select_mirror_source),
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
    ctx: android.content.Context,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = ctx.getString(R.string.guide_step3_title_local),
            color = BrewText,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        Text(
            text = ctx.getString(R.string.guide_step3_desc),
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
                    text = ctx.getString(R.string.authorized) + " ✓",
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
                    text = ctx.getString(R.string.authorize_btn),
                    color = BrewBg,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Text(
            text = ctx.getString(R.string.guide_step3_desc),
            color = BrewMuted,
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun StepInstructions(currentStep: GuideStep, ctx: android.content.Context) {
    val instructions = when (currentStep) {
        GuideStep.SELECT_HOST_APP -> ctx.getString(R.string.guide_step1_desc)
        GuideStep.SELECT_MIRROR_SOURCE -> ctx.getString(R.string.guide_step2_desc)
        GuideStep.AUTHORIZE -> ctx.getString(R.string.guide_step3_desc)
        GuideStep.READY -> ""
    }
    
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = instructions,
            color = BrewMuted,
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
        )
    }
}
