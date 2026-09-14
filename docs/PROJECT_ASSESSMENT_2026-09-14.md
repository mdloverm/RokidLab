# RokidLab 全项目评估报告（v3.6 / 2026-09-14）

- **评估对象**：`D:\rokidapp\cxrl\RokidLab`（phone-app + RokidLink，Kotlin / Compose）
- **基线**：HEAD = `f95deb4`（`fix(v3.6): 缺陷复核修复与安全加固 + 构建入口唯一化`），`versionName=3.6` / `versionCode=21`
- **工作区状态**：干净（仅 4 个未跟踪的新文档 / mermaid 图）；**已恢复"可 clean checkout 编译"基线**
- **评估方式**：只读静态分析（Read / Grep / Glob / Git 只读 / 脚本统计），**未修改任何源码、未执行构建、未连接真机**
- **范围说明**：按用户明确指示，**"明码 / 凭据类"问题（硬编码 token、签名口令入库）整体排除**，不计入本报告的问题清单
- **与既有文档的关系**：本报告**独立复核**并**刷新**仓库内已有的 5 份评估文档（`ENGINEERING_ASSESSMENT_2026-09-12` / `CODE_AUDIT` / `ARCHITECTURE_REVIEW` / `FUNCTIONAL_BUG_REVIEW_2026-09-13` / `REMEDIATION_REVIEW_2026-09-13`）——凡与旧结论冲突处均以本次实测为准，并在 §6 逐条标注闭环状态

---

## 0. 结论速览

**综合评级：B+**（技术实现 A-，工程保障 B）

一句话：**这是一个"产品完成度罕见地高、工程纪律正在快速追上来"的独立项目。** 2026-09-12 → 09-14 的三轮整改不是文档层面的自我安慰：**09-13 报告点名的 4 个功能性缺陷（F1–F4）已全部真修**，工作区已提交、基线可重建，构建门禁从 1 道扩到 6 道，测试从 9 类 / 102 例涨到 **15 类 / 162 例**。

**最值得肯定的 5 件事**

1. **"假成功"被系统性根除** —— 项目历史上反复踩「AI 工具假成功」这个坑，本轮把最后两条漏网路径也堵上了：AIUI 宿主拉起失败不再上报 `"OK"`（`AiuiFrontendController.PUSHED_OPEN_FAILED`）、文件下载不再"收到 `DONE` 即判成功"（`reconcileDownloadedSize` 按远端字节数对账 + 失败删半成品）。这是**工程价值观层面的进步**，比修 N 个 bug 更值钱。
2. **六道构建期闸门** —— 4 道 `preBuild`（协议双端同源 / i18n key 相等 / 关键链路空 catch 零容忍 / 全仓空 catch 棘轮预算）+ 2 道 `packageRelease`（工作区必须干净 / 单测必须绿）。**用构建失败强制纪律**，是很少见的成熟工程直觉。
3. **双端协议同源 + 能力握手** —— `LinkProtocol.kt` 双端逐字节同源、`checkProtocolSynced` 构建期守护；`GlassesHandshake` 三态（未知=乐观 / 确认不支持=快速降级 / 4s 超时判旧版），把"跨端协议漂移"这类最难查的问题变成了构建失败。
4. **零依赖的自研协议栈** —— ADB TCP 协议（含 RSA 认证、sync 协议、`pullFile` 字节对账）、蓝牙 HID 描述符与三层键盘输入降级链、scrcpy H.264 流接收解码，全部从零实现且带回归测试。
5. **死代码与失实注释被清理** —— 零引用文件（`store/AdbToolsModule.kt` 等 7 处）、可疑死代码 `mirror/PhoneMirrorActivity.kt`（已删除）、零引用 `BuildConfig` 字段，以及**失实注释**（"`AiuiLinkActivity` 为 exported=true"、"总长度 ≤64 字节"）都已按实测改写。

**三个最该处理的问题（按"会不会挡住发版"排序）**

| # | 问题 | 性质 | 一句话 |
|---|---|---|---|
| **R1** | 内置 `assets/RokidLink.apk` 仍被 git 跟踪，与发布闸门 `checkGitClean` 互斥 | **发布阻塞** | 构建动作本身会把工作区改脏，转头被自己的门禁拦下 |
| **R2** | `checkProtocolSynced` 给的是"虚假的绿" | **虚假安全感** | 正则只匹配函数实参，全仓 **40 处**赋值形式的裸 `rokidlab_*` 字面量全部漏网 |
| **R3** | 副作用确认闸门实质空转 | **虚假安全感** | `ToolRiskMap` 中**没有任何真实工具**登记为 `EXTERNAL_SIDE_EFFECT`，且策略为 fail-open → 闸门对真实工具永不触发 |

---

## 1. 项目概况

### 1.1 是什么

Rokid 眼镜（RV101 系列）的配套手机应用 + 眼镜端常驻服务，覆盖**应用商店 / AI 语音助理 / 双向投屏 / 文件管理 / ADB 工具 / 蓝牙手柄**六大块能力，并把"眼镜"从一个只读设备变成**可被 AI 一句话编程的终端**（AIUI 对话即开发）。

