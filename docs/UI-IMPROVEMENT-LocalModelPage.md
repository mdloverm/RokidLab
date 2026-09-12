# 本地模型页面（LocalModelPage）UI 改进方案

> 评审对象：`phone-app/src/main/java/com/rokidlab/phone/store/LocalModelPage.kt`（约 828 行，含工作区未提交改动）
> 依据规范：`UI-DESIGN.md`（设计系统/组件/本地化）、`RULES.md`（第四章设计系统规范、第九章纠错清单）
> 评审角色：UI Designer ｜ 目标：设计系统合规 + 信息架构收敛 + WCAG AA

---

## 0. 结论摘要

| 维度 | 现状 | 目标 |
|------|------|------|
| 设计系统合规 | 页面 100% 手写 Material3 组件，未走 `design` 包 | 组件/形状/颜色 100% 走 token |
| 信息架构 | 单文件 828 行、20+ 个 `mutableStateOf` 平铺 | 三段式 + 状态持有者 |
| 反馈机制 | 全部 Toast（短暂、不可操作） | 内联 `BrewStateCard` + 重试 |
| 无障碍 | 状态仅靠颜色、触控目标 < 44dp、11sp 文本 | 三重编码、≥44dp、≥12sp |
| 功能完整性 | 只能"切到本地"，无法"切回在线" | 双向开关 |

**改动量估计**：P0 约 0.5 天，P1 约 2 天，P2 约 1 天。

---

## 1. P0 — 设计系统合规（必须先做，风险最低）

### 1.1 形状硬编码 → `BrewShape*`

`RULES.md` 9.5 明确禁止直接写 `RoundedCornerShape(...)`。当前页面有 4 处：

| 位置 | 现状 | 改为 |
|------|------|------|
| L838/840 `PageCard` | `RoundedCornerShape(16.dp)` | `BrewShapeLarge` |
| L875/878 `ModelRow` | `RoundedCornerShape(12.dp)` | `BrewShapeStandard` |
| L968/971 `PullSection` | `RoundedCornerShape(12.dp)` | `BrewShapeStandard` |
| L1063/1065 `SuggestChip` | `RoundedCornerShape(8.dp)` | `BrewShapeMedium` |

`StoreTheme.kt` 已提供 `BrewShapeSmall/Medium/Standard/Large/XLarge`，无需新增。

### 1.2 Material3 组件 → `design` 包组件

当前用了 5 个 M3 `Button`（L439/502/516/994/1002）、7 个 `TextButton`（L382/549/559/567/699/897/920）、1 个 `AlertDialog`（L775），与全站 `Brew*` 组件的按压反馈（scale 0.95 + spring）、禁用态 alpha、字号都不一致。

替换映射：

| 现状 | 替换为 | 说明 |
|------|--------|------|
| M3 `Button`（主操作） | `BrewButton(text, onClick, color = BrewChat)` | 启动/拉取/授权 |
| M3 `Button`（次操作） | `BrewOutlineButton(text, onClick, color = BrewText)` | 停止/取消 |
| M3 `TextButton`（行内） | `BrewCompactButton(text, onClick, color = ...)` | 设为对话/删除/复制命令 |
| M3 `AlertDialog`（删除确认） | `BrewDialog(title, color = BrewRed, ...)` | 符合 UI-DESIGN 2.1.2 统一弹窗 |

> `design` 包组件是 `internal`，`store` 包同模块可直接调用，无需改可见性。

### 1.3 硬编码文本

| 位置 | 现状 | 处理 |
|------|------|------|
| L914 | `detail.ifBlank { "—" }` | 新增 `local_model_detail_unknown` |
| L790 | `sizeLabel.ifBlank { "?" }` | 新增 `local_model_size_unknown` |
| L1051 | `"$label $percent%"` | 改为带占位符资源 `local_model_pull_percent`（`%1$s %2$d%%`） |

同时补 `values-en/strings.xml`（`RULES.md` 三.2）。

### 1.4 临时 alpha

L969 `BrewPanelHi.copy(alpha = 0.5f)` 是页面内自造的中间色。要么直接用 `BrewPanelHi`，要么在 `StoreTheme.kt` 补一个 `BrewPanelSub` token，不要在页面里手写 `copy(alpha = ...)`。

### 1.5 导入顺序

L72 `import kotlinx.coroutines.delay` 插在 `Dispatchers` 之后，违反 `RULES.md` 9.1 的同组字母序约定，移到 `Dispatchers` 之前。

---

## 2. P1 — 信息架构与状态机

### 2.1 三段式结构（见上方线框图）

当前页面是"卡片 + 列表"的自然堆叠，标题层级靠 15sp/13sp/12sp 的字号差硬撑，缺少统一的区块标识。改为与 `SettingCard`、`ToolsManagePage` 一致的区块头：

