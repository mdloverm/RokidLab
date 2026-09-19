# RokidLab 项目开发规范

## 一、项目架构

### 包结构

```
com.rokidlab.phone
├── platform/          # L0 平台适配层（AdbTransport, ShellOps, SdkBridge, RomAdapter, HidBridge, Capability 等）
├── connection/        # L1 连接层（ConnectionRouteManager, ChannelArbiter 通道租约仲裁）
├── adb/               # L1 ADB 调试工具（核心功能模块）
│   ├── *.kt           # 业务逻辑类（AdbShellClient, AdbFileManagerClient 等）
│   └── ui/            # UI 层（AdbToolsScreen, TimerDialog, ShellDialog 等）
├── glasses/           # L2 眼镜连接与授权（CxrLHiRokidSession, AsrBridgeCoordinator, AiuiFrontendController,
│                      #    PhotoQuizFlow, GlassesHandshake, LinkProtocol, AiChannel）
├── ai/                # L4 AI 能力
│   ├── tools/         # 工具 Provider（Info/Knowledge/Glasses/Timer/Media/Display/Web/Files/Aiui/Phone）
│   │                  #    + ToolEntry（工具的完整声明：风险/副作用/域/schema/摘要 一处写全）
│   ├── approval/      # 工具审批接缝（ApprovalGate 唯一入口、ToolGuard 策略源、
│   │                  #    PseudoTools 伪工具风险档、PageScope 页面准入域与文案）
│   ├── llm/           # 模型能力接缝（LlmRegistry 能力解析 + 客户端构造）
│   ├── compaction/    # 上下文压缩接缝（CompactionEngine + BasicCompactionEngine）
│   └── ToolRisk.kt    # 工具风险分级表
├── domain/            # L3 领域服务（AiConversationService, ConnectionService, DeviceControlService 等）
├── feature/           # L5 功能状态与协调（MainScreen, *StateHolder, RokidLinkController）
├── store/             # L5 应用商店与对话 UI
├── app/               # 应用入口与 DI（LabApplication, MainActivity, AppContainer）
├── design/            # 设计系统组件库（DesignComponents, StoreTheme, theme/）
├── filemanager/       # 文件管理
├── hid/               # 蓝牙手柄（BluetoothHidManager, BtHidCompat）
├── keepalive/         # 后台保活
├── mirror/            # 投屏（手机投屏 + 屏幕镜像, MirrorCompat）
├── model/             # 数据模型
├── music/             # 音乐与媒体按键
├── network/           # 网络请求（下载、图标加载）
├── permission/        # 权限申请横切模块（AppPermission 权限总表, AppForegroundTracker 前台判定,
│                      #    PermissionRequestActivity 统一授权页, PermissionBridge 工具侧桥接）
├── settings/          # 设置页面
└── util/              # 工具类（AppConfig, HttpClient, LocalizationManager, RomFingerprint）
```

### 分层规则

- **L0 平台适配层**（`platform/`）：ADB 传输、Shell、SDK 反射、ROM 适配、HID 桥；不得依赖上层
- **L1 连接层**（`connection/`、`adb/`）：线路判定、通道租约、ADB 协议客户端
- **L2 眼镜链路**（`glasses/`）：CXR-L 会话、ASR 桥、AIUI 前端、协议同源文件
- **L3 领域服务**（`domain/`）：业务编排（会话、连接、设备控制、文件传输、镜像协调）
- **L4 AI 能力**（`ai/`）：模型调用、工具注册与执行、记忆、知识库、技能
- **L5 功能与 UI**（`feature/`、`store/`、`settings/`、`app/`）：Composable、状态持有器、DI 装配
- **依赖方向单向**：L5 → L4 → L3 → L2 → L1 → L0，禁止反向依赖
- **权限申请横切模块**（`permission/`）：权限总表 / 前台判定 / 统一授权页 / 工具侧桥接。
  它是**纵切**而非某一层 —— 可被 L4（AI 工具）与 L5（UI）直接调用，但自身只允许依赖
  android 框架与 `util/ManufacturerUtils`，**禁止**反向依赖 `ai/`、`app/`、`glasses/` 等业务包
- **UI 层**（`ui/` 子包）：存放 Composable 函数、Dialog、Screen 等界面组件
- **设计系统层**（`design/`）：存放全局共享的 UI 组件和主题常量

## 二、命名规范

### 包名

- 功能模块根包：`com.rokidlab.phone.<feature>`（如 `com.rokidlab.phone.adb`）
- UI 子包：`com.rokidlab.phone.<feature>.ui`（如 `com.rokidlab.phone.adb.ui`）

### 类/接口名

- 业务逻辑类：使用 PascalCase，功能明确（如 `AdbShellClient`, `LocalizationManager`）
- Composable 函数：使用 PascalCase，与文件名一致（如 `TimerDialog`, `AdbToolsScreen`）
- 数据类：使用 PascalCase（如 `TimerTask`, `TimerAction`）
- 密封类：使用 PascalCase（如 `TimerSchedule`, `TimerAction`）

### 资源名

- 字符串资源：`<模块>_<描述>`，全小写蛇形（如 `timer_task_list`, `shell_command_subtitle`）
- 布局文件：全小写蛇形

## 三、多语种规范

### 强制规则

1. **所有用户可见的文本必须通过字符串资源引用**
   - Kotlin 中使用：`ctx.getString(R.string.xxx)` 或 `context.getString(R.string.xxx)`
   - XML 中使用：`@string/xxx`

2. **字符串资源必须同时在两个文件中定义**
   - `res/values/strings.xml`：中文
   - `res/values-en/strings.xml`：英文

3. **禁止硬编码文本**
   - ❌ `Text("暂无定时任务")`
   - ✅ `Text(context.getString(R.string.timer_no_tasks))`

4. **日志使用英文**
   - ❌ `Log.w("Tag", "连接失败")`
   - ✅ `Log.w("Tag", "Connection failed")`

### 新增字符串流程

1. 在 `values/strings.xml` 添加中文定义
2. 在 `values-en/strings.xml` 添加对应英文定义
3. 代码中使用 `R.string.xxx` 引用
4. 多个项目重复时仅保留一份定义，用 `<string name="xxx">` 的唯一 name 标识

## 四、设计系统规范

### 可用组件

所有 UI 组件定义在 `com.rokidlab.phone.design` 包中：

