package com.rokidlab.phone.platform

import com.rokid.cxr.Caps
import com.rokid.cxr.link.CXRLink
import com.rokid.sprite.aiapp.externalapp.IMediaStreamService
import com.rokid.sprite.aiapp.externalapp.auth.AuthorizationHelper
import com.rokid.sprite.aiapp.externalapp.auth.GlassPermission
import java.lang.reflect.Field

/**
 * 唯一允许触碰 CXR-L 私有 API / SDK 内部字段的边界。
 *
 * 所有反射调用集中在此，失败以 [Capability] 显式上报，绝不静默吞掉。
 * 上层（CxrLHiRokidSession 等）只依赖本接口，SDK/ROM 升级时只需改这里。
 *
 * **字段探测策略**：候选名（含语义化别名）+ 类型校验（见 [SdkFieldMap]），
 * 名字全不中时对「类内唯一类型」做类型扫描兜底。这样 SDK 混淆名变化（b → mediaStreamService）
 * 或候选名猜错都不会取到异类型字段。
 */
interface SdkBridge {
    /**
     * 绕过 ExternalAppClient 的 cmd 黑名单，直发 Sys/Ai 等保留频道
     * （反射取私有 IMediaStreamService）。
     */
    fun rawSendCustomCmd(link: CXRLink, cmd: String, caps: Caps): Capability<Int>

    /**
     * 补齐 SDK 内部静态权限数组 + 授权标志，
     * 使 takePhoto()/startAudioStream() 在 Android 15 可用。
     */
    fun grantPermissions(perms: Array<GlassPermission>): Capability<Unit>
}

/** 默认实现：反射细节集中在此，失败以 reason 显式上报。 */
class DefaultSdkBridge : SdkBridge {

    private companion object {
        /** IMediaStreamService 字段候选名：混淆短名优先，其后为语义化明文名（未混淆构建）。 */
        val MEDIA_STREAM_FIELD_CANDIDATES = listOf(
            "b",
            "mediaStreamService",
            "mMediaStreamService",
            "iMediaStreamService",
        )

        /** AuthorizationHelper 权限数组字段候选名。 */
        val PERM_ARRAY_FIELD_CANDIDATES = listOf(
            "b",
            "permissions",
            "mPermissions",
        )

        /** AuthorizationHelper 授权标志字段候选名。 */
        val AUTH_FLAG_FIELD_CANDIDATES = listOf(
            "c",
            "authorized",
            "isAuthorized",
            "mAuthorized",
        )
    }

    override fun rawSendCustomCmd(link: CXRLink, cmd: String, caps: Caps): Capability<Int> {
        val type = IMediaStreamService::class.java
        val field = when (val byName = SdkFieldMap.instanceFieldOfType(link, MEDIA_STREAM_FIELD_CANDIDATES, type)) {
            is Capability.Available -> byName
            is Capability.Unavailable -> when (val byScan = SdkFieldMap.scanInstanceFieldOfType(link, type)) {
                is Capability.Available -> byScan
                is Capability.Unavailable -> return Capability.Unavailable(
                    "IMediaStreamService 字段探测失败：按名(${byName.reason}) / 按类型(${byScan.reason})"
                )
            }
        }
        val svc = runCatching { field.value.get(link) as? IMediaStreamService }.getOrNull()
            ?: return Capability.Unavailable("IMediaStreamService 字段存在但取值为空/类型不符")
        return runCatching { Capability.Available(svc.sendCustomCmd(cmd, caps.serialize())) }
            .getOrElse { Capability.Unavailable("sendCustomCmd 调用失败: ${it.message}") }
    }

    override fun grantPermissions(perms: Array<GlassPermission>): Capability<Unit> {
        val clazz = AuthorizationHelper::class.java
        val arrayType = Array<GlassPermission>::class.java
        val boolType = java.lang.Boolean.TYPE

        val arrayField: Field = when (
            val byName = SdkFieldMap.staticFieldOfType(clazz, PERM_ARRAY_FIELD_CANDIDATES, arrayType)
        ) {
            is Capability.Available -> byName.value
            is Capability.Unavailable -> when (
                val byScan = SdkFieldMap.scanStaticFieldOfType(clazz, arrayType)
            ) {
                is Capability.Available -> byScan.value
                is Capability.Unavailable -> return Capability.Unavailable(
                    "AuthorizationHelper 权限数组字段探测失败：按名(${byName.reason}) / 按类型(${byScan.reason})"
                )
            }
        }

        // 授权标志是 boolean：类内同类型字段可能不止一个，故**不做**类型扫描兜底，只按候选名
        val flagField: Field = when (
            val byName = SdkFieldMap.staticFieldOfType(clazz, AUTH_FLAG_FIELD_CANDIDATES, boolType)
        ) {
            is Capability.Available -> byName.value
            is Capability.Unavailable -> return Capability.Unavailable("授权标志字段不可用: ${byName.reason}")
        }

        runCatching {
            arrayField.set(null, perms)
            flagField.setBoolean(null, true)
        }.onFailure { e ->
            return Capability.Unavailable("设置权限数组/标志失败: ${e.message}")
        }
        return Capability.Available(Unit)
    }
}
