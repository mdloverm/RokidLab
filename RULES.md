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
- **`ALLOWED_DOMAINS` 基线是全部域开放**（用户 2026-09-09 拍板），收窄/放宽都只改这一处。
  ⚠️ **2026-09-22 起摘除 `shell` 域**（`DOMAIN_ALL - DOMAIN_SHELL`）：本机执行域里是
  `run_shell`（任意命令执行）与 `install_packages`（任意装包，2026-09-23 新增），
  二者都是**能力原语**，页面（对话生成／商店导入的 `.aix`）属第三方制品 ——
  开放它等于把"在用户手机上跑任意命令/装任意软件"交给页面作者。对话路径保留该能力。
  **要放开就删掉 `PageScope.ALLOWED_DOMAINS` 上那一个减法**（一处，`ApprovalGateTest.H6` 会跟着失败）。
  之所以走域而不走黑名单：域是装配单位，摘掉后页面拿到的**工具清单**里也不会有它。
  ⚠️ 同域的 `save_script` / `list_scripts` / `run_script` / `delete_script` 一并被摘除 ——
  这是有意的：脚本库只是"把命令存下来再跑"，留着它等于给页面留一条任意命令执行的侧门。
- **`DENY_TOOLS` 只放技术故障工具**（如 `open_aiui_app` 自指递归），**不放"危险"工具**，安全边界由 `isEnabled` 总开关负责；按"危险"收窄要落在**域**白名单上（见上一条），不加进这张表
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
- **会话记忆开关必须是纯开关**：关闭只停用（不注入、不记录），**不得顺带销毁数据**；销毁只能由用户显式触发
- **清空的作用域必须与文案一致**：`clear()` = 当前对话（记录＋记忆）；`clearAll()` = **全部对话**的记忆且不动聊天记录。设置页按钮写"全部"就不许只清当前会话
- **不得恢复"空闲自动过期"**：记忆随对话持久保存。原先 `maybeExpire`（10 分钟无活动清空）与"记录永久保存"的用户心智相抵，且静默发生（界面零提示）

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
4. **动态工具集（外部 MCP）不走上面三步**：它由 `ai/mcp/McpRegistry` 在运行期注册，
   经 `ToolRegistry.setDynamicProviders()` 注入 `McpToolProvider`（该 provider 的两个 getter
   必须动态读 `McpRegistry`，写成属性初始化就会固化成快照、工具永远进不了派生表且不报错）。
   ⚠️ 动态工具的安全是**两层**（2026-09-23 起第二层才真正生效）：
   **①闸门**：未信任的 MCP 工具声明 `confirmPolicy = BLOCK`（由 `risk = EXTERNAL` 派生，
   `McpRegistry.toEntry` 故意不显式覆写），问不到用户即**拒绝**并给出出路（连眼镜确认 /
   把该 server 标为信任）—— 见 §12.20；已信任的降为 `LOCAL` 档，`RiskApprovalGuard`
   根本不产生 Ask。**②准入**（仍是第一道防线 —— 闸门只在"该问"时介入，它管不了"不该出现"）：
   「地址必须 https（**唯一例外＝回环 http**，判据＝两份 `network_security_config` 白名单的交集，
   故 debug/release 行为一致；**不要**改成按 `BuildConfig.DEBUG` 放开——那是「调试能跑、发布才挂」）」+
   「工具首次出现时显式写 false（`ToolRegistry.ensureDisabledByDefault`）」+
   「schema 不合规的工具整个丢弃」（一个坏 schema 会让**整轮请求 400** ⇒ 所有工具一起失效）。
   ⚠️ 地址判定只有一处 `McpRegistry.isUrlAllowed()`，UI 必须复用，别自己写 `startsWith`（会分叉）

