# Agent 实跑诊断：gps-live（2026-10-09 21:00–21:27）

> 现象（用户）：**跑一半不跑了 / 工具识别错误 / 很笨**。
> 结论（一句话）：**不是模型"笨"，是七处缺口叠在一起** ——
> ① 页面"拿不到位置"的原因**不是位置拿不到**（`get_location` 实测通、页面也能调），
>    而是它选了宿主**没实现**的 `navigator.geolocation`，而**唯一能纠正它的文档是死链、正解在文档里零覆盖**，
>    且它写的回退只把结果贴成一行小字、**没接进主数据流**；
> ② **双端能力归属没分清**：`requiresGlasses` 只标注"依不依赖眼镜"、不标注"在哪端执行"；
>    文档 §9 标题说「手机端工具」却混列眼镜工具，18 个眼镜专属工具只提到 1 个，
>    且**从不说"所有工具都在手机执行"** ⇒ 模型自行补出"眼镜有眼镜的定位"这种错误语义；
> ③ **agent 自己也看不到"端"这个维度**：`group`/`category`/`requiresGlasses` 都**不下发**，
>    模型只能读 `schema.description` 的散文猜；**成对工具没有选端判据**
>    （全项目只有 `open_aiui_app.target` 那一条做对了）；
> ④ **自省只覆盖"异常"**：`LessonText.retryGuidance` 判据是"工具抛异常"，
>    而本轮 **37 次工具调用全部成功、0 次写操作** ⇒ 它**一次都没触发**；
>    "成功但原地打转"（`index.ink` 读了 **5 遍**）与"要修却只读"**零保护** —— 这是 27 分钟空转的最深层原因；
> ⑤ 关键词路由把"重写/继续/拿不到位置"这类**指代上一轮产物的续说词**判成"菜单探测轮"，
>    每轮削掉写文件的能力并多花一次 RTT；
> ⑥ 模型两次喊内部标记 `[NEED_TOOLS]`，只有第一次被逃生舱接住，**第二次没有任何分支处理，直接当成正文播给用户**
>    → 用户看到的就是气泡里一行 `[NEED_TOOLS]`，而这一轮被记成 `reason=completed`（"做完了"）；
> ⑦ agent 对 AIUI 页面运行时**零可观测性**（读不到页面 console/JS 错误）⇒ 决定性报错永远看不见，只能猜。
>
> 📌 **修正留痕**：初版把 ① 写成"宿主没有定位能力 ⇒ 实时 GPS 做不出来"，**过头**（见 §4.1/§4.5）；
> 21:39 经用户追问补入 ②（§5）；21:44 补入 ③④（§6，**agent 侧的机制缺口**）。

---

## 0. 证据来源（可复现）

| 证据 | 位置 |
| --- | --- |
| 事件流（权威，逐条带 seq/ts） | 设备 `files/agent_sessions/ef1ed96f6f81.jsonl`，152 行 → 本地 `.workbuddy/tmp/agent_session.jsonl` |
| 用户界面实际看到的对话 | 设备 `files/chat_sessions/ef1ed96f6f81.jsonl`（16 条）→ `.workbuddy/tmp/chat_session.jsonl` |
| 生成物（真因现场） | 设备 `files/aiui_projects/gps-live/`（`app.json` + `pages/index/index.ink` 10558 字符）→ `.workbuddy/tmp/gps-live-index.ink` |
| 宿主能力清单 | `RokidLink/src/main/assets/ink/host.js:243 buildHostCapabilities()` |
| 手机端 JS 桥 | `phone-app/src/main/java/com/rokidlab/phone/aiui/AiuiWebHost.kt:189-250` |
| 技能文档 | `phone-app/src/main/assets/skills/aiui-dev/`（`apis.md`、`apis-navigator.md`、`lab-runtime.md`） |

⚠️ 运行期 logcat 已被 IMU 日志冲掉（main 缓冲起点 21:28:44，晚于本轮结束 21:27:45），
所以装配路径的归因**不靠 logcat**，靠事件流的 `modelCalls` 与工具调用序列反推 + 代码分支的唯一性。
（下文凡是"反推"都会标注。）

---

## 1. 时间线（7 轮，全程 27 分钟）

| 轮 | 时间 | 用户 | 模型调用 | 工具调用 | 失败 | input tok | output tok | 最终正文 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| T1 | 21:00:57–21:02:23 | 生成一个可以实时显示手机GPS的项目 | 12 | 18 | 3 | 298,746 | 17,872 | 正常（2 文件落盘 + 打开演示） |
| T2 | 21:07:39–21:08:31 | 显示拿不到位置 | 12 | 15 | 3 | 256,882 | 9,117 | **`[NEED_TOOLS]`** |
| T3 | 21:09:40–21:09:50 | 你看一下GPS 然后看下真实工具是什么 在修复 | 5 | 5 | 0 | 96,544 | 1,624 | **`[NEED_TOOLS]`** |
| T4 | 21:10:31–21:10:34 | 你看下我的位置 然后在看那里有问题 | 2 | 1 | 0 | 3,642 | 454 | 长文诊断（**错因**，且含代码围栏） |
| T5 | 21:12:08–21:12:19 | 重写 | 5 | 6 | 1 | 54,666 | 1,898 | **`[NEED_TOOLS]`** |
| T6 | 21:26:50–21:27:05 | 继续 | 6 | 7 | 2 | 107,549 | 2,729 | **`[NEED_TOOLS]`** |
| T7 | 21:27:32–21:27:45 | 继续 | 5 | 3 | 0 | 55,282 | 2,034 | **`[NEED_TOOLS]`** |
| | | | **47** | **55** | **9** | **873,311** | **35,728** | 第 2 轮起**净产出为零** |

用户界面侧（`chat_session.jsonl`）逐条对上：T2/T3/T5/T6/T7 的 assistant 气泡内容**就是** `[NEED_TOOLS]` 这 11 个字符。

---

## 2. P0-①：为什么"跑一半不跑了"——标记只被接住一次，第二次漏给用户

### 事实链

1. `[NEED_TOOLS]` 是**内部协议标记**（三层降载的"逃生舱"）：`ChatFastPath.kt:31`。
   语义 =「手头工具子集不够用，请挂载全量工具重建请求重来」。
2. 接住它的分支只有两处，且**都只生效一次**：
   - 菜单模式收束：`AiConversationService.kt:1889`（`wantsFull`）→ `:1893-1908`；
   - 快路/路由升级：`:1946-1965`（注释原文"**探测轮只生效一次**"）。
   两处都会 `menuModeActive=false` / `routedToolNames=null` / `fastPathActive=false`，然后 `continue`。
3. **第二次**喊标记时：`menuModeActive=false`、`routedToolNames=null` → `probeMode=false`（`:1946`），
   菜单分支也不进（`:1879` 要求 `menuModeActive`）→ 直接落到 `:2023 reply = turn.content`。
4. 唯一的清洗是 `ReplySanitizer.sanitize()`（`:2757`），它**只剥 `<answer>…</answer>` 一类 XML 包裹标签**（`ReplySanitizer.kt:33` 要求以 `<` 开头）—— 对 `[NEED_TOOLS]` 零作用。
5. 本轮照样以 `TurnEndReason.COMPLETED` 收尾（`:2764-2768`）→ 事件流 `reason=completed`，用户侧看起来"它答完了"。

### 反推证据（算术对得极整齐）

把每轮的工具调用按时间戳分组得到"工具轮数 N"，则 `modelCalls` 应为 `N+1`（末轮最终回复）：

| 轮 | 工具轮 N | 应有 modelCalls | 实际 | 差值 |
| --- | --- | --- | --- | --- |
| T1 | 11 | 12 | 12 | 0 ✅（无标记） |
| T2 | 9 | 10 | 12 | **+2** |
| T3 | 2 | 3 | 5 | **+2** |
| T4 | 1 | 2 | 2 | 0 ✅（有正文，无标记） |
| T5 | 2 | 3 | 5 | **+2** |
| T6 | 3 | 4 | 6 | **+2** |
| T7 | 2 | 3 | 5 | **+2** |

**凡最终正文是 `[NEED_TOOLS]` 的轮，一律多出整整 2 次模型调用**；有正文的两轮（T1/T4）差值恰好为 0。
只有一种解释与代码分支相容：**每轮喊了两次标记** ——
第一次（`+1`）被逃生舱接住 → 挂全量工具重来 → 模型**又喊了一次**（`+1`）→ 这次无人接管 → 泄漏。

### 为什么"给了全量工具还喊"：提示里那条指令从来没人撤掉

升级发生在**非探测轮**时，代码**只换工具表、不重建消息序列**：

```kotlin
// AiConversationService.kt:1900-1901
// 探测轮：整体重建（摘菜单文本）；后续轮只换表（in-flight 消息不可重建）
if (probeRound) messages = buildMessages()
```

而 `<tool_menu>` 那段约定是在**本轮 tail**里注入的，一旦注入就留在上下文到本轮结束：

```kotlin
// OpenAiService.kt:696-700
append("若需要清单里的其他工具，请只回复一行 [TOOLS] 工具名1,工具名2,…")
append("若你确认必须依赖某个清单里不存在的能力（如设备控制、本地文件操作）才能完成，")
append("回复 [NEED_TOOLS]，系统会挂载全量工具；")
```

⇒ 升级之后，模型**既拿到了全量工具，又仍然读到"要回复 [NEED_TOOLS]"这条指令**，且它再也拿不到"已经升级完成"的反馈。
这就是典型"**指令指向一个已经不成立的状态**"（同 **审计判据 ⑥**"指令不能指向不存在的对象"），弱模型必然照办。

### 附带的交互缺陷

- 这两个标记轮**不流式**（`:1768`），所以用户不是"看到字往外蹦然后卡住"，是**整条气泡突然出现一行 `[NEED_TOOLS]`** —— 观感上就是"跑一半不跑了"。
- 标记没有出口加工：手机气泡原文、眼镜 TTS 也会念这 11 个字符（`sanitizeForGlass` 同样不过滤）。

---

## 3. P0-②：为什么"工具识别错误"——三层降载把"续说词"判成了探测轮