| 维度 | 事实 |
|---|---|
| 形态 | 双端：`phone-app`（手机，`com.rokidlab.phone`）+ `RokidLink`（眼镜，可执行 APK，随手机端构建自动打入 assets） |
| 版本 | v3.6（versionCode 21） |
| SDK | compileSdk 34 / minSdk 29 / targetSdk 34 / `abiFilters = arm64-v8a` / `jvmTarget = 11` |
| 技术栈 | Kotlin + Jetpack Compose (M3)；CXR-L 1.1.2 / CXR-S 1.0（Maven）；OkHttp 4.12.0；RapidOCR + ONNX Runtime + OpenCV；RokidLink 开 R8 |
| 构建根 | `D:\rokidapp`（`RenewCXRLSample`），模块名 `:cxrl:RokidLab:phone-app` / `:cxrl:RokidLab:RokidLink`；Gradle 8.7 + JDK 17 |
| 许可 / 作者 | MIT / DLOVER |
| 分发 | Gitee Release + 自建 `RokidBrew-Registry` 商店源（支持 Gitee / GitHub 双源切换） |

### 1.2 规模（实测）

| 指标 | 数值 |
|---|---|
| Kotlin 文件（排除 build） | **193**（其中 `src/main` 177） |
| 代码总行数 | **50,846** |
| 平均文件行数 | ≈ 263 |
| release APK | **93.02 MB**（debug 144.79 MB） |
| RokidLink APK | **8.35 MB release** / 11.50 MB debug |
| 文档 | `docs/` 下 **10 份 .md** + 1 份 pptx；根目录 `README / RULES / DEV_GUIDE / UI-DESIGN` 四份大文档 |

---

## 2. 功能矩阵与完成度

> 完成度按"代码是否完整实现 + 是否有降级兜底 + 是否有回归保护"综合判断。✅ 完备 / ⚠️ 可用但有短板 / ⚪ 已评估未实施

| 模块 | 能力 | 完成度 | 关键实现 / 短板 |
|---|---|---|---|
| **乐奇 AI 聊天** | 文字对话（OpenAI 兼容，可切任意服务商 / 本地 Ollama）、多轮滚动摘要（≤800 字 pinned）、长期记忆（SQLite，FIFO 200 / 90 天 / bigram top-12）、RAG 知识库（BM25 IDF + provenance）、SSE 指数退避重连 | ✅ | 记忆与检索三层设计完整；`ChatStateHolder` 本体仍无测试 |
| **AI 工具** | **34 工具 / 10 域**（info / knowledge / glasses / timer / media / display / web / files / aiui / phone），`ToolProvider` 按域拆分，`ToolPolicy` 限流 + `ToolRisk` 分级 | ✅ | 注册表、schema 缓存、启动自检完整；风险闸门**空转**（见 R3） |
| **拍照问 AI** | 镜腿按键 / 手机按钮双入口 → 拍照 → 本地 OCR（PP-OCRv4 + ONNX，全离线）→ RAG 检索 → AI 答案 + 眼镜语音 | ✅ | 全链路离线，无 GMS 依赖；OCR 首启耗时**未量化** |
| **AIUI 对话即开发** | 对话生成 `.ink` 页面 → 打包 `.aix` → 推送眼镜渲染；技能体系（Skill 按章节加载）；`read_code_file` 先读再改；项目管理（打开 / 重装 / 删除）；内容指纹版本控制 | ✅ | 这是项目**最具差异化**的能力；宿主拉起失败已能如实上报（F1 已修） |
| **Lab 工具桥** | AIUI 页面通过 `globalThis.Lab.callTool` 回调手机端 **33 个工具**，复用 ASR RFCOMM 通道，15s 超时 + 8000 字截断 + `open_aiui_app` 防自指递归 | ✅ | 页面 realm 桥（`lab-page-bridge.js`）解决 ink 沙箱 realm 隔离，方案扎实 |
| **屏幕镜像（眼镜→手机）** | scrcpy-server H.264 硬编流 + ADB `tunnel_forward` + MediaCodec 零拷贝解码；缩放 / 双指平移；late/partial 帧加固 | ✅ | 首次自动推送 server jar；断线 3 次自动重连 |
| **手机投屏（手机→眼镜）** | MediaProjection + Socket 灰度传屏；帧健康看门狗（30s 无帧重建 ImageReader）；全品牌黑屏修复（EMUI/MIUI/ColorOS/Funtouch 禁 HWC + 软渲 + 降分辨率）；旋转不中断 | ✅ | 兼容性处理是项目最"接地气"的一块 |
| **文件管理** | ADB sync 协议自实现；上传 / 下载 / 删除 / 重命名 / 建目录 / 复制剪切粘贴；图片预览、文本查看；排序；中文目录上传修复 | ✅ | 下载已按字节对账 + 失败删半成品（F4 已修）；**CUT 路径 drain** 已修 |
| **ADB 工具** | 应用管理（启动 / 卸载 / 冻结 / 提取 APK）、定时功能（消息 / 打开应用 / Shell / 点击 / 按键 / **TTS 播报**）、系统信息、Shell 命令、输入模拟、按键设置 | ✅ | 定时任务已修"不降级不记账"（F3 已修） |
| **蓝牙手柄** | 手机模拟蓝牙 HID：鼠标模式（触控板）、手柄模式（14 键可拖拽自定义）、键盘输入（TCP 剪贴板 → ADB `KEYCODE_PASTE` → HID Ctrl+V 三层降级）；智能重连 3~15s | ✅ | 三层降级链设计正确；`AdbPasteCompat` 已删除并改走共享会话 |
| **应用商店** | 多源切换（Gitee / GitHub）、下拉刷新、精选横滑、22 分类中英本地化、本地 APK 安装、应用详情来源识别、自更新 | ✅ | 商店注册表 + 自更新链路完整 |
| **设置 / 国际化** | 中英双语（**1103 / 1103 key 完全对齐**，构建期强制）、运行时切语言、主题双套（Velvet Dark / Cool Blue）、系统日志面板、后台保活开关、开发者提交应用 | ✅ | 双语 key 由 `checkI18nKeysSynced` 守护 |
| **稳定性 / 兼容** | 后台保活前台服务（START_STICKY）、`ChannelArbiter` 通道租约仲裁（BACKGROUND/NORMAL/LONG_LIVED）、`ConnectionRouteManager` WiFi/蓝牙双线路 + 陈旧缓存记账、`CapabilityProbe` 启动期能力探测 | ⚠️ | 通道争抢是眼镜端"同一 SCN 仅一条 RFCOMM"的必然结果，仲裁方案方向正确；仍有 3 处消费者缺降级契约（见 §7.2） |
| **APK 瘦身** | 已有完整剥离评估（T1 保守 -16.9% / T2 推荐 -61.1% / T3 激进 -66.9%），含 W^X 硬约束与辅助 APK 形态结论 | ⚪ | **已评估、未实施**；OCR 全栈 55.4 MB（占 93 MB 的 59.5%）代码上完全孤立，是最干净的切入点 |