> 现状说明（2026-09-19 更新）：判定已全部收敛到 `ai/approval/ApprovalGate`（`ToolPolicy.kt` 已删除）。
> **「问不到用户时放行还是拒绝」在 2026-09-23 改成了工具的自声明字段**（`ToolEntry.confirmPolicy`
> → `ToolConfirmPolicy`，只有 `PROCEED` / `BLOCK` 两个值），闸门读声明、**不再猜域**：
>  - `BLOCK`（越出本机边界、不可撤销：`send_sms`、**未信任的 MCP 工具**）→ 问不到就**拒绝**
>    并给出可操作出路（`ApprovalGate.denyUnconfirmed`，按 `ToolRegistry.isMcpTool` 名字前缀分流）；
>  - `PROCEED`（影响不出本机、可重做：删文件 / 删脚本 / 容器装包）→ 问不到就**放行**
>    （硬拒会让功能表现为"被安全策略挡住"，正是 2026-09-11 打不出电话那次事故的方向）。
>  - 缺省值按风险档派生：`EXTERNAL_SIDE_EFFECT` ⇒ `BLOCK`，其余 ⇒ `PROCEED`。
> ⚠️ **只有用户显式取消**（`wasCancelled()`）在两条分支上都拒绝 —— 那是唯一真正的"用户说不"。
> ⚠️ 未知名（模型幻觉）的 `confirmPolicy` 兜底也是 `BLOCK`（"未知即最保守"），但它会先被
> `UnknownToolGuard` 单调拒绝，因此不会真的拖用户进一次注定超时的确认。<br>
> 现状更新（2026-09-23）：**走确认闸门的内置工具恰为四个** —— `delete_file`、`delete_script`、
> `install_packages`、`send_sms`（`ApprovalGateTest` 的 **E1** 钉住这份名单；E3b / E3c 钉住
> 前三个的确认摘要要读出被操作对象）。**新增 EXTERNAL 档内置工具时必须同步 E1 + 补一条摘要用例**，
> 否则 E1 会失败 —— 那正是提醒信号。前三个的 `confirmPolicy` 是显式 `PROCEED`（本机可重做），
> 这一点会被 `check_tool_wiring.py` 作为**提示**列出来（它有 `EXTERNAL_FAIL_OPEN_ALLOWLIST` 豁免表，
> 每加一个都必须写明理由）。
>
> ⚠️ **MCP 外部工具：闸门现在真的会拦**（2026-09-23 起）。`McpRegistry.toEntry` 把未信任的第三方工具
> 登记为 `EXTERNAL_SIDE_EFFECT` ⇒ 派生 `confirmPolicy = BLOCK` ⇒ 无通道时**拒绝**；
> 用户显式标了「信任」才降为 `LOCAL_SIDE_EFFECT`（此时不产生 Ask，也不再需要逐次确认）。
> 改造前这里写的是"闸门 fail-open ⇒ 无通道照样放行"，**那句话已经失效**。
> 但**准入仍是第一道防线**（见 12.10 第 4 条）—— 闸门只管"该不该问"，管不了"这个工具该不该存在"。
> `check_tool_wiring.py` 的「README 工具清单」**不含**动态 provider 的工具（运行期才有）。

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
- ⚠️ 仍无 `androidTest`；**不建托管 CI**（#10 评估结论：门禁已挂 `preBuild`、本地构建即触发，托管 CI 属重复执行且需复刻 SDK/NDK 环境）；`KeyButtonService` / `CxrLHiRokidSession` 仍无测试。
  ⚠️ `ChatStateHolder` **本体可测**（2026-09-20 起，见 `ChatStateHolderTraceTest`）：`unitTests.isReturnDefaultValues = true` ⇒ `Looper.myLooper()` 与 `getMainLooper()` 同为 null，`runOnMain` 走内联分支；`appContext` 为 null ⇒ 落盘静默跳过。写这类测试时**必须**在 `@Before`/`@After` 清 `messages` 并 `finishTrace()` 释放锚点（单例跨用例复用）

### 12.16 聊天历史落盘约束（`ChatStateHolder` / `ChatHistoryStore`）

历史教训：旧实现每条消息都在**主线程**把整个列表重新 JSON 序列化后 `writeText` —— 消息越多越卡（O(n²) 写放大）；且 `clear()` 只写空数组、不删文件。约定：