三层降载的设计（闲聊快路 → 关键词路由 → 菜单探测）本身是省 token 的正解，问题出**判据落在关键词上，而续说轮没有关键词**。

逐轮复核（T4 是唯一命中关键词的一轮，也正因如此它的行为完全不同）：

| 轮 | 用户话 | `route()` 结果 | 实际路径 | 后果 |
| --- | --- | --- | --- | --- |
| T2 | 显示拿不到位置 | null（无关键词；`我的位置`才命中 location） | 菜单探测 | 首轮只有 3 个引导工具（搜索/读网页/时间），必须先 `[TOOLS]` 点名 |
| T3 | 你看一下GPS 然后看下真实工具是什么 在修复 | null | 菜单探测 | 同上 |
| **T4** | 你看下**我的位置** 然后在看那里有问题 | **命中 `location` 规则 → 只下发 `{get_location, search_web}` 2 个工具** | 路由子集 | 模型拿到了位置（34.26260/108.97017 ✅）却**没有写文件的能力** → 只能"动嘴" |
| T5 | 重写 | null，且因含"写"被 `TASK_SHAPED_CONFLICTS[ALL]` **全局否决**（`ToolIntentRouter.kt:208-212`） | 菜单探测 | 明明是"改上一轮的代码"，却退化成最弱的装配 |
| T6/T7 | 继续 | null | 菜单探测 | 同上，每轮白付一次探测 RTT |

三处具体后果：

1. **T4 的错误诊断**：只有 `get_location` + `search_web` 时，模型改成用散文解释，于是把真因归到
   "浏览器（尤其非 HTTPS）会拒绝 `navigator.geolocation`" —— **完全错**（真正原因是宿主没实现该能力，见 §4）。
   这条回复还违反了 aiui-dev 的交付纪律（`lab-runtime.md` §8：回复 ≤3 句、纯文本、无代码围栏），
   却因为"路由子集轮"没有被任何护栏拦住。
2. **每个续说轮都先花一轮探测**：菜单探测轮只挂 `BOOTSTRAP_TOOLS` 且**不流式**，模型必须先回一行
   `[TOOLS] 工具名…` 才拿到真正要用的工具。对"继续/重写"这种**天然指代上一轮产物**的指令，
   这次探测是纯损耗（多 1 次 RTT + 削掉写工具）。
3. **"重写"被全局否决是判据错位**：`TASK_SHAPED_CONFLICTS[ALL]` 放"写"是为了拦「写个天气 app ≠ 查天气」，
   但对已经存在 `gps-live` 项目的续做轮，"写"正是任务本身。现有实现没有"会话已进入代码/AIUI 场景"这一维。

---

## 4. P0-③：为什么这个页面显示"拿不到位置"——**位置明明拿得到**

> ⚠️ **本节 2026-10-09 21:35 经用户提醒后修正**。
> 初版结论"实时 GPS 在当前宿主上做不出来"**过头了**：agent 侧 `get_location` 在 T4 实测成功
> （`陕西省 西安市 碑林区 炮房街 34.26260/108.97017 ±21 米 来源 gps`），
> 说明**位置拿得到**。真正失败的是"页面这条实现路径"，不是"能力不存在"。见 §4.4。

### 4.1 澄清：有两条完全不同的"取位置"通路

| 通路 | 机制 | 状态 |
| --- | --- | --- |
| **A. agent 直接调 `get_location`** | 模型调工具 → `LocationTools.getLocation()` 同步取位（缓存5min → 实时6s → IP兜底），返回一句人话 | ✅ **实测通**（T4 #99/#100） |
| **B. 页面 `navigator.geolocation`** | ink 页面调标准 Web API → 运行时当**宿主能力**派发 → 需要宿主提供 hook | ❌ 宿主未实现（§4.2） |
| **C. 页面 `Lab.callTool('get_location')`** | 页面 JS 桥 → `ToolGateway` → 同一个 `LocationTools` | ✅ **通路本来就在**（`PageScope.ALLOWED_DOMAINS = DOMAIN_ALL`，`DENY_TOOLS` 不含它） |

关键：**B 和 C 都是页面能用的，B 失败了但 C 通**。所以问题不是"没有能力"，而是
"**页面选了 B，而文档从未告诉它该选 C**"（§4.3），且"**它自己写的 C 回退没接进主数据流**"（§4.4）。

### 4.2 通路 B 为什么必失败：宿主确实没实现 geolocation

生成的页面（`gps-live-index.ink:59/73/89`）首选了标准 Web API：

```js
this._geo = (navigator.geolocation) ? navigator.geolocation : null;
this._geo.getCurrentPosition(onOk, onErr);      // 单次定位
this._geo.watchPosition(onOk, onErr);           // 实时跟踪
```

运行时把 `navigator.geolocation` 当**宿主能力**派发给宿主，宿主必须提供 hook：

```js
// RokidLink/src/main/assets/ink/index.js:1269
geolocation: {
  getCurrentPosition: (result) => serializeGeolocationPositionResponse(result),
  watchPosition: () => serializeSuccessResponse(),
  clearWatch: () => serializeSuccessResponse(),
},
// 缺 hook 时直接抛错（index.js:1984）
throw new Error(`Host capability ${capability}.${method} is not configured on Web host`);
```

而**两端的宿主都没给这个 hook**：

```js
// RokidLink/src/main/assets/ink/host.js:243
/** hostCapabilities：四传感器 start/stop 钩子（默认 serializer 把结果转成功响应） */
function buildHostCapabilities() {
  const caps = {};
  for (const cap of SENSOR_CAPS) { caps[cap] = { start, stop }; }   // 只有 4 个传感器
  return caps;
}
```

```kotlin
// phone-app/.../aiui/AiuiWebHost.kt:189-250  —— 手机端 JS 桥的全部成员
hostReady / ready / closeRequested / closed / error / log / callTool / sensorStart / sensorStop
// RokidLink/.../AiuiLinkActivity.kt:74+        —— 眼镜端同样是 callTool + sensorStart/Stop
```

全仓库检索 `getCurrentPosition` **只命中 `ink/index.js` 一处**（运行时库自身）。
⇒ `getCurrentPosition()` 必然失败，错误码回落成 **2（POSITION_UNAVAILABLE）**，
而页面给 code 2 的文案正是 —— `暂时拿不到位置（室内或信号弱），稍后再试`（`index.ink:133`）。
**用户 T2 那句"显示拿不到位置"就是这句文案。**

补充风险：即便将来回落到 WebView 原生定位，`AiuiWebHost` 的 `WebChromeClient`（`:273`）**只重写了 `onConsoleMessage`**，
没有重写 `onGeolocationPermissionsShowPrompt` —— Android WebView 在这种情况下不会授予定位。

### 4.3 为什么模型会选 B：技能文档既没给 C 的范式，还把 B 的文档写成了死链

① **`lab-runtime.md` §9（工具口章节）对"位置/定位/GPS/location"零命中** —— 整章讲的是
音乐/天气/搜索/提醒/设备信息，**没有一句**说"要位置就调 `Lab.callTool('get_location')`"。

② 既然我们的文档没讲，模型只能去查 API 索引，而索引指向一份不存在的文档：

```
apis-navigator.md:19  | `geolocation` | `Geolocation` | Location entry point; see [Geolocation API](./geo/geolocation.md). |
apis.md:20            | `navigator.geolocation`, current position, location watching | [Geolocation API](./geo/geolocation.md) |
```

`assets/skills/aiui-dev/` 下**没有 `geo/` 目录、没有任何 geolocation 文档**（扁平命名只有 `apis-*.md`）。
模型两次按链接取文件，两次被拒：

```
T1 #11  load_skill_section {file:"apis/geo/geolocation.md"} → 「目录中没有文件…」
T2 #61  load_skill_section {file:"geo/geolocation.md"}      → 「目录中没有文件…」
```

于是它只能"凭印象"写 `navigator.geolocation` —— 这正是用户说的**"工具识别错误"**：
**唯一能纠正它的那份文档是死链，而正解（C）在自己的文档里一个字都没有。**

### 4.4 它其实自己写了回退到 C，但**回退没接进主数据流**（"拿不到位置"的最后一层）

模型在页面里写了一个 find-fallback（`index.ink:145-186`），逻辑是对的：
`listTools()` → 找名字含 `locat|geo|gps` 的工具 → `callTool(那个名字, {})`。
`get_location` 在页面白名单里，**这一步实际会成功拿到真实坐标**。

问题出在**它拿到之后干什么**：

```js
// index.ink:169-178
callLocTool(name) {
    this.setData({ st: '改用 ' + name + ' 取位置…' });
    globalThis.Lab.callTool(name, {}, function (res, e2) {
      if (e2) { self.setData({ note: '替代通道 ' + name + ' 失败：' + e2 }); return; }
      self.setData({ note: name + '：' + String(res).replace(/\s+/g, ' ').substring(0, 70) });
      //        ↑ 只写 note！不调 applyFix()
    });
}
```

而页面主体**每一个数据位都只由 `applyFix(pos)` 填充**（`index.ink:98`）：

```html
<!-- index.ink:248-286 -->
<text class="cval">{{ lat }}</text>   <text class="cval">{{ lng }}</text>      ← 大字经纬度
<view class="cell">精度 {{ acc }}</view>  … 速度/海拔/修正次数/累计里程/最后更新  ← 六宫格
<view class="rline">{{ r0 }}</view> … {{ r4 }}                                ← 5 条记录
<!-- 底部小字区 -->
<text class="f">{{ st }}</text>  <text class="f2">{{ err }}</text>  <text class="f2">{{ note }}</text>
```

`note` 只是**页面最底部一行 `.f2` 小字**（`:284`）。
⇒ **即使 `get_location` 成功返回了真实 GPS，主界面（大字经纬度/精度/里程/记录）依然全空**，
用户扫一眼仍然只有"拿不到位置"。**这就是观感与事实矛盾的来源。**

### 4.5 修正后的能力边界（用户提醒后的精确版）