**功能完整度小结**：11 大模块中 10 个 ✅ 完备，无"半成品功能"。项目的完成度显著高于同体量独立项目，主要短板不在"功能没做"，而在**工程保障的覆盖面**与**状态层的现代化**。

---

## 3. 量化指标总表

| 指标 | 数值 | 评价 |
|---|---|---|
| Kotlin 文件 / 行数 | 193 / 50,846 | 中大型 |
| 最大文件 | `RokidLink/KeyButtonService.kt` **1,813** 行 | ⚠️ 仍是头号上帝类（但从 2,282 降了 **469** 行） |
| 次大文件 | `adb/AdbFileManagerClient.kt` 1,534 / `hid/BluetoothHidManager.kt` 1,391 / `settings/DeveloperScreen.kt` 1,188 | ⚠️ |
| 测试类 / `@Test` | **15 个文件 / 162 例** | ✅ 从 9 类 / 102 例增长；**但仍是"最好测的层"占比高** |
| `androidTest` | **0（目录不存在）** | ⚠️ 设备侧全链路（按键 / CXR / 投屏）零自动化 |
| 构建门禁 | **4 道 `preBuild` + 2 道 `packageRelease`** | ✅ 但 4 道只挂 phone-app（见 §7.4） |
| `StateFlow` / `ViewModel` | **0 / 0**（`mutableStateOf` 317、顶层 `object` 49） | ⚠️ 状态层完全靠 Compose state + 单例约定 |
| `runCatching` | **339** | ⚠️ 大量未区分"预期失败"与"异常吞噬" |
| 空 `catch(...) {}`（单行形式） | **72**（门禁口径：67 处，已标注 25 / 未标注 42，预算 47） | ⚠️ 棘轮只降不升，方向对 |
| `Log.*` 调用 | 1,281 | ⚠️ 无结构化日志（有 `LogCollector` 面板补足） |
| 裸线程 / `Executors` | 126 | ⚠️ 协程与裸线程混用 |
| TODO / FIXME / XXX | **4** | ✅ 从 16 降下来 |
| i18n key | phone-app **1103/1103**、RokidLink **22/22** | ✅ 完全对齐，构建期强制 |
| 权限面 | phone-app 27 项（含 `QUERY_ALL_PACKAGES` / `REQUEST_INSTALL_PACKAGES` / `SYSTEM_ALERT_WINDOW` / 联系人 / 日历 / 电话 / 定位）；RokidLink 17 项 | ⚠️ 多数有刚需，但上架应用商店会触发严格审查 |
| 文档 | 10 份专项 md（含 5 份评估/复核）+ 4 份根级指南 | ✅ 远超同规模项目 |
| CI | **无（有意）** | 评估结论：本地闸门已等价覆盖，托管 CI 需复刻 SDK/NDK 环境，收益为负 |

---

## 4. 架构评估

### 4.1 六层架构（L0–L5）—— 方向正确，落地扎实

```
L5  app/ · feature/ · store/(UI)      UI 与入口 + 手动 DI（AppContainer，不引 Hilt）
L4  ai/                               Agent：ToolRegistry + tools/ 10 个 Provider + ToolPolicy / ToolRisk
L3  domain/                           领域服务（Connection / Authorization / DeviceControl /
                                      AiConfig / AiConversation / AiuiHost / Mirror / FileTransfer / PhotoQuiz）
L2  glasses/                          会话层（CxrLHiRokidSession 薄路由 / LinkProtocol / GlassesHandshake /
                                      AsrBridgeCoordinator / AiuiFrontendController / PhotoQuizFlow）
L1  connection/ + adb/                通道仲裁（ChannelArbiter / ConnectionRouteManager）+ ADB 协议客户端
L0  platform/                         能力与 hook 适配（CapabilityProbe / SdkBridge / SdkFieldMap /
                                      HidBridge / AdbTransport / ShellOps / RomAdapter / AvrcpLyricBridge）
```

