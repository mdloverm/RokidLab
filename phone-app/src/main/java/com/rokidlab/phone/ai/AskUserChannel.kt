package com.rokidlab.phone.ai

import android.content.Context
import android.util.Log
import com.rokid.cxr.Caps
import com.rokidlab.phone.glasses.AiChannel
import com.rokidlab.phone.permission.PermissionBridge
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * L4 agent/AskUserChannel —— ask_user 伪工具的**作答通道**（手机端点选 + 眼镜端提醒）。
 *
 * 结构照抄 [PhoneToolConfirmChannel]（透明 Activity 拉起 + latch 阻塞等待 + 超时语义），
 * 但语义不同：
 *  - 工具确认问的是「允许/取消」（二值放行判断）；本通道问的是「A/B/C 你选哪个」（收集作答），
 *    作答原文（选项 label 或自由输入）要**原文回填给模型**。
 *  - 「没有作答」（超时/页面被回收）≠「用户跳过」：前者回 [AskUser.TIMEOUT_NOTE]
 *    （不许臆测、可稍后再问），后者回 [AskUser.DECLINED_NOTE]（按默认走、但要说明）。
 *    这个区分必须由界面如实传，不能在这里猜 —— 与 ToolConfirmActivity 的 cancelled 语义同理。
 *
 * ## 眼镜端为什么只做「提醒」不做「点选」（2026-10-12 设计取舍）
 * 眼镜没有承载 N 选一的输入手段：按键只有短按/双击/长按三种手势（[ToolConfirmController]
 * 用它们表达允许/取消已经占满）；语音作答要走手机 ASR → 会触发新一轮 sendAiTextMessage
 * → 代际 +1 把正在等待的任务顶掉（自我打断）。所以眼镜在线时只推一条 TTS
 * 「有问题需要你选择，请看手机」（[AiChannel.TOPIC_TTS_PLAY]），作答仍回到手机弹窗。
 *
 * ⚠️ [ask] 是**阻塞**调用（最长 [ASK_TIMEOUT_MS]），调用方必须是非主线程
 * —— ask_user 是 READ_ONLY 档伪工具，走并行泳道专属线程，满足。
 */
internal object AskUserChannel {

    private const val TAG = "AskUser"

    /**
     * 等待上限。比工具确认（手机 40s）宽：做选择要读题、比较选项，用户还可能在忙别的；
     * 但也不能无限等 —— 这段等待发生在工具循环里，整轮回复都被它挡住。
     * 超时按「没问到」回填 [AskUser.TIMEOUT_NOTE]，模型可自行决定再问或走默认。
     */
    private const val ASK_TIMEOUT_MS = 120_000L

    /** 一次作答的结果 */
    class Answer(
        val declined: Boolean,
        val selected: List<String>,
        val freeText: String,
    )

    private val seq = AtomicLong(0)
    private val pending = ConcurrentHashMap<String, Waiter>()

    private class Waiter {
        val latch = CountDownLatch(1)
        @Volatile var answer: Answer? = null

        /** 页面被回收/异常退出而**没有**作答：countDown 但 [answer] 保持 null = 没问到 */
        @Volatile var dismissed = false
    }

    @Volatile
    private var appContext: Context? = null

    /** 注入（`LabApplication.onCreate` 调用，与 [PhoneToolConfirmChannel.init] 并排） */
    fun init(context: Context) {
        appContext = context.applicationContext
        Log.i(TAG, "ask user channel registered")
    }

    /**
     * 供主循环调用的便捷入口：解析参数 → 弹窗 → 返回**回填给模型的文本**。
     *
     * ⚠️ 三种"没作答"语义在这里分流：参数坏 = 用法说明；拉不起界面 = [AskUser.UNAVAILABLE_NOTE]
     * （让模型把选项写进回复正文等文字回复，**绝不假装问过**）；超时 = [AskUser.TIMEOUT_NOTE]。
     */
    fun askParsed(appContext: Context, arguments: String): String {
        val q = AskUser.parse(arguments) ?: return AskUser.USAGE_NOTE
        if (!PermissionBridge.canLaunchUi(appContext)) return AskUser.UNAVAILABLE_NOTE
        val answer = ask(q) ?: return AskUser.TIMEOUT_NOTE
        return if (answer.declined) {
            AskUser.DECLINED_NOTE
        } else {
            AskUser.formatAnswer(answer.selected, answer.freeText)
        }
    }