| 组件 | 用途 |
|------|------|
| `BrewButton` | 主要操作按钮（填充色 + 白色文字） |
| `BrewOutlineButton` | 次要操作按钮（边框样式） |
| `BrewCompactButton` | 紧凑按钮（列表行内操作） |
| `BrewIconButton` | 图标按钮 |
| `BrewDialog` | 弹窗容器 |
| `BrutalTextField` | 文本输入框 |
| `BrewStatusDot` | 状态圆点指示器 |
| `BrewStatusPill` | 状态药丸标签 |
| `BrewStateCard` | 状态卡片 |
| `BrewErrorCard` / `BrewWarningCard` / `BrewLoadingCard` / `BrewResultCard` | 特定状态卡片 |

### 颜色常量

```kotlin
BrewCoral      // 珊瑚色 - 主要强调色
BrewInfo       // 蓝色 - 信息
BrewGreen      // 绿色 - 确认/成功
BrewWarning    // 琥珀色 - 警告
BrewMagenta    // 品红 - Shell/特殊
BrewRed        // 红色 - 错误/删除
BrewSuccess    // 亮绿 - 成功完成
BrewText       // 主文字色
BrewTextBright // 亮文字色（按钮文字）
BrewMuted      // 辅助文字色
BrewBorder     // 边框色
BrewBg         // 背景色
BrewPanel      // 面板背景色
```

### 形状常量

```kotlin
BrewShapeSmall   // 小圆角
BrewShapeMedium  // 中圆角
BrewShapeStandard// 标准圆角
BrewShapeLarge   // 大圆角
```

### 使用原则

- 优先使用 `design` 包中的现有组件，避免自行实现
- 颜色优先使用上述常量，避免直接写 Color 值
- 形状优先使用 `BrewShape*` 常量
- 新组件如具有通用性应纳入 `DesignComponents.kt`

### UI 设计一致性规范

所有模块页面必须保持统一的 UI 结构，修改 UI 时必须同步全局：

1. **布局结构统一**：所有功能模块页面（文件管理、投屏、ADB 工具等）必须使用相同的布局层级：
   ```
   ModuleHeader → [RokidLinkStatusCard] → IpAddressInputCard → BrutalButton(s) → UsageInstructionsCard
   ```

2. **颜色方案统一**：模块标题、IP 输入框边框、使用说明标题使用模块主题色。可用主题色：
   - 文件管理 → `BrewAmber`
   - 屏幕镜像 → `BrewCyan`
   - 手机投屏 → `BrewPurple`
   - ADB 工具 → `BrewAmber`（与文件管理一致）

3. **按钮样式统一**：
   - 按钮不使用任何前缀图标（如 `▶`、`●` 等符号装饰）
   - 仅保留状态卡片内的状态指示图标（如 "✔" 已安装、"▶" 运行中）

4. **删除的图标不做保留**：所有功能按钮删除前缀图标后，必须同步更新所有模块页面的对应按钮，确保全局一致。

5. **多语种同步**：所有 UI 文本修改（包括按钮标签、说明文字）必须同步更新 `values/strings.xml` 和 `values-en/strings.xml`。

6. **适配检查**：修改任一模块的 UI 结构或样式时：
   - 同步检查其他所有模块是否也需要对应修改
   - 确保颜色值来自 `com.rokidlab.phone.design` 的 `Brew*` 常量
   - 确保所有用户可见文本已通过字符串资源引用

## 五、Compose 编码规范

### Composable 函数签名

```kotlin
@Composable
fun FeatureScreen(
    client: AdbShellClient?,
    connected: Boolean,
    scope: CoroutineScope,
    getOrConnect: ((AdbShellClient?) -> Unit) -> Unit,
    onDismiss: () -> Unit,
)
```

- 参数传递 ADB client 等外部依赖，而非在函数内部创建
- 回调参数使用函数类型 `() -> Unit`
- 避免在 Composable 内部定义类（Class is prohibited here）

### 局部函数

- Composable 内部的辅助 Composable 函数必须标记 `@Composable`
- 非 Composable 辅助函数不需标记

### 导入顺序

```
1. 项目包导入（com.rokidlab.phone.*）
2. Android/Compose 框架导入（androidx.*）
3. Kotlin 标准库与协程（kotlinx.*, java.util.*）
```

## 六、ADB 模块规范

### 架构

```
adb/
├── AdbShellClient.kt        // ADB Shell 连接客户端（核心）
├── AdbFileManagerClient.kt  // ADB 文件管理客户端
├── AdbKeyManager.kt         // ADB 密钥管理
├── AdbScreenMirrorClient.kt // ADB 投屏客户端
├── ScreenStreamDecoder.kt   // 屏幕流解码
└── ui/
    ├── AdbToolsScreen.kt    // ADB 主页面（整合各功能入口）
    ├── AdbDialogContent.kt  // ADB 通用弹窗容器（处理连接状态）
    ├── SysInfoDialog.kt     // 系统信息弹窗
    ├── AppMgrDialog.kt      // 应用管理弹窗
    ├── ShellDialog.kt       // Shell 命令弹窗
    └── TimerDialog.kt       // 定时功能弹窗
```

### AdbDialogContent 使用规则

所有 ADB 子功能模块使用 `AdbDialogContent` 作为容器：
- `AdbDialogContent` 自动处理连接/错误/就绪三种状态
- 子模块只需实现 `@Composable (AdbShellClient) -> Unit` 内容
- 标题和颜色由子模块通过参数传入

### ADB 连接规范

- 使用 `getOrConnect` 回调模式获取 ADB 连接
- 不使用全局单例管理连接
- 连接在 `Dispatchers.IO` 线程执行

## 七、多语言字符串维护

### 字符串分组

在 `strings.xml` 中使用注释按功能分组：

```xml
<!-- ADB -->
<string name="xxx">...</string>

<!-- Timer -->
<string name="timer_xxx">...</string>
```

### 重复资源清理

- 避免同名 `string` 重复定义（会导致资源合并失败）
- 迁移代码时检查新旧定义是否冲突
- 冲突时删除旧定义，保留新位置的定义

### 格式化字符串

- 使用 `%d` 格式化数字：`<string name="timer_actions_count">%d actions</string>`
- 使用 `%s` 格式化文本：`<string name="timer_action_notify">Notify: %s</string>`
- Kotlin 中调用：`context.getString(R.string.xxx, arg1, arg2)`

## 八、Git 提交规范

- 提交信息使用英文
- 格式：`<type>: <description>`（如 `fix: correct timer multilang strings`）
- type 可选：`feat`, `fix`, `refactor`, `style`, `docs`, `chore`

