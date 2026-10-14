package com.rokidlab.phone.ai

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rokidlab.phone.R
import com.rokidlab.phone.design.BrewBorder
import com.rokidlab.phone.design.BrewDim
import com.rokidlab.phone.design.BrewMuted
import com.rokidlab.phone.design.BrewPanel
import com.rokidlab.phone.design.BrewSuccess
import com.rokidlab.phone.design.BrewText
import com.rokidlab.phone.design.BrewTextBright

/**
 * 用户选择页（ask_user 伪工具的**界面部分**，结构照抄
 * [com.rokidlab.phone.ai.approval.ToolConfirmActivity] 的透明 Activity 手法）。
 *
 * ## 为什么是一个透明 Activity
 * ask_user 在**后台线程**的工具循环里执行、没有 Activity 上下文，而弹窗需要 Activity。
 * 独立 Activity 后"从后台拉起选项卡"退化成一次 `startActivity`，能否成功只取决于
 * BAL 豁免（判定收口在 [com.rokidlab.phone.permission.PermissionBridge.canLaunchUi]，
 * 见 [AskUserChannel]）——拉不起来时通道如实返回"没问到"，**不会**假装问过。
 *
 * ## 交互形态（尽量少分支，保持可预期）
 *  - 有候选项：单选 = 圆点选择行，多选 = 勾选行；底部「确定」提交、「跳过」= 显式放弃；
 *  - 允许自由输入（allow_free_text）：列表下方附输入框，「确定」时**自由输入优先**于勾选项
 *    （用户打了字就是想要自定义答案）；
 *  - 「确定」但既没勾也没填 → 按「跳过」处理（用户实质上没有作答）。
 *
 * ## 界面形态（2026-10-12 用户要求）
 * 由 `AlertDialog` 改为**底部弹层**（`Theme.RokidLabPhone.BottomSheet` 的透明全屏窗口 +
 * Compose `ModalBottomSheet`，与「任务计划」浮层同一套样式）。窗口透明且不可浮动 ⇒
 * 弹层下面能直接看到聊天界面，"从底部弹出"的层次关系成立。
 *
 * ## 回执恰好一次
 * [onDestroy] 与按钮两条路都可能走到：answered 标志保证只回一次；没作答就消失
 * （系统回收）按「没问到」回报（[AskUserChannel.onDismiss]），**不冒充**「用户跳过」。
 */
class AskUserActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "AskUserActivity"
        private const val EXTRA_REQUEST_ID = "request_id"
        private const val EXTRA_QUESTION = "question"
        private const val EXTRA_TIMEOUT = "timeout_ms"

        /** 自关兜底相对通道超时的宽限（与 ToolConfirmActivity 同判据，见其 KDoc） */
        private const val EXPIRE_GRACE_MS = 400L

        /**
         * 同时只允许存在一个选择页（与 ToolConfirmActivity 同判据）：
         * 并发多个 ask_user 叠加弹窗会让用户连点两个，且后者会把前者的回执顶掉。
         */
        @Volatile
        private var active: AskUserActivity? = null

        /** 构造拉起本页的 Intent（[AskUserChannel] 是唯一调用方） */
        fun createIntent(
            context: Context,
            requestId: String,
            question: AskUser.Question,
            timeoutMs: Long,
        ): Intent =
            Intent(context, AskUserActivity::class.java)
                .putExtra(EXTRA_REQUEST_ID, requestId)
                .putExtra(EXTRA_QUESTION, toJson(question))
                .putExtra(EXTRA_TIMEOUT, timeoutMs)
                .addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP,
                )

        /** 序列化走 ask_user 的参数 JSON（AskUser.parse 的逆操作，界面侧零新格式） */
        private fun toJson(q: AskUser.Question): String {
            val opts = org.json.JSONArray()
            q.options.forEach { o ->
                val obj = org.json.JSONObject().put("label", o.label)
                if (o.hint.isNotEmpty()) obj.put("hint", o.hint)
                opts.put(obj)
            }
            return org.json.JSONObject()
                .put("question", q.text)
                .put("options", opts)
                .put("multi_select", q.multiSelect)
                .put("allow_free_text", q.allowFreeText)
                .toString()
        }
    }

    private var requestId: String = ""

    /** 是否已经回过执：保证"回执恰好一次" */
    private var answered = false

    /** 自关兜底（超时后本页要自己消失，见 [scheduleExpiry]） */
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val previous = active
        if (previous != null && previous !== this) {
            Log.i(TAG, "another ask screen is active, skip this launch")
            // ★ 2026-10-13 审计修 M1：跳过启动也必须回执（同 ToolConfirmActivity）。
            //   改造前 requestId 尚未赋值 ⇒ onDismiss("") 无人认领 ⇒ 等待方白挂满 120s，
            //   而这段挂起发生在**并行泳道**，会一路拖住整轮的 latch 汇合。
            val skippedId = intent.getStringExtra(EXTRA_REQUEST_ID).orEmpty()
            answered = true
            AskUserChannel.onDismiss(skippedId)
            finish()
            return
        }
        active = this

        requestId = intent.getStringExtra(EXTRA_REQUEST_ID).orEmpty()
        val question = intent.getStringExtra(EXTRA_QUESTION)
            ?.let { AskUser.parse(it) }
        if (question == null) {
            // 入参损坏：如实按"没问到"处理，绝不弹一个空问题让用户猜
            Log.w(TAG, "ask question missing/corrupt, finish")
            AskUserChannel.onDismiss(requestId)
            answered = true
            finish()
            return
        }
        showSheet(question)
        scheduleExpiry(intent.getLongExtra(EXTRA_TIMEOUT, 0L))
    }

    /**
     * 自关兜底（2026-10-13 审计修 M2，与 ToolConfirmActivity 同判据）。
     *
     * 通道超时（120s）后 agent 侧已按"没问到"回填结论，而弹层原先**原样留着** ——
     * 用户之后点选只会落进 "unknown/expired id"，看不到任何反馈。
     *
     * @param timeoutMs 通道本次的等待上限；≤0 = 未提供（不启用自关，保持旧行为）
     */
    private fun scheduleExpiry(timeoutMs: Long) {
        if (timeoutMs <= 0L) return
        handler.postDelayed({
            if (!answered) {
                Log.w(TAG, "ask sheet expired (id=$requestId) -> auto close")
                answered = true
                AskUserChannel.onDismiss(requestId)
                Toast.makeText(
                    this,
                    getString(R.string.ask_user_expired),
                    Toast.LENGTH_SHORT,
                ).show()
                finish()
            }
        }, timeoutMs + EXPIRE_GRACE_MS)
    }

    private fun showSheet(q: AskUser.Question) {
        setContent {
            // 只补一套"深色基底"，让 sheet 的默认色（拖拽条等）在深色面板上可读；
            // 具体前景色一律显式用 Brew* 色值，不依赖 MaterialTheme。
            MaterialTheme(
                colorScheme = darkColorScheme(
                    surface = BrewPanel,
                    background = BrewPanel,
                    onSurface = BrewText,
                    onSurfaceVariant = BrewDim,
                    primary = BrewSuccess,
                ),
            ) {
                AskUserSheet(
                    question = q,
                    onResult = { declined, selected, free -> answer(declined, selected, free) },
                )
            }
        }
    }

    private fun answer(declined: Boolean, selected: List<String>, freeText: String) {
        if (answered) return
        answered = true
        val accepted = AskUserChannel.onAnswer(requestId, declined, selected, freeText)
        // ★ 2026-10-13 审计修 M2：没被认领 ⇒ 本次提问早已作废，如实告知用户
        if (!accepted) {
            Toast.makeText(this, getString(R.string.ask_user_expired), Toast.LENGTH_SHORT).show()
        }
        finish()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (active === this) active = null
        // 页面被系统回收而**没有**作答：按「没问到」回报（等待方拿 null 走超时语义），
        // 绝不冒充「用户跳过」—— 那会让模型把"用户什么都没做"读成"用户默许了默认方案"。
        if (!answered) {
            answered = true
            AskUserChannel.onDismiss(requestId)
        }
        super.onDestroy()
    }
}