| 能力 | 手机预览宿主 | 眼镜自托管宿主 | 依据 |
| --- | --- | --- | --- |
| `navigator.geolocation`（通路 B） | ❌ 未实现 | ❌ 未实现 | `host.js:243` 只有 4 传感器；两端 bridge 无 geolocation |
| `Lab.callTool('get_location')`（通路 C） | ✅ 可用 | ✅ 可用 | `PageScope` 全域开放；T4 实测同工具成功 |
| 定时器（`setInterval`，轮询的前提） | ⚠️ 大概率可用，**未实测** | ❌ **零执行**（真机实测） | `lab-runtime.md` §9.0 |
| 单次定位 | ✅ | ✅ | 通路 C |
| **持续"实时"流（`watchPosition`）** | ❌ 无宿主支持，只能轮询近似 | ❌ **无宿主支持 + 无定时器** | 上两行合并 |

**正确的结论**（替换初版）：
- **位置本身完全拿得到**（通路 C，实测已证），"实时 GPS"不是做不出来；
- **手机预览**可用 `setInterval` 轮询 `get_location` 近似实时
  （工具内部有 5 分钟缓存 + 6 秒实时取位，够用）；
- **眼镜端**因宿主无定时器，只能**按键触发单次定位**（如 Z 键），
  这一端的"持续实时跟踪"受事件循环限制，**需要宿主补定时器或 geolocation 能力才能做**；
- 当前唯一的"必失败项"是 **`navigator.geolocation` 这条首选路径**（宿主未实现）。

### 4.6 附带：agent 对页面运行时**零可观测性**

没有任何工具能读 AIUI 页面的 console / JS 错误（`read_screen` 只读像素）。
`Host capability geolocation.getCurrentPosition is not configured on Web host` 这类**决定性证据**，
agent 永远看不见，只能靠用户一句"拿不到位置"猜 —— 于是猜成了 HTTPS/浏览器问题（T4 那条错因）。

### 4.7 页面取位置的**正确范式**（可直接写进技能文档）

```js
// ✅ 自托管宿主取位置：只用工具口，且必须把结果接进主数据流
onLoad() {
  this.pull();                       // 首帧拉一次
  // 手机预览可用轮询近似"实时"；眼镜端 setInterval 零执行，须改为按键触发
  this._timer = setInterval(() => this.pull(), 3000);
},
pull() {
  var self = this;
  globalThis.Lab.callTool('get_location', {}, function (res, err) {
    if (err) { self.setData({ st: '定位失败：' + err }); return; }
    // ⚠️ 不要只把 res 当提示文字——要解析成主数据字段
    self.setData({ st: '已定位', lat: res, note: '' });
  });
},
```

⚠️ 注意：`get_location` 返回的是**一句人话**（`当前位置：地址（纬度 x，经度 y，精度 ±n 米，来源 gps）`），
不是结构化坐标。页面若需要数值化的 lat/lng/速度/里程，要么解析该文本，要么
**给工具加一个 `format=json` 参数**（推荐，见 §6 的 P1-4）。


---

## 5. 双端能力归属：**标注只有一个维度，真模型是三类**（用户追问补录）

> 用户追问："眼镜有眼镜的能力，手机有手机的能力，工具里也都有标注，生成项目的时候
> 可能是双端功能调用，也有可能是单边调用，**这个是不是也没分清**？"
> ⇒ **是的，而且两层都没分清**（标注维度缺一层 + 文档把两端混成一端）。

### 5.1 代码层：标注只表达"依不依赖眼镜"，不表达"在哪端执行"

`ToolEntry.requiresGlasses`（`tools/ToolEntry.kt:79`）＝ **「没有眼镜就做不成」**（`ToolRegistry.kt:522-527`）。
它只有两个消费方：

1. 乐奇聊天「本机模式」整条摘除 —— `schemasFor(excludeGlassesTools=…)`、`toolMenu`、`schemasUnattended`；
2. `GlassesDependencyGuard`（`ToolGuards.kt:219-231`）—— ⚠️ **只对 `ToolSource.CONVERSATION` 生效**，
   **页面路径压根不走这条 guard**（注释给的理由是"AIUI 页面的代码就在眼镜上跑，不存在本机模式这个状态"）。

精确清单：**18 个**（脚本按 `ToolEntry(` 切块解析；⚠️ 别用"名字附近出现 requiresGlasses"的宽匹配，会误抓成 12 个）

| 域 | 工具 |
| --- | --- |
| `glasses` | `get_glasses_status` `list_glasses_apps` `launch_glasses_app` `manage_glasses_app` `list_glasses_files` `read_glasses_file` `download_glasses_file` |
| `aiui` | `install_aiui_project` `list_my_aiui_apps` `pull_official_aiui` `stop_aiui_app` |
| `motion` | `get_head_pose` `get_motion_history` `set_motion_rule` `remove_motion_rule` |
| `hearing` | `listen_ambient`（**眼镜麦克风**） |
| `vision` | `look_at_view`（**眼镜摄像头**） |
| `media` | `show_lyrics` `stop_tts` |

⇒ 这 18 个**全是页面可调的**（`PageScope.ALLOWED_DOMAINS = DOMAIN_ALL`、`DENY_TOOLS` 不含它们、页面路径也没有眼镜依赖 guard）。
也就是说「眼镜的能力」在页面里**有 18 个入口**，而 `lab-runtime.md` §9 **只提到 `get_glasses_status` 一个**。

### 5.2 事实层：真正的模型是**三类**，不是"手机端/眼镜端"两类

| 类别 | 内容 | **实际跑在哪** |
| --- | --- | --- |
| ① **宿主侧能力** | IMU 传感器（`new Gyroscope()` / `AbsoluteOrientationSensor`）、画布、按键/触摸、音频播放 | **跟着页面所在的宿主走**：眼镜→眼镜 IMU；手机预览→手机 IMU |
| ② **工具口能力**（`Lab.callTool` 的一切） | 位置/天气/音乐/文件/提醒/搜索/知识库… | **永远在手机执行** |
| ③ **眼镜专属工具**（上表 18 个） | 状态/应用/文件/相机/麦克风/头姿/歌词 | 执行体仍在手机，**动作代理到眼镜**；眼镜离线即失败 |

②③ 的执行落点证据：
- **眼镜页面**：`AiuiLinkActivity.invokeToolCall:150-175` → `AsrPushServer.pushControlDetailed(CTRL_TOOL_CALL + payload)` → 经链路 → 手机 `ToolGateway`；
- **手机预览**：`AiuiWebHost.invokeToolCall:435-447` → `ToolGateway.call(ctx.applicationContext, …)` **本进程直接执行**。

⇒ **关键推论**：页面里**不存在"调用眼镜端能力"这种调用方式**。
所谓"双端功能调用"不是两种通道，而是**同一个调用口 + 不同工具组合**。
⇒ 也因此：**眼镜页面调 `get_location` 拿到的也是手机的 GPS**（不存在眼镜侧定位工具）。

### 5.3 文档层：三处直接让模型分不清

1. **标题与内容自相矛盾**：§9 标题是「调用**手机端**工具」，可 §9.2 的"逐字照抄"范例清单里
   **同时列了** `get_glasses_status`（眼镜侧）和 `get_phone_status`（手机侧）——
   标题说手机端、内容混着两端，模型只能理解成"这些都是手机工具"。
2. **眼镜能力几乎零覆盖**：`look_at_view`（眼镜摄像头）、`listen_ambient`（眼镜麦克风）、
   `get_head_pose` / `set_motion_rule`（眼镜头部姿态）**一个都没出现**
   ⇒ 页面永远不会想到能用眼镜相机/麦克风，明明它们可调。
3. **缺最关键那句事实**：全文**没有一句**说"工具永远在手机执行"；
   而 **`lab-runtime.md` §7** 对传感器说的"同一份页面代码两端都跑、**无需区分**"——这句**只对宿主侧（①类）成立**，
   文档没标适用范围，模型很容易外推成"端差异都不用管"。

### 5.4 实证：模型正是按这个框架**编**出来的

生成的页面写了这一句（`index.ink:43`）：

```js
note: '手机预览读取手机 GPS；推到眼镜后读取眼镜定位',
```

**这句是错的** —— 眼镜页面调 `get_location` 拿的仍是**手机的** GPS（§5.2 ②③，且无眼镜侧定位工具）。
它不是"笨"，是**按文档给的（单端）框架做了一次合理但错误的推断**：
文档只说"手机端工具"，它便自行补出"眼镜端应该有眼镜的定位"。

### 5.5 第二层"没分清"：**预览 ≠ 眼镜**

- **页面无法知道自己跑在哪端**：两端共用同一份 `host.js` / `lab-page-bridge.js`；
  `AiuiWebHost` 与 `AiuiLinkActivity` 都只注入 `Android` 桥 + `__lab/*` 端点，
  **没有任何平台标记**（已 grep 核实，`host.js` 里的 `target` 是传感器 session 的 targetId，无关）。

| 项 | 手机预览（`AiuiWebHost`） | 眼镜（`AiuiLinkActivity`） |
| --- | --- | --- |
| 工具执行落点 | 手机（本进程 `ToolGateway`） | 手机（`CTRL_TOOL_CALL` 经链路） |
| ③类眼镜工具 | 眼镜离线即失败，**且不走 guard**（工具内部报错） | 正常 |
| 定时器 `setInterval` | 大概率可用（**未实测**） | **零执行**（§9.0 真机实测） |
| IMU 数据源 | 手机 IMU | 眼镜 IMU |

⇒ **"手机预览通过"不蕴含"眼镜上能跑"** —— 这份差异清单文档里一份都没有。

### 5.6 代码注释也把这两端混成了一个概念

`ToolGuards.kt:218` 原文：

> "⚠️ 只对 `ToolSource.CONVERSATION` 生效：**AIUI 页面的代码就在眼镜上跑**，不存在"本机模式"这个状态。"

把「AIUI 页面」直接等同于「眼镜宿主」，**忽略了手机预览宿主**。
同一个名字对应两种落点，读代码的人和写代码的人都默认是单端 —— 这正是"分不清"的制度性来源。

### 5.7 该写进技能文档的判据（建议直接落 `lab-runtime.md` §9）