## 九、纠错检查清单

在完成代码修改后，必须逐项验证以下检查清单。

### 9.1 导入检查

| 检查项 | 说明 |
|--------|------|
| `clip` 导入来源正确 | 必须使用 `androidx.compose.ui.draw.clip`，禁止使用 `androidx.compose.foundation.clip` |
| 无重复导入 | 同一个类不能出现两行 import |
| import 单行声明 | 禁止在同一行用换行符拼接多个 import（如 `import A\nimport B` 必须分行） |
| 通配符导入(*) 使用正确 | `import com.rokidlab.phone.design.*` 是合法的，但仅用于 design 包 |

### 9.2 字符串资源检查

| 检查项 | 说明 |
|--------|------|
| name 唯一性 | 新增的字符串资源 name 不能与 `values/strings.xml` 和 `values-en/strings.xml` 中已有定义重复 |
| 双语同步 | 新增字符串必须同时在中文和中英文件中添加 |
| 旧资源清理 | 删除旧代码时同步清理不再使用的字符串资源 |
| 格式化参数匹配 | `%d` = Int, `%s` = String，调用时参数类型必须匹配 |
| 占位符数量一致 | xml 中的占位符数量与 `getString()` 传入的参数数量一致 |

### 9.3 编译错误预防

| 检查项 | 说明 |
|--------|------|
| @Composable 标注 | Composable 函数内部的局部 Composable 函数必须标注 `@Composable` |
| 组件参数名 | 使用 design 组件时必须验证参数名（如 `BrewCompactButton` 参数为 `text` 而非 `label`） |
| 函数引用歧义 | lambda 参数传递函数引用时若类型推断歧义，使用 lambda 包装：`{ cb -> getOrConnect(cb) }` |
| 字符串资源存在性 | 使用 `R.string.xxx` 前确认该 name 在 `strings.xml` 中有定义 |
| 格式化字符串构建 | 列表描述等文本使用 `context.getString(R.string.xxx_fmt, arg1, arg2)` 而非字符串拼接 |

### 9.4 函数/类定义约束

| 检查项 | 说明 |
|--------|------|
| 文件顶层类 | 数据类（`data class`）和密封类（`sealed class`）定义在文件顶层，不在 Composable 函数内部 |
| 前向引用 | 被调用的函数必须在调用者之前定义（如 `resetEditor` 定义在 `createTask` 之前） |
| 内部类限制 | 不要在 Composable 函数内部定义 `class`、`data class`、`sealed class` |

### 9.5 颜色与主题检查

| 检查项 | 说明 |
|--------|------|
| 颜色使用 | 使用 `design` 包的 `Brew*` 颜色常量，禁止 `Color(0xFF...)` 或 `Color.Red` 等硬编码 |
| 形状使用 | 使用 `BrewShape*` 形状常量，避免直接写 `RoundedCornerShape(8.dp)` |

### 9.6 构建验证流程

修改代码后按以下顺序验证（**必须在仓库根 `d:\rokidapp` 执行**，所有任务名带 `:cxrl:RokidLab:` 前缀）：

1. **代码审查**：对照 9.1~9.5、9.7 检查清单逐项确认
2. **编译验证**：`D:\gradle-8.7\bin\gradle.bat :cxrl:RokidLab:phone-app:compileDebugKotlin :cxrl:RokidLab:RokidLink:compileDebugKotlin --offline` 检查编译
3. **单测验证**：涉及 ADB sync / HID 描述符 / 跨端协议 / 工具风险表的改动，必须跑 `:cxrl:RokidLab:phone-app:testDebugUnitTest :cxrl:RokidLab:RokidLink:testDebugUnitTest --offline` 且 EXIT=0（详见 §12.15）
4. **日志检查**：确认 build 输出无 `ERROR`，warning 可接受
5. **功能验证**：在有条件的情况下连接真机测试 ADB 功能
6. **出 release 包**：`packageRelease` 已挂两道发布闸门（`checkGitClean` 工作区必须干净 + `testDebugUnitTest` 必须先绿），脏工作区直接构建失败；本地临时验证可用 `-PallowDirtyWorktree=true` 跳过，**禁止用于正式出包**（§12.13）

### 9.7 回归测试检查

| 检查项 | 说明 |
|--------|------|
| 改字节同步改测试 | 动过 HID 描述符字节 / sync 帧格式 / 跨端载荷格式，必须同步更新对应测试并真机回归（§12.15） |
| 新工具必登风险表 | 新增工具后 `ToolRiskMap.unregisteredTools()` 必须为空（§12.10） |
| 测试状态复位 | 用例 `@After` 必须复位被测单例的全局状态，避免用例间污染 |
| 不迁就测试改行为 | 测试与生产语义冲突时登记现象待评估，不得为让测试变绿而改生产行为 |
| 门禁必过 | `checkProtocolSynced` / `checkI18nKeysSynced` / `checkKeyPathEmptyCatch` / `checkNoBareCatch` **四道**均挂 `preBuild`，构建通过即视为已过（`checkNoBareCatch` 为棘轮预算，见 §12.14） |
| 发布闸门必过 | 出 release 包额外受 `checkGitClean` + `testDebugUnitTest` 约束（挂在 `packageRelease`），见 §12.13 |

## 十、App 更新发布流程

### 自动化执行约定

当我说"发布"或"发布 vX.Y"时，AI 助手需自动按以下 7 步完整执行，无需逐项确认。

### 发布 RokidLab 新版本

1. **修改版本号**
   - 编辑 `phone-app/build.gradle.kts`：`versionCode` 递增、`versionName` 更新
   - 示例：`versionCode = 2`、`versionName = "1.1"`

2. **提交版本号变更到 Git**
   先提交再打 tag，确保 tag 指向正确的新版本 commit：
   ```bash
   git add phone-app/build.gradle.kts
   git commit -m "Bump version to {version}"
   git push
   ```

3. **打包带签名的正式版 APK**
   Gradle 会自动使用 `release.keystore` 签名（配置在 `signingConfigs.release`）：
   ```bash
   D:\gradle-8.7\bin\gradle.bat :cxrl:RokidLab:phone-app:assembleRelease   # 在仓库根 d:\rokidapp 执行
   ```
   APK 位置：`phone-app/build/outputs/apk/release/RokidLab-v{version}-release.apk`

