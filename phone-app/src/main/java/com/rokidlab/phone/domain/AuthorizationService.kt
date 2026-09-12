package com.rokidlab.phone.domain

import android.util.Log
import com.rokid.sprite.aiapp.externalapp.auth.GlassPermission
import com.rokidlab.phone.platform.Capability
import com.rokidlab.phone.platform.CapabilityProbe

/**
 * L3 domain/AuthorizationService —— Phase 3 拆 `CxrLHiRokidSession`（2939 行）的第一个 domain 服务。
 *
 * 职责：眼镜端授权相关的"补齐 SDK 内部权限列表"动作。我们通过 ComponentName 直接打开
 * AuthorizationActivity 完成授权（避免 ContentProvider 查询在 Android 15 上受限），绕过了
 * `AuthorizationHelper.requestAuthorization()`。但 SDK 的 `takePhoto()` / `startAudioStream()`
 * 会检查静态权限数组 `AuthorizationHelper.b`（仅在 `requestAuthorization()` 中填充），绕过后恒为空，
 * 导致拍照/录音被拒 —— 因此授权成功（或恢复 token）后需手动补齐。
 *
 * 依赖：仅 L0 `platform/`（[CapabilityProbe.sdkBridge]）。不依赖传输层 / 会话层。
 *
 * 本轮范围：仅迁移 `grantGlassPermissions()`。`requestAuthorization()` / `handleAuthorizationResult()`
 * / token 持久化等仍留在 `CxrLHiRokidSession` 门面（它们需要 `authLauncher` / `activityRef` /
 * `hostApp` 等紧耦合状态，后续按域逐个迁移）。
 */
class AuthorizationService {
    private companion object { const val TAG = "AuthorizationService" }

    /**
     * 补齐 SDK 内部权限列表（MICROPHONE / CAMERA / MEDIA + flag）。
     * 走 L0 [com.rokidlab.phone.platform.SdkBridge]，反射失败原因以 [Capability.Unavailable.reason] 上报。
     */
    fun grantGlassPermissions() {
        when (
            val r = CapabilityProbe.sdkBridge.grantPermissions(
                arrayOf(
                    GlassPermission.MICROPHONE,
                    GlassPermission.CAMERA,
                    GlassPermission.MEDIA,
                ),
            )
        ) {
            is Capability.Available ->
                Log.i(TAG, "glass permissions granted: MICROPHONE/CAMERA/MEDIA, flag=true")
            is Capability.Unavailable ->
                Log.e(TAG, "grant glass permissions failed: ${r.reason}")
        }
    }
}