1. **术语先分开**：不要再用「AIUI 页面」指代「眼镜端」；两端分别叫
   **手机预览宿主（AiuiWebHost）** / **眼镜宿主（AiuiLinkActivity）**。
2. **给出三类模型**（§5.2 那张表）+ 一句"**页面没有第二种调用通道：所有工具都在手机执行**；
   眼镜专属工具只是'手机代理去操作眼镜'"。
3. **给出能力归属表**：明确哪些结果来自手机（位置/天气/音乐/文件…）、
   哪些动作落到眼镜（状态/应用/文件/相机 `look_at_view`/麦克风 `listen_ambient`/头姿 `get_head_pose`/歌词 `show_lyrics`）。
4. **修正 §9 标题**：从「调用手机端工具」改为「调用端侧工具（手机执行 + 眼镜代理）」之类，别再单端表述。
5. **页面不该判断"我在哪端"，只该判断"能力在不在"**：
   `Lab.listTools()` 是权威清单；③类工具先用 `get_glasses_status` 探活再调。
6. **给一张"预览 ≠ 眼镜"差异清单**（定时器 / 眼镜离线 / IMU 来源）。

---

## 6. agent 主循环的两个机制缺口（用户追问：不只是 AIUI，agent 自己也该分清 + 会自省纠偏）

> 用户追问："不但是 AIUI，是不是应该让 agent 分清工具到底是哪边的、能根据要求准确调用不同的？
> 还有在过程中，也应该有思维跟判断能力，及时改正。"
> ⇒ **两个都缺**，而且第二个是本轮 27 分钟空转的**最深层原因**。

### 6.1 缺口一：「端」不是模型可感知的维度

**代码里有标注，但没有一条进入模型能看到的地方。**

| 字段 | 含义 | 模型能看到吗 |
| --- | --- | --- |
| `ToolEntry.group` | 装配域（`glasses`/`aiui`/`motion`…） | ❌ 不下发 |
| `ToolEntry.category` | **用户视角**分组（`GLASSES`/`PHONE`…，设置页用） | ❌ 不下发 |
| `ToolEntry.requiresGlasses` | **运行依赖**（没眼镜做不成 → 本机模式摘除） | ❌ 不下发 |
| `schema.description` | 自然语言散文 | ✅ **唯一可见处** |

⇒ 模型判断"这条工具作用在哪端"，**只能靠读 description 的散文**，而且各 provider 写得**极不一致**：

- `look_at_view`：`"用眼镜摄像头拍一张…" + "⚠️ 本工具需要眼镜在线"` ← **标了**（`VisionToolProvider.kt:167`）
- `get_glasses_status`：`"查询 Rokid 眼镜的整体状态…"` ← **标了**
- `get_phone_status`：`"查询手机当前状态：电量…"` ← **标了**
- `control_music` / `show_image` / `get_weather` / `get_location`：← **完全没提端**

而**成对工具**最容易出事，它们恰恰**没有选端判据**：

| 用户说 | 正确工具 | 相邻的"错端"工具 |
| --- | --- | --- |
| 眼镜还有多少电 / 眼镜里有什么 | `get_glasses_status` / `list_glasses_files` | `get_phone_status` / `list_files` |
| 看看前面有什么（**眼镜**看） | `look_at_view`（眼镜相机） | `read_screen`（**手机屏幕**） |
| 把那张照片传给我（**眼镜**里的） | `list_glasses_files` + `download_glasses_file` | `list_files`（手机文件） |

**唯一做对的地方只有一个参数**（`OpenAiService.kt:571`，`open_aiui_app` 的 `target`）：

> "说'在手机上/先演示一下' → target=phone；说'在眼镜上/戴上眼镜看' → target=glasses。
> **两种说法都没有时先问一句「在手机上看还是眼镜上看」**，不要自己替他决定。"

这条判据的形状是对的（**明说 → 照做；没说 → 问**），但它**只服务一个工具的一个参数**，没有推广成端维度的通用规则。

⚠️ 与 §5 的结论配合：**执行永远在手机，端指的是"作用于哪端"**。
所以字段名该叫 **`actsOn`（作用于哪端）**而不是 `executedOn` —— 后者会误导实现者以为有两条执行通道。

#### 6.1.1 ✅ 已决策（2026-10-09 用户拍板）：**成对工具且未指明端 ⇒ 询问，不设默认端**

##### A. 逐工具复核：现状是**三种互相矛盾的处理**

我把全部工具描述里提到"手机/眼镜/优先/默认"的都过了一遍，现存三种做法：

| 工具 | 现状判据 | 类型 |
| --- | --- | --- |
| `open_aiui_app` | "两种说法都没有时**先问一句**" | ✅ **问** |
| `set_phone_alarm` | "「X分钟后提醒」若未强调手机，**优先用眼镜定时任务**（`manage_timer`）" | ⚠️ **默认眼镜** |
| `stop_aiui_app` | "用户没说位置时**不必追问**，按最近一次是在手机还是眼镜上展示的关即可" | ✅ 上下文锚定 |
| `get_phone_status` / `get_glasses_status` | 各说各的端，**完全没写"没说端时怎么办"** | ❌ 空白 |
| `list_phone_apps` / `list_glasses_apps` | 同上 | ❌ 空白 |
| `open_phone_app` / `launch_glasses_app` | 互相指路，但见下方 ⚠️ **冲突** | ❌ 抢活 |
| `list_files` / `list_glasses_files` | 同上 | ❌ 空白 |
| `capture_screen`（**手机屏幕**）/ `look_at_view`（**眼镜相机**） | 各自标注了端，**未写两者关系** | ⚠️ 半空白 |

##### B. 两处**现存冲突**（不修就会与新条款打架）

1. **`launch_glasses_app` 的触发词没限定眼镜** —— 原文：
   > "打开眼镜上安装的应用。当用户说**「打开某应用」「启动某应用」**时调用。"

   而 `open_phone_app` 也写着"打开手机上安装的应用……打开眼镜上的应用请用 `launch_glasses_app`"。
   ⇒ 用户说"打开微信"时，**两个工具的 description 都声称该调自己**（眼镜那句的触发词完全覆盖手机场景）
   ⇒ 模型只能**随机挑一个**。这正是"工具识别错误"的一个独立来源。
2. **`set_phone_alarm` 用的是"默认眼镜"策略**（"未强调手机 → 优先 `manage_timer`"），
   与本次决定的"**未指明端就问**"直接矛盾。

##### C. 成对表（按"要不要问"分三类，这是权威产地）

**① 强成对 ⇒ 一律问**（同一诉求两端都能完成，用户没说端时信息不足）

| 诉求 | 手机 | 眼镜 | 询问示例 |
| --- | --- | --- | --- |
| 设备状态（电量/存储/系统信息） | `get_phone_status` | `get_glasses_status` | "你是问手机还是眼镜？" |
| 应用列表 | `list_phone_apps` | `list_glasses_apps` | "要看哪边的应用列表？" |
| 打开应用 | `open_phone_app` | `launch_glasses_app` | "在手机上打开还是眼镜上打开？" |
| 文件列表 | `list_files` | `list_glasses_files` | "手机里的还是眼镜里的文件？" |
| 读文本文件 | `read_text_file` / `read_code_file` | `read_glasses_file` | 同上 |
| 定时提醒/闹钟 | `set_phone_alarm`（**手机响**） | `manage_timer`（**眼镜提醒**） | "让手机响还是眼镜提醒你？" |

**② 弱成对 ⇒ 能判就判，判不出才问**（诉求措辞常自带端线索）

| 诉求 | 手机 | 眼镜 | 判据 |
| --- | --- | --- | --- |
| 看画面 | `capture_screen`（手机**屏幕**） | `look_at_view`（眼镜**相机**） | 含"面前/眼前/实物/手上拿着" → 眼镜；含"屏幕/这个页面/界面/截屏" → 手机；**真判不出才问** |

⚠️ **弱成对不该一律问** —— 否则用户每说一次"看看这是什么"都被追问，体验反而更差。

**③ 非成对 ⇒ 不问，直接用**（诉求本身决定端；问是多余）

| 诉求 | 唯一工具 | 为什么只有一端 |
| --- | --- | --- |
| "看看我前面有什么" | `look_at_view` | **手机拍不到"面前"** |
| "截个屏/看看屏幕" | `capture_screen` | 眼镜没有"截屏"概念 |
| "眼镜里那张照片传给我" | `download_glasses_file` | 用户已明说端 |
| 手机侧专属 | 拨号/短信/剪贴板/音量/手电筒/计步/导航/日历/卸载/闹钟 | 眼镜上不存在对应能力 |
| 眼镜侧专属 | `get_head_pose`/`listen_ambient`/`show_lyrics`/`stop_tts`/`install_aiui_project` 等 | 手机上不存在对应能力 |

##### D. 询问的四条**例外**（不该问的时候问，比不问更糟）

1. **用户已明说端** ⇒ 照做（"眼镜还有多少电" → 直接 `get_glasses_status`）。
2. **上下文已锚定** ⇒ 沿用，不问。判据：本轮或上一轮刚用过某端工具 / 刚在某端打开过页面
   （`stop_aiui_app` 已是这个模式："按最近一次展示的关即可"）。
3. **某端不可用** ⇒ 不问，直接用可用端并说明。
   - 眼镜未连接 ⇒ 只有手机可选："眼镜没连上，我先用手机帮你查"。
   - 诉求只能在离线端完成（"看看前面有什么"而眼镜没连）⇒ **如实说需要眼镜**，不要偷偷换端。
4. **唯一通道（③类）** ⇒ 不问。

##### E. 询问本身的纪律

- **一轮只问一次**：把该轮所有歧义**合并**成一个问题，不要连环追问。
- **简短、语音友好**：一句问完（"手机还是眼镜？"），不要长句解释。
- **同一诉求只问一次**：用户答完即执行；**若用户回答仍含糊，不得再问** ——
  选一端执行并在回复里说明选了什么、为什么（否则会陷入"问了又问"的死循环）。
- **问了就要等**：不得"边问边猜着执行"（那等于没问）。

##### F. 落点（三层，按可靠性递增）

