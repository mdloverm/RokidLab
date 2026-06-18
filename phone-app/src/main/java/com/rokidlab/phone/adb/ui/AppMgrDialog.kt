package com.rokidlab.phone.adb.ui

import com.rokidlab.phone.adb.AdbShellClient
import com.rokidlab.phone.design.*
import com.rokidlab.phone.R
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.LinearEasing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

@Composable
fun AppMgrDialog(
    client: AdbShellClient?,
    connected: Boolean,
    scope: kotlinx.coroutines.CoroutineScope,
    getOrConnect: ((AdbShellClient?) -> Unit) -> Unit,
    onDismiss: () -> Unit,
) {
    val ctx = LocalContext.current
    AdbDialogContent(ctx.getString(R.string.app_manager), BrewGreen, client, connected, scope, getOrConnect, onDismiss) { c ->
        var packages by remember { mutableStateOf(emptyList<String>()) }
        var disabledPkgs by remember { mutableStateOf(emptySet<String>()) }
        var showSystem by remember { mutableStateOf(false) }
        var search by remember { mutableStateOf("") }
        var loading by remember { mutableStateOf(true) }
        var selectedPkg by remember { mutableStateOf("") }
        var statusMsg by remember { mutableStateOf("") }
        
        val filtered = if (search.isBlank()) packages else packages.filter { it.contains(search, ignoreCase = true) }
        val isSelectedFrozen = selectedPkg.isNotEmpty() && (selectedPkg in disabledPkgs)
        
        LaunchedEffect(showSystem) {
            Log.w("AppMgr", "LaunchedEffect start showSystem=$showSystem")
            loading = true
            selectedPkg = ""
            try {
                val pkgs = withContext(Dispatchers.IO) { c.listPackages(showSystem) }
                packages = pkgs
            } catch (e: Exception) { Log.w("AppMgr", "listPackages FAILED: ${e.message}") }
            try {
                val disabled = withContext(Dispatchers.IO) { c.listDisabledPackages() }
                disabledPkgs = disabled
            } catch (e: Exception) { Log.w("AppMgr", "listDisabled FAILED: ${e.message}") }
            loading = false
        }

        fun refreshAll(from: String = "unknown") {
            scope.launch {
                loading = true
                selectedPkg = ""
                try {
                    val pkgs = withContext(Dispatchers.IO) { c.listPackages(showSystem) }
                    packages = pkgs
                } catch (e: Exception) { Log.w("AppMgr", "refreshAll listPackages FAILED: ${e.message}") }
                try {
                    val disabled = withContext(Dispatchers.IO) { c.listDisabledPackages() }
                    disabledPkgs = disabled
                } catch (e: Exception) { Log.w("AppMgr", "refreshAll listDisabled FAILED: ${e.message}") }
                loading = false
            }
        }
        
        fun doAction(action: suspend (String) -> String, pkg: String, shouldRefresh: Boolean = true) {
            scope.launch(Dispatchers.IO) {
                try {
                    val result = action(pkg)
                    withContext(Dispatchers.Main) {
                        statusMsg = result.lines().firstOrNull { it.isNotBlank() } ?: ctx.getString(R.string.done_label)
                        if (shouldRefresh) refreshAll("after_action")
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) { statusMsg = "${ctx.getString(R.string.error_label)}: ${e.message}" }
                }
            }
        }
        
        @Composable
        fun ActionButton(label: String, color: Color, onClick: () -> Unit) {
            Box(
                Modifier.height(40.dp).clip(BrewShapeSmall)
                    .background(color.copy(alpha = 0.12f))
                    .border(1.dp, color.copy(alpha = 0.6f), BrewShapeSmall)
                    .clickable(onClick = onClick)
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, color = color, fontSize = 13.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
            }
        }
        
        Column(modifier = Modifier.fillMaxWidth().heightIn(max = 580.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BrutalTextField(
                    value = search, onValueChange = { search = it },
                    placeholder = ctx.getString(R.string.search_apps_placeholder), color = BrewGreen,
                    modifier = Modifier.weight(1f), singleLine = true,
                )
                Spacer(Modifier.width(6.dp))
                Box(
                    Modifier.height(42.dp).clip(BrewShapeMedium)
                        .background(if (showSystem) BrewGreen.copy(alpha = 0.2f) else Color.Transparent)
                        .border(1.dp, if (showSystem) BrewGreen else BrewBorder, BrewShapeMedium)
                        .clickable { showSystem = !showSystem; selectedPkg = "" }
                        .padding(horizontal = 14.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(if (showSystem) ctx.getString(R.string.filter_all) else ctx.getString(R.string.filter_third_party), color = if (showSystem) BrewGreen else BrewMuted, fontSize = 14.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                }
                Spacer(Modifier.width(6.dp))
                val refreshTransition = rememberInfiniteTransition(label = "refreshSpin")
                val refreshAngle by refreshTransition.animateFloat(
                    initialValue = 0f, targetValue = 360f,
                    animationSpec = infiniteRepeatable(tween(800, easing = LinearEasing)),
                    label = "refreshAngle",
                )
                Box(
                    Modifier.size(42.dp).clip(BrewShapeMedium)
                        .background(BrewGreen.copy(alpha = 0.12f))
                        .border(1.dp, BrewGreen.copy(alpha = 0.5f), BrewShapeMedium)
                        .clickable { refreshAll("refresh_icon") },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Outlined.Refresh, contentDescription = ctx.getString(R.string.refresh),
                        tint = BrewGreen, modifier = Modifier.size(24.dp)
                            .graphicsLayer { rotationZ = if (loading) refreshAngle else 0f },
                    )
                }
            }
            
            Spacer(Modifier.height(8.dp))
            
            Box(
                Modifier.fillMaxWidth().clip(BrewShapeMedium)
                    .background(if (selectedPkg.isNotEmpty()) BrewGreen.copy(alpha = 0.08f) else Color.Transparent)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            ) {
                if (selectedPkg.isNotEmpty()) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        ActionButton(ctx.getString(R.string.launch), BrewSuccess) { doAction({ c.launchApp(it) }, selectedPkg) }
                        ActionButton(ctx.getString(R.string.uninstall), BrewRed) { doAction({ c.uninstallApp(it) }, selectedPkg) }
                        ActionButton(
                             if (isSelectedFrozen) ctx.getString(R.string.unfreeze) else ctx.getString(R.string.freeze),
                             if (isSelectedFrozen) BrewSuccess else BrewWarning,
                         ) {
                             if (isSelectedFrozen) {
                                 doAction({ c.enableApp(it) }, selectedPkg)
                             } else {
                                 doAction({ c.disableApp(it) }, selectedPkg)
                             }
                         }
                        ActionButton(ctx.getString(R.string.extract), BrewMagenta) {
                            val pkg = selectedPkg
                            scope.launch(Dispatchers.IO) {
                                if (pkg.isEmpty()) return@launch
                                val r = c.extractApkToDownloads(pkg)
                                Log.i("AppMgr", "extract result: $r")
                                withContext(Dispatchers.Main) { statusMsg = r }
                            }
                        }
                    }
                } else {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text(ctx.getString(R.string.select_app_below), color = BrewMuted.copy(alpha = 0.5f), fontSize = 13.sp)
                    }
                }
            }
            
            if (statusMsg.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text("  $statusMsg", color = if (statusMsg.startsWith(ctx.getString(R.string.error_label))) BrewRed else BrewGreen, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(String.format(ctx.getString(R.string.app_count_fmt), filtered.size), color = BrewMuted, fontSize = 12.sp)
                if (loading) { Spacer(Modifier.width(8.dp)); Text(ctx.getString(R.string.updating_dots), color = BrewGreen.copy(alpha = 0.6f), fontSize = 11.sp) }
            }
            Spacer(Modifier.height(2.dp))
            
            Box(Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                if (loading && packages.isEmpty()) {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        Text(ctx.getString(R.string.loading), color = BrewMuted, fontSize = 14.sp)
                    }
                } else {
                    Column {
                        filtered.forEach { pkg ->
                            val isSelected = pkg == selectedPkg
                            val isFrozen = pkg in disabledPkgs
                            Row(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
                                    .background(if (isSelected) BrewGreen.copy(alpha = 0.12f) else BrewBg)
                                    .border(if (isSelected) 1.dp else 0.dp, BrewGreen.copy(alpha = 0.3f), RoundedCornerShape(6.dp))
                                    .clickable { selectedPkg = if (isSelected) "" else pkg }
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(pkg, color = if (isSelected) BrewGreen else BrewText, fontSize = 11.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (isFrozen) {
                                    Text(" ❄️", color = BrewInfo.copy(alpha = 0.8f), fontSize = 11.sp)
                                    Spacer(Modifier.width(4.dp))
                                }
                                if (isSelected) {
                                    Text("  ✓", color = BrewGreen, fontSize = 13.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                                }
                            }
                            Spacer(Modifier.height(2.dp))
                        }
                    }
                }
            }
        }
    }
}