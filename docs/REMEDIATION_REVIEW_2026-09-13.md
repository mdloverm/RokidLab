# RokidLab 修复进展复核与新增代码质量审查（2026-09-13）

- **复核对象**：`D:\rokidapp\cxrl\RokidLab`，HEAD = `6e46eee`，工作区另有 **29 项未提交改动**
- **对照基准**：`docs/ENGINEERING_ASSESSMENT_2026-09-12.md`（前次全项目评估的 P0/P1 清单）
- **方法**：只读静态复核（Read/Grep/Git 只读命令）+ **一次编译验证**（`:phone-app:compileDebugKotlin` / `:RokidLink:compileDebugKotlin`）→ `BUILD SUCCESSFUL in 1m 14s`
- **交叉核验声明**：本文所有结论均由 team-lead 独立实测复核，与架构师原始评估不一致处已标注并修正（见 §6）

---

## 1. 总评

**评级从 B- 提升到 B。** 本轮是**真刀真枪的一轮整改**，不是文档层面的自我安慰：

- **安全侧 5 个 P0 里 3 个真修好了**，且实现质量高于预期（不是打补丁，是改机制）。
- **工程护栏从 1 道门禁变成 6 道**，测试从 9 类 / 102 例涨到 **15 类 / 162 例**。
- 编译全绿，四道 `preBuild` 门禁**实际通过**（非纸面声明）。

但三类问题必须点名：

| 类别 | 内容 |
|---|---|
| **两个 P0 原地未动** | P0-2 三个 token 明文、P0-3 签名口令入库 —— 均需 owner 在 Gitee 后台 / keystore 侧执行不可逆操作 |
| **两处「修一个引入一个」** | ① `exported=false` 顺手掐断了自己的 ADB 直启兜底（N1）；② `checkGitClean` 与「构建会改脏被跟踪的内置 APK」自相矛盾（N2） |
| **一处「看着修好了」** | 副作用确认闸门（`ToolRisk` + `ToolPolicy`）**接进了调用链，却拦不到任何真实工具**（N3） |

---

## 2. 修复进展对照表

### P0（5 条）

| 项 | 状态 | 证据（实测） |
|---|---|---|
| **P0-1** 调试广播接收器 | ✅ **已修** | `DebugAiuiReceiver.kt` 已整体迁至 `phone-app/src/debug/java/...`；`src/main/AndroidManifest.xml` 全文无 `DEBUG_CMD`；`src/debug/AndroidManifest.xml:12-18` 唯一声明 → **release 包不再包含，`act="shell"` 分支不可达** |
| **P0-4** 眼镜端宿主导出 | ⚠️ **修好但引入 N1** | `RokidLink/AndroidManifest.xml:78` `exported="false"` ✅；`AiuiLinkActivity.kt:161-168` `isAllowedAixPath` 用 `canonicalPath` 白名单 ✅。**但** `AiuiFrontendController.kt:217` 的 `am start` 兜底路径因此失效（详见 N1） |
| **P0-5** release 全局明文 | ✅ **已修** | 新增 `main/res/xml/network_security_config.xml`（`base-config` 禁明文，仅放行 `localhost`/`127.0.0.1`/`::1`/`192.168.1.168`/`192.168.49.1`/`ip-api.com`）；`build.gradle.kts:20/39/48` → defaultConfig `false`、debug `true`、**release `false`**；`debug/res/xml/` 同名文件整体覆盖（放开明文 + 信任用户 CA 便于抓包）。**补充**：NSC 只约束 `HttpURLConnection`/`WebView`/`OkHttp`，**裸 `Socket` 不受约束** → ADB/TextInput/ASR/AIUI-push 等裸 socket 链路不会被误伤，真正受影响面比想象小 |
| **P0-2** 三个硬编码 token | ❌ **未修** | 值实测仍在：`settings/DeveloperScreen.kt:53`（Gitee PAT，`:1176` 用于 contents API）、`settings/SettingsScreen.kt:567`（IFDIAN）、`util/AppConfig.kt:9`（KUWO，`KuwoMusicApi.kt:45` 使用）。新增的 `SecretStore` **只服务于用户自己填的 AI API Key**（`AiConfigService.kt:75/132/179`、`KeyButtonService.kt:1586`），三 token 均未接入 |
| **P0-3** 签名口令入库 | ❌ **未修** | `gradle.properties:5-7` 明文；`git ls-files gradle.properties` 仍有输出（仍被跟踪）；`phone-app/build.gradle.kts:30-33` 与 `RokidLink/build.gradle.kts:23-26` 的 `orElse("rokid123")` / `orElse("rokidbrew")` / 绝对路径 `D:\rokidapp\release.keystore` 全在 |