**三个真正做对了的地方**

1. **`platform/` 是全仓唯一的反射边界。** `getDeclaredField` / `Class.forName` / `setAccessible` 只允许出现在这里，SDK / ROM 升级只改一个包。配套的 `Capability` 三态（`Available` / `Unavailable(reason)` / 未知）用**显式降级**取代静默反射 —— 这是处理 Android ROM 碎片化的正确姿势，比"到处 try-catch 然后假装成功"高一个层次。
2. **上帝类的拆分不是口号。** `CxrLHiRokidSession` 3,550 → 2,780 → **826 行**（三轮有效拆分），`ToolRegistry.execute` 的巨型 `when` 拆成 10 个 `ToolProvider`，**新增工具只需注册一行 `ToolMeta`**，网关 / 协议 / JS / skill 全不动。这个"可扩展性契约"是真实的。
3. **通道仲裁被抽象成原语。** 眼镜端"同设备同 SCN 只允许一条 RFCOMM"是所有通道争抢问题的根因。`ChannelArbiter` 把它收敛为"按优先级持有租约"（BACKGROUND / NORMAL / LONG_LIVED），`acquire` 非阻塞必成功、`shouldYield` 判断让路、LONG_LIVED 超 10 分钟打泄漏告警。设计取舍（协作式闸门而非互斥锁）也解释清楚了。

### 4.2 架构上的欠账

- **L5 仍在承载业务逻辑与 IO。** `MainActivity`（971 行）、`FileManagerStateHolder`、`ScreenMirrorStateHolder` 里连接、文件 IO、UI 兜底绘帧揉在一起；`adb/ui/` 下各 Dialog 直接出现 `Thread{` / `runBlocking` / `File(` / `getSharedPreferences`。**"UI 只调 StateHolder/Service"这条自家的 RULES 规则尚未系统落地。**
- **缺少状态层。** `StateFlow` / `ViewModel` 全仓 **0 命中**，热点状态靠 `mutableStateOf`（317 处）与顶层 `object`（49 个）承载。跨线程写 `mutableStateOf` / `SnapshotStateList` 在 Compose 里是未定义行为，目前靠"约定必须 post 主线程"的注释维持。这是**最值得投入的一项长期改造**（先抽 `CxrLStateRepository`）。
- **`KeyButtonService`（1,813 行）仍是综合职责**：CXR 桥接 + 按键路由 + ASR 桥 + TTS + 悬浮层（歌词 / 图片）+ WiFi 连接 + 自愈重启 + 工具确认 UI。已降 469 行，但仍需继续拆。

---

## 5. 工程质量评估

### 5.1 六道闸门（本轮最大的工程进步）

| 闸门 | 挂载点 | 作用 | 覆盖缺口 |
|---|---|---|---|
| `checkProtocolSynced` | `preBuild` | `AiChannel.kt` / `LinkProtocol.kt` 双端逐字节同源 + 禁裸协议字面量 | ⚠️ **仅 phone-app**；`rokidlab_*` 未纳入 forbidden 集（R2） |
| `checkI18nKeysSynced` | `preBuild` | 两模块 zh↔en `<string name>` 集合相等 | 仅 phone-app；不校验 `<string-array>` / `<plurals>` 与占位符 |
| `checkKeyPathEmptyCatch` | `preBuild` | 6 个关键链路文件空 catch 零容忍（须带 `// catch-ok:`） | 仅 phone-app |
| `checkNoBareCatch` | `preBuild` | 全仓空 catch 棘轮预算（基线 47，只降不升） | 仅 phone-app；不扫 `src/debug` 与测试 |
| `checkGitClean` | `packageRelease` | `git status --porcelain` 非空即失败 | ⚠️ 与内置 APK 互斥（**R1**）；可被 `-PallowDirtyWorktree=true` 关闭 |
| `packageRelease → testDebugUnitTest` | `packageRelease` | 单测不绿出不了包 | — |

### 5.2 测试护栏 —— 从"最好测的层"扩到"最易坏的层"

| 测试类 | 例数 | 锁住什么 |
|---|---|---|
| `AdbSyncProtocolTest` | 15 | `pullFile` 的 `FAIL` / `CLSE` / 读流异常 / 残包提取 / 分片收尾语义（历史事故：远端 FAIL 被当成功 → 0 字节文件） |
| `AdbFileManagerSyncTest` | 6 | sync 帧编解码 + 陈旧 CLSE 排空 + `parseDateTime` |
| `HidReportTest` | 12 | HID 描述符字节、`normalize` 截断/补零、Report ID 交叉校验（实测推翻旧注释"≤64 字节"，实为 67 / 121 字节） |
| `AiChannelTest` | 21 | 跨端载荷 v0/v1 矩阵 + 常量名稳定性（改名即断双端） |
| `ToolRiskMapTest` | 8 | `unregisteredTools()` 必须为空 + `ToolPolicy` 的 fail-open 语义 |
| `ChatHistoryStoreTest` | 14 | JSONL 落盘格式、同 id 后写覆盖先写、旧 JSON 数组迁移不丢历史、崩溃截断半行容错、`clear()` 删文件 |
| `GoldenAgentEvalTest` | 23 | Agent 评测（SSE 重放语义 / 工具声明完整性 / prompt 路由） |
| 其余 8 类（`Calculator` / `SkillMarkdown` / `SkillFetcher` / `SseStreamAccumulator` / `TruncateToolOutput` / `AgentSessionHistory` / `RokidLink/AiChannelProtocolTest` 等） | 63 | 纯逻辑层 |
| **合计** | **162** | |