| 层 | 做法 | 成本 | 可靠性 |
| --- | --- | --- | --- |
| **a. 工具 description 统一模板** | 每个成对工具的描述里加：**"「X」诉求手机与眼镜都有对应工具（手机 `A`／眼镜 `B`）；**用户未指明哪一端时必须先问一句**，不要自行选一端。"** ⚠️ **两边都要加、互相指名**（只加一边＝另一边仍在抢） | 低 | 中 |
| **b. 系统提示加一条总则** | 一处生效："同一诉求两端都有工具时（设备状态/应用/文件/提醒），明确了就照做、**没明确先问一句**；若诉求只在某一端成立（看眼前画面只能用眼镜相机、截屏只能用手机），直接用那一端、不要问。" | 低 | 中 |
| **c. 结构化成对表 + 框架兜底** | `ToolEntry` 加 `pairedWith: String?`（成对关系）→ 装配时**自动生成** a+b 的文案；进一步在 `ApprovalGate` 前加一道"**端未定 ⇒ 返回澄清请求**"的 guard（模型没问也没猜错的机会） | 高 | 高 |

📌 建议先做 **a+b**（纯文案、当天可验证），把 **c** 列为机制化目标。
📌 无论哪条，**B 节那两处冲突必须同批修掉**，否则新条款与旧描述互相矛盾。

### 6.2 缺口二：自省只覆盖「失败」，三种「假成功」零覆盖

现有的自省/纠偏机制（`LessonText.retryGuidance`，注释标为 **Agent 缺口 #4**）判据是
**"工具抛异常且瞬时重试耗尽"**（`AiConversationService.kt:1045-1048`）。而本轮 6 轮里：

**它一次都没触发。** 因为——

| 类型 | 框架视角 | 用户视角 | 现有覆盖 |
| --- | --- | --- | --- |
| 工具抛异常 | 失败 | 失败 | ✅ `retryGuidance` + `addLessonOnce` |
| 工具成功且推进了任务 | 成功 | 成功 | ✅ 正常 |
| **① 成功但"报告做不到"** | **成功** | 失败 | ❌ **零覆盖** |
| **② 成功但原地打转** | **成功** | 卡住 | ❌ **零覆盖** |
| **③ 成功但方向错了** | **成功** | 更糟 | ❌ **零覆盖** |

#### 类型①：工具"成功返回了一句做不到"

`read_code_file(file="index.html")` 找不到文件时**不是抛异常**，而是**正常返回一句解释**
（事件流里 `outcome=null`、detail = `项目"gps-live"里没有文件"index.html"`）。
⇒ 框架认为成功 ⇒ `retryGuidance` 不触发。**而模型 T5 失败后，T6 又原样读了一次**（见下表）——
连"当轮引导"也没被跨轮内化（`retryGuidance` **只在当轮回填、不进长期库**，这是有意的防注入设计，
代价就是**它跨轮失忆**）。

#### 类型②：成功但原地打转（本轮的主症状）

我把 152 行事件流里**每一条工具调用的指纹**（工具名 + 归一化参数）做了跨轮统计：

| 重复次数 | 出现在 | 调用指纹 |
| --- | --- | --- |
| **5 次** | T2,3,5,6,7 | `read_code_file(file=pages/index/index.ink, project=gps-live)` |
| 4 次 | T3,5,6,7 | `read_code_file(file=app.js, …)` |
| 4 次 | T2,3,5,6 | `read_code_file(file=app.json, …)` |
| 3 次 | T3,6,7 | `read_code_file(project=gps-live)`（**只给 project = 列目录**） |
| **2 次** | T5,6 | `read_code_file(file=index.html, …)` ← **同一个不存在的文件，读了两次** |
| 2 次 | T1,2 | `load_skill(aiui-dev)` + 6 个 section **各 2 次**（整个技能重读一遍） |

**写类调用只有 3 个，且全在 T1**（`save_code_file` ×2 + `open_aiui_app` ×1）。
**T2–T7：37 次工具调用、0 次写操作。** 用户说的是"修"和"重写"，agent 做了 6 轮只读侦察。

⇒ **没有任何机制会发现"同一份文件读了 5 遍"、"用户要修却零写操作"**。
`emptyRounds`（连续空轮计数）判的是"模型没出声"，`planContinues` 判的是"计划还有未完成步骤"——
**而本次 agent 压根没建计划**，两道闸门全部空转。

#### 类型③：成功但方向错了

T4 那条 454 token 的"诊断"把真因归给 HTTPS/浏览器（§4.3），
**它没有任何工具调用**，也没有任何机制要求它**去验证这个假设**（比如"调一次 `list_tools` 看有没有定位工具"）。
结果这个错因被写进会话历史，**污染了后面 3 轮的全部推理**（T5/T6/T7 都在这个错前提上打转）。

📌 **一句话**：现有自省是 **"异常驱动"**；本轮遭遇的是 **"无进展驱动"**，
后者不抛异常，所以所有守卫都看不见它。

### 6.3 缺口三：事件流没有 reflect 事件 ⇒ 反思不可观测、不可回归

`SessionEventKind`（`session/SessionEvent.kt:54`）现有 11 类：
`turn_start` / `turn_end` / `user` / `attempt` / `assistant` / `tool_call` / `tool_result` /
`context` / `compaction_*` / `cut` / `effect_intent`。

**没有 `reflect` / `correction` / `replan` 一类** ⇒ 就算补了 6.2 的机制，
"它哪一轮意识到自己错了、改成了什么"也**落不了盘**，于是：
- 无法观测（= 出问题时只能靠用户抱怨）
- 无法做 replay/golden 回归（**审计判据 ⑫** 的 `SessionReplayTest` 拿不到输入）

### 6.4 缺口四：教训库按「工具名」去重 ⇒ 记下的是无用教训

`addLessonOnce(dedupKey = tc.name, …)`（`:1039-1043`）按**工具名**去重，
写的又是**不含任何工具输出**的固定文案（`LessonText.toolFailed`，防注入的硬约束，见该类注释）。

于是本轮沉淀的教训是 `"工具 read_code_file 在这台设备上失败过：原样重试无效"` ——
⚠️ 这句话**既是错的**（`read_code_file` 没坏，是那个文件不存在），**也没用**（下次照样不知道哪个文件不存在）。

**这是个真两难**：防注入要求"绝不引用工具输出"，而有用性要求"记住具体是什么不存在"。
裁决方向不是放宽防注入，而是**给教训加"结构性"而非"内容性"的维度** ——
例如记 `(工具名, 参数形状)` 而不是原文（`read_code_file` + `file 指向项目里不存在的路径`），
细节见 §8 的 **P1-12**。

---

## 7. 附带损耗（都是可省的）

### 5.1 无效工具调用（9 次被拒/超限）

| 轮 | 调用 | 返回 |
| --- | --- | --- |
| T1 | `load_skill_section(file=apis/geo/geolocation.md)` | 没有该文件（死链） |
| T1 | `load_skill_section(section="全文")` | 找不到章节（非法用法） |
| T2 | `load_skill_section(file=geo/geolocation.md)` | 没有该文件（**同一死链第 2 次**） |
| T2 | `load_skill_section(section="9.0")` | 找不到章节 |
| T5/T6 | `read_code_file(project=gps-live, file=index.html)` | 项目里没有 index.html（**凭空假设了一个网页工程结构**） |
| T6 | `list_files {}` | "project 不能为空" |

### 5.2 工具语义有歧义，模型学坏了

- `read_code_file` **只给 `project` 不给 `file` 时静默返回文件清单**（T3 #83、T6 #125、T7 #145 都这样用）
  ⇒ 模型形成"这个工具也能列目录"的错误语义，于是反复去 read 不存在的文件。
- `list_files {scope:"downloads"}` 返回 **5309 项（只显示前 200）**（T6 #134），
  把整个下载目录（含 `.7934039a/.u/u0/0/1/1…` 这种系统垃圾树）灌进上下文 —— T6 的 input 涨到 107.5k 与此直接相关。

### 5.3 重复侦察

- T2 把 `aiui-dev` **又完整读了一遍**（12 次模型调用里含 1 次 `load_skill` + 8 次 `load_skill_section`），
  而 T1 刚读过同一份技能。会话内没有"技能已加载"的短路。
- T3/T5/T6/T7 每轮都 `read_code_file` 同样那 5 个文件（`index.ink` 10.5KB ≈ 每次 3k+ tokens）。
- 净效果：**873k input + 35.7k output，买到的是"把同一批文件读了 4 遍、一行代码没改"**。

---

## 8. 修复建议（按优先级，均带判据）

> **优先级判据**：P0 = 本轮 27 分钟空转的直接成因，或"不修还会再犯"；
> P1 = 能力/文档缺口；P2 = 体验与降噪。
> 其中 **P0-5 / P0-6 / P0-7** 来自 §6（用户追问的"agent 自己也要分清端 + 会自省纠偏"），
> 是本报告认为**最值钱**的三刀 —— 它们修的是**机制**，不是"这一条 bug"。

### P0-1 终态护栏 ✅【已实施 2026-10-09】：内部标记永不进入用户可见文本
- 收口点：`AiConversationService.kt:2023`（`reply = turn.content`）、`:2705`（fallback 总结）、`:2751`（空正文兜底）**之后统一过一道**。
- 判据：`ChatFastPath.needsUpgrade(reply) || ToolIntentRouter.isMarkerLike(reply)` ⇒ 视为"**未产出**"：
  ① 本轮还没升级过 → 升级重来（需要"整轮最多 N 次"的显式计数，不能只靠 `probeMode` 这个瞬时布尔）；
  ② 已升级过 → 不再回标记，改为强约束"必须给出可读结论，或明确说明缺什么能力"，最终落兜底文案；
  ③ `TurnEndReason` 不得再记 `COMPLETED`（现在记 COMPLETED 等于骗人说做完了）。
- 同时给 `ReplySanitizer` 加一条：`[NEED_TOOLS]` / `[TOOLS]` 行永不出现在手机气泡与眼镜 TTS。
- 测试：新增"标记不进最终回复"断言，并做**反向验证**（把护栏删掉必须变红，审计判据 ⑪）。