### P1（14 条）

| 项 | 状态 | 证据（实测） |
|---|---|---|
| **P1-1** 上帝类 | ⚠️ **部分** | `KeyButtonService` 2282→2158；但 `AdbFileManagerClient` 1556→**1620**、`CxrLHiRokidSession` 1003→**1081** —— 属修 bug 的代价（协议重组/兼容性查询），非职责膨胀 |
| **P1-2** 超长方法 | ❌ 未处理 | 本轮未涉及 |
| **P1-3** 协议门禁覆盖 | ❌ **未修** | `build.gradle.kts:170` `forbiddenChannels` 仍为 `{"Ai","Sys","Wifi","Jsai","Ai_RenderPayload"}`，**未纳入 `rokidlab_*`**；正则（:171）仍只匹配实参位置。眼镜端 `KeyButtonService.kt:433-474` 仍有 11 个自建重复常量 |
| **P1-4** 保障机制 | ⚠️ **大部分已修** | 6 道闸门确已建立（见 §3）。**但覆盖有缺口**：4 道 `preBuild` 门禁只挂在 phone-app；`RokidLink/build.gradle.kts:7` 仅 `apply(local-gates)` → **单独 `:RokidLink:assembleRelease` 不触发协议/i18n/空catch 三闸**，而 `local-gates.gradle.kts:2-3` 的注释声称"同样受约束"，**与事实不符** |
| **P1-5** 状态层 | ❌ 未修 | 代码内 `StateFlow`/`MutableStateFlow` 命中 **0**（仅出现在 .md 文档里） |
| **P1-6** 聊天主线程 IO | ✅ **已修** | 抽出 `ChatHistoryStore`（纯 JVM）+ 单线程 daemon 落盘器，加载/迁移/压实全部后台；`ChatHistoryStoreTest` **14 例**（含旧 JSON 数组格式迁移不丢历史、崩溃截断半行、`rewrite(空)` 删文件） |
| **P1-7** UI 承载业务/IO | ⚠️ 部分 | 本轮未系统清理，但 ADB 链路改造间接收拢了一部分 |
| **P1-8** 异常粒度 | ⚠️ **部分** | 4 条关键链路 16 处 catch 已落 `LogCollector`；新增 `checkKeyPathEmptyCatch`（6 文件 20 处全部标注豁免）+ `checkNoBareCatch`（全仓 67 处，已标注 25 / 未标注 42，预算 47，**只降不升**）。**残留**：42 处未标注 + 336 处 `runCatching` |
| **P1-9** APK 体积 60MB OCR | ❌ 未处理 | 阶段三事项 |
| **P1-10** SNAPSHOT 依赖 | ❌ 未处理 | `cxr-service-bridge:1.0-20260715.121510-107` 仍在 |
| **P1-11** 测试盲区 | ✅ **大幅改善** | 类 9→**14**（+`AdbTestPeer` 工具类），`@Test` 102→**162**。新增 `AdbSyncProtocolTest`(8→15)、`AdbFileManagerSyncTest`(12)、`HidReportTest`(7)、`ChatHistoryStoreTest`(14)、`ToolRiskMapTest`(8)，正是上次点名的 5 个方向。**残留**：`KeyButtonService` / `ChatStateHolder`（本体）/ `CxrLHiRokidSession` 仍无测试；`androidTest` 仍 0 |
| **P1-12** 文档漂移 | ⚠️ 待复核 | 本轮文档大改，此前点名的包结构树缺项是否补齐未逐一核对 |
| **P1-13** 死代码 | ✅ **已修** | RokidLink 的 `connectToWifiApi29` + 4 个 `enableWifiVia*` 已删；`ROKIDBREW_REGISTRY_URL` 全仓 0 引用 |
| **P1-14** i18n | ✅ **已修** | 5 条英文串补齐；`checkI18nKeysSynced` 实测通过：**phone-app 1106 条 / RokidLink 22 条，双语 key 集合一致** |

