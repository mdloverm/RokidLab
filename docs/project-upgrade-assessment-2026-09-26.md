# RokidLab 项目整体评估与升级建议

> 评估日期：2026-09-26 · 代码版本 v4.1 (versionCode 26/19) · phone-app (273 kt / ~37.5k 行) + RokidLink (32 kt / ~8k 行)
> 评估维度：工具链时效性 / 架构 / 代码质量 / 安全 / 测试 / 工程化。与 `docs/compatibility-assessment-2026-09-26.md`（兼容性专项）互补，不重复其内容。

---

## 一、总体结论

**工程质量评级：B+（高于多数同类个人/小团队项目，工具链与架构形态落后于 2026 年主流约两年）。**

- **强项突出**：发布闸门体系（签名硬失败、git clean、release 必跑单测、i18n/协议同源/空 catch 棘轮五道 preBuild 闸门）、双 workflow CI/CD、文档文化（17 篇 + RULES 885 行 + 归档制度）、安全姿势（NSC 白名单、零硬编码凭据、gitignore 卫生、零 TODO 债务）在同类项目中少见。
- **最大短板**：工具链停在 2024 年中（AGP 8.5 / Kotlin 2.0.21 / Compose BOM 2024.08 / compileSdk 34），Compose 生态新特性与性能优化吃不到；架构形态为「全 Compose + 自研 StateHolder + 48% 文件是 object 单例」，4 个 >1500 行的上帝类是维护性风险集中点。
- **一句话路线**：先修已知 bug 与 SNAPSHOT 依赖风险（本周可做），再做一轮工具链阶梯升级（1-2 周），架构治理按「改哪块顺手拆哪块」的棘轮方式推进，不做大爆炸重构。

---

## 二、工具链时效性（差距最大的部分）

| 项 | 项目现状 | 2026-09 主流 | 落后 |
|---|---|---|---|
| AGP | 8.5.0 | 9.4.0（AGP 10 将强制新 Variant API） | ~2 年 / 1 个大版本 |
| Gradle | 8.7 | 9.6.x | 2 个大版本 |
| Kotlin | 2.0.21 | 2.4.x（2.4.20, 09-07） | 4 个次版本线 |
| Compose BOM | 2024.08.00 | 2026.09.00（Compose 1.12，要求 compileSdk 37 + AGP 9.1.1+） | 2 年 |
| compileSdk / targetSdk | 34 / 34 | 36（Play 侧载不强制，但 15/16 的行为差异需前瞻分支） | 2 个 API 级 |
| jvmTarget | 11 | 17（AGP 9 已要求 JDK 17 工具链，项目 toolchain 已是 17） | 可顺手补 |
| Gson | 2.10.1 | kotlinx.serialization 为主流；Gson 处于维护态 | — |

**升级建议（阶梯式，每步可独立回退）：**

1. **第一步（低风险，先做）**：Kotlin 2.0.21 → 2.2.x（K2 增量编译速度收益明显，且是 AGP 9.x 的 KGP 最低线 2.2.10）；AGP 8.5.0 → 8.13（8.x 末代，16KB zip 对齐在 8.5.1+ 默认开启）；Gradle 8.7 → 8.14。注意：仓库根与外层壳 `D:\rokidapp` 的 `libs.versions.toml` 是同源双改约定，升级时两头同步。
2. **第二步（收益点）**：AGP 升到 8.5.1+ 后，重新评估 `useLegacyPackaging = true`——当初它是绕开「未压缩 so 未做 16KB 对齐」的规避路线，代价是安装后解压落盘 **+45MB**。AGP 8.5.1+ 默认对 so 做 16KB 对齐，可切回未压缩直载，把这块磁盘占用拿回来（需真机回归 proot 执行链，这是当初改动的硬需求，见 phone-app/build.gradle.kts packaging 注释）。
3. **第三步（大版本）**：AGP 9.x + Gradle 9.6 + compileSdk 36 → 之后 Compose BOM 2026.x（该步会连动 compileSdk 37，建议单独一个里程碑做，全量真机回归）。AGP 10 的 Variant API 强制迁移目前对本项目无实际影响（无自定义 Variant API 用法），但升级 9.x 时顺手确认。
4. **顺手项**：`kotlinOptions.jvmTarget` 11 → 17；建议引入 **Renovate/Dependabot** 管理依赖漂移——本次评估显示版本断层正是「没人盯」的产物。

