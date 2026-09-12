package com.rokidlab.phone.platform

import java.lang.reflect.Field

/**
 * SDK 私有字段映射：把硬编码的单字母魔名（b/c/d）收敛成「多候选名 + 类型校验探测」。
 *
 * SDK 升级时字段名可能变化（例如 b → mediaStreamService），这里依次尝试候选名，
 * 首个**名字命中且类型匹配**的字段即返回；全部失败返回 [Capability.Unavailable]，
 * 让上层显式降级，而不是 [NoSuchFieldException] 被吞进 `catch (Exception) {}`。
 *
 * **为什么必须做类型校验**：候选名里难免有"猜"的成分（如 `service` / `permissions`）。
 * 若只按名字命中，一个同名但类型不同的字段会被误取，导致更难排查的 `ClassCastException`
 * 或静默错值。类型校验让"多猜几个候选名"变得**安全** —— 猜错就被跳过。
 *
 * 两级探测策略：
 * 1. [instanceFieldOfType] / [staticFieldOfType]：按候选名找，且要求类型匹配（首选）。
 * 2. [scanInstanceFieldOfType]：纯类型扫描（名字全不中时的最后手段，仅用于目标类型在类内唯一时）。
 *
 * 全仓只有本文件允许出现 getDeclaredField / setAccessible / superclass 遍历。
 */
object SdkFieldMap {

    /** 在对象自身及其所有父类里按候选名查找实例字段（不校验类型，保留给已知精确名场景）。 */
    fun instanceField(holder: Any, candidates: List<String>): Capability<Field> =
        instanceFieldOfType(holder, candidates, null)

    /** 按候选名查找静态字段（不校验类型）。 */
    fun staticField(clazz: Class<*>, candidates: List<String>): Capability<Field> =
        staticFieldOfType(clazz, candidates, null)

    /**
     * 在对象自身及其所有父类里查找实例字段：**名字命中且类型匹配**才算成功。
     *
     * @param type 期望类型；字段必须可赋值给该类型。传 null 表示不校验（仅按名字）。
     */
    fun instanceFieldOfType(holder: Any, candidates: List<String>, type: Class<*>?): Capability<Field> {
        var clazz: Class<*>? = holder.javaClass
        while (clazz != null) {
            for (name in candidates) {
                val found = runCatching { clazz.getDeclaredField(name) }.getOrNull() ?: continue
                if (type != null && !type.isAssignableFrom(found.type)) continue
                found.isAccessible = true
                return Capability.Available(found)
            }
            clazz = clazz.superclass
        }
        return Capability.Unavailable(
            "找不到实例字段（候选=$candidates, 期望类型=${type?.name ?: "任意"}）于 ${holder.javaClass.name}"
        )
    }

    /** 按候选名查找静态字段：名字命中且类型匹配才算成功。 */
    fun staticFieldOfType(clazz: Class<*>, candidates: List<String>, type: Class<*>?): Capability<Field> {
        for (name in candidates) {
            val found = runCatching { clazz.getDeclaredField(name) }.getOrNull() ?: continue
            if (type != null && !type.isAssignableFrom(found.type)) continue
            found.isAccessible = true
            return Capability.Available(found)
        }
        return Capability.Unavailable(
            "找不到静态字段（候选=$candidates, 期望类型=${type?.name ?: "任意"}）于 ${clazz.name}"
        )
    }

    /**
     * 纯类型扫描静态字段（名字全不中时的最后手段）：返回类中**第一个**可赋值给 [type] 的字段。
     * 仅应在「目标类型在类内唯一」时使用。
     */
    fun scanStaticFieldOfType(clazz: Class<*>, type: Class<*>): Capability<Field> {
        for (f in clazz.declaredFields) {
            if (type.isAssignableFrom(f.type)) {
                f.isAccessible = true
                return Capability.Available(f)
            }
        }
        return Capability.Unavailable("类型扫描失败：${clazz.name} 内无 ${type.name} 类型静态字段")
    }

    /**
     * 纯类型扫描实例字段（名字全不中时的最后手段）：返回类中**第一个**可赋值给 [type] 的字段。
     *
     * 仅应在「目标类型在类内唯一」时使用，否则可能取到语义无关的同类型字段。
     */
    fun scanInstanceFieldOfType(holder: Any, type: Class<*>): Capability<Field> {
        var clazz: Class<*>? = holder.javaClass
        while (clazz != null) {
            for (f in clazz.declaredFields) {
                if (type.isAssignableFrom(f.type)) {
                    f.isAccessible = true
                    return Capability.Available(f)
                }
            }
            clazz = clazz.superclass
        }
        return Capability.Unavailable("类型扫描失败：${holder.javaClass.name} 内无 ${type.name} 类型字段")
    }
}