4. **验证签名（必须执行）**
   使用 `apksigner` 确认 APK 已签名，否则无法安装到设备：
   ```bash
   & "D:\android-sdk\build-tools\34.0.0\apksigner.bat" verify --print-certs "D:\rokidapp\cxrl\RokidLab\phone-app\build\outputs\apk\release\RokidLab-v{version}-release.apk"
   ```
   成功输出示例：
   ```
   Signer #1 certificate DN: CN=RokidBrew, OU=Rokid, O=Rokid, L=Unknown, ST=Unknown, C=CN
   ```
   - 如果提示 `jar 未签名`，说明不是标准签名格式，请改用 `apksigner` 检查（新版 Android 使用 v2/v3 签名方案，`jarsigner` 检测不到）
   - 如果 `apksigner` 报错，说明 APK 确实未签名，需检查 `build.gradle.kts` 中 `signingConfigs.release` 配置

5. **推 Git Tag 并创建 Gitee Release**
   ```bash
   git tag -a v{version} -m "RokidLab v{version}"
   git push origin v{version}
   ```
   然后通过 Gitee API v5 创建 Release（需要 access_token）：
   ```json
   POST /repos/dlover1314/RokidLab/releases
   Body: {"tag_name":"v{version}","name":"v{version}","body":"更新说明","target_commitish":"master"}
   ```

6. **上传 APK 到 Release**
   ```bash
   curl -X POST "https://gitee.com/api/v5/repos/dlover1314/RokidLab/releases/{release_id}/attach_files?access_token={token}" -F "file=@RokidLab-v{version}-release.apk"
   ```
   注意上传后会得到 `browser_download_url`，即 APK 直链。

7. **更新 apps.v1.json 版本信息**
   - 仓库：`dlover1314/RokidBrew-Registry`
   - 文件：`dist/apps.v1.json`
   - 通过 Gitee API v5 读取 → 修改 → base64 编码 → PUT 写回
   - 需修改的字段：

     | 字段 | 说明 |
     |------|------|
     | `generatedAt` | 更新日期（如 `"2026-06-24T00:00:00.000Z"`） |
     | `brewVersion` | 版本号（如 `"1.1"`） |
     | `brewVersionCode` | 版本码（递增，如 `2`） |
     | `brewApkUrl` | APK 下载直链（Gitee Release 的 download URL） |
     | `brewReleaseUrl` | Release 页面链接 |
     | `brewNotes` | 更新简述 |
     | `brewChanges` | 更新详情列表 |

### 部署注意事项

- **Release APK 与 Debug APK 签名不同**：手机上如果之前装的是 debug 版（Android 默认 debug 证书签名），覆盖安装 release 版会失败（`INSTALL_FAILED_UPDATE_INCOMPATIBLE: signatures do not match`）。需先 `adb uninstall com.rokidlab.phone` 再安装 release 版。
- **验证安装**：打包 release 后建议先用 `adb install -r` 测试能否覆盖安装，确认签名一致

### 更新数据源

用户打开 App → 商店刷新 → `checkRokidLabUpdate()` 从 Gitee 读取 `apps.v1.json` → 检测到 `brewVersionCode > 当前版本码` → 弹出更新对话框 → 用户点击更新 → 下载 APK → 请求安装。

### 关键代码位置

| 作用 | 文件 |
|------|------|
| 版本号定义 | `phone-app/build.gradle.kts` |
| 自更新检查 | `Models.kt` → `BrewIndex.checkSelfUpdate()` → `SELF_UPDATE_URL` |
| 更新对话框 UI | `UpdateDialog.kt` → `UpdateDialog` Composable |
| 下载安装逻辑 | `MainActivity.kt` → `performSelfUpdate()` |
| Gitee API token | `${GITEE_TOKEN}`（占位符，**禁止明文入库**；从环境变量 / 不入库的 `local.properties` 注入。2026-09-12 移除了此前明文写入的旧 token，旧 token 需到 Gitee 后台撤销轮换） |
| Gitee 推送认证 | `git remote set-url origin https://dlover1314:{TOKEN}@gitee.com/dlover1314/RokidLab`（临时，推送完恢复为不需要 token 的 URL） |

## 十一、部署规范

### RokidLink 安装规则

1. **禁止直接通过 adb install 将 RokidLink APK 安装到手机**
   - RokidLink 是眼镜端应用，不是手机端应用
   - RokidLink APK 已自动打包进 phone-app 的 `src/main/assets/RokidLink.apk`

2. **部署流程**
   - 只需安装 `phone-app/build/outputs/apk/debug/RokidLab-v3.5-debug.apk`（文件名格式：`RokidLab-v<versionName>-<buildType>.apk`，由 `build.gradle.kts` 的 `applicationVariants` 生成）
   - 构建 phone-app 时，`buildRokidLinkDebug` 任务会自动：
     1. 先构建 RokidLink 模块
     2. 将生成的 `RokidLink-debug.apk` 拷贝到 `src/main/assets/RokidLink.apk`
   - 用户通过手机端 `installRokidLinkToGlasses()` 从 assets 推送到眼镜

3. **验证方式**
   - `src/main/assets/RokidLink.apk` 的修改时间应晚于 `RokidLink/build/outputs/apk/debug/RokidLink-debug.apk`
   - 如果 assets 中的 APK 不是最新，重新构建 phone-app 即可

## 十二、v3.5 架构硬约束

### 12.1 CXR SDK 依赖

- **CXR-L SDK** 必须使用 `com.rokid.cxr:client-l:1.1.2`（Maven），并显式 pin `cxr-service-bridge:1.0-20260715.121510-107` timestamped snapshot
- **CXR-S SDK** RokidLink 必须使用 `com.rokid.cxr:cxr-service-bridge:1.0`（Maven），**禁止再用本地 `libs/*.aar`**
- ⚠️ **RokidLink 绝不能切换到 `client-l` 1.1.0+**，其 fat aar 缺 `ReplyImpl` 类，native 层反射加载会 SIGABRT（已在 2026-09-08 真机验证）
- **R8 启用**：RokidLink release 必须开 R8，`proguard-rules.pro` 必须保留 `keep com.rokid.cxr.**`，因 CXR SDK 通过 JNI 反射调用自身

### 12.2 Lab 工具桥约束