```
SectionHeader(label = "服务状态", color = BrewChat)     // 10sp Bold uppercase letterSpacing 3sp
  + 32×3dp 模块色装饰线
SectionHeader(label = "对话接入", color = BrewChat)
SectionHeader(label = "模型库 · N", color = BrewChat) + 右侧 BrewCompactButton("拉取")
```

`BrewSectionHeader` 目前不存在，建议新增到 `DesignComponents.kt`（SettingCard 里已有同样的"标签 + 装饰线"结构，抽取即可复用）。

### 2.2 状态持有者抽离

当前 20+ 个 `mutableStateOf` + 4 个业务函数（`checkServer`/`start`/`stop`/`pull`）全部内联在 Composable 里，且存在两个具体问题：

- **L129 `pullJob` 用 `mutableStateOf` 存 Job** —— Job 每次赋值都会触发重组，而它只用于 `cancel()` / `isActive` 判断，完全不需要是状态。改成普通 `var pullJob: Job?` + `remember`。
- **L190 `checkServer` 里 `scope.launch` 嵌套 `scope.launch`** —— 内层拉取模型列表的协程不受外层 `finally` 保护，异常路径下 `probeInFlight` 可能提前释放，退化成并发探测。合并成单次 `async`/顺序执行。

建议抽出：

```kotlin
internal class LocalModelPageState(
    private val app: LabApplication,
    private val scope: CoroutineScope,
) {
    val termuxOk: StateFlow<Boolean>
    val serverUp: StateFlow<Boolean>
    val models: StateFlow<List<OllamaModel>>
    val chatModel: StateFlow<String>
    val pullProgress: StateFlow<PullProgress>   // status / percent / bytesDone / bytesTotal
    val guidance: StateFlow<StartGuidance?>
    fun start() / fun stop() / fun pull(name: String) / fun cancelPull() / fun useAsChat(m: OllamaModel)
}
```

Composable 只保留 `collectAsState()` + 渲染，页面体量可从 828 行降到 300 行以内。

### 2.3 轮询生命周期化

L219-224 的 `while (true) { delay(5000) ... }` 有三个问题：

1. 页面被系统对话框（授权弹窗）遮挡时仍在轮询；
2. 固定 5s 无退避，服务挂掉时每 5s 打一次可能长达 8s 的请求（虽有 `probeInFlight` 兜底，但失败后立即重置）；
3. 退出页面靠 Dialog 销毁才停，没有显式取消。

改为：

```kotlin
DisposableEffect(Unit) {
    val job = scope.launch {
        var delayMs = 5_000L
        while (isActive) {
            delay(delayMs)
            if (!isInTransition()) {
                val ok = probe()
                delayMs = if (ok) 10_000L else min(delayMs * 2, 60_000L)  // 指数退避
            }
        }
    }
    onDispose { job.cancel() }
}
```

### 2.4 Toast → 内联反馈

当前 12 处 `toast(...)`，其中 6 处是**失败/引导**语义（`local_model_start_timeout`、`launch_denied`、`pull_fail`、`delete_fail` 等）。Toast 2 秒消失、不可操作、文字被截断，是这一页最大的可用性问题。

改法：把 L476-497 的三段 `guidance` 文字（权限/拒绝/超时）统一收敛为一个 `BrewStateCard`：

```kotlin
when (guidance) {
    StartGuidance.PERMISSION -> BrewStateCard(
        type = StateCardType.WARNING,
        title = stringResource(R.string.local_model_launch_perm_title),
        message = stringResource(R.string.local_model_launch_perm),
        actionLabel = stringResource(R.string.local_model_btn_grant),
        onAction = { LocalOllamaManager.openAppPermissionSettings(ctx) },
    )
    StartGuidance.DENIED -> ...
    StartGuidance.TIMEOUT -> ...
}
```

成功类 Toast（启动成功、删除成功）保留即可，符合"轻反馈"定位。

### 2.5 拉取进度信息不足

`PullSection` 只有百分比 + 阶段文案。1.5B 模型下载动辄数分钟，用户拿不到"还剩多少/多快/要不要等"的判断依据。补三项：

- 已下载 / 总量：`412 MB / 986 MB`（`LocalOllamaManager.pullModel` 的 `onProgress` 已能拿到 `completed`/`total`，只是没往 UI 传）；
- 平均速率 + 预计剩余时间；
- 进度更新节流到 200ms 一次（当前每帧回填，长下载会持续重组整个页面）。

同时给进度条补 `Modifier.progressSemantics(...)`，让 TalkBack 能读出进度。

---

## 3. P2 — 无障碍与交互细节

