package com.rokidlab.phone.platform

/**
 * 能力探测结果：取代「直接调用 + catch 吞掉」的静默失败模式。
 *
 * 每个 hook 的成败都通过 [Capability] 显式表达，调用方据此决定降级策略，
 * 而不是在 Exception 黑洞里无感知地失败（这正是原 12 处 hook 的通病）。
 */
sealed interface Capability<out T> {
    val isAvailable: Boolean get() = this is Available<T>
    val reasonOrNull: String? get() = (this as? Unavailable)?.reason

    data class Available<out T>(val value: T) : Capability<T>
    data class Unavailable(val reason: String) : Capability<Nothing>
}

inline fun <T> Capability<T>.onAvailable(block: (T) -> Unit): Capability<T> {
    if (this is Capability.Available) block(value)
    return this
}

inline fun <T> Capability<T>.onUnavailable(block: (String) -> Unit): Capability<T> {
    if (this is Capability.Unavailable) block(reason)
    return this
}