- **AIUI 页面调用手机端工具的唯一入口**是 `ToolGateway.call()`，不得绕过
- **`ALLOWED_DOMAINS = DOMAIN_ALL`**（用户 2026-09-09 拍板全开），收窄时只改一处
- **`DENY_TOOLS` 只放技术故障工具**（如 `open_aiui_app` 自指递归），**不放"危险"工具**，安全边界由 `isEnabled` 总开关负责
- **结果截断 8000 字符**：RFCOMM 单帧上限 64KB，且页面渲染不下超长文本
- **15s 超时后不 interrupt**：工具可能持有文件/网络资源，强中断留下半写状态，让线程自己跑完（daemon 线程不阻塞进程退出）
- **AIUI 页面侧必须用 `globalThis.Lab.callTool(...)` 或 `window.Lab.callTool(...)`**，不能写裸 `Lab.callTool` —— ink 沙箱页面 realm 不走 globalThis 解析裸标识符，会 ReferenceError
- **生成的 AIUI 代码必须 try/catch + loading 态**（见 `lab-runtime.md` 第 8 章）
- **`__LAB_TOOL__` 复用现有 ASR 推送 RFCOMM 通道**，不新开通道 —— 眼镜端同一时刻只允许一条 RFCOMM（adb 隧道已占满）
- **`AsrBridgeCoordinator` 必须把 `__LAB_TOOL__` 前缀分流到 `onToolCall`**，绝不能当成 ASR 文字送进对话链路

### 12.3 启动参数下发约束

- `open_aiui_app` 工具的 `params` 字段是 **JSON 对象字符串**（如 `{"songName":"西厢"}`）
- **非法 JSON 直接丢弃**（`parseLaunchParams` 返回 null），绝不把脏串下发到页面 —— 页面侧无法容错
- 启动参数必须在 `open` 时一起下发，**不能在 open 之后补一条 msg** —— 页面此刻尚未解包渲染，`hostMessage` 会被 host.js 的 `if (!view) return` 静默丢弃
- 同一包再次打开换参数时**不重启宿主**，直接更新 `launchParamsJson` 并在已 boot 后 `deliverLaunchParams()` 下发新参数
- 页面在 `onMessage` 接收（**不能在 `onLoad` 里拿**），且 `e.data` 是 JSON 字符串，必须 `JSON.parse(e.data)`

### 12.4 ASR 防抖与补读约束

- `AsrBridgeCoordinator.onAsrText` 去重条件：**仅当「同文 + 上一条仍在处理中」**才丢弃；用户连说两次同文应当执行两次
- `asrHandling` 标志由 `CxrLHiRokidSession.dispatchGlassesAsrText` 在处理开始 / 结束时调 `markAsrHandling(true/false)` 维护
- **90s 兜底超时**：防止异常路径下标志卡死，导致该指令被永久吞掉
- `AsrPushClient` 必须在 `connect()` 成功**之后**才置 `socket`，避免握手期间 `isConnected` 短暂 true 让上层跳过文件兜底轮询
- `AsrPushClient.onConnected` 回调置位 `catchUpRequested`，唤醒兜底轮询立刻补读推送断连期间积压的文件文字
- `start()` 必须做 1.5s 防抖，避免重复创建 push client 抢同一条 RFCOMM 通道把通道搞断

### 12.5 轻量配置下发约束

- `sendKeyQuizConfig` 等轻量配置下发时，若链路已就绪（`cxrLink != null && cxrlConnected && glassBtConnected`），**直接 `sendCustomCmd`** 复用现有连接
- 不得走 `connectAndRunCustomAppOperation` 的 `cleanup()` 全链路重建 —— 重建期间 ASR 推送通道与 ADB 隧道均不可用，保存设置后紧接着说话的第一条语音必然丢失
- 复用失败时再退回完整流程重建链路后下发

### 12.6 长期记忆与会话记忆约束

- 长期记忆必须用 SQLite 存储（`LongTermMemoryManager`），不得回退 SharedPreferences
- FIFO 上限 200 条，90 天过期，旧 SharedPreferences 数据首次启动自动迁移
- 注入策略必须用中文 bigram 重叠度评分 top-12，不得全量注入（会撑爆 system prompt）
- 会话记忆超长上下文被裁剪时，前缀必须折叠为 ≤800 字 pinned system message `[summary of earlier conversation]`，不得直接截断丢弃

### 12.7 KeyButtonService 稳定性约束

- **短命 destroy 计数器 `KEY_SHORT_LIVED_DESTROY_COUNT` 必须持久化**，否则崩溃循环会无限重启服务
- 屏幕亮起接收器必须用 `RECEIVER_NOT_EXPORTED` 注册
- `CMD_AIUI_OPEN` 必须支持 `caps[2]` 启动参数

### 12.8 CxrLHiRokidSession 拆分约束

- v3.5 已抽出三个协调器，`CxrLHiRokidSession` 保留**每一个 public method 作为委派 facade**，调用方不动
- 必须改持有 **app context** 而非 Activity；需要 lifecycle owner 的地方用 `WeakReference`，避免内存泄漏
- 后续仍可拆分连接引擎（`connectAnd*` ~1200 行）和 `sendAiTextViaLink`（~600 行）

### 12.9 HTTP 与文件传输约束

- HTTP 必须用 `HttpClient`（基于 OkHttp 4.12.0），不得回退裸 `HttpURLConnection` —— 连接池 + HTTP/2 复用避免每次重新握手
- `AdbFileManagerClient` 在 CUT (close-wait) 路径上必须 **drain socket 后再结束传输**，避免截断/丢数据
- **明文流量由 `network_security_config` 收口，禁止全局放开**：`phone-app/src/main/res/xml/network_security_config.xml` 默认 `cleartextTrafficPermitted="false"`，只白名单回环（`localhost` / `127.0.0.1` / `::1`：本地 Ollama + 蓝牙隧道手机侧转发端口）、眼镜局域网 IP（`192.168.1.168` / `192.168.49.1`）与 `ip-api.com`（定位工具免费档仅 http）；debug 变体由 `src/debug/res/xml/` 同名文件整体放开（含信任用户 CA，便于抓包）。**新增需要 http 的目标时必须先加白名单，不得改回 `usesCleartextTraffic=true` 全局放开**（声明了 NSC 后该属性本身也会被忽略）
- 眼镜 WiFi IP 由眼镜经 `TOPIC_GLASSES_IP` 自报、可能是任意局域网地址，而 NSC 不支持 CIDR/通配无法枚举 —— 因此所有对眼镜开发者 WebServer（8848）的 HTTP 调用必须保留「明文被拒 → 回落蓝牙隧道 `127.0.0.1`」兜底（见 `ai/AiuiProject.kt` 的 `uploadOnce` / `deleteOnce` + `isCleartextBlocked`），不得只做单次 WiFi 直连