**顺带**：`AdbPasteCompat.kt` 已删除，全仓无悬挂引用（编译通过佐证）。

---

## 3. 本轮新建的工程护栏（6 道）

编译时实际输出（`BUILD SUCCESSFUL`）：

```
> Task :phone-app:checkI18nKeysSynced      phone-app 1106 条 key 双语一致 / RokidLink 22 条
> Task :phone-app:checkKeyPathEmptyCatch   6 个关键链路文件，20 处空 catch 均已标注豁免理由
> Task :phone-app:checkNoBareCatch         全仓 67 处（已标注 25 / 未标注 42，预算 47）
> Task :phone-app:checkProtocolSynced      AiChannel.kt / LinkProtocol.kt 双端同源 + 无裸协议字面量
> Task :phone-app:preBuild →(4 道门禁全部挂载)
```

| 门禁 | 挂载点 | 作用 | 覆盖缺口 |
|---|---|---|---|
| `checkProtocolSynced` | `preBuild` | 双端协议逐字节同源 + 禁裸协议字面量 | **仅 phone-app**；`rokidlab_*` 未纳入 |
| `checkI18nKeysSynced` | `preBuild` | zh↔en key 集合相等 | **仅 phone-app** |
| `checkKeyPathEmptyCatch` | `preBuild` | 关键链路空 catch 零容忍 | **仅 phone-app** |
| `checkNoBareCatch` | `preBuild` | 全仓空 catch 棘轮预算（只降不升） | **仅 phone-app**；不扫 `src/debug` 与测试 |
| `checkGitClean` | `packageRelease` | 出 release 前工作区必须干净 | 可被 `-PallowDirtyWorktree=true` 一键关闭；**与 N2 自相矛盾** |
| `packageRelease → testDebugUnitTest` | `packageRelease` | 出 release 前单测必须绿 | — |

---

## 4. 新增问题清单

### N1 —— `exported=false` 掐断了自己的 ADB 兜底路径（P1，**修一个引入一个**）

- **证据**：`phone-app/.../glasses/AiuiFrontendController.kt:208`
  ```kotlin
  /** 经 ADB `am start` 直启宿主 Activity（AiuiLinkActivity 为 exported=true，shell 可拉起） */
  ```
  `:217` 实际执行 `am start -n com.rokidlab.rokidlink/.AiuiLinkActivity --es aix_path ...`
- **矛盾**：`RokidLink/AndroidManifest.xml:78` 已改为 `exported="false"`。shell（uid 2000）启动非导出 Activity 必报 `Permission Denial: not exported`。
- **影响**：当 `balExempt=false`（未授权悬浮窗）时，CXR topic 主路径失败后回落到这条 ADB 路径 → 必然失败，用户侧表现为「点了没反应」。**且注释仍在说 `exported=true`，会误导下一个维护者。**
- **修复方向**：兜底改走 `rokidlab_aiui_host` topic，由同进程的 `KeyButtonService` 调 `AiuiLinkActivity.open()`；同时删掉 `:208` 的失实注释。

### N2 —— `checkGitClean` 与内置 APK 互相打架（P1，**修一个引入一个**）

- **证据**：
  - `phone-app/src/main/assets/RokidLink.apk` **被 git 跟踪**（`git ls-files` 有输出），且当前工作区即为 ` M`（已修改）态。
  - `phone-app/build.gradle.kts:120-134` 的 `buildRokidLinkRelease` 无条件 `copyTo(..., overwrite = true)` 覆盖它。
  - `local-gates.gradle.kts` 把 `checkGitClean` 挂在 `packageRelease`；而 `packageRelease` 同时（间接）依赖 `mergeReleaseAssets → buildRokidLinkRelease`，两者是**同级依赖、无 `mustRunAfter`**，执行顺序不定。