- **格式与回放逻辑只允许放在 `store/ChatHistoryStore.kt`**（纯 JVM、可单测）：JSONL 每行一条消息，**同 id 后写覆盖先写**（`finalizeLastAi` 靠这条语义只追加一行）。改动该文件的读写语义 → 必须同步改 `ChatHistoryStoreTest`（24 例）
- **`SnapshotStateList` 只在调用线程（Compose 主线程）读写**：任何文件 I/O 一律经 `ChatStateHolder.writer`（单线程 daemon）提交，且**序列化必须在调用线程完成后把字符串交给后台** —— 后台任务不得触碰 `messages`
- **加载不得同步阻塞 `Application.onCreate`**：读盘 / 旧格式迁移 / 压实都在 `writer` 上做，只有"塞进 `messages`"这一步 `post` 回主线程
- **兼容旧格式**：旧版"整份 JSON 数组"的 `chat_history.json` 必须能在首启被识别并迁移为 JSONL，**迁移不丢历史**（测试已锁）。未迁移完不得改变文件名
- **`clear()` 必须删文件**（`rewrite(file, emptyList())`），否则重启后历史复活
- ⚠️ **一轮的「过程」落在哪条消息上，只由 `ChatStateHolder` 内部的 `traceAnchorId` 锚点决定，不看位置**。
  历史教训（2026-09-20 真机 bug「图片已经显示出来了，过程里还在思考」）：原先按"列表末尾那条非用户
  非状态消息"定位，而**位置不是身份** —— 一轮进行中只要**别的**消息被追加，"末尾"就换人，同一个
  `tool:<call_id>` 的 `RUNNING` 留在旧气泡、`OK/FAILED` 写进新气泡；而 `AgentStep` 的「同 key 覆盖」
  只在单条消息内生效 ⇒ 覆盖失效，旧气泡**永久转圈**。两条**互相独立**的插入路径都踩过（与调用了
  哪个工具无关）：① `show_image` 的图片气泡；② 拍照流程的状态气泡（`onStage` → `add` /
  `onStageText` → `updateLastStatus` 在末尾不是状态气泡时会**新增**一条）。
  ⇒ 以后新增"轮中途插消息"的能力不必再动过程代码；但**禁止**把 `upsertTrace` /
  `finalizeTraceReply` / `finalizeLastAi` 的落点改回"取末尾那条"（回归见 `ChatStateHolderTraceTest`）
- ⚠️ **终态收尾只结清锚点那一条**（`ChatStateHolder.finishTrace`）。**禁止**改成"扫全表、见到
  `RUNNING` 就统统标 OK"——那是拿兜底盖症状，会把"这里为什么会有残留"一起抹掉。锚点制让残留
  **在结构上不再产生**，所以收尾不需要兜底；历史消息里若真有残留，它应该被看见、被查
- ⚠️ **`ChatHistoryStore.parse` 里的 `RUNNING` 归一 = 崩溃修复，不是本 bug 的解法**：正常收尾必写
  终态，所以盘上还留着 `RUNNING` 只可能是那一轮被杀进程（`addImage` 会在轮中途带着 `RUNNING`
  落盘，之后才被终态覆盖）。**不要**因为它在此处兜住就省掉收尾
- **落盘失败必须落 `LogCollector`**：聊天记录丢失是用户可见问题，不能只写 logcat
- ⚠️ **「过程」卡片显示给用户的名字 ≠ 落盘的标识符**（2026-09-20）：
  `AgentStep.title` 对工具步骤存的是模型的 **wire name**（`get_current_time` /
  `mcp__<serverId>__<工具名>`），**禁止把显示名写进去** —— 那会污染历史数据（改名/换语言后旧消息
  不跟着变），也断掉与日志的对应关系。面向用户的名字由 UI 层在**渲染时查表**：
  `ChatBubble.stepText()` → `ToolRegistry.displayNameOf()`（＝工具设置页同一套名字：静态工具取
  `ai_tool_*_name`，MCP 取 `"服务器名 · 原始工具名"`），查不到**原样回退**。
  ⇒ 旧历史消息无需迁移即自动变成友好名；代价是界面名与日志名不一致，要原始标识符排查请走
  `SessionTraceDialog`。⚠️ 取值口**只有 `ToolRegistry.displayNameOf` 一处**，UI 别自己拼
  `dynamicName ?: getString(...)`（漏一处就会显示成无关资源名且不报错）