> ⚠️ 升级 Kotlin 2.2+ 时注意本项目已知的增量编译缓存问题（跨文件 Unresolved 假报错），升级后首次构建先清 `build/kotlin` 与 `tmp/kotlin-classes`。

---

## 三、架构评估

**形态**：单体 + 手动 DI（`AppContainer`，注释明确「单人项目不引 Hilt」——**这个决策应尊重，不算债务**）+ 纯 Compose（res/layout 为空）。

### 3.1 需要正视的结构性问题

| 问题 | 证据 | 风险 |
|---|---|---|
| **零 ViewModel** | 全仓无 `: ViewModel`；无 lifecycle-viewmodel-compose 依赖 | 配置变更/进程重建时状态靠自研 StateHolder（mutableStateOf）扛，`savedInstanceState`/`SavedStateHandle` 链路缺位；新人是按「奇怪的自研框架」入门成本 |
| **4 个上帝类** | `AiConversationService` 2043 行、`ChatScreen` 1574、`AdbFileManagerClient` 1562、`BluetoothHidManager` 1517 | 改动热点 = 回归热点；ChatScreen 尤其典型（UI + 状态 + 事件处理混一文件） |
| **48% 文件是 `object` 单例** | 130/273 文件含顶层 object | 可测试性靠 JVM 桩（`isReturnDefaultValues = true`）硬撑；依赖关系不可见，初始化顺序靠约定 |
| **网络双轨** | OkHttp 仅 2 个文件；裸 `HttpURLConnection` 13 个文件 | 连接池/HTTP2/超时策略不统一；SSE 为手写逐行解析（虽已有 30 个 ai 单测护航，但修复面分散） |
| **SharedPreferences 扩散** | 36 个文件引用（占 13%） | 无类型安全、无事务性；迁移偏好/开关时只有手工代码 |
| **线程模型** | `Thread(` ~85 处 + `postDelayed` ~51 处；协程主要在 ai 域 | 协程域（ai）与线程域（adb/glasses/mirror）并存——协议层裸线程属合理（需要独占 socket），但 KeepAlive/Watchdog/TTS 类用 postDelayed 链的可以协程化 |

### 3.2 架构升级建议（棘轮式，不搞大爆炸）

1. **StateHolder → ViewModel 渐进迁移**：从最独立的 StateHolder 开始（如 `StoreInstallStateHolder`），引入 `lifecycle-viewmodel-compose`；目标不是全量替换，而是「新页面一律 ViewModel，老页面改到谁拆谁」。SavedStateHandle 顺路解决状态重建。
2. **上帝类拆分**：`AiConversationService`（2043 行）优先——它是改动最频繁的热点，且 ai 域已有清晰子包（llm/tools/mcp/approval/compaction），按会话状态机 / 工具循环 / 播报编排三刀切；`ChatScreen` 按「消息列表 UI / 输入区 / 事件路由」拆。项目已有 Kotlin 拆类方法论，按门面委派方式做，调用方零改动。
3. **网络收口**：13 处裸 `HttpURLConnection` 逐个迁到 `util/HttpClient`（OkHttp 已在）；SSE 可保持自研解析（有测试护栏，换库收益低于回归风险），但把「EventSource 语义」收敛到 HttpClient 一个入口。
4. **SharedPreferences → DataStore**：不必一次清 36 个文件；立一个「新代码禁 SharedPreferences、改动到哪个文件顺手迁哪个」的棘轮预算（项目对空 catch 已有成熟棘轮先例，照搬即可）。
5. **JSON 层**：长期方向 kotlinx.serialization（编译期、无反射、R8 友好）；Gson 目前仅因 CXR SDK 传递依赖必须保留，业务代码新序列化点不再新增 Gson 用法即可。

---

## 四、代码质量与安全

**好的方面（保持）**：TODO/FIXME 零债务；GlobalScope/AsyncTask/LocalBroadcastManager 零使用；@Suppress("DEPRECATION") 33 处但多处带理由注释；NSC 白名单每条开口有理由；密钥管理（gradle.properties 隔离 + example 模板 + 签名闸门无回退）是范本级。

**待办（按优先级）：**