### P0-2 升级后必须撤掉尾部那条指令 ✅【已实施 2026-10-09】
- 位置：`:1893-1908`（menu 分支）与 `:1947-1965`（probe 升级）。
- 非探测轮不能整段 rebuild（会劈散 in-flight 的 tool 配对 → 400），但**可以**在 tail 位置追加一条覆盖说明：
  "本轮已挂载全量 N 个工具，**不要**再回复 `[NEED_TOOLS]`；直接从工具表里选。"
- 判据：提示里不允许存在任何指向"当前已不成立状态"的指令（审计判据 ⑥）。

### P0-3 修技能死链 ✅【已实施 2026-10-09】 + 把"页面取位置"的正解写进文档（成本最低、收益最大）
- `apis.md:20`、`apis-navigator.md:19` → 要么补 `geo/geolocation.md`，要么把链接改成本目录实际文件名。
- `lab-runtime.md` §9 增一条硬约束（现在该章对"位置"是**零覆盖**）：
  "**自托管宿主未实现 `geolocation` 宿主能力**（`ink/host.js:243 buildHostCapabilities()` 只有 4 个传感器钩子；
  手机端 `AiuiWebHost` 与眼镜端 `AiuiLinkActivity` 的 JS 桥都只有 callTool/sensor/log）
  ⇒ **不要写 `navigator.geolocation`**（必失败，错误码 2）。
  页面取位置**只用** `globalThis.Lab.callTool('get_location', {}, cb)`；
  ⚠️ 拿到结果要**解析进主数据字段**，不要只贴一行提示文字。
  ⚠️ 眼镜端 `setInterval` 零执行 ⇒ 实时跟踪只能按键触发单次定位。"
- 附上 §4.7 的范式片段（含"必须接进主数据流"这条踩过的坑）。

### P0-4 给 `get_location` 加结构化输出 ✅【已实施 2026-10-09】（页面需要数值，不只是人话）
- 现状 `LocationTools.getLocation()` 返回**一句人话**（`当前位置：地址（纬度 x，经度 y，精度 ±n 米，来源 gps）`），
  页面要画经纬度/精度/速度/里程只能**正则抠文本** —— 天然不可靠。
- 加可选参数 `format="json"`（默认仍是人话，保持模型侧不变），返回
  `{lat,lng,accuracy,altitude,speed,provider,ts}`；页面侧一律用 json 形态。
- ⚠️ 改工具签名要同步 `InfoToolProvider` 的 schema 与 `auditToolSchemas` 校验。

### P0-5 把「端」升为模型可感知的一等维度 ⚠️【部分实施：a+b 层已落地，字段层未做】（§6.1）
> 现状：模型只能读 `schema.description` 的散文猜端；成对工具无选端判据。
> 目标形状复用 `open_aiui_app.target` 那条**已经做对**的判据：
> **明说端 → 照做；没说 → 按默认规则；含糊 → 问一句**。

1. **`ToolEntry` 新增 `actsOn: ToolSide = ToolSide.PHONE`**（PHONE / GLASSES）——
   ⚠️ 名字必须是 **`actsOn`（作用于哪端）**，不是 `executedOn`：执行**永远在手机**（§5.2），
   叫 `executedOn` 会让后来者以为存在两条执行通道。
   - **不复用 `category`**（那是用户视角分组、会随设置页重排而变）；
     **也不从 `requiresGlasses` 派生**（那是"有没有眼镜"的运行依赖，与"作用于哪端"正交：
     `show_lyrics` 是 `requiresGlasses=true` + `actsOn=GLASSES`，但 `install_aiui_project`
     是 `requiresGlasses=true` 而动作**两端都合理**）。
2. **注入 schema**：装配时给 `description` 加**统一端前缀** —— `[眼镜] …` / `[手机] …`。
   - 判据：**模型看到的每条工具描述都自带头部端标记**，不再靠正文散文。
   - ⚠️ 前缀进的是**已下发 schema**（`namesOf` / `auditToolSchemas` 那条链），别只在设置页生效。
3. **系统提示补成对工具的选端总则**（§6.1.1 F-b），并**同批修掉两处现存冲突**：
   - `launch_glasses_app` 的触发词"打开某应用"**没限定眼镜** ⇒ 与 `open_phone_app` 抢活，必须改成"打开**眼镜上**的应用"（并保留"打开手机上的应用用 `open_phone_app`"的互指）。
   - `set_phone_alarm` 的"未强调手机 → **优先眼镜**"与新决策（**未指明端就问**）矛盾 ⇒ 改为询问。
   - 逐条落 **§6.1.1 C 的成对表**：①强成对 6 组**一律问**；②弱成对（看画面）**能判就判、判不出才问**；
     ③非成对**不问**。并实现 **D 的四条例外**（明说端 / 上下文锚定 / 某端不可用 / 唯一通道）。
4. **过程卡片带上端**：工具行显示 `[眼镜] look_at_view(...)`，
   让用户一眼看出"它调的是哪端"——与 P2-15（可观测性）同源。
5. **回归测试**：断言"每个工具都有明确 `actsOn`"（新工具漏填 → 红），
   以及"§6.1.1 C 表里每对工具的 `actsOn` 相反"这条结构不变式。

### P0-6 补「无进展检测」 ⚠️【部分实施：第1/2条已落地，第3/4条未做】——覆盖三种"假成功"（§6.2）
> 现有自省是**异常驱动**（`LessonText.retryGuidance`，本轮**一次没触发**）；
> 本轮遭遇的是**无进展驱动**（工具全成功、37 次调用、0 次写操作）。
> 判据落点：**"这一轮相比上一轮前进了没有"**，而不是"有没有报错"。

1. **跨轮调用指纹去重**（最小可行）：
   维护会话级 `Set<指纹>`（`name + 归一化 args`，args 做 key 排序 + 项目名归一）。
   同一指纹第 N 次出现（建议 N=2）⇒ 在工具结果后**追加一句框架自产的提示**：
   "⚠️ 你在本次任务里第 N 次做同样的调用（`<name>`）。如果你没有拿到新信息，
   说明这个动作不会推进任务：请换一个动作（写文件/改参数/换工具），或直接说明卡在哪。"
   - 证据：本轮 `read_code_file(index.ink)` **5 次**、`read_code_file(index.html)` **2 次**（同一不存在文件）。
   - ⚠️ 归一化必须**排除易变字段**（时间戳、随机 id），否则等于不检测（同 **审计判据 ③**）。
2. **"诉求—动作"对齐检查**（覆盖"要修却只读"）：
   收尾前（`reply` 落地之前）做一次纯函数判定：
   - 用户诉求含**动作意图**（`修/改/重写/装/生成/删/加`）**且**
   - 本轮 `turnUsedTools` 为真但**零 `sideEffect` 调用**（`save_code_file`/`edit_*`/`install_*`…）**且**
   - 本轮没给出"缺什么能力"的明确结论
   ⇒ 触发**一次**自问轮（复用 `MAX_PLAN_CONTINUES` 那套计数上限）：
   "用户的诉求是「<原话>」。你本轮只做了查询、没有产生任何改动 —— 是已经不需要改（说明理由），
   还是没找到改的路径（说明卡在哪）？"
   - ⚠️ 防误伤：**必须要求"有动作意图"**才触发，否则用户只是问个问题也会被无端打断。
3. **失败但未抛异常（类型①）**：给工具加 `ToolOutcome` 维度（成功 / 业务失败 / 异常），
   让"找不到文件、没有该记录"这类**正常返回的业务失败**也能进 `retryGuidance` 那条路。
   - 最小切入：先给 `read_code_file` / `read_text_file` / `list_*` 这几族的"不存在"分支打标记。
4. **假设需验证（类型③）**：当模型给出**归因型结论**却没有工具调用支撑时（本轮 T4 的 HTTPS 错因），
   在收尾前插入一次"验证提示"：要求至少调一次能证实/证伪该结论的工具，或明确标注"这是推测"。
   - ⚠️ 这条最容易过度设计，建议**先只对"诊断类任务"开启**（诉求含 `为什么/哪里有问题/修`）。

### P0-7 事件流补 `reflect` 事件 ✅【已实施 2026-10-09】（§6.3）
- `SessionEventKind` 加 `REFLECT("reflect")`，字段：`trigger`（`loop`/`no_progress`/`assumption`）、
  `detail`、`action`（改了策略 / 只是重试 / 放弃）。
- **两个不变式**（可钉单测）：① `reflect` 与它触发的**下一条 `tool_call`** 成对可读
  （能回答"反思之后它做了什么不同的事"）；② 反思轮**不改写**已落盘历史（只追加）。
- 收益：`SessionReplayTest`（**审计判据 ⑫**）第一次能拿"该反思却未反思"做回归。

### P1-8 技能文档补「双端能力归属」 ✅【已实施 2026-10-09】（§5 落地）
- `lab-runtime.md` §9 **改标题**（现为「调用手机端工具」，单端表述）→ 如「调用端侧工具」。
- 章内补：**三类模型表** + 一句"页面只有**一个**调用口，所有工具都在**手机**执行；
  眼镜专属工具只是'手机代理去操作眼镜'"。
- 补 **能力归属表**，把 18 个眼镜工具**按域列全**（现在只提了 `get_glasses_status`），
  特别是三个从未被文档提到的：`look_at_view`（眼镜摄像头）、`listen_ambient`（眼镜麦克风）、
  `get_head_pose` / `set_motion_rule`（眼镜头部姿态）。
- 补 **"预览 ≠ 眼镜"差异清单**（定时器零执行 / 眼镜离线时 ③类工具失败且不走 guard / IMU 来源不同）。
- 判据：**凡是"某个能力到底在哪端生效"的问题，答案必须能在文档里一个字查到**，不靠模型推断。