- ⚠️ **思考行的两态文案由 UI 本地化，服务层只发语义**：`AgentStep.thinking()` 的 `title` 恒为空串
  ⇒ 文案落在 `ChatBubble.stepLabel()`（`chat_trace_thinking`「正在思考」/ `chat_trace_thought`
  「思考完毕」）。工具行**不吃**这套文案：`stepLabel` 里 `Kind.TOOL` 那一支是**防御性兜底**
  （工具 title 按约定恒非空，正常不可达），保留它的理由是"服务层漏填名字"时不能掉进思考文案里

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

### 12.18 AI 对话能力面约束（文件 / 看画面 / 技能安装）

2026-09-20 补齐的三块能力，各自带一条**硬边界**，不要为了"能力更强"而放宽：

**① 文件工作区（`ai/FileWorkspace.kt`）**
- ⚠️ **`MANAGE_EXTERNAL_STORAGE` 已声明**（`AndroidManifest.xml` + `AppPermission.ALL_FILES`），但它的用途
  **只有一处**：proot 容器把 `Download/Lab` 通过**内核 `write()` 直连**绑定成 `/mnt/lab`（绕开 MediaProvider，
  见 D20/D23）。**这不等于**文件工作区获得了全盘读写权 —— 见下。
- 文件工作区仍只开放两个边界：`project`（`filesDir/aiui_projects/<项目>/`
  私有镜像，**全能力**：列/读/搜/改/删/移）与 `downloads`（系统下载目录经 MediaStore，**只可列/读/删**）。
- **禁止**对外承诺"能读任意文件"，也**禁止**在 schema 描述里暗示这一点 —— 边界必须写进各工具 schema，
  否则模型会按"能读全盘"的直觉乱猜路径。声明的权限是**给容器通道用的**，不是给工具放开边界的理由。
- 所有相对路径必须经 `splitRel()` 拒绝 `..` / `.` / 空段 / 绝对路径，并用 `canonicalPath` 前缀比对防越界。
- **删除**（`delete_file`）走确认闸门（见 12.10；走闸门的内置工具现为四个：`delete_file` /
  `delete_script` / `install_packages` / `send_sms`）；`edit` / `move` 不弹确认。
  `path` 留空 + 传 `project` = 删除**整个项目**（含下载目录公开副本）—— 该组合必须靠确认闸门拦住。

**② 看画面（`look_at_view`，`vision` 域）**
- 走**眼镜相机**（`PhotoQuizService.takeGlassesPhoto` → CXRLink），**不申请也不需要手机 `CAMERA` 权限**。
- 工具**不直接发起模型请求**：视觉路径只把 base64 暂存 `VisionToolProvider.pendingImage`（`ThreadLocal`，
  因工具并发执行），由**对话主循环**在紧随其后补一条带 `image_url` 的 user 消息。
  ⚠️ **禁止**改成在工具内回调 `sendAiTextMessage` —— `aiSendLock` **非重入**，会自锁。
- ⚠️ 出图门槛与「拍照问 AI」**刻意不同**：拍照答题"未知也先试"（失败有 OCR 兜底），
  本工具只在**确认 `supportsImage == true`** 且用户开了「图像理解」时才走视觉路径
  —— 工具回调没有二次机会，图一旦进了请求体，服务端 400 就是**整轮失败**。
- ⚠️ 取图失败/眼镜离线时的文案必须**如实**说"需要连接眼镜"，**绝对不要**说成「没有相机权限」
  —— 那是虚构归因，会误导用户去改没用的设置（这正是本条约束的由来）。

**③ 技能安装（`install_skill` / `list_skills` / `delete_skill`，伪工具）**
- 这三个走**伪工具**通道（`ai/approval/PseudoTools`）：schema 拼在 `AiConversationService.buildTools()`，
  执行在 `runTool()` 的 `when` 分支，**不写进 `ToolRegistry.toolList`**。
- ⚠️ **必须登记进 `PseudoTools.BY_NAME`**：漏登记 ⇒ `ToolRiskMap` 兜底成 `EXTERNAL_SIDE_EFFECT`
  ⇒ `UnknownToolGuard` 视作已知但风险档错 → 被要求眼镜端确认，通道不可用就整条链失败。