**进步**：测试从"全在 `ai/` 纯逻辑层"扩到 `adb/` `hid/` `glasses/` `store/`，且**新增的基础设施本身是资产**（`testOptions { unitTests.isReturnDefaultValues = true }`、真实 JSON 实现替代 android.jar 桩、`AdbTestPeer` 脚本化对端 + `attachStreamsForTest` 注入流）。

**残留盲区**：`KeyButtonService`（1,813 行）、`CxrLHiRokidSession`（826 行）、`ChatStateHolder` 本体、`AdbShellClient` / `AdbFileManagerClient` 的非 sync 部分、`BluetoothHidManager` 的非描述符部分 —— **全部零测试**。`androidTest` 为 0。

### 5.3 可观测性与异常处理

- **正向**：`LogCollector` 已成为 App 内日志面板的真源，4 条关键链路（ASR 补读 / RFCOMM 隧道 / ADB sync / AIUI 工具网关）的 catch 分支强制落面板**且带异常对象**（只记 `e.message` 无法区分"对端未启动"与"RFCOMM 被栈拒绝"—— 这条经验很具体、很值钱）。
- **欠账**：339 处 `runCatching` + 未标注空 catch 42 处仍未系统性区分"预期失败"与"异常吞噬"。棘轮预算只保证"不再变差"。

### 5.4 文档与规范

- `RULES.md`（§12 有 16 条硬约束）、`DEV_GUIDE.md`（8 章开发指南）、`UI-DESIGN.md`（精确到 dp / sp / 色值）——**文档质量远超代码规模应有的水平**。
- ⚠️ **文档漂移仍存在**：`README.md` 仍以 v3.5 为叙述主线（实际已是 v3.6）、i18n 条数记 1100（实测 1103）、`RULES.md` 包结构树缺 `platform/ connection/ domain/ feature/` 等目录是否补齐未逐一核对。**建议：文档内禁止出现"会变的数字"（行数 / 条数 / 文件名），一律由脚本或门禁输出。**

---

## 6. 历史问题闭环核查（逐条实测）

### 6.1 2026-09-13 功能缺陷 —— **4/4 全部已修** ✅

| 编号 | 缺陷 | 状态 | 本次实测证据 |
|---|---|---|---|
| **F1**（P0） | 推送 `.aix` 后宿主没打开却上报 `"OK"` | ✅ **已修** | `AiuiFrontendController.kt:58` 新增 `PUSHED_OPEN_FAILED`；`:284` `val opened = !openAfter \|\| openHostBestEffort(...)` → `return if (opened) "OK" else PUSHED_OPEN_FAILED`；socket 兜底分支 `:328-330` 同款处理 |
| **F2**（P1） | `exported=false` 之后 ADB `am start` 兜底已死 | ✅ **已修** | `openHostBestEffort`（`:201-210`）**已删除 ADB 路径**，只走同进程 topic；`:268` 注释改为"AM 直启路径已随 exported=false 失效"，失实注释已改写 |
| **F3**（P1） | `TimerScheduler` 兜底短连接失败后不降级、不记账 | ✅ **已修** | `TimerScheduler.kt:270-277` 新增 `noteWifiFailure()` + `tunnelTo(ADB_PORT)` 蓝牙隧道重试一次，并带清晰注释说明旧实现的 60s 死缓存问题 |
| **F4**（P1） | 文件下载只信 `DONE`、不对账、失败不删半成品 | ✅ **已修** | `AdbFileManagerClient.kt:359` `downloadFile(..., expectedSize)` + `:520-524` 落盘后 `reconcileDownloadedSize`；`:545-557` 不符即 `File(localPath).delete()` + 落 `LogCollector`；`:559/:576` shell 降级路径同契约 |

> 这一轮修复的**质量高于"打补丁"**：不是把 `"OK"` 换个值，而是把"落盘成功 ≠ 拉起成功"这个语义**显式建模**进了返回值契约，并在两个消费方（`AiuiManagePage` / `AiuiToolProvider`）保持兼容。

### 6.2 2026-09-13 新增问题（N 系列）

| 编号 | 问题 | 当前状态 |
|---|---|---|
| **N1** | `exported=false` 掐断自己的 ADB 兜底 | ✅ **随 F2 一并修复** |
| **N2** | 内置 APK 与 `checkGitClean` 互斥 | ❌ **仍存在**（→ R1） |
| **N3** | 副作用确认闸门实质空转 | ❌ **仍存在**（→ R3） |
| **N4** | 乐观 WiFi 判定把降级责任下放给各调用方 | ⚠️ **部分修复**：`FileManagerStateHolder.kt:129`、`ScreenMirrorStateHolder.kt:177` 补了 `noteWifiFailure()`；`mirror/PhoneMirrorActivity.kt` 已删除（疑似死代码清理） |
| **N5** | 眼镜端新增局域网监听面（`AsrPushServer` 绑 `0.0.0.0:7660`，替换式单客户端） | ❌ **仍存在**（`AsrPushServer.kt:152` `bind(InetSocketAddress(WIFI_PORT))`，无对端校验） |
| **N6** | 两份 `SecretStore` 非字节同源、无门禁；Keystore 复位后 API Key 静默丢失 | ❌ 仍存在（两份文件在，无同源校验） |
| **N7** | `KuwoMusicApi` 强制 http→https 依赖 CDN 行为 | ⚠️ 未复核（需真机放歌） |
| **N8** | 门禁扫描根不含 `src/debug` 与测试 | ❌ 仍存在 |