- **影响**：**「出 release 包」这个动作本身会把工作区改脏，转头被门禁拦下**。Git 历史里连续 3 个 commit（`85a8982` / `1b30a49` / `c9a9d85`）都在「重新同步内置眼镜端 APK」——正是这个坑留下的脚印。
- **修复方向**：把该 APK 移出版本控制（加 `.gitignore`，改为构建期生成）；或让 `checkGitClean` 只校验 `src/**` 的 porcelain 输出（排除 `assets/RokidLink.apk`）。

### N3 —— 副作用确认闸门实质空转（P1，**看着修好了**）

- **证据**：`ToolRisk.kt:31-78` 的 `ToolRiskMap.map` 全量枚举后，**没有任何一条真实工具登记为 `EXTERNAL_SIDE_EFFECT`**（只有 `READ_ONLY` 与 `LOCAL_SIDE_EFFECT` 两档；`call_phone` 已按 2026-09-11 的用户明确要求显式降为 `LOCAL_SIDE_EFFECT`）。而 `ToolPolicy.check()` 只在 `risk == EXTERNAL_SIDE_EFFECT` 时走确认，且无确认通道/超时一律 `Decision.Allow`（fail-open，`ToolPolicy.kt:106-110/118-120`）。
- **结论**：闸门**确实接进了调用链**（AIUI 页 `ToolGateway.kt:184`、对话 `AiConversationService.kt:415`），但对任何真实工具都**不会触发**。唯一会进 EXTERNAL 的是"未知名字"，而它随后必被 `ToolRegistry.toolList.firstOrNull` 拒绝。→「副作用工具强制眼镜端二次确认」当前是**装饰性的**。
- **附带矛盾**：`ToolPolicy.kt:11-13` 的 KDoc 写"降级为拒绝 + 明确提示"，实际实现是降级放行 —— 文档与代码相反。
- **修复方向**：二选一 —— 要么承认当前无外发工具、删掉这层以免虚假安全感；要么把真正外发动作登记为 EXTERNAL 并改 **fail-closed**。

### N4 —— 乐观 WiFi 判定把降级责任下放给每个调用方（P1）

- **证据**：`connection/ConnectionRouteManager.kt:117-150` 的 `resolve()` 现在「adbd(5555) 可达就选 WiFi」（乐观），但目标服务 8848/7654/7656/7658 可能 `ECONNREFUSED`。
- 已补降级的调用方：`AiuiProject`（上传/删除，`catch IOException` → BT 重试）、`GamepadActivity`(7656)、`AiuiFrontendController`(7658)、`PhoneMirrorService`(7654)。
- **未逐一核对**其余 `resolve()` / `tunnelTo()` 消费者（文件管理器、`AdbShellClient` 内部等）。
- **风险**：任一消费者漏接降级 → 退化为「切了 WiFi 反而不能用」，比不切更差。
- **修复方向**：把「WiFi 首选 + 一次 BT 重试」封装进路由层统一原语（如 `routeManager.withRoute{...}`），避免各调用方各写一遍。

### N5 —— 眼镜端新增局域网监听面（P2，安全）

- **证据**：`RokidLink/.../AsrPushServer.kt:142-144` `server.bind(InetSocketAddress(WIFI_PORT))`（注释 `:39` 明确"绑 0.0.0.0，手机与眼镜同网段即可直连"）；`:173` `attachClient` 是**替换式单客户端**（`:161-172`）。
- **影响**：同网段任意主机连上 7660 即可**挤掉真手机连接**（DoS），并可读取下发的 ASR 文本（信息泄露）。原 RFCOMM 通道无此暴露面。
- **修复方向**：校验对端（比对已配对手机 IP / 加握手 token），或仅在 RFCOMM 不可用时才开 TCP 监听。

### N6 —— `SecretStore` 两份"同源"并非字节同源（P2）