- ⚠️ 风险档取 `LOCAL_SIDE_EFFECT`（`list_skills` 为 `READ_ONLY`）：**刻意不设 `EXTERNAL_SIDE_EFFECT`**
  —— 那是纯本机文件操作，让用户在眼镜上确认"删手机里的技能"既无意义又容易超时。
- ⚠️ 伪工具总数由 `ApprovalGateTest` 的 **B2** 钉住（现为 7 个），新增/删除必须同步那条断言。

### 12.19 外部文本一律判编码，不许硬解 UTF-8

2026-09-20 真机 bug 的由来：知识库导入对字节流**无条件按 UTF-8 解码**。中文用户从 Windows
记事本/导出工具拿到的 txt 常是 `ANSI(GBK)` 或 `Unicode(UTF-16LE)`，硬解后整篇变成 U+FFFD
（实测 59 字里 45 个替换符），而**文件名与字节数照旧正确** ⇒ 界面完全看不出异常。
症状于是是"文档明明导入成功了，问里面的内容永远答不出来"，**检索侧完全无辜**（它只是没东西可命中）。

- 统一入口 `ai/TextEncoding.kt`（`decide()` / `decode()`）。判定顺序：BOM → 无 BOM 的 UTF-16
  （NUL 密度 + 奇偶位）→ 严格 UTF-8 试解 → `GB18030` 兜底。
- ⚠️ **凡是"读用户给的文本"的地方都必须走它**（知识库导入、`FileWorkspace` 读文件、任何新导入链路）。
  硬编码 `Charsets.UTF_8` 读用户文件 = 把乱码当内容"如实"转述给用户，比直接报错更糟。
- ⚠️ 严格 UTF-8 试解必须容忍**样本尾部被切断**的多字节字符（流式样本 64KB 边界必然切断）：
  不容忍就会把正常 UTF-8 文件误判成 GB ⇒ 把本来好的文档弄成乱码，是**比不判定更糟的反向破坏**。
  同理，**无 BOM 的 UTF-16 必须先于 UTF-8 判定**（`0x00` 是合法 UTF-8 单字节）。
- ⚠️ **`SQLiteOpenHelper.onUpgrade` 禁止 drop 重建**：知识库/记忆库存的是用户资料，
  版本一升就清空属于不可恢复的数据丢失。新增列一律 `ALTER TABLE … ADD COLUMN`。
- ⚠️ 检索的两段口径必须一致：df/候选来自 SQLite `LIKE`（对 ASCII **不区分大小写**），
  打分若用区分大小写的 `indexOf`，英文查询会出现"候选块查得到、得分全是 0"的**假空结果**。

### 12.20 确认闸门的降级策略与通道约束（2026-09-23）

「问不到用户时放行还是拒绝」原本是**硬编码**的（`failClosedConfirmation` 写死 `domain == MCP`），
后果是 `send_sms`（**真的会把短信发出去**）与 `call_phone`（只开拨号盘）共享同一条静默放行路径 ——
两者的代价根本不是一个量级。现在它跟风险档一样是工具的**自声明字段**。

- 声明面 = `ToolEntry.confirmPolicy`（`ToolConfirmPolicy`，**只有 `PROCEED` / `BLOCK` 两个值**）。
  缺省按风险档派生：`EXTERNAL_SIDE_EFFECT` ⇒ `BLOCK`，其余 ⇒ `PROCEED`。
  刻意不加第三个值（曾想加"工具自己会降级"的 `SAFE_DEGRADE`）：处置与 `PROCEED` 完全相同，
  会变成一个没人读的字段。消费者的分支只有"放行/拒绝"两种。
- `BLOCK` 的拒绝文案是 `ApprovalGate.denyUnconfirmed`，**必须给出可操作出路**，
  且 MCP / 内置两条分支的判据是 `ToolRegistry.isMcpTool(name)`（**名字前缀**，产地
  `ToolRegistry.MCP_TOOL_PREFIX`），**不是**查 `domainOfOrNull` —— MCP 工具是**动态**注册的，
  server 断开或单测环境里查表得到 null，会让文案误落到内置分支（用户看到"去打开乐奇实验室"，
  而正确出路是"把该 server 标为信任"）。