### 6.3 更早的 P0/P1（2026-09-12）

| 编号 | 状态 |
|---|---|
| P0-1 调试广播接收器（可远程执行任意 shell） | ✅ **已修**：类与 manifest 声明均移入 `src/debug`，release 合并清单零匹配 |
| P0-2 / P0-3 token 与签名口令 | **按用户指示整体排除** |
| P0-4 眼镜端宿主导出 | ✅ **已修**：`exported="false"` + `isAllowedAixPath` 白名单 + WebView 导航锁定；且兜底路径已随 F2 收敛 |
| P0-5 release 全局明文流量 | ✅ **已修**：`network_security_config.xml` 默认禁明文，仅白名单回环 / 眼镜 IP / `ip-api.com`；debug 变体单独放开 |
| P1-6 聊天历史主线程 IO / O(n²) 写放大 | ✅ **已修**：抽 `ChatHistoryStore`（纯 JVM）+ 单线程 daemon 落盘，JSONL 增量写，加载/迁移/压实全后台，14 例测试 |
| P1-8 异常吞噬 | ⚠️ **部分**：关键链路 16 处已落面板；存量靠棘轮约束 |
| P1-11 测试盲区 | ✅ **大幅改善**（162 例，覆盖历史高危文件） |
| P1-13 死代码 | ✅ **已修**：删零引用文件 / 字段 / WiFi 死代码；`mirror/PhoneMirrorActivity.kt` 也已清理 |
| P1-14 i18n 断链 | ✅ **已修**：1103/1103 对齐 + 构建期强制 |
| P1-1 上帝类 | ⚠️ **部分**：`KeyButtonService` -469 行、`CxrLHiRokidSession` -177 行；`AdbFileManagerClient` 因协议重组反而 + |
| P1-3 协议门禁覆盖 | ❌ **未修**（→ R2） |
| P1-5 状态层 | ❌ 未修（`StateFlow` 0） |
| P1-9 APK 体积 | ⚪ 已评估未实施（T2 方案就绪） |
| P1-10 SNAPSHOT 依赖 | ❌ 未处理（`cxr-service-bridge:1.0-20260715.121510-107` 仍 pin 时间戳） |

**总体闭环率**：09-12 的 5 个 P0 → 4 个已修（1 个按用户指示排除）；14 个 P1 → 6 个已修、4 个部分修、4 个未动；09-13 的 4 个功能缺陷 → **4/4 全修**。

---

## 7. 仍然存在的问题（按优先级）

### 7.1 R1 —— 内置 APK 与发布闸门互斥（**发布阻塞**）

- **实测**：`git ls-files -- phone-app/src/main/assets/*` → **`phone-app/src/main/assets/RokidLink.apk` 仍被跟踪**；`.gitignore` 只忽略 `*debug.apk`（第 30 行）。
- **冲突**：`phone-app/build.gradle.kts:120-131` 的 `buildRokidLinkRelease` 无条件 `copyTo(..., overwrite = true)` 覆盖它；`checkGitClean` 挂 `packageRelease`，而 `packageRelease` 同时（间接）依赖 `mergeReleaseAssets → buildRokidLinkRelease`，二者**同级依赖、无 `mustRunAfter`**，执行顺序不定。
- **后果**：**"出 release 包"这个动作本身会把工作区改脏，转头被自己的门禁拦下**。Git 历史里连续 3 个 commit（`85a8982` / `1b30a49` / `c9a9d85`）都在"重新同步内置眼镜端 APK" —— 正是这个坑留下的脚印。
- **建议（二选一）**：① 把该 APK 移出版本控制（`.gitignore` + 构建期生成，`checkGitClean` 校验 `src/**` 时排除该路径）；② `checkGitClean` 改为只看 `src/**`（排除 `assets/RokidLink.apk`）。**推荐 ①**，因为它同时消除了"仓库里存二进制产物"的根因。

### 7.2 R2 —— 协议门禁给的是"虚假的绿"（**虚假安全感**）

- **实测**：`phone-app/build.gradle.kts:170` `forbiddenChannels` 仍为 `setOf("Ai", "Sys", "Wifi", "Jsai", "Ai_RenderPayload")`，**未纳入 `rokidlab_*`**；扫描正则只匹配 `sendCustomCmd(...)` / `caps.write(...)` 等**函数实参位置**。
- **漏网形态**：`internal const val TOPIC = "rokidlab_key_config"` —— 赋值形式全量绕过。Git 历史与文档记载全仓存在 **40 处**赋值形式的裸 `rokidlab_*` 字面量（眼镜端 `KeyButtonService.kt:433-474` 自建 11 个重复常量）。
- **为什么比"没有门禁"更危险**：它让维护者认为"改了协议会有构建失败兜底"，而实际上改一端常量不会触发任何失败 → **静默跨端错位**，正是这套机制本来要防的问题。
- **建议**：① `rokidlab_*` / `Sys_*` / `Jsai_*` / `Wifi_*` 全部纳入 forbidden 集；② 扫描升级到"字符串常量赋值"级别（匹配 `= "..."` 而非仅实参）；③ 眼镜端逐步复用 `AiChannel` 常量；④ 把该门禁也挂到 RokidLink 的 `preBuild`。