### 12.10 新工具注册约束

新增 AI 工具必须同时完成三步，缺一不可：

1. 在 `ai/tools/` 下对应域的 `ToolProvider` 里实现 `execute`，并在它的 `tools()` 里**声明一个 `ToolEntry`** ——
   域 / 设置页分类 / 是否隐藏 / 风险档 / 是否副作用 / 是否需要眼镜 / `statusText` / `summarize` / schema
   全部写在这一个结构体里（接缝化后的**唯一登记面**，不再有六张表需要手工对齐）
2. 风险档（`ToolEntry.risk`）必须显式声明是 `READ_ONLY` / `LOCAL_SIDE_EFFECT` / `EXTERNAL_SIDE_EFFECT` 中的哪一档。
   `EXTERNAL_SIDE_EFFECT` = 触达第三方且不可撤销 → 会走眼镜端确认闸门（`ai/approval/ApprovalGate`）
3. 改完必须跑 `skills/rokidlab-chat-standalone-mode/scripts/check_tool_wiring.py` 双向核对
   （六张派生表 vs 各 provider 的声明；**禁止再出现手写 `toolList` 字面量**）；
   `ToolRegistry.unregisteredTools()` 自检必须为空（接缝化后结构上恒成立，用作防回退断言）

> 现状说明（2026-09-19 更新）：判定已全部收敛到 `ai/approval/ApprovalGate`（`ToolPolicy.kt` 已删除）。
> fail-open 是**刻意设计**并写在 `ApprovalGate.resolveAsk` 的 KDoc 里（唯一产地）：
> 无确认通道 / 眼镜端旧版 / 用户超时未响应 → 降级放行，仅眼镜端显式回 "no"（`wasCancelled()`）才拒绝。
> 另：风险档的实际兜底是**最保守档** `EXTERNAL_SIDE_EFFECT`，但「完全未知的名字」会先被
> `UnknownToolGuard` 单调拒绝，因此不会触发确认。<br>
> 仍然成立的老问题：**当前没有任何真实工具被登记为 `EXTERNAL_SIDE_EFFECT`**
> （`check_tool_wiring.py` 可核对）—— 确认闸门对真实工具仍处于空转状态。
> 要让某个工具真正走眼镜确认，只需在它的 `ToolEntry.risk` 里声明 `EXTERNAL_SIDE_EFFECT`。

### 12.11 通道租约约束

- **长连接消费者**（屏幕镜像 / 手机投屏 / 文件浏览）**必须**通过 `ConnectionRouteManager.channelArbiter` 取 `LONG_LIVED` 租约，且所有失败/异常/取消路径都要 `close()`（幂等）；持有超 10 分钟会打泄漏告警
- **常规 ADB 消费者**（ADB 工具页 / AI 工具 / AIUI 工具 / 定时任务 / ASR 兜底轮询）**禁止自建会话**，一律走 `CxrLHiRokidSession.getAdbShellClient()` 复用全 App 唯一共享会话（`platform/AdbTransport` 为唯一所有者）
- 优先级：`LONG_LIVED`（投屏/文件浏览/手机投屏）> `NORMAL`（ADB 工具页/AI 工具/AIUI 工具/定时任务）> `BACKGROUND`（ASR 兜底轮询）。`NORMAL` 消费者之间共享同一条 ADB 会话，**互不让路**，因此不持有常驻租约 —— 只有 `LONG_LIVED` 才 `acquire()`
- 让路方式：`NORMAL` 经 `getAdbShellClient()` 内的 `shouldYield()` 守卫；`BACKGROUND` 经其 `adbClientProvider` 的 `shouldYield()` 守卫。`acquire()` 非阻塞必成功，低优先级消费方用 `shouldYield()` 判断后自行退避（不在建链期阻塞）
- 长连接上场前的完整动作：取 `LONG_LIVED` 租约 **并** `releaseAdbShellClient()` 腾出 RFCOMM（由 `domain/MirrorCoordinator` 统一执行）
- 眼镜端只注册了一个 RFCOMM SCN，同一设备同 SCN 仅允许一条客户端通道 —— 这是所有通道争抢问题的根因，新增长时间独占消费者前必须评估

### 12.12 双端协议同源约束

- 所有跨端 topic / marker / 能力位必须定义在 `LinkProtocol.kt`（手机端 `glasses/LinkProtocol.kt` 与 RokidLink 端 `LinkProtocol.kt`），**禁止在业务代码里另写跨端字面量**
- 两份 `LinkProtocol.kt` 除 `package` 行与空行外必须逐字节一致，由 `phone-app/build.gradle.kts` 的 `checkProtocolSynced` 任务在 preBuild 校验
- ⚠️ 当前该任务只挂在 `:cxrl:RokidLab:phone-app:preBuild`，单独执行 `:cxrl:RokidLab:RokidLink:assembleRelease` 不会校验；且 `forbiddenChannels` 仅覆盖 5 个通道名，`const val X = "rokidlab_xxx"` 赋值形式可绕过正则
- 能力协商：眼镜端不得无条件上报 `Cap.ALL`；未接线位必须移出 ALL。`GlassesHandshake` 三态语义 —— `null`=未握手（乐观放行）/ `false`=确认不支持（快速降级）/ legacy=4s 超时（判为旧版）

### 12.13 发布工程约束

- **未跟踪文件未入库前禁止打 release** —— 当前工作区有 36 个未跟踪条目（含几乎全部 L0~L5 重构源码**与 #8 / #9 新增的 5 个回归测试文件**），clean checkout 会编译失败
- **release 出包两道机器闸门（#10，`gradle/local-gates.gradle.kts`，双端共用同一份实现）**：
  - `checkGitClean` —— 挂 `packageRelease`，`git status --porcelain` 非空即**构建失败**（上线报告 B1「发布的不是仓库里的东西」的兜底）。本地临时验证可用 `-PallowDirtyWorktree=true` 跳过，**禁止用于正式出包**
  - `packageRelease` 依赖 `testDebugUnitTest` —— 单测未跑绿则**出不了包**
  - 闸门挂 `packageRelease` 而非 `preReleaseBuild`：只堵「把脏工作区打成包」，不连带堵死 `compileReleaseKotlin` 这类纯编译校验
  - ⚠️ 该脚本由两个模块各自 `apply`，因此 `:cxrl:RokidLab:RokidLink:assembleRelease` 单跑同样受约束（不同于 §12.12 里 `checkProtocolSynced` 只挂 phone-app 的历史局限）