| # | 问题 | 位置 | 改法 |
|---|------|------|------|
| 1 | 状态只用颜色区分（绿/红/灰圆点） | L848 `StatusDot` | 保留圆点，但补 `Modifier.semantics { contentDescription = ... }`；右侧状态文字本身已是冗余编码，只需让圆点不再"只承载颜色"信息 |
| 2 | `TextButton` 设了 `contentPadding = PaddingValues(0.dp)`，12sp 文字的实际可点区域远小于 44dp | L897、L920 | 改用 `BrewCompactButton`（自带 padding），或至少 `Modifier.sizeIn(minWidth = 44.dp, minHeight = 44.dp)` |
| 3 | 11sp 说明文字（保活提示、内存提示、参数说明） | L472、L641、L730、L743、L942 | 提升到 12sp；补 `lineHeight = 20.sp`（UI-DESIGN 2.6 使用说明卡已定 12/20） |
| 4 | 删除当前正在使用的模型无额外提示 | L920 → L773 | `isCurrent` 时确认文案换成"该模型正在用于眼镜对话，删除后将回退到在线模型"，二次确认 |
| 5 | 拉取中整个模型列表禁用（`enabled = !pulling`）但无解释 | L762 | 禁用时把操作位替换为一行 12sp 灰字"下载中，完成后可操作" |
| 6 | 全站 `fontScale` 被 `coerceAtMost(1.0f)` 钳死 | UI-DESIGN 1.3 | 违反 WCAG 1.4.4（文本缩放 200%）。这是全局决策，建议放宽到 1.3 并对关键卡片做自适应高度；短期内至少保证本页卡片不用固定高度 |

### 对比度核算（Velvet Dark）

| 前景 / 背景 | 对比度 | WCAG AA |
|-------------|--------|---------|
| `BrewMuted` #8A8780 / `BrewPanel` #151518 | 5.15 : 1 | 通过 |
| `BrewTextBright` #F2EFEA / `BrewPanel` #151518 | 14.9 : 1 | 通过 |
| `BrewChat`（当前值待核）/ `BrewPanel` | 需补测 | — |
| `BrewSuccess` #00B894 / `BrewPanel` #151518 | 需补测 | — |

建议对 `BrewChat`、`BrewSuccess`、`BrewWarning` 在两主题下的面板/背景组合跑一次批量对比度校验，把结果写进 `UI-DESIGN.md` 固定下来。

---

## 4. 功能缺口：只能进不能出

`CxrLHiRokidSession` 只暴露了 `setLocalChatModel()`（L410，内部会置 `KEY_AI_USE_LOCAL = true`），**没有任何方法能把它关闭**。也就是说：用户一旦在本页点了"设为对话模型"，就再也回不到在线模型——只能去设置页改。

这与"对话接入"卡的心智模型冲突：卡片显示"当前指向本地"，却给不了开关。

**建议**：

```kotlin
// CxrLHiRokidSession
fun setLocalChatEnabled(enabled: Boolean) {
    appContext.getSharedPreferences(AI_PREFS, 0).edit()
        .putBoolean(KEY_AI_USE_LOCAL, enabled).apply()
    pushAiConfigToGlass(getAiConfig())
}
```

UI 上在"对话接入"卡右上角放一个 Switch（参考 `ToolsManagePage` 的工具开关：`checkedTrackColor = BrewChat` / `unchecked = BrewPanelHi`），文案"用本地模型回答眼镜对话"。关闭时保留已选模型名，方便再开回来。

---

## 5. 落地顺序

**PR 1（P0，0.5 天）** — 纯规范对齐，零行为变化
- 4 处形状 → `BrewShape*`
- 13 处 M3 组件 → `Brew*`
- 3 处硬编码文本 → string resource（中英双语）
- `BrewPanelHi.copy(0.5f)` → token
- import 排序

**PR 2（P1，2 天）** — 结构重构
- 新增 `BrewSectionHeader` 到 `DesignComponents.kt`
- 抽出 `LocalModelPageState`
- `pullJob` / 嵌套协程 / 轮询生命周期三个 bug
- guidance 三段文字 → `BrewStateCard`
- 拉取进度补 MB/速率/ETA + 节流

**PR 3（P1.5，1 天）** — 功能与无障碍
- `setLocalChatEnabled()` + 对话接入卡 Switch
- 触控目标 44dp、11sp → 12sp、语义标签
- 删除当前模型二次确认
- 对比度批量核算并回写 `UI-DESIGN.md`

---

## 6. 验收清单

- [ ] 页面内无 `RoundedCornerShape(` 字面量，全部经 `BrewShape*`
- [ ] 页面内无 `Material3 Button` / `TextButton` / `AlertDialog` 直接调用
- [ ] `grep -n '"—"\|"?"' LocalModelPage.kt` 为空
- [ ] `values/strings.xml` 与 `values-en/strings.xml` 新增项数量一致
- [ ] 关闭页面后 5s 内不再有 Ollama 探测请求（logcat 验证）
- [ ] 断网状态下打开页面，超时引导是可点击的 StateCard 而非 Toast
- [ ] 拉取 1.5b 模型时，进度区同时显示百分比、MB/MB、速率
- [ ] TalkBack 下能读出"服务状态 运行中"、"下载进度 42%"
- [ ] 所有可点元素实测触控区 ≥ 44dp
- [ ] 关闭"用本地模型回答"开关后，眼镜对话回落到在线模型
- [ ] `./gradlew :phone-app:compileDebugKotlin` 通过