1. ~~**P1 · 修 RomAdapter 反射 bug**~~ **已修复（2026-09-26，同日兼容性评估后已改 + 编译通过）**：`RomAdapter.kt:53` 类名已改为 `android.view.WindowManager$LayoutParams`。
2. **P1 · SNAPSHOT 依赖供应链风险**：`cxr-service-bridge:1.0-20260715.121510-107` 是固定时间戳 SNAPSHOT，Nexus 一旦清理即断构建。建议像 `rapidocr4j-android-1.0.0-thin.aar` 一样**把该 AAR vendor 进 `phone-app/libs/`**（`implementation(files(...))`），构建彻底脱离 Rokid maven 的清理策略。
3. **P2 · 空 catch 棘轮继续压**：预算 42 处（基线 47→42），按既有规则「改哪个文件顺手收敛哪个」，目标年内压到 <30。
4. **P2 · `runBlocking` 14 处**集中在 `AiuiProject.kt`(7)/`AdbTransport.kt`/`CxrLHiRokidSession.kt`，逐个评估换 `runBlocking` → 协程作用域（挂起函数），消除主线程卡顿面。

---

## 五、测试体系

- 现状：50 个 JVM 单测（ai 域 30 个，密度很好），**androidTest 双模块为空**，RokidLink 仅 1 个协议测试。生产:测试 ≈ 6.1:1。
- 建议：
  1. **androidTest 烟囱测试**：不需要补全量 UI 测试，补 1-2 个冒烟（App 启动 + 主页导航可达）即可拦截「打包成功但打开即崩」级别回归；可跑在 CI 的模拟器矩阵（GitHub Actions 支持）。
  2. **RokidLink 测试倾斜**：眼镜端 32 个文件只有 1 个测试，而它是双端协议另一半——`AsrPushServer` / `AiTakeoverCoordinator` 的状态机值得像 phone-app 的 ai 域一样补纯 JVM 状态机测试。
  3. **CI 加覆盖率报告**（Kover），让「6.1:1」变成可见指标并设门槛（如 ai 域 ≥60%）。

---

## 六、仓库与发布卫生

1. **deliverables/ 含 ~15 个 mp4 + 字体**（宣传视频成品与分镜素材）随仓库走——建议迁 Git LFS 或直接移出仓库放 Release 资产，clone 体积受益。
2. CI/CD 已齐备（ci.yml debug 全量构建 + 单测 + APK artifact；release.yml tag 触发签名发布），**保持**；可加 PR 触发单测的门禁状态检查（若未配 branch protection）。
3. `apps/` 商店元数据单文件入库是合理形态，保持。

---

## 七、行动清单（按优先级排序）

| # | 事项 | 量级 | 优先级 |
|---|---|---|---|
| 1 | ~~修 RomAdapter 反射类名~~ 已修复（2026-09-26） | — | ✅ 完成 |
| 2 | vendor 固化 cxr-service-bridge SNAPSHOT AAR | 半天 | P1，本周 |
| 3 | Kotlin 2.2.x + AGP 8.13 + Gradle 8.14 + jvmTarget 17（双改 toml） | 1-2 天 + 回归 | P1 |
| 4 | AGP 8.5.1+ 后评估关闭 useLegacyPackaging（省 45MB 安装体积） | 1 天 + 真机回归 proot | P2 |
| 5 | 拆 `AiConversationService` / `ChatScreen` | 各 2-3 天 | P2，随功能迭代 |
| 6 | StateHolder → ViewModel 试点 + 新页面强制 | 渐进 | P2 |
| 7 | 13 处裸 HttpURLConnection 收口 HttpClient | 渐进 | P2 |
| 8 | SharedPreferences → DataStore 棘轮预算 | 渐进 | P3 |
| 9 | androidTest 冒烟 + RokidLink 状态机测试 + Kover | 2-3 天 | P3 |
| 10 | AGP 9.x + compileSdk 36 + Compose BOM 2026.x 里程碑 | 1 周 + 全量回归 | P3，规划中 |
| 11 | deliverables 视频迁 LFS/Release 资产 | 半天 | P3 |
| 12 | Gson 业务用法冻结（新代码用 kotlinx.serialization） | 约定 | P3 |

> 升级执行顺序建议严格 3 → 4 → 10：每步独立成 PR、独立可回退；第 4 步和第 10 步都涉及 proot/so 打包链路，**必须真机回归自持 proot 执行链**（这是历史上一改就炸的点）。

---

*评估方法：三个并行代码勘察（架构 / 质量·安全 / RokidLink·文档）+ 构建脚本与 CI 审读 + 2026-09 版本基线核对。数据快照时点 2026-09-26，v4.1 (versionCode 26/19)。*
