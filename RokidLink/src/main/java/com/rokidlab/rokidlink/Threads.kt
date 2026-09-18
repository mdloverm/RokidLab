package com.rokidlab.rokidlink

import kotlin.concurrent.thread

/**
 * 统一的命名线程构造入口（v3.9 工程卫生）：
 * 线程名必须可辨识，否则 ANR/battery 追踪里全是 "Thread-N"，无法定位泄漏源头。
 *
 * @param start 默认 false（返回未启动线程，交由调用方按原时机 start）；
 *              原代码是构造即启动的（`Thread{...}.start()`），传 start=true 并删掉原 `.start()`。
 * @param daemon 原代码 `.apply { isDaemon = true }` 的传 true。
 */
fun namedThread(name: String, daemon: Boolean = false, start: Boolean = false, block: () -> Unit): Thread =
    thread(start = false, isDaemon = daemon, name = name, block = block).also { if (start) it.start() }