- 密钥（Gitee token、签名口令、API Key）**禁止明文入库**，一律走环境变量或不入库的 `local.properties`
  - **签名材料具体口径（2026-09-18 落地）**：`release.keystore` / `*.jks` / `keystore.properties` 在 `.gitignore` 中永久排除；`gradle.properties` 已 `git rm --cached` 移出索引（⚠️ 它此前**确实被跟踪过**，明文口令 2026-06-16 起进入历史并推送至 Gitee —— 仅改文件不清理历史无法真正止损）
  - 两端 `signingConfigs.release` 已去掉 `orElse("rokid123")` 明文回退：**缺失即中止构建**，绝不静默产出「签名看似正确」的包
  - ⚠️ **校验必须挂在任务图上，不能挂在 `preBuild` / 配置阶段**（2026-09-18 踩坑修正）：
    初版把 `requireSigningProperty(...)` 直接写在 `signingConfigs.create("release") {}` 里，
    而该块在**配置阶段无条件执行** —— 于是没配 release 口令的机器连 `compileDebugKotlin` /
    `installDebug` 都构建失败，发布闸门被误当成日常开发闸门。
    现拆为：`optionalSigningProperty(...)`（缺失返回 null，不抛）+ `releaseSigningProblems()`
    + `gradle.taskGraph.whenReady { ... }`，**仅当任务图里出现 `package/assemble/bundle/install/publish*Release`
    时才硬失败**；判定基于实际任务图而非任务名猜测，故 `assemble` / `build` 这类同时产出
    debug+release 的聚合任务同样被拦住。
  - ⚠️ Kotlin DSL 写法坑：`TaskExecutionGraph.whenReady` 有 `Action` 与 Groovy `Closure` 两个重载，
    裸 lambda 会解析到 `Closure` 报 `Closure<(raw) Any!> was expected`；而 Kotlin DSL 的
    `Action<T> { }` 又是**带接收者**变体（`T.() -> Unit`），写成带参数 lambda 会报 `Expected no parameters`。
    正确写法：`gradle.taskGraph.whenReady(org.gradle.api.Action<org.gradle.api.execution.TaskExecutionGraph> { /* this = graph */ })`
  - 参数提供方式（四选一）：`gradle.properties`（不入库）/ `~/.gradle/gradle.properties`（推荐）/ 环境变量 `ORG_GRADLE_PROJECT_<名>` / 命令行 `-P<名>=...`；模板见仓库根 `gradle.properties.example`（该文件可入库）
  - 修改两端任一签名读取/闸门函数时必须**双改**（两份各持一份，逐字一致）
- `phone-app/src/main/assets/RokidLink.apk` 必须是 RokidLink **release** 产物，不得内嵌 debug 包
- 新增/修改/删除 `values/strings.xml`（中文）时必须同步 `values-en/strings.xml`，**两模块（phone-app / RokidLink）的 key 集合必须完全相等**（当前 phone-app 1100=1100、RokidLink 22=22）—— 由构建期任务 `checkI18nKeysSynced` 挂在 `preBuild` 强制，不一致直接构建失败并列出差异键名（历史欠账 5 条已于 2026-09-12 补齐）

### 12.14 异常吞噬约束（空 catch 禁例）

历史教训：全仓约 55 处空 `catch`、315 处 `runCatching`，把「静默失败」当成「健壮」。链路出问题（ASR 丢字、隧道被拒、sync 降级）时 App 内日志面板里查不到真因，只能靠用户口述现象反推，同类问题反复复发。

- **全局 CR 禁例**：禁止裸空 catch（`catch (...) {}`）。确需吞掉异常时，必须写注释说明「为什么可以吞」；关键路径不得吞 —— 见下条
- **关键链路强制落 `LogCollector`**：ASR 补读（`AsrBridgeCoordinator` / `AsrPushClient`）、RFCOMM 隧道（`ConnectionRouteManager` / `AdbTransport`）、ADB sync（`AdbFileManagerClient`）、AIUI 工具网关（`ToolGateway`）**四类链路的 catch 分支必须落日志面板**，且**必须带异常对象**（`LogCollector.e(tag, msg, e)` / `LogCollector.w(tag, msg, e)`）—— 只记 `e.message` 无法区分「对端未启动」与「RFCOMM 被栈拒绝」
- **级别选择**：预期内的高频失败（链路断开、退避重连、ping 失败）用 `LogCollector.w`（不进「仅错误日志」导出）；用户操作失败、不可预期异常用 `LogCollector.e`
- **机器门禁（关键链路零容忍）**：`phone-app/build.gradle.kts` 的 `checkKeyPathEmptyCatch` 任务（挂 `preBuild`）扫描上述 6 个关键链路文件，空 catch 必须带 `// catch-ok: <原因>` 标注，否则**构建失败**；`LogCollector.w(tag, message, throwable)` 重载即为此新增（保留 W 级别同时带堆栈）
- **机器门禁（全仓棘轮预算）**：`checkNoBareCatch`（挂 `preBuild`）扫描双端 `src/main` 全量，未标注的空 catch 数**只允许下降不允许上升**（基线 `bareCatchBudget = 47`，实测 67 处 / 已标注 20 处）。新增一处未标注空 catch 即构建失败；存量随改动顺手收敛后须同步下调基线
- ⚠️ 仍无 `androidTest`；**不建托管 CI**（单作者、唯一发布路径是本地构建，托管 CI 属重复执行），门禁全部落在 Gradle 任务上——见 §12.15

### 12.15 回归测试约束

历史教训：测试全部集中在「最好测的」纯逻辑层，而 `AdbFileManagerClient` / `BluetoothHidManager` / `AdbShellClient.pullFile` / `ToolRiskMap` 这些**历史真出过 bug 的文件一个测试都没有** —— 同类问题（远端 FAIL 被当成功、陈旧 CLSE 被误读、描述符字节漂移）只能靠真机复现才发现。#8（2026-09-12）已补第一批，约定如下：

- **执行位置与命令**：单测必须在**仓库根** `d:\rokidapp` 下跑（`RenewCXRLSample`；`cxrl\RokidLab` 只是模块目录、已不再是独立 Gradle 根）
  ```powershell
  $env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.11.9-hotspot"
  & D:\gradle-8.7\bin\gradle.bat :cxrl:RokidLab:phone-app:testDebugUnitTest :cxrl:RokidLab:RokidLink:testDebugUnitTest --offline
  ```