/**
 * 选项卡的底部弹层（[AskUserActivity] 唯一的界面）。
 *
 * 与原 `AlertDialog` 版本**语义完全等价**：多选 = 勾选行，单选 = 圆点行，allow_free_text 附输入框；
 * 「确定」时自由输入优先于勾选项；既没勾也没填 → 按「跳过」处理。
 *
 * ⚠️ `onDismissRequest` 刻意**什么都不做**：原实现是 `setCancelable(false)`（必须作答），
 * 这里保持同一约束 —— 下滑/点遮罩不关闭，用户只能选「跳过」显式放弃。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AskUserSheet(
    question: AskUser.Question,
    onResult: (declined: Boolean, selected: List<String>, freeText: String) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    val multiPicked = remember { mutableStateListOf<Int>() }
    var singlePicked by remember { mutableStateOf(-1) }
    var freeText by remember { mutableStateOf("") }

    ModalBottomSheet(
        onDismissRequest = { /* 不可取消：与原 setCancelable(false) 一致 */ },
        sheetState = sheetState,
        containerColor = BrewPanel,
        contentColor = BrewText,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 18.dp, end = 18.dp, bottom = 24.dp),
        ) {
            Text(
                text = stringResource(R.string.ask_user_title),
                color = BrewTextBright,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(6.dp))
            Text(text = question.text, color = BrewText, fontSize = 13.sp, lineHeight = 18.sp)

            if (question.options.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                question.options.forEachIndexed { i, o ->
                    val picked = if (question.multiSelect) multiPicked.contains(i) else singlePicked == i
                    OptionRow(
                        label = o.label,
                        hint = o.hint,
                        multi = question.multiSelect,
                        picked = picked,
                        onToggle = {
                            if (question.multiSelect) {
                                if (multiPicked.contains(i)) multiPicked.remove(i) else multiPicked.add(i)
                            } else {
                                singlePicked = i
                            }
                        },
                    )
                }
            }

            if (question.allowFreeText) {
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = freeText,
                    onValueChange = { freeText = it },
                    singleLine = true,
                    placeholder = {
                        Text(
                            text = stringResource(R.string.ask_user_free_hint),
                            color = BrewDim,
                            fontSize = 12.sp,
                        )
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = BrewBorder,
                        unfocusedBorderColor = BrewBorder,
                        focusedTextColor = BrewText,
                        unfocusedTextColor = BrewText,
                        cursorColor = BrewSuccess,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Spacer(Modifier.height(14.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = { onResult(true, emptyList(), "") }) {
                    Text(
                        text = stringResource(R.string.ask_user_skip),
                        color = BrewMuted,
                        fontSize = 13.sp,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = {
                        val free = freeText.trim()
                        val selected = when {
                            question.multiSelect -> multiPicked.sorted().map { question.options[it].label }
                            singlePicked >= 0 -> listOf(question.options[singlePicked].label)
                            else -> emptyList()
                        }
                        when {
                            // 用户打了字 = 想要自定义答案（即便也勾了项）
                            free.isNotEmpty() -> onResult(false, emptyList(), free)
                            selected.isNotEmpty() -> onResult(false, selected, "")
                            // 既没勾也没填就点「确定」：实质上没有作答，按显式跳过处理
                            else -> onResult(true, emptyList(), "")
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = BrewSuccess,
                        contentColor = Color.White,
                    ),
                ) {
                    Text(text = stringResource(R.string.ask_user_ok), fontSize = 13.sp)
                }
            }
        }
    }
}

/**
 * 一行候选项：多选画方框勾，单选画圆点。
 *
 * 不用 Material3 的 Checkbox/RadioButton：它们的默认尺寸与配色（依赖 MaterialTheme）
 * 与 App 的 Brew 配色/紧凑行高对不上，这里手绘一个 16dp 指示器反而更一致。
 */
@Composable
private fun OptionRow(
    label: String,
    hint: String,
    multi: Boolean,
    picked: Boolean,
    onToggle: () -> Unit,
) {
    val shape = if (multi) RoundedCornerShape(4.dp) else CircleShape
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable { onToggle() }
            .padding(horizontal = 6.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(16.dp)
                .clip(shape)
                .background(if (picked) BrewSuccess else Color.Transparent)
                .border(1.dp, if (picked) BrewSuccess else BrewBorder, shape),
            contentAlignment = Alignment.Center,
        ) {
            if (picked) Text(text = "✓", color = Color.White, fontSize = 10.sp)
        }
        Spacer(Modifier.width(9.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, color = BrewText, fontSize = 13.sp)
            if (hint.isNotEmpty()) {
                Text(text = hint, color = BrewDim, fontSize = 11.sp)
            }
        }
    }
}