- **实测**：`phone-app/util/SecretStore.kt` 与 `RokidLink/SecretStore.kt` 代码体完全一致（`ALIAS`/`TRANSFORM`/`GCM_TAG_BITS=128`/`IV_BYTES=12`/`PREFIX="enc:v1:"` 全同），仅 KDoc 详略不同 → **语义同源成立**，但无门禁守护，存在漂移风险。
- **安全评估（正面）**：IV 取 `cipher.iv`（GCM 每次 `init` 由 SecureRandom 生成）→ **随机 ✓**；tag 128 ✓；密钥驻 Android Keystore 不可导出 ✓；**降级路径不会写坏已加密数据**（密文带 `enc:v1:` 前缀，与明文分支互斥）。
- **真实弱点**：① Keystore 复位（恢复出厂 / 生物识别变更）后旧密文解密失败 → `get` 返回 `null`，**用户 API Key 静默丢失、无提示**；② `plainCache` 用 `WeakHashMap<SharedPreferences, …>` 作键，依赖 `ContextImpl` 按名缓存 prefs 实例，换实现会静默失效（性能回退，非正确性问题）。
- **修复方向**：解密失败落 `LogCollector` + UI 提示重填；给两文件加同源校验门禁。

### N7 —— `KuwoMusicApi` 强制 http→https 依赖 CDN 行为（P2）

`KuwoMusicApi.kt:86-96` 的 `toHttps()` 把直链强升 https（为绕过 NSC）。代码注释称已实测有效，本轮**未复核**；若部分 CDN 节点不支持 https，表现为「能搜到、放不了」。

### N8 —— 门禁扫描根不含 `src/debug` 与测试（P2）

`checkNoBareCatch` 只扫 `src/main/java`（`build.gradle.kts:332-335`）→ debug 源集新增的空 catch 不计入预算。

---

## 5. 门禁的漏网模式（实证，可直接照着构造绕过写法）

### ① `checkProtocolSynced` —— 最严重

扫描正则（`build.gradle.kts:171`）只匹配**函数实参位置**：
```kotlin
(?:sendCustomCmd|sendMessage|rawSendCmd|caps\.write|pushControl|push)\s*\(\s*(?:[^,)]*,\s*)?["']([^"']+)["']
```
**赋值形式全量绕过**：
```kotlin
internal const val TOPIC = "rokidlab_key_config"   // KeyButtonService.kt:433 —— 不命中
```
**实证**：本次编译该门禁输出「**无裸协议字面量**」，但全仓实际存在 **40 处**赋值形式的裸 `rokidlab_*` 字面量（`AiuiFrontendController.kt:51`、`CxrLHiRokidSession.kt:103/108`、`RokidLinkController.kt:310`、眼镜端 `KeyButtonService.kt:433-474` 的 11 个常量…）→ **门禁在这件事上给的是虚假的绿**。

### ② `checkNoBareCatch` / `checkKeyPathEmptyCatch`

正则 `catch\s*\([^)]*\)\s*\{\s*\}`，且是**逐行** `containsMatchIn(line)`：
```kotlin
catch (e: IOException) {      // 漏网 A：左括号与右括号分行 → 不匹配
}
catch (_: Exception) { /* ignore */ }   // 漏网 B：体内有任何注释即绕过预算
```
豁免判据是「行内含 `catch-ok:`」——**出现在任意位置即放行**（`:295/:348`）。
→ 漏报面大，误报面小。棘轮预算的约束力偏弱。

### ③ `checkI18nKeysSynced`

只认双引号 `<string name="...">`；`<string-array>` / `<plurals>` 不校验；`readText()` 含 XML 注释 → **被注释掉的 `<string>` 仍计入 key 集合**，可能造成假失败或反向掩盖。不比占位符（`%1$s` vs `%s` 数量/类型不一致不报）。

### ④ `checkGitClean`

- **绕过**：`-PallowDirtyWorktree=true` 命令行一键关闭，无环境校验。
- **误伤**：见 N2。

---

## 6. 对架构师原始结论的修正（team-lead 交叉核验）