### 7.3 R3 —— 副作用确认闸门实质空转（**虚假安全感**）

- **实测**：`ai/ToolRisk.kt:33-77` 的 `ToolRiskMap` 中**没有任何一条真实工具登记为 `EXTERNAL_SIDE_EFFECT`**（只有 `READ_ONLY` 与 `LOCAL_SIDE_EFFECT`；`call_phone` 已于 2026-09-11 按用户要求显式降为 `LOCAL_SIDE_EFFECT`）。
- **闸门位置**：`ToolPolicy.check()` 只在 `risk == EXTERNAL_SIDE_EFFECT` 时走确认通道，且确认通道不可用/超时一律 **fail-open 放行**（仅用户显式取消才 Deny）。
- **唯一会进 EXTERNAL 的**是"完全未知的工具名"（模型幻觉 / 攻击构造），而它随后必被 `ToolRegistry` 查不到而失败。
- **附带矛盾**：`ToolPolicy` 类注释仍写"降级为拒绝"，实现是降级放行 —— **文档与代码相反**。
- **建议（二选一，不要保持现状）**：① 承认当前无外发工具，**删掉这层**以免虚假安全感；② 把真正外发动作（如未来的上传 / 推送类）登记为 `EXTERNAL_SIDE_EFFECT` 并改 **fail-closed**。同时修正 KDoc。

### 7.4 P1 —— 其它工程债

| 项 | 内容 | 建议 |
|---|---|---|
| **门禁覆盖缺口** | 4 道 `preBuild` 门禁只挂 phone-app；`RokidLink/build.gradle.kts:7` 仅 `apply(local-gates)` → 单独 `:RokidLink:assembleRelease` 不触发协议 / i18n / 空 catch 三闸（而 `local-gates.gradle.kts:2` 的注释声称"同样受约束"，与事实不符） | 把四道门禁抽成共享脚本，双端各自 `apply` |
| **N5 局域网监听面** | `AsrPushServer.kt:152` `bind(InetSocketAddress(7660))` 绑 `0.0.0.0`；`:211` `attachClient` 为**替换式单客户端** → 同网段任意主机连上即可挤掉真手机连接（DoS）并读取下发的 ASR 文本 | 校验对端（比对已配对手机 IP / 加握手 token），或仅在 RFCOMM 不可用时才开 TCP |
| **状态层缺失** | `StateFlow` / `ViewModel` = 0；`mutableStateOf` 317、顶层 `object` 49；跨线程写 `SnapshotStateList` 靠约定 | 先抽 `CxrLStateRepository`（连接 / 聊天热点状态），Compose 侧用 `collectAsStateWithLifecycle` |
| **上帝类与长方法** | `KeyButtonService` 1,813 / `AdbFileManagerClient` 1,534 / `BluetoothHidManager` 1,391；含多处 150~780 行的单 Composable | 先建状态容器再拆；UI 长 Composable 按区块抽子组件 |
| **APK 93 MB** | OCR 全栈 55.4 MB 占 59.5%；剥离评估已完成（T1/T2/T3），含 W^X 硬约束与辅助 APK 形态结论 | 按 T1 先落地（模型走下载，省 ~17%），T2 需接受第二个 APK（省 ~61%） |
| **依赖可复现性** | `cxr-service-bridge:1.0-20260715.121510-107` 为时间戳 SNAPSHOT，Nexus 清理即构建失败 | 私有镜像到自控仓库或固化到 `libs/`；锁 release 版本并真机回归 |
| **N6 SecretStore 健壮性** | 两份"同源"非字节同源、无门禁；Keystore 复位（恢复出厂 / 生物识别变更）后旧密文解密失败 → 用户 API Key **静默丢失无提示** | 解密失败落 `LogCollector` + UI 提示重填；给两文件加同源校验门禁 |
| **N8 门禁扫描根** | `checkNoBareCatch` 只扫 `src/main/java` → `src/debug` 新增空 catch 不计入预算 | 扫描根扩到 `src/debug` 与测试 |
| **权限面** | `QUERY_ALL_PACKAGES` / `REQUEST_INSTALL_PACKAGES` / `SYSTEM_ALERT_WINDOW` / 联系人 / 日历 / 电话 / 定位 | 上架国内商店前需准备用途说明；`QUERY_ALL_PACKAGES` 可考虑改 `<queries>` 精确声明 |
| **文档漂移** | `README` 主线仍是 v3.5（实际 3.6）；i18n 条数记 1100（实测 1103） | 文档内的"会变的数字"改由门禁输出 |

---

## 8. 分发出账

| 形态 | 体积 | 说明 |
|---|---|---|
| `RokidLab-v3.6-release.apk` | **93.02 MB** | 侧载分发成本高，主要来自 OCR 全栈（55.4 MB） |
| `RokidLab-v3.6-debug.apk` | 144.79 MB | 含 debug 源集与未混淆产物 |
| `RokidLink-release.apk` | 8.35 MB | R8 已启用，从 13.8 MB 降下来 |
| 内置 `assets/RokidLink.apk` | 8.35 MB | 已确认是 **release（R8）产物**，不再是 debug 包 —— 这是相对 09-12 的实质改善 |