- **Android 依赖桩**：`phone-app` 已设 `testOptions { unitTests.isReturnDefaultValues = true }` —— 否则 `android.util.Log` 一碰就抛 `RuntimeException("Stub!")`。JSON 用真实实现（`org.json:json:20231013`），不要依赖 android.jar 桩
- **高价值目标（必须锁）**：ADB sync 帧编解码与 `FAIL`/`CLSE` 分支、HID 描述符字节与 `normalize` 语义、`AiChannel` 跨端载荷 v0/v1 矩阵与常量名、`ToolRiskMap` 完整性（`unregisteredTools()` 必须为空，见 §12.10）、聊天历史落盘格式与迁移（`ChatHistoryStore`）
- **改字节必须改测试**：任何改动 HID 描述符字节 / sync 帧格式 / 跨端载荷格式 / 聊天历史落盘格式的提交，**必须同步更新对应测试并真机回归**；不得只按注释里的长度或格式假设行事（#8 实测推翻了 `buildQtiCompatibleDescriptor` 注释声称的「≤64 字节」，实为 67 / 121 字节）
- **行为记录 ≠ 契约**：测试发现生产代码语义不一致时，先以注释登记现象并指向评估文档待办，**不得为了让测试变绿而修改生产行为**。确需变更语义时必须拿到明确授权，并把锁行为的测试一并翻面（先例：`pullFile` 遇 CLSE 返回 true 与 `downloadFile` 返回 false 的分歧，已于 2026-09-13 按用户要求收敛为「按远端字节数对账」，测试同步改为断言 false；见 `CODE_AUDIT.md` C7 与 `ENGINEERING_ASSESSMENT_2026-09-12.md` §[P1-11]）
- **状态复位**：测试必须复位被测单例的全局状态（`@After` 中 `BtHidCompat.setManualMode(null)` / `ApprovalGate.resetForTest()` 等），避免用例间污染
- ⚠️ 仍无 `androidTest`；**不建托管 CI**（#10 评估结论：门禁已挂 `preBuild`、本地构建即触发，托管 CI 属重复执行且需复刻 SDK/NDK 环境）；`KeyButtonService` / `ChatStateHolder`（本体）/ `CxrLHiRokidSession` 仍无测试

### 12.16 聊天历史落盘约束（`ChatStateHolder` / `ChatHistoryStore`）

历史教训：旧实现每条消息都在**主线程**把整个列表重新 JSON 序列化后 `writeText` —— 消息越多越卡（O(n²) 写放大）；且 `clear()` 只写空数组、不删文件。约定：

- **格式与回放逻辑只允许放在 `store/ChatHistoryStore.kt`**（纯 JVM、可单测）：JSONL 每行一条消息，**同 id 后写覆盖先写**（`finalizeLastAi` 靠这条语义只追加一行）。改动该文件的读写语义 → 必须同步改 `ChatHistoryStoreTest`（14 例）
- **`SnapshotStateList` 只在调用线程（Compose 主线程）读写**：任何文件 I/O 一律经 `ChatStateHolder.writer`（单线程 daemon）提交，且**序列化必须在调用线程完成后把字符串交给后台** —— 后台任务不得触碰 `messages`
- **加载不得同步阻塞 `Application.onCreate`**：读盘 / 旧格式迁移 / 压实都在 `writer` 上做，只有"塞进 `messages`"这一步 `post` 回主线程
- **兼容旧格式**：旧版"整份 JSON 数组"的 `chat_history.json` 必须能在首启被识别并迁移为 JSONL，**迁移不丢历史**（测试已锁）。未迁移完不得改变文件名
- **`clear()` 必须删文件**（`rewrite(file, emptyList())`），否则重启后历史复活
- **落盘失败必须落 `LogCollector`**：聊天记录丢失是用户可见问题，不能只写 logcat

### 12.17 手机权限申请约束（全品牌统一）

历史教训（2026-09-18）：`call_phone` / `set_phone_alarm` / `open_phone_app` 在缺权限时**只会回一句"请去设置里开"**，
用户听到的却是"这功能坏了"；而更隐蔽的一类是 Android 10+ 的 **BAL 静默失败** ——
后台 `startActivity` 被系统直接丢弃（不抛异常、不打 error 日志），代码以为成功了，
表现为"AI 说「正在拨打：X」但手机屏幕毫无反应"。根因是 `SYSTEM_ALERT_WINDOW`（AppOps）
在**卸载重装/换签名**后会被清零（debug ↔ release 签名不同，`adb install -r` 覆盖不了）。

- **需要权限的功能一律走 `PermissionBridge.ensure(context, reason, perm...)`**，不得再自写
  "请打开手机设置…" 文案：`ensure` 会把缺失权限**自动拉起系统授权界面**并返回一句如实文案
- **`ensure` 返回非 null 时必须放弃本次实际动作并原样把文案回给模型** —— 否则就是"假成功"
- **权限登记唯一入口是 `permission/AppPermission`**：新增需要权限的工具 → 先在枚举里登记，
  再在 `MainActivity.startupPermissionOrder` 补上启动期自检（两条都做，缺一不可）
- **`OVERLAY` 只能跳设置页**（`requestPermissions` 对它不弹窗），走 `ManufacturerUtils.openOverlaySettings`
- **"能不能从后台拉起界面"必须用 `PermissionBridge.canLaunchUi()` 判定**，不能靠 try/catch：
  BAL 拦截既不抛异常也不返回失败。判定依据 = 前台（`AppForegroundTracker`）或持有悬浮窗
- ⚠️ **禁止用 `isChineseRom()` 过滤 BAL / 权限判定**：BAL 限制与 AppOps 清零在 Pixel / 三星上完全一样
  （历史遗留：`checkOverlayPermissionForMirror` 曾对非国产 ROM 直接放行）
- ⚠️ **设置页跳转链必须"AOSP 契约优先、厂商私有页兜底"**：厂商 Action / 组件名在 ROM 版本间**不是稳定契约**，
  猜错时 `ActivityNotFoundException` 会被静默吞掉，用户看到"点了没反应"。候选链末尾固定兜底到
  `ACTION_APPLICATION_DETAILS_SETTINGS`（AOSP 必有）
- **`PermissionRequestActivity` 必须 `exported=false` + `noHistory` + `excludeFromRecents`**：
  它只是授权通道，且不应暴露给外部应用触发弹窗