    /**
     * 阻塞等待用户在手机上作答。返回 null = 没问到（超时 / 拉不起界面 / 被中断兜底），
     * 调用方按 [AskUser.TIMEOUT_NOTE] 语义分流。
     */
    fun ask(question: AskUser.Question): Answer? {
        val ctx = appContext ?: return null
        // 与 PhoneToolConfirmChannel 同一判据：能否把界面拉起来是全项目唯一的
        // PermissionBridge.canLaunchUi —— 不另起一套，拉不起来就如实说"没问到"。
        if (!PermissionBridge.canLaunchUi(ctx)) {
            Log.w(TAG, "cannot launch ask dialog (no BAL exemption / not foreground)")
            return null
        }
        notifyGlasses(question)
        val id = "au-${System.currentTimeMillis()}-${seq.incrementAndGet()}"
        val waiter = Waiter()
        pending[id] = waiter
        return try {
            ctx.startActivity(
                // ⚠️ 把等待上限一并传进界面（2026-10-13 审计修 M2）：界面据此在**同一时刻**
                //   自我收尾 —— 否则通道超时后弹层还留在屏幕上，用户之后点选只会落进
                //   "unknown/expired id"，看不到任何反馈，误以为自己还能作答。
                AskUserActivity.createIntent(ctx, id, question, ASK_TIMEOUT_MS),
            )
            if (!waiter.latch.await(ASK_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                Log.w(TAG, "ask timeout (id=$id)")
                null
            } else {
                waiter.answer
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        } catch (e: Exception) {
            // BAL 拦截是静默丢弃；能走到这里的只有 Activity 未注册/系统拒绝启动这类硬错误
            Log.w(TAG, "ask launch failed (id=$id): ${e.javaClass.simpleName}: ${e.message}")
            null
        } finally {
            pending.remove(id)
        }
    }

    /**
     * 眼镜端提醒（尽力而为，失败静默）：TTS 一句「有问题需要你选择，请看手机」。
     * 不下发选项原文 —— 眼镜上念一堆选项既念不完也无法作答，只会把用户绕晕。
     */
    private fun notifyGlasses(question: AskUser.Question) {
        runCatching {
            val s = GlassToolConfirmChannel.global.liveSession() ?: return
            val link = s.cxrLink ?: return
            val caps = Caps()
            AiChannel.encodeTtsPlay("我有一个问题需要你选择，请拿出手机查看").forEach { caps.write(it) }
            val r = s.rawSendCustomCmd(link, AiChannel.TOPIC_TTS_PLAY, caps)
            Log.i(TAG, "glasses tts hint sent -> $r (q=${question.text.take(20)})")
        }.onFailure { Log.w(TAG, "glasses tts hint failed: ${it.message}") }
    }

    /**
     * 用户作答回执（由 [AskUserActivity] 调用，主线程）。
     *
     * @return true = 被认领；false = 已超时/已被中止 ⇒ 界面应给"已过期"反馈，
     *   而不是静默关闭（2026-10-13 审计修 M2）。
     */
    fun onAnswer(requestId: String, declined: Boolean, selected: List<String>, freeText: String): Boolean {
        val w = pending.remove(requestId) ?: run {
            Log.w(TAG, "ask result for unknown/expired id=$requestId")
            return false
        }
        w.answer = Answer(declined, selected, freeText)
        w.latch.countDown()
        Log.i(TAG, "ask result (id=$requestId declined=$declined selected=${selected.size} free=${freeText.isNotEmpty()})")
        return true
    }

    /**
     * 页面被系统回收而**没有**作答：按「没问到」处理（≠ 跳过），等待方拿到 null 走超时文案。
     *
     * @return true = 被认领（对应等待者还在）
     */
    fun onDismiss(requestId: String): Boolean {
        val w = pending.remove(requestId) ?: return false
        w.dismissed = true
        w.latch.countDown()
        Log.w(TAG, "ask dialog dismissed without answer (id=$requestId)")
        return true
    }

    /**
     * 清空全部等待中的作答（App 切后台拉不起界面、连接流程重启等场景），
     * 避免工具循环白挂满 120s。与 [PhoneToolConfirmChannel.abortAll] 对称。
     */
    fun abortAll() {
        val n = pending.size
        pending.values.forEach { it.latch.countDown() }
        pending.clear()
        if (n > 0) Log.w(TAG, "aborted $n pending asks")
    }
}