| # | 架构师原始结论 | 复核结果 |
|---|---|---|
| 1 | P1-4「6 道闸门已建立」 | **部分修正**：4 道 `preBuild` 门禁**只挂在 phone-app**；`RokidLink/build.gradle.kts:7` 仅 `apply(local-gates)` → 单独构建眼镜端 release 时 3 道闸门不触发，而 `local-gates.gradle.kts:2-3` 的注释声称"同样受约束"，**与事实不符**（已实测确认） |
| 2 | P1-11 测试"15 个文件 / 162 例" | **口径修正**：`@Test` **162** 成立；测试类为 **14** 个，第 15 个 `AdbTestPeer.kt` 是脚本化对端工具类，无 `@Test` |
| 3 | P0-5 影响面 | **补强**：NSC 只约束 `HttpURLConnection`/`WebView`/`OkHttp`，**裸 `Socket` 不受约束** → ADB(5555)/TextInput(7656)/ASR(7660)/AIUI-push(7658) 不会被误伤；真正受影响的只有走 HttpURLConnection 的 AIUI 8848 上传 |
| 4 | N1 / N2 / N3 / N5 | **全部实证成立**，无夸大（已逐条核对源码与 git 状态） |

---

## 7. 下一步建议（面向「即将发 v3.6」）

按「会不会挡住发版」优于「发出去安不安全」优于「长期质量」排序：

1. **先恢复可 clean-checkout 的基线（阻塞发布）** —— `checkGitClean` 已就位，而当前有 29 项未提交改动。**必须先分批 `git add` 提交重构源码与新测试**，否则根本出不了 release 包。这一步排在所有事情之前。
2. **修 N2（内置 APK 与门禁冲突）** —— 不修则每次出包都会卡在 `checkGitClean`，反复重演历史上那 3 个「重新同步 APK」的 commit。
3. **修 N1（ADB 兜底被掐）** —— 直接决定部分真机上 AIUI 能否打开；顺手删掉那句失实注释。
4. **P0-3 先做「不需要轮换就能止血」的一半** —— 删掉两端 `orElse("rokid123")` / `orElse("rokidbrew")`（缺失即 `error()` 中止构建）+ keystore 路径改 `rootProject.file(...)`。**这一步不依赖密钥轮换，可立刻消除"缺配置静默产出错误签名包"的风险。** 若 v3.6 为公开分发，P0-3 的紧迫性最高（口令已随 `gradle.properties` 推至远端）。
5. **P0-2 / P1-3 低成本高收益** —— P0-2 需 owner 在 Gitee 后台轮换（不可代办）；P1-3 只需把 `rokidlab_*` / `Sys_*` / `Jsai_*` / `Wifi_*` 纳入 `forbiddenChannels` 并把扫描升级到「字符串常量赋值」级别，**立刻消除虚假安全感**，同时逼眼镜端复用 `AiChannel` 常量。
6. **N3 二选一定性** —— 删掉空转的确认层，或把真实外发工具登记 EXTERNAL 并改 fail-closed。不要留着"以为有防护"。

> 结论：**比 P0-2/P0-3 更该先做的，是第 1–3 项** —— 它们是「能不能按时发包」的工程阻塞；P0-2/P0-3 是「发出去安不安全」的安全债。二者都优先于 P1-1/P1-5 的重构。

---

## 8. 不确定项（需真机 / 作者确认）

1. **N1 的实际可达性**：shell(uid 2000) 启动非导出 Activity 是否**一定**被拒 —— 项目自有注释（`RokidLink/AndroidManifest.xml:84-91`）称已实测"非导出 Activity 一律被拒"，但那是 `KeyButtonService` 场景。建议真机验证 `AiuiFrontendController` 兜底路径。
2. **N2 的任务执行顺序**：`checkGitClean` 与 `buildRokidLinkRelease` 在真实 `assembleRelease` 中的先后未实跑（当前工作区本就该失败）。**需先提交基线后再验一次**。
3. **N7 Kuwo https**：CDN 是否全节点支持，需真机放歌验证。
4. **P0-5 的行为矩阵**：用户自定义 http `baseUrl`、`web_fetch` http 链接、商店 http 镜像在 release 下被拒的**用户可见影响**，需真机逐条回归。
5. **P1-5 状态层与 `KeyButtonService` / `CxrLHiRokidSession` 仍无测试** —— 本批新增测试未触及这两个最高风险文件，正确性仍依赖真机。

---

*本报告为只读复核产物（含一次编译验证），未修改任何源码。*