---

## 9. 建议行动清单（按优先级）

### 第一优先：解除发布阻塞（不改任何业务逻辑）

1. **修 R1** —— 把 `assets/RokidLink.apk` 移出版本控制，改为构建期生成（或让 `checkGitClean` 排除该路径）。**不修则每次出包都会卡在自家门禁上。**
2. **把四道 `preBuild` 门禁抽成共享脚本并挂到 RokidLink** —— 消除"单独发眼镜端可绕过校验"的缺口，并修正 `local-gates.gradle.kts:2` 的失实注释。

### 第二优先：消除"虚假安全感"（低成本、高收益）

3. **修 R2** —— `forbiddenChannels` 纳入 `rokidlab_*` / `Sys_*` / `Jsai_*` / `Wifi_*`，扫描升级到"字符串常量赋值"级别，倒逼眼镜端复用 `AiChannel` 常量。
4. **给 R3 定性** —— 删掉空转的确认层，或把真实外发工具登记 `EXTERNAL_SIDE_EFFECT` 并改 fail-closed；同时修正 `ToolPolicy` 的 KDoc（当前与实现相反）。

### 第三优先：真实可达的风险

5. **修 N5** —— `AsrPushServer` 增加对端校验（已配对手机 IP / 握手 token），或仅在 RFCOMM 不可用时才开 TCP。
6. **`AdbTransport` 探测移出类锁** —— 当前 `get()` 持 `@Synchronized` 做 2s 阻塞探测，空闲 >5s 会锁住约 2s（调用方都在后台线程，无 UI 卡死，但会拖慢 AI 工具）。
7. **N6** —— `SecretStore` 解密失败落日志 + UI 提示重填。

### 长期：结构性改造（需护栏提供安全网）

8. **状态层现代化** —— 抽 `CxrLStateRepository`（`StateFlow`），迁移热点状态。
9. **继续拆 `KeyButtonService`（1,813 行）与 `CxrLHiRokidSession`（826 行）**，并为这两个文件补测试（当前零覆盖，是正确性最大的不确定性来源）。
10. **APK 瘦身** —— 按 T1 先落地，再评估 T2。
11. **补 `androidTest`** —— 至少覆盖"连接建立 → 一次 AI 工具调用 → 一次投屏启停"的设备侧冒烟链路。

---

## 10. 不确定项（需真机或作者确认）

1. **`N5` 的实际暴露**：眼镜通常处于家庭 / 个人热点网络，同网段攻击者概率低；但"替换式单客户端"意味着**同网段任何一次误连都会让眼镜上的 AI 回答停更**，这是可用性问题而非纯安全问题 —— 建议至少做对端校验。
2. **`R1` 的闸门执行顺序**：`checkGitClean` 与 `buildRokidLinkRelease` 在真实 `assembleRelease` 中的先后未实跑（当前工作区本就干净、资产已同步，一次实跑即可确认）。
3. **OCR 首启耗时**：`onnxruntime` + `opencv` 首次加载与 PP-OCRv4 推理在真机上的耗时未量化，直接决定"拍照问 AI"的体感。
4. **投屏兼容性的长期有效性**：EMUI / MIUI / ColorOS / Funtouch 的 HWC 禁用 + 软渲方案随 ROM 版本演进可能失效，需持续回归。
5. **`runCatching` 339 处的实际风险分布**：未逐一区分"预期失败"与"异常吞噬"，只有关键四链路做过。

---

## 11. 附：值得保留并推广的既有实践

> 建议写进新人文档，避免后续重构时被误删。

1. **`platform/` 作为唯一反射边界** + `Capability` 三态显式降级 —— 处理 ROM 碎片化的正确姿势。
2. **六道构建期闸门** —— 用构建失败强制纪律，把"应该做的事"变成"不做就过不了"。
3. **双端协议同源 + `checkProtocolSynced`** —— 思路正确（覆盖面待扩大，见 R2）。
4. **`GlassesHandshake` 三态握手**（未知=乐观 / 不支持=快速降级 / 4s 超时判旧版）—— 避免每次工具确认都空等 35s。
5. **`ChannelArbiter` 通道租约仲裁** —— 把分散的 `reserveTunnel/releaseTunnel` 计数收敛为可观测的优先级租约 + 泄漏告警。
6. **`ToolRegistry` 域装配 + schema 缓存 + `ToolRiskMap` 启动自检** —— 扩展性契约真实可验证（新增工具只改两处）。
7. **关键链路 catch 强制落 `LogCollector` 且带异常对象** —— 把"静默失败"变成"可观测"。
8. **`ChatHistoryStore` 抽成纯 JVM 可单测** + 单线程 daemon 落盘 —— 线程模型与格式解耦，14 例测试锁住迁移语义。
9. **"如实回报"原则** —— 任何有眼镜端副作用的工具都必须把真实结果（`OK` / `NOT_CONNECTED` / `FAILED` / `PUSHED_OPEN_FAILED`）回给模型，**禁止静默 return + 无条件宣称成功**。这条原则本轮又落了两个实现，是本项目最重要的工程价值观。
10. **16KB 页面对齐处理**（`useLegacyPackaging=false`）+ R8 keep `com.rokid.cxr.**`（SDK 走 JNI 反射）—— 前瞻性兼容 Android 16 强制要求。

---

*本报告为只读评估产物，未对仓库做任何修改。*
