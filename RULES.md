# RokidLab 项目开发规范

## 一、项目架构

### 包结构

```
com.rokidlab.phone
├── adb/               # ADB 调试工具（核心功能模块）
│   ├── *.kt           # 业务逻辑类（AdbShellClient, AdbFileManagerClient 等）
│   └── ui/            # UI 层（AdbToolsScreen, TimerDialog, ShellDialog 等）
├── app/               # 应用入口（LabApplication, MainActivity）
├── design/            # 设计系统组件库（DesignComponents, StoreTheme）
├── filemanager/       # 文件管理
├── glasses/           # 眼镜连接与授权
├── hid/               # 蓝牙手柄
├── mirror/            # 投屏（手机投屏 + 屏幕镜像）
├── model/             # 数据模型
├── network/           # 网络请求（下载、图标加载）
├── settings/          # 设置页面
├── store/             # 应用商店主页面
└── util/              # 工具类（AppConfig, LocalizationManager）
```

### 分层规则

- **业务逻辑层**（feature 根包）：存放数据模型、网络请求、设备通信等非 UI 代码
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

修改代码后按以下顺序验证：

1. **代码审查**：对照 9.1~9.5 检查清单逐项确认
2. **编译验证**：运行 `./gradlew :phone-app:assembleDebug --no-daemon` 检查编译
3. **日志检查**：确认 build 输出无 `ERROR`，warning 可接受
4. **功能验证**：在有条件的情况下连接真机测试 ADB 功能

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
   ./gradlew :cxrl:RokidLab:phone-app:assembleRelease
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
| Gitee API token | `f79578621ec9da315fa31a80b6c8da8c`（token，存于 RULES.md 仅供 API 操作参考） |
| Gitee 推送认证 | `git remote set-url origin https://dlover1314:{TOKEN}@gitee.com/dlover1314/RokidLab`（临时，推送完恢复为不需要 token 的 URL） |

## 十一、部署规范

### RokidLink 安装规则

1. **禁止直接通过 adb install 将 RokidLink APK 安装到手机**
   - RokidLink 是眼镜端应用，不是手机端应用
   - RokidLink APK 已自动打包进 phone-app 的 `src/main/assets/RokidLink.apk`

2. **部署流程**
   - 只需安装 `phone-app/build/outputs/apk/debug/RokidLab-v1.0.0-debug.apk`
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