### P1-9 给宿主补 geolocation 能力 ❌【未实施：属宿主能力开发，非本轮范围】（为"持续实时流"而做，不是为"能拿到位置"）
> ⚠️ 修正：**位置已经拿得到**（通路 C，实测），本条解决的是 **`watchPosition` 持续流**与**眼镜端轮询缺失**。
- `host.js:243 buildHostCapabilities()` 增加 `geolocation.getCurrentPosition/watchPosition/clearWatch`
  → Android 桥（`AiuiWebHost.bridge` 与 `AiuiLinkActivity.bridge` 各加 start/stop → LocationManager）；
  眼镜端还需一并补定时器（或让 geolocation 走事件推送，绕开 `setInterval`）。
- 顺带补 `AiuiWebHost` 的 `onGeolocationPermissionsShowPrompt`（否则回落 WebView 原生定位也拿不到授权）。

### P1-10 续说词不该退化成探测轮 ✅【已实施 2026-10-09】
- 判据：识别"指代上一轮产物"的续说词（`继续 / 接着 / 重写 / 再改 / 修一下 / 还有问题`），
  且会话已进入代码/AIUI 场景（`lastTurnHadToolCalls` 且用过 `save_code_file` 或 `load_skill(aiui-dev)`）⇒
  **直接沿用上一轮工具子集**（代码子集），不再探测、不再走菜单。
- ⚠️ 防回退：这不违反「路由子集不自动扩全量（42.4k 教训）」——扩的是**同一任务的子集**，不是全量。
- 另：`TASK_SHAPED_CONFLICTS[ALL]` 里的"写/生成/代码"对"已有 AIUI 项目的续做轮"不成立，需加会话态维度。

### P1-11 术语与注释统一 ⚠️【部分实施：ToolGuards 主处已改，其余注释待扫】
- `ToolGuards.kt:218` 等处的注释把「AIUI 页面」等同于「眼镜宿主」——
  统一为 **手机预览宿主（AiuiWebHost）** / **眼镜宿主（AiuiLinkActivity）**；
  「AIUI 页面」只指页面制品本身，不指运行端。
- （可选）给页面注入平台标记（`window.__labHost = 'phone' | 'glasses'`）：
  ⚠️ 但**更推荐不注入** —— 页面应按"能力在不在"分支（`Lab.listTools()` / `get_glasses_status` 探活），
  而不是按"我在哪端"分支，否则双端差异会渗进每个页面。

### P1-12 教训库加「结构性」维度 ❌【未实施：**依赖 P0-6 第3条 ToolOutcome**，缺它无法做出有意义的粒度】
- 现在 `addLessonOnce(dedupKey = tc.name)` 写出的是**既错又无用**的一句（"工具 X 失败过"）。
- 改法**不是**放宽"不含工具输出"（那是防注入硬约束），而是换成**结构化签名**：
  `dedupKey = name + 参数形状`（如 `read_code_file:file存在性`），文案改成
  "`read_code_file` 在**项目里不存在的路径**上会失败：先 `list_files` 确认路径再读"。
- 判据：**教训必须能改变下一次的动作选择**；只描述"某个工具坏过"的教训一律不写。

### P2-13 消除工具语义歧义 ✅【已实施 2026-10-09（含对原建议的一处修正）】
- `read_code_file` 缺 `file` ⇒ 直接报错并指路 `list_files`（别再静默当"列目录"）。
- `list_files{scope:"downloads"}` ⇒ 默认聚合/分页（隐藏目录与系统树不展开），别再把 5309 项灌进上下文。

### P2-14 会话内去重复侦察 ⚠️【部分实现：跨轮重复检测见 P0-6；技能去重缓存未做】
- 技能加载结果在会话内缓存，`load_skill` 命中已加载时直接回"已加载，无需重读"。
- 已读过且未变更的项目文件，在 tail 里给一段"当前已知文件摘要"，减少重复 `read_code_file`。

### P2-15 补可观测性 ✅【已实施（手机端）2026-10-09】：让 agent 能自己看见页面报错
- 新增 `read_aiui_log`：把 `AiuiWebHost` 的 `js[...]` console 与 `error` 回调做成环形缓冲暴露成工具；
  或让 `open_aiui_app` 的结果里带回页面最近 N 条 console/error。
- 判据：**凡是"只有用户能看见、agent 看不见"的故障信号，都是必然的误诊来源**。

### 实施记录（2026-10-09 22:00–23:00，本轮实际落地）

| 项 | 状态 | 落点 |
| --- | --- | --- |
| **P0-1 标记终态护栏** | ✅ | 新增 `ai/InternalMarkerGuard.kt`；`AiConversationService` 加 `markerUpgrades` 计数 + 终态清洗 + `TurnEndReason` 不再谎报 completed；`ReplySanitizer.sanitize` 加标记过滤（覆盖眼镜 TTS）；新增 `InternalMarkerGuardTest`（14 条断言） |
| **P0-2 撤尾部旧指令** | ✅ | 新增 `InternalMarkerGuard.neutralizeMenuInstruction`（幂等）；接线在菜单模式**后续轮升级**那条"不重建 messages"的路径上（这正是旧指令残留处） |
| **P0-3 技能死链/正解** | ✅ | `lab-runtime.md` 新增 §9.00/§9.01/§9.02（端归属三类 + 18 工具 + 页面取位置正解 + 死链说明） |
| **P0-4 `get_location` 结构化** | ✅ | `LocationTools` 加 `format="json"`，抽 `resolveFix()` 让 text/json **共用同一套取数逻辑**（避免两条路降级行为漂移）；`InfoToolProvider` schema 同步 |
| **P0-5 端维度（a+b 层）** | ⚠️ 部分 | 系统提示总则 + 14 个工具描述互指已落地；**`ToolEntry.actsOn` 字段层未做**（需动装配链，风险另评） |
| **P0-6 无进展检测（第1/2条）** | ⚠️ 部分 | 新增 `ai/ToolRepetitionTracker.kt`（滑动窗口指纹，按会话隔离）+ `ai/ActionIntent.kt`（动作意图白名单）；接在工具结果回填处与收尾判定处；新增 `NoProgressDetectionTest`（12 条断言）。**第3条 `ToolOutcome` 三态、第4条归因需验证未做** |
| **P2-13 工具语义歧义** | ✅ | `list_files(scope=downloads)` 过滤隐藏/系统条目 + 超量时给收窄建议；`read_code_file` 清单**改为指路而非删功能**（见下） |
| **P0-7 `reflect` 事件** | ✅ | `SessionEventKind.REFLECT` + `Reflect` 事件类 + `ReflectTrigger` 四态；同步 `SessionLog` 编解码、`AgentTurn.reflect()`、`AgentSessionStore.appendReflect()`；**三处纠偏点全部落盘**（标记泄漏 / 重复调用 / 诉求-动作不一致）；四个消费点（`SessionDump`/`SessionGraph`/`SessionProjection`/`SessionTrace`）逐个补分支 —— **由编译器点名，漏一个编译不过** |
| **P1-10 续说词豁免** | ✅ | `ToolIntentRouter.isContinuation()`（白名单 + 调用方补会话态）+ 主循环 `continuationTurn` ⇒ **跳过菜单探测、直接给全量**；顺带绕开「重写」被 `TASK_SHAPED_CONFLICTS[ALL]` 误否决 |
| **P1-11 术语统一** | ⚠️ 部分 | `ToolGuards.kt` 主处已改（"AIUI 页面的代码就在眼镜上跑" → 区分手机预览宿主/眼镜宿主）；其余注释未逐处扫 |
| **P2-15 页面日志可见** | ✅ | 新增 `aiui/AiuiPageLog.kt`（按项目分桶的环形缓冲，80 条/项目）；`AiuiWebHost` 的 console/error/log 三处接入；`open_aiui_app(手机端)` 结果自动附带最近日志并在有 error 时显著提示；打开时先清空旧日志 |
| P1-9 宿主 geolocation / P1-12 教训结构化 | ❌ 未做 | 见文末"本轮未做的项与理由"（**P1-12 依赖 ToolOutcome**） |

#### ⚠️ 对 P2-13 原建议的一处修正（重要）

原建议是"`read_code_file` 缺 `file` ⇒ **直接报错**并指路 `list_files`"。实施时**没有照做**，理由：

- "只传 `project` 返回文件清单"是**文档化的有意设计**，且服务于一个真实流程 ——
  **改代码前先看有哪些文件**。改成报错会把这条正常流程一起砍掉（模型得先调 list_files 再回来读）。
- 真机的问题是**语义漂移**（模型以为"`read_code_file` 就是列目录的"，于是 3 次用它列清单、
  还去 read 一个不存在的 `index.html`），**根因是职责重叠、不是能力多余**。
- 所以改成：**保留能力 + 在返回文案里明确划界** ——
  "只是浏览/查找文件请改用 `list_files` / `search_files`，本工具只负责读取你已确定要看的那个文件"。

📌 判据：**修"语义漂移"的正解通常是"把职责讲清楚"，而不是"把功能删掉"** ——
 删功能会连带砍掉它的正当用法，而漂移的根源往往只是描述里没划界。

#### 本轮未做的项与理由（诚实清单）

| 项 | 为什么没做 |
| --- | --- |
| **P1-9 宿主补 geolocation** | **不是"忘做"，是刻意不做**。它要在 `host.js` 的 `buildHostCapabilities()` 里挂 `getCurrentPosition/watchPosition` 并等待 Android 异步回调 —— 而自托管宿主的 **Promise/定时器回调支持情况本身不确定**（§9.0 真机实测：页面 realm 里 `setTimeout`/`Promise.then` **零执行**）。改错方向的后果是页面**永久挂起**（比现在"报错回落"更糟），且**只能靠设备实测发现**。⇒ 应作为独立一轮，改完立刻在真机上验一次"调用后能返回/能超时"，再谈合并。 |
| **P0-6 第3条 `ToolOutcome` 三态** | 需要改**工具协议层**（每个工具的执行结果要能表达"业务失败"而非仅靠字符串），涉及全部 provider 的返回值约定。改动面比本轮其他项加起来还大，且要同步 `ToolRiskMapTest` 一类不变式 ⇒ 单独一轮。 |
| **P0-6 第4条 归因需验证** | 依赖"识别归因型结论"的判据（`ActionIntent` 只能识别动作意图）。做浅了会误伤正常回答，做深了要额外一次模型调用 ⇒ 先观察第1/2条的效果再定。 |
| **P1-12 教训库结构化** | **硬依赖 `ToolOutcome`**：现在框架只知道"抛异常"，无法区分"文件不存在/权限不足/超时"。没有这个维度，"结构化 dedupKey"只能做成 `name + "execute"`，与现在按 `name` 去重**等价** ⇒ 做了也是假改进。 |
| **P1-11 其余注释** | 主处已改；其余散落注释属"读代码时顺手改"的量级，单独扫一遍收益低。 |
| **P2-14 技能去重缓存** | 与 P0-6 的重复检测功能重叠（技能重读同样会被"同指纹第2次"提示）。**先看 P0-6 是否已覆盖，避免两套机制打架**。 |