- ⚠️ **显式给 EXTERNAL 档工具写 `PROCEED` 是一个"越界也照做"的洞**，`check_tool_wiring.py`
  会把它报成**错误**。确属有意（本机可重做：删文件 / 删脚本 / 容器装包）必须登记进脚本的
  `EXTERNAL_FAIL_OPEN_ALLOWLIST` 并写明理由。
- **确认通道有两条，取值顺序＝眼镜优先、手机兜底**（`ApprovalGate.activeChannel()`）：
  1. `GlassToolConfirmChannel`（`confirmationResolver`）—— 既有主路径，要求眼镜在线；
  2. `PhoneToolConfirmChannel`（`phoneConfirmationResolver`，`LabApplication.onCreate` 自注册）
     —— 补的是「本机模式 = 不连眼镜」与「眼镜通道要求在线」在**定义上互斥**这个洞。
  手机通道**不替换**眼镜通道：眼镜在线时行为与改造前完全一致（`ApprovalGateTest` F9 钉住）。
- ⚠️ **通道不可用时必须如实返回 false，绝不假装问过**。`confirm()` 返回 true 的含义是
  "用户同意了"；未注入 Context 就返回 true，会让一个需要点头的动作被**静默执行**，
  而用户什么都没看到 —— 那比"拒绝"坏得多。`PhoneToolConfirmChannel` 在未 `init` 时
  `isAvailable()` / `confirm()` 都必须为 false。
- ⚠️ `ConfirmResolver.channelId` **刻意不给默认值**：排查确认链路的第一个问题是"问到谁了"，
  漏声明会让日志里出现无法归因的空标识 —— 宁可编译错（新增实现类时要一起补，含测试假实现）。

### 12.21 本机执行（proot 容器）的三条边界（2026-09-23）

**① 容器出网不经过任何 URL 级闸门 —— 如实声明，不做半吊子拦截**

proot 容器里的命令（`curl` / `wget` / `apt` / `pip` …）以 **App 自己的 uid 直接开 socket**：
既不经过 `ai/NetGuard`（那只作用于 Java 层的 `HttpURLConnection`），也不经过
`res/xml/network_security_config.xml`（那是 `NetworkSecurityPolicy` 的事）。
⇒ 容器**能**访问内网与回环地址（眼镜 WebServer、蓝牙隧道本地端口、本机 Ollama…）。

- ⚠️ **不要试图用 `LD_PRELOAD` 垫片或改 `/etc/hosts` 去堵**：只能挡住"按域名访问"、挡不住直连 IP，
  还会制造"以为堵住了"的错觉。真正的隔离要靠 netns/iptables，那**需要 root —— 本 App 没有**。
- 因此容器的能力约束**只能落在工具的声明上**：`run_shell` / `run_script` 是 `LOCAL_SIDE_EFFECT`
  （**不产生 `Ask`**），`install_packages` 是 `EXTERNAL_SIDE_EFFECT` + `confirmPolicy = PROCEED`。
  要让容器执行也受确认约束，改的是**这些工具的 `risk` / `confirmPolicy`**，不是 NetGuard。
- 输出侧已有隔离：`run_shell` / `run_script` 声明 `contentTrust = UNTRUSTED_EXTERNAL`
  （容器输出可能夹带外部内容，进上下文前按不可信处理）。

**② 容器写出的文件 MediaStore 看不见 ⇒ 必须重新登记**

容器把 `Download/Lab` 经**内核 `write()`** 直连绑定成 `/mnt/lab`（绕开 MediaProvider），
而**文件工作区的 `downloads` 边界走的是 MediaStore** ⇒ 两套视图分叉：
文件明明在磁盘上，`list_files` 却说"没有"。

- 因此 `run_shell` / `run_script` 执行完必须调 `ai/LabMediaScan.scanLabOutputs(context)`
  把 `Download/Lab` 重新扫描入媒体库（`MediaScannerConnection.scanFile`，最多 300 个文件，**永不抛异常**）。
  `ProotShell.shareStatus.publicDownload == false`（私有目录）时直接返回 —— 私有目录本来就不在媒体库。