📌 **本轮的取舍原则**：**能自动验证的先做**（有单测、编译即验），
**需要设备实测才能确认的往后放**（P1-9），**改动面超出本轮的一律不塞进来**（ToolOutcome）。
宁可交一份"做完了且验证过"的清单，也不要一份"看起来都做了"的清单。

### 决策记录（实施前已拍板 / 仍待定）

**✅ 已决策（2026-10-09 用户）**
1. **成对工具且用户未指明端 ⇒ 询问**，**不设默认端**。
   —— 完整判据（成对表三分类 + 四条例外 + 询问纪律）见 **§6.1.1**；
   两处必须同批修掉的现存冲突见 **§6.1.1 B**。

**⏳ 仍待定**
1. **无进展提示的强度**：温和追加一句提示（模型可忽略，零误伤）还是
   强硬到"拒绝重复调用 + `tool_choice` 强制换策略"（有效但有误伤风险）？
   —— 报告倾向**先温和**（P0-6 第 1 条），跑一段时间看效果再收紧。
2. **成对表的维护方式**：走 a+b（文案，手工同步）还是 c（`pairedWith` 字段 + 自动生成文案）？
   —— 报告倾向 a+b 先行、c 作为机制化目标（§6.1.1 F）。

---

### 附：P0-5 配套文案草稿（a+b 层，可直接采用）

> ⚠️ 提示词措辞本身就是行为边界，故逐字列出待复核。改动面：
> `OpenAiService.buildSystemMessage` 一处 + **13 个工具 description**（6 组强成对 = 12 个，加 2 个弱成对）。

#### 1) 系统提示总则（插入 `OpenAiService.kt` 工具使用准则区）

```
- 【端的选择】同一个诉求在手机和眼镜上都有对应工具时（设备状态、应用、文件、定时提醒）：
  用户说明了哪一端就照做；**没说明时必须先问一句**（如「你是问手机还是眼镜？」），不要替他选。
  三种例外不必问：① 上一轮刚用过某一端、或刚在某端打开过东西 → 沿用那一端；
  ② 某一端不可用（眼镜没连）→ 直接用可用那端并说明；若诉求只能在离线那端完成，如实说需要眼镜；
  ③ 该诉求本就只在一端成立（看眼前的画面只能用眼镜相机、截手机屏只能用手机）→ 直接用。
  同一轮**只问一次**，多个歧义合成一个问题；用户答得仍含糊时**不要再问**，选一端执行并说明选了哪端。
```

#### 2) 六组强成对：统一句式模板

每对**两边各加一句**（互指 + 要求询问），插在 description 末尾：

```
「<诉求>」手机与眼镜都有对应工具（手机 <A>／眼镜 <B>）。
用户未指明哪一端时，**先问一句<问法>**，不要自行选一端。
```

| # | 手机工具 | 眼镜工具 | 问法 |
| --- | --- | --- | --- |
| 1 | `get_phone_status` | `get_glasses_status` | "你是问手机还是眼镜？" |
| 2 | `list_phone_apps` | `list_glasses_apps` | "要看哪一边的应用列表？" |
| 3 | `open_phone_app` | `launch_glasses_app` | "是在手机上打开还是眼镜上打开？" |
| 4 | `list_files` | `list_glasses_files` | "是手机里的文件还是眼镜里的？" |
| 5 | `read_text_file` / `read_code_file` | `read_glasses_file` | 同上 |
| 6 | `set_phone_alarm` | `manage_timer` | "让手机响还是眼镜提醒你？" |

#### 3) 弱成对：`capture_screen` ⇄ `look_at_view`

```
「看画面」这一类请求有两条路：看用户**眼前/手里**的东西用眼镜相机（look_at_view），
看用户**手机屏幕**上的内容用 capture_screen。
按用户措辞判断：出现「面前/眼前/手上/拿着/这个实物」→ 用眼镜相机；
出现「屏幕/这个页面/界面/截屏」→ 用手机截屏。
**真的判不出来时才问一句**（不要一律问，这类请求多数自带端线索）。
```

#### 4) 两处冲突的具体改法

**a) `launch_glasses_app`（`GlassesToolProvider.kt:113`）—— 必须限定端**

```
改前：打开眼镜上安装的应用。当用户说"打开某应用""启动某应用"时调用。传入用户口中的应用名称…
改后：打开**眼镜上**安装的应用。仅当用户指明眼镜端时（「打开眼镜上的 XX」「戴上眼镜打开 XX」）才调用；
      用户没说哪一端时，先问一句「是在手机上打开还是眼镜上打开？」，不要自行选一端。
      传入用户口中的应用名称（如"小智""Via""小游戏"），不需要包名。
      要打开**手机上**安装的应用用 open_phone_app。
```

**b) `set_phone_alarm`（`PhoneToolProvider.kt`）—— 撤掉"默认眼镜"**

```
改前：…「X分钟后提醒」若未强调手机，优先用眼镜定时任务（manage_timer 的 create，即 set_timer）。
改后：…用户没说是让手机响还是眼镜提醒时，**先问一句**「让手机响还是眼镜提醒你？」，
      不要默认任何一端（手机闹钟响在手机、眼镜定时提醒响在眼镜，两者体验不同）。
```

#### 5) 落地后的验证方式（不打包，仅编译 + 人工确认）

1. `./gradlew.bat :phone-app:compileDebugKotlin` 确认无编译错误；
2. 导出一次工具 schema JSON（可用临时单测跑 `ToolRegistry.toolList`，
   见记忆里的判据：**量 schema 必须走真实 JSON，不能用源码文本估**），
   逐条核对：13 个工具的 description 里**都出现了互指的对方工具名**；
3. 人工走查三条：①"还有多少电" → 应**问**；②"眼镜还有多少电" → 应**直接调** `get_glasses_status`；
   ③"看看我前面有什么" → 应**直接调** `look_at_view`（不问）。

---

## 9. 一句话复盘

`[NEED_TOOLS]` 本身设计没错（省 token 又保能力），错在**它只有一次被接住的机会，第二次就漏给用户**；
而它之所以被连喊两次，是因为**升级后没人告诉模型"你已经拿到全量工具了"**；
而它之所以被第一次喊出来，是因为**续说词被降载成探测轮、写文件的能力被削掉**；
而这一切之所以发生在一个"拿不到位置"的页面上，是因为
**页面选了宿主没实现的 `navigator.geolocation`，而唯一能纠正它的文档是死链、正解（`Lab.callTool('get_location')`）自己的文档里一个字都没写**
—— 且它写的回退虽然调对了工具，却**只把结果贴成一行小字、没接进主数据流**，
所以哪怕 GPS 早就在手里，屏幕上依然是"拿不到位置"。
而它之所以会把"眼镜端应该有眼镜的定位"当真，是因为
**系统从来没把"双端能力归属"讲清楚**：标注只说"依不依赖眼镜"、文档标题只说"手机端工具"、
18 个眼镜工具只提了 1 个、且从不说"所有工具都在手机执行" ——
**缺的那句话，模型用自己的想象补上了。**
而它之所以能**空转 6 轮、37 次调用、0 次写入还不自知**，是因为
**所有守卫都是"异常驱动"的**：工具没抛异常、输出也不为空 ⇒ 没有任何机制问过它
"你这一轮相比上一轮前进了吗"、"用户要的是修改，你改了什么" ——
**框架只监听了"出错"，没监听"没进展"。**
七层里任何一层补上，这一轮都不会走到"气泡里一行 `[NEED_TOOLS]`"。

📌 **本次修正留痕（21:35，用户提醒后）**：初版把"实时 GPS 做不出来"当成宿主能力缺口，是
**把"首选通路失败"误读成"能力不存在"**。判据教训：
**一个功能失败时，先数清它有几条通路（这里是 B/C 两条），再看是哪一条断、另一条是否本就可达** ——
别用"宿主没实现某个 API"直接推导"功能不可能"。
用户那句"当我说位置的时候确实可以出现位置的 GPS"就是反例。

📌 **本次修正留痕（21:39，用户追问后）**：补入 §5「双端能力归属」。
判据教训：**只要系统里存在"同一个制品跑在多个宿主上"的结构，就必须显式回答三个问题** ——
① 每个能力的**执行落点**在哪；② 页面**拿到的数据**属于哪端；③ **两端行为差异**清单是什么。
三者缺任何一条，模型都会用想象填空（本次填出的是"眼镜端有眼镜的定位"）。
同源：**标注只表达"依不依赖"，不等于表达了"在哪端"** —— 缺的那一维不会自己出现。

📌 **本次修正留痕（21:44，用户追问后）**：补入 §6「agent 侧的两个机制缺口」。
判据教训（本报告最想留的一条）：**"没有报错"不等于"在前进"。**
一个 agent 的守卫若只挂在异常路径上，那么它最大的失效模式——
**反复做同一件事、成功返回但毫无进展**——将完全不可见。
判据要落在**"这一轮的产出与上一轮相比新增了什么"**上（新增信息 / 新增副作用 / 新增结论），
而不是落在"有没有异常"上。
配套：**"成功但业务失败"是第三种状态**（`read_code_file` 找不到文件时正常返回一句话），
不抛异常 ⇒ 现有 `retryGuidance` 看不到它 ⇒ 必须显式引入 `ToolOutcome`。
同源：**只描述"某个工具坏过"的教训无法改变下一次的动作选择**（§6.4）。