- `download_file` 不传 `folder` 时**默认落 `Download/Lab`**，与容器 `/mnt/lab` 是**同一个物理目录**。
  默认值取 `ProotShell.SHARE_DIR_NAME`，**禁止**在别处再写一份目录名字面量。

**③ 并发装包必须独占 rootfs**

`ProotShell` 的执行路径取**读锁** + `Semaphore(2)`；`install_packages` 走
`runScript(exclusive = true)` 取**写锁**。少这一条，两条并发 `apt install` 会互相看到半写的 dpkg 状态。

**④ 按需组件（Python / Node.js / Git）—— 两条安装路径 + 一个固定挂载点**

设置页「本机执行环境」的三个可选组件（`settings/LocalExecScreen.kt`），安装方式刻意不同：

| 组件 | 安装方式 | 理由 |
|---|---|---|
| Python 3 | guest `apt`（`python3` + `ca-certificates`） | 顺带装上证书 ⇒ 之后 https 源可用（解开"首次 apt 只能走 http"的鸡生蛋） |
| Git | guest `apt`（`git` + **`ca-certificates`**） | 它没有官方静态二进制，手动凑 deb 等于自己重写一遍依赖解析 |
| Node.js（含 npm/npx） | 官方自包含包下载（`NodeAddon`） | 见下 |

- ⚠️ **装 git 不带 `ca-certificates` 是坏的**：`installPackages` 走 `--no-install-recommends`，
  而证书包在 Ubuntu 24.04 里只是 git 的 **Recommends** ⇒ 单独装 git 后 `git clone https://…`
  必然证书校验失败。走 `install_packages` 工具的**模型路径同理**，故该工具的 schema 里已写明要一起装。
- **Node.js 为什么不走 apt**（三条硬理由）：① 源里是已 EOL 的版本；② `npm` 在 universe 源，
  还得先让用户开源、多一轮等待；③ apt 装进 rootfs ⇒ **重装环境就一起没了**。
- **附加组件落在 rootfs 之外**：宿主 `files/proot/extras/` ↔ guest `/opt/extras`
  （`ProotShell.extrasDir` / `nodeDir`，挂载见 `ensureExtrasBind`），与 rootfs 平级 ⇒ 重装 rootfs 不丢组件。
  `guestExtraPath` 只在 `bin/node` **在位**时把 `/opt/extras/node/bin` 追加进 guest `PATH`
  —— 判据是**产物在位**，不是"某个安装流程跑完了"，这样"命令能不能用"只有一处产地。
- **就绪判定与 rootfs 同一套口径**：产物在位（`bin/node`）+ 版本标记里的版本 == 代码常量，**缺一不可**。
  只看产物会把「解压到一半被杀」判成"已装好"，而重装入口只在未就绪分支里 ⇒ 用户**永远修不好**。
- ⚠️ **版本号与 sha256 必须同改**（`NodeAddon.VERSION` / `SHA256`，rootfs 同理）：只改版本必然校验失败。
  镜像必须与官方**同字节**才共用同一个 sha256（已实测 node 三个镜像 `Content-Length` 全为 57,824,078）。
- ⚠️ **Node 归档必须 `.tar.gz`**：设备自带 toybox `tar` 不支持 `J`（去调不存在的 `xz`），用 `.tar.xz`
  就得先进 guest 用 GNU tar 解 ⇒ 凭空多一条"rootfs 必须先能用"的前置依赖。代价是 58 MB 而非 27 MB。
- **就位是原子的**：解到同级 `.staging` → 验 `bin/node` → 整体 `rename`；半截目录**不**参与判定。
- 组件目录随「删除执行环境」一起走（`ProotInstaller.uninstall` 直接 `rm -rf files/proot/`），不单独留卸载入口。
- **新增一个可执行组件的清单（缺一条视为未完成）**：
  ① 落地位置（默认 extras，要放 rootfs 内须写明理由）；② `LocalExecScreen` 的卡片；
  ③ `readStatus` 那次 `probeCommands` 的探测命令（**三个组件合成一条**，别各起一次 proot）；
  ④ 中英 strings（`local_exec_<组件>_*`）；⑤ `run_shell` schema 里"可用组件"那句话
  （模型据此判断该自己 `install_packages` 还是指引用户去设置页）。
