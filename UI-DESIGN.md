# RokidLab UI Design Reference

## 一、全局设计系统

### 1.1 设计风格

双主题配色系统，可在设置页面随时切换：

**丝绒炭黑（Velvet Dark）** — 画廊暗室 × 油画颜料（默认主题）
- 底画布：温暖的丝绒暗色（`#0B0B0E`），绝非纯黑
- 配色理念：每个颜色都从油画色板取色，带温度和深度
- 克制而有质感的对比，不使用纯三原色

**冰蓝冰川（Cool Blue）** — 北极冰川 × 浅蓝天光
- 底画布：天光白蓝（`#F0F5FF`），清新明亮
- 配色理念：浅蓝基底 + 高饱和度撞色点缀

**共同规范：**
- 全站统一 12dp 圆角，柔和而不失几何感
- 统一 1dp 边框宽度
- 无硬阴影、无装饰性底纹线条
- 以留白和色彩本身构成画面

### 1.2 配色方案

配色接口 `BrewColors` 定义 **14 色**，新增主题只需实现此接口：

```
┌──────────────────────────────────────────────────────────────────────┐
│  底色系统 — 4 色                                                      │
├────────────┬─────────────────────────┬───────────────────────────────┤
│  属性         │  Velvet Dark           │  Cool Blue                     │
├────────────┼─────────────────────────┼───────────────────────────────┤
│  bg         │  #0B0B0E   丝绒炭黑       │  #F0F5FF   天光白蓝              │
│  panel      │  #151518   暗灰板         │  #E6EEFA   浅蓝灰板              │
│  panelAlt   │  #1C1C21   亮灰板         │  #DCE5F5   中蓝灰板              │
│  panelHi    │  #24242A   高亮面板       │  #CCD8EE   冰蓝高亮              │
├────────────┼─────────────────────────┼───────────────────────────────┤
│  文字系统 — 4 色                                                      │
├────────────┼─────────────────────────┼───────────────────────────────┤
│  textBright │  #F2EFEA   暖羊皮白       │  #1A2332   深蓝黑                 │
│  text       │  #D4D0CA   沙石灰         │  #3D4F6A   靛蓝灰                 │
│  muted      │  #8A8780   风化石         │  #6B7BA0   雾蓝灰                 │
│  dim        │  #5C5952   深石色         │  #9AABCA   淡蓝灰                 │
├────────────┼─────────────────────────┼───────────────────────────────┤
│  五模块五色 — 撞色方案                                                │
├────────────┼─────────────────────────┼───────────────────────────────┤
│  store      │  #E85D3F   珊瑚红         │  #E85D3F   珊瑚红 (撞色)          │
│  mirror     │  #5B8FB9   静谧蓝         │  #00B894   翡翠绿                 │
│  projection │  #D4A85C   画廊金         │  #6C5CE7   明媚紫                 │
│  fileManager│  #A78BFA   雾紫           │  #F39C12   琥珀金                 │
│  settings   │  #7D7A70   暖灰褐         │  #5A7BA0   钢灰蓝                 │
├────────────┼─────────────────────────┼───────────────────────────────┤
│  边框 — 1 色                                                         │
├────────────┼─────────────────────────┼───────────────────────────────┤
│  border     │  #2C2C33                │  #C8D4E8                       │
└────────────┴─────────────────────────┴───────────────────────────────┘
```

**功能色映射：** 功能语义色复用模块色，避免色值冗余。

| 全局别名 | 映射到 | 语义 |
|---------|--------|------|
| `BrewRed` | → `store` | 错误/停止 |
| `BrewSuccess` | → `mirror` | 成功 |
| `BrewWarning` | → `fileManager` | 警告 |
| `BrewInfo` | → `settings` | 信息 |
| `BrewGreenDim` | → `panelHi` | 次要成功 |

**全局颜色别名（代码中使用，自动感知当前主题）：**

| 别名 | 来源属性 | 说明 |
|------|---------|------|
| `BrewBg` | bg | 主背景 |
| `BrewPanel` | panel | 面板底色 |
| `BrewPanelAlt` | panelAlt | 次要面板 |
| `BrewPanelHi` | panelHi | 高亮面板 |
| `BrewTextBright` | textBright | 正文/标题 |
| `BrewText` | text | 次要文字 |
| `BrewMuted` | muted | 辅助文字 |
| `BrewDim` | dim | 禁用文字 |
| `BrewBorder` | border | 边框 |
| `BrewCoral` | store | 商店 |
| `BrewCyan` | mirror | 屏幕镜像 |
| `BrewPurple` | projection | 手机投屏 |
| `BrewAmber` | fileManager | 文件管理 |
| `BrewMagenta` | settings | 设置 |
| `BrewRed` | → store | 错误 |
| `BrewSuccess` | → mirror | 成功 |
| `BrewWarning` | → fileManager | 警告 |
| `BrewInfo` | → settings | 信息 |

> 加新主题：在 `theme/` 下新建 `XxxColors.kt` 实现 `BrewColors` 接口，在 `BrewThemeManager.switchTheme` 中添加映射即可。

### 1.3 字体系统

| 层级 | 字体 | 字重 | 大小 | 颜色 | 用途 |
|------|------|------|------|------|------|
| H0 | JetBrains Mono | Bold(700) | 36sp | BrewCoral+Cyan | Store 页面大标题 "Rokid Lab" |
| H1 | JetBrains Mono | Black(900) | 32sp | 模块色 | 模块标题 (ModuleHeader) |
| H2 | JetBrains Mono | Bold(700) | 24sp | 模块色 | 精选标题 / 开发者名 |
| H3 | JetBrains Mono | Bold(700) | 20sp | 状态色 | 退出对话框标题 |
| Body | JetBrains Mono | Regular(400) | 14sp | BrewText | 正文 |
| Body-S | JetBrains Mono | Regular(400) | 12sp | BrewMuted | 副文本 / 使用说明 |
| Caption | JetBrains Mono | Bold(700) | 10sp | BrewMuted | 标签/大写 (letterSpacing 3sp) |
| Meta | JetBrains Mono | SemiBold(600) | 13sp | 模块色 | 分类标签 / 按钮 (letterSpacing 1sp) |
| Button | JetBrains Mono | SemiBold(600) | 14sp | 模块色 | BrutalButton |

- **JetBrains Mono** 等宽字体，全站唯一字体
- **TabularNumbersStyle**：`fontFeatureSettings = "tnum"`，版本号等数字列对齐
- 全局 `fontScale` 最大 **1.0x**（`coerceAtMost(1.0f)`）

### 1.4 统一规范

| 属性 | 值 |
|------|-----|
| 圆角系统 | **BrewShapeSmall(4dp)** / **BrewShapeMedium(8dp)** / **BrewShapeStandard(12dp)** / **BrewShapeLarge(16dp)** / **BrewShapeXLarge(20dp)** |
| 边框宽度 | **1dp**（全站统一） |
| 模块间距 | 24dp |
| 组件间距 | 16dp |
| 小间距 | 8dp / 12dp |
| 内边距 | 16dp |
| 页面水平 padding | 16dp |

### 1.5 补色定律 — 模块色互补对

每个模块色有明确的主色与互补色关系，用于按钮按压态、背景色、边框色等场景：

| 模块 | 主色 | 互补/辅助色 | 说明 |
|------|------|------------|------|
| 商店 (珊瑚红) | `BrewCoral=#E85D3F` 珊瑚红 | `BrewCyan=#5B8FB9` 静谧蓝 | 暖红 ↔ 冷蓝 |
| 屏幕镜像 | `BrewCyan=#5B8FB9` 静谧蓝 | 暖琥珀 | 冷调主色 |
| 手机投屏 | `BrewPurple=#D4A85C` 画廊金 | 紫色调 | 暖而有质感 |
| 文件管理 | `BrewAmber=#A78BFA` 雾紫 | 金色调 | 柔和区分 |
| 设置 | `BrewMagenta=#7D7A70` 暖灰褐 | — | 最低调中性色 |

> 浅色主题（Cool Blue）中模块色为撞色点缀，底色为浅蓝白。

---

## 二、全局组件

### 2.1 标准交互组件体系

定义：[design/DesignComponents.kt](file:///d:/rokidapp/cxrl/RokidLab/phone-app/src/main/java/com/rokidlab/phone/design/DesignComponents.kt)

所有按钮遵循统一的交互反馈模型：**按下时 scale(0.95~0.97) + 背景色加深**。动画统一使用 `spring(dampingRatio = MediumBouncy)`。

#### 2.1.1 标准按钮

**BrewButton — 主要操作按钮**

| 属性 | 值 |
|------|-----|
| 形状 | BrewShapeStandard (12dp) |
| 背景 | 模块色，按下时 alpha 0.8 |
| 文字 | 14sp Bold, BrewTextBright |
| 禁用态 | alpha 0.45 |
| 加载态 | 显示 "..." |
| 按下交互 | scale 0.95 + 背景变暗 |

**BrewOutlineButton — 次要操作按钮（边框样式）**

| 属性 | 值 |
|------|-----|
| 形状 | BrewShapeStandard (12dp) |
| 背景 | 透明，按下时 color alpha 0.12 |
| 边框 | 1dp, color alpha 0.6→按下时 1.0 |
| 文字 | 14sp Bold, 模块色 |
| 禁用态 | alpha 0.4 |
| 按下交互 | scale 0.95 + 背景染色 |

**BrewCompactButton — 紧凑操作按钮（行内操作）**

| 属性 | 值 |
|------|-----|
| 形状 | BrewShapeMedium (8dp) |
| 背景 | color alpha 0.15→按下时 0.25 |
| 边框 | 1dp, color alpha 0.4→按下时 0.7 |
| 文字 | 12sp Bold, 模块色 |
| 按下交互 | scale 0.93 |

**BrewIconButton — 图标操作按钮**

| 属性 | 值 |
|------|-----|
| 形状 | BrewShapeStandard (12dp) |
| 背景 | 自定义，按下时 bg alpha 0.7 |
| 内边距 | 7dp |
| 按下交互 | scale 0.90 |

#### 2.1.2 标准对话框（BrewDialog — RokidLink 卡片样式）

**BrewDialog — 统一对话框模板，所有弹窗均使用此样式**

| 属性 | 值 |
|------|-----|
| 容器形状 | BrewShapeLarge (16dp) |
| 背景色 | BrewPanelAlt |
| 边框 | 1dp `color`（模块色） |
| 标题栏背景 | `color`（模块色） |
| 标题栏高度 | 56dp |
| 标题文字 | 18sp Bold, BrewBg, letterSpacing 2sp |
| 装饰分隔线 | 48×4dp, BrewBg, 位于标题与内容之间 |
| 内容区内边距 | 24dp |
| 操作行 | Row, Arrangement.End, padding 16dp |

**标题栏结构（从左到右）：**
```
┌──────────────────────────────────────┐
│ ■ 标题文字                        [X] │  ← 模块色背景，BrewBg 文字
├──────────────────────────────────────┤
│ ████                                  │  ← 48×4dp 装饰线，BrewBg
├──────────────────────────────────────┤
│                                      │
│           内容区域                    │
│                                      │
│                      [取消]  [确认]   │
└──────────────────────────────────────┘
```

**四种模块色边框配色：**

| 模块 | color | 用途 |
|------|-------|------|
| 商店 | `BrewCoral` | 应用安装/更新对话框 |
| 屏幕镜像 | `BrewCyan` | 镜像状态/应用管理弹窗 |
| 手机投屏 | `BrewPurple` | 定时功能弹窗 |
| 文件管理 | `BrewAmber` | 文件操作确认/Shell 命令弹窗 |
| ADB 工具 | `BrewTeal` | ADB 工具主色（非弹窗标题色，各子功能使用上述四色） |

**BrewDialogTitle — 标题栏组件**

| 属性 | 值 |
|------|-----|
| 背景色 | `color`（模块色） |
| 标题文字 | 18sp Bold, BrewBg, letterSpacing 2sp |
| 关闭按钮 | ✕ 18sp, BrewBg, 右侧 16dp padding |
| 关闭按钮形状 | 32×32dp, 圆角 8dp, 按下时 alpha 0.7 |

#### 2.1.3 标准状态指示器

**BrewStatusDot — 状态圆点**

| 属性 | 值 |
|------|-----|
| 形状 | CircleShape (8dp) |
| 激活色 | BrewSuccess (绿) |
| 非激活色 | BrewRed (红) |

**BrewStatusPill — 状态标签**

| 属性 | 值 |
|------|-----|
| 形状 | BrewShapeSmall (4dp) |
| 背景 | color alpha bgAlpha (默认0.15) |
| 文字 | 12sp Bold, 模块色 |
| 内边距 | horizontal 10dp, vertical 4dp |

**BrewStateCard — 状态卡片**

| 属性 | 值 |
|------|-----|
| 形状 | BrewShapeMedium (8dp) |
| 背景 | color alpha 0.08 |
| 边框 | 1dp, color alpha 0.25 |
| 内边距 | 12dp |
| 标题 | 10sp Bold, 模块色, letterSpacing 1sp |
| 消息 | 12sp, 模块色 alpha 0.9 |
| 操作按钮 | 可选 BrewCompactButton |

四种类型：`StateCardType.SUCCESS` / `ERROR` / `WARNING` / `INFO`

#### 2.1.4 兼容组件（已委托给 BrewStateCard）

- `BrewErrorCard` → StateCardType.ERROR
- `BrewWarningCard` → StateCardType.WARNING  
- `BrewLoadingCard` → 独立实现（BrewPanel + BrewBorder）
- `BrewResultCard` → StateCardType.SUCCESS / ERROR

#### 2.1.5 BrutalButton（旧有，保留兼容）

> 以下旧按钮组件仍存在于各页面中，与新组件并存。计划逐步迁移至标准组件。

定义：[store/StoreHomeScreen.kt](file:///d:/rokidapp/cxrl/RokidLab/phone-app/src/main/java/com/rokidlab/phone/store/StoreHomeScreen.kt) `BrutalButton`、[settings/SettingsScreen.kt](file:///d:/rokidapp/cxrl/RokidLab/phone-app/src/main/java/com/rokidlab/phone/settings/SettingsScreen.kt) `BrutalButton`

| 属性 | 值 |
|------|-----|
| 高度 | 52dp / compact 32dp |
| 宽度 | fillMaxWidth / compact wrapContentWidth |
| 圆角 | BrewShapeStandard / BrewShapeMedium |
| 边框 | 1dp, 模块色 alpha 0.5→按下 0.7 |
| 背景 | 模块色 alpha 0.12→按下 0.20 |
| 文字 | 14sp SemiBold, letterSpacing 1sp, 模块色 |
| 按下交互 | scale 0.97（无 alpha 变化，仅背景加深） |
| 动画 | `spring(dampingRatio = MediumBouncy, stiffness = Medium)` |
| 禁用态 | alpha 45%

### 2.2 ModuleHeader（模块标题）

| 属性 | 值 |
|------|-----|
| 标题 | 32sp Black(900), 模块色, letterSpacing 4sp |
| 副标题 | 14sp BrewMuted |
| 标题与副标题间距 | 8dp |

### 2.3 SettingCard（设置卡片）

| 属性 | 值 |
|------|-----|
| 背景 | BrewPanel, 圆角 12dp |
| 边框 | 1dp BrewBorder, 圆角 12dp |
| 内边距 | 16dp |
| 标签 | 10sp Bold uppercase, BrewMuted, letterSpacing 3sp |
| 标签下装饰线 | 32×3dp 模块色色块 |
| 内容文字 | 18sp Bold, 模块色, letterSpacing 1sp, TabularNumbersStyle |
| 可点击 | 有 onClick 传入时可交互 |

### 2.4 IpAddressInputCard（IP 输入卡片）

| 属性 | 值 |
|------|-----|
| 背景 | BrewPanel, 圆角 12dp |
| 边框 | 1dp 模块色, 圆角 12dp |
| 内边距 | 16dp |
| 标签 | 12sp Bold, 模块色, letterSpacing 1sp |
| 输入框 | OutlinedTextField, placeholder "192.168.1.168" BrewMuted |
| 输入框样式 | TextStyle color=BrewTextBright |
| 键盘类型 | KeyboardType.Number |
| focusBorderColor | 模块色 |
| unfocusedBorderColor | BrewBorder |

### 2.5 RokidLinkStatusCard（眼镜端服务状态卡片）

定义：[store/StoreHomeScreen.kt](file:///d:/rokidapp/cxrl/RokidLab/phone-app/src/main/java/com/rokidlab/phone/store/StoreHomeScreen.kt) `RokidLinkStatusCard`

| 属性 | 值 |
|------|-----|
| 容器 | clip(RoundedCornerShape(12dp)), background(BrewPanel) |
| 边框 | 1dp, statusColor alpha 0.3, 圆角 12dp |
| 顶部状态条 | 填满宽度, 背景=statusColor, padding 20dp×14dp |
| 状态图标 | 20sp Bold, color=BrewBg |
| 状态文字 | "ROKIDLINK {状态}" 16sp Bold, BrewBg, letterSpacing 2sp |
| 副文字 | "眼镜端所有服务必须" 11sp Medium, BrewBg alpha 0.7 |
| 偏移装饰线 | 高 4dp, BrewBorderHi 背景, 安装中时 0↔8dp 脉冲动画 |
| 安装中容器 | 高 52dp, BrewPanelAlt 背景 12dp, 1dp 边框 BrewCoral alpha 0.3 |
| 安装中文字 | "⟳ 安装中..." 14sp Bold BrewCoral letterSpacing 3sp |
| 脉冲动画 | `infiniteRepeatable(tween 800ms, LinearEasing, Reverse)` |

**颜色规则：`moduleColor` 参数传入各自导航栏色（BrewCoral/Cyan/Purple/Amber/Teal），未安装和安装中统一使用商店色 BrewCoral**

| 场景 | 状态条/图标/文字色 | 按钮色 |
|------|-------------------|--------|
| 运行中 | `moduleColor`（各自导航栏色） | 停止按钮 → `BrewCoral`（商店色） |
| 已安装 | `moduleColor` | 启动按钮 → `moduleColor` |
| 安装中 | `BrewCoral` | —（仅显示安装中动画） |
| 未安装 | `BrewCoral` | 安装按钮 → `BrewCoral` |

**四个模块调用示例：**

| 模块 | moduleColor | 运行中/已安装 | 未安装/安装中/停止 |
|------|-----------|-------------|-----------------|
| 屏幕镜像 | `BrewCyan` | 静谧蓝 | 珊瑚红 |
| 手机投屏 | `BrewPurple` | 画廊金 | 珊瑚红 |
| 文件管理 | `BrewAmber` | 雾紫 | 珊瑚红 |
| ADB 工具 | `BrewTeal` | 青绿 | 珊瑚红 |

### 2.6 UsageInstructionsCard（使用说明卡片）

| 属性 | 值 |
|------|-----|
| 背景 | BrewPanel, 圆角 12dp |
| 边框 | 1dp BrewBorder, 圆角 12dp |
| 内边距 | 16dp |
| 标题 | "使用说明" 12sp Bold, 颜色=参数 color, letterSpacing 1sp |
| 步骤文字 | 12sp BrewMuted, lineHeight 20sp, 条目间距 4dp |

### 2.7 搜索栏

| 属性 | 值 |
|------|-----|
| 高度 | 46dp |
| 背景 | BrewPanel, 圆角 12dp |
| 边框 | 1dp BrewBorder, 圆角 12dp |
| 水平内边距 | 14dp |
| 搜索图标 | Icons.Outlined.Search, 20dp, BrewMuted |
| 输入框 | BasicTextField, 14sp, BrewTextBright |
| 占位文字 | "搜索应用..." 14sp BrewDim |
| 清除按钮 | "×" 18sp BrewMuted, 圆角 8dp |

### 2.8 CategoryChip（分类标签）

| 属性 | 值 |
|------|-----|
| 高度 | 34dp |
| 最小宽度 | 64dp |
| 圆角 | 12dp |
| 选中态 | 背景 模块色, 文字 BrewBg, 边框 1dp 模块色 |
| 未选中态 | 背景 BrewPanelAlt alpha 0.86, 文字 BrewTextBright, 边框 1dp BrewBorderHi alpha 0.44 |
| 文字 | 13sp SemiBold (fixedSp) |
| 选中动画 | spring 弹性放大至 1.04x (MediumBouncy + Low stiffness) |
| 水平内边距 | 13dp |

### 2.9 EmptyState（空状态）

| 属性 | 值 |
|------|-----|
| 高度 | 132dp |
| 宽度 | fillMaxWidth |
| 圆角 | 12dp |
| 背景 | BrewPanel |
| 边框 | 1dp BrewBorder, 圆角 12dp |
| 文字 | "未找到应用" 14sp Bold BrewMuted |

### 2.10 AppListItem（应用列表项）

| 属性 | 值 |
|------|-----|
| 圆角 | 12dp |
| 边框 | 1dp BrewBorder |
| 内边距 | 12dp |
| 应用图标 | 64×64dp AppIcon |
| 应用名 | 14sp Bold BrewTextBright |
| 描述 | 12sp BrewMuted, maxLines=2 |
| 安装目标标签 | 10sp Bold, INSTALLED→BrewSuccess / UPDATE→BrewWarning / else→BrewText |
| 进度条 | LinearProgressIndicator, 4dp 高, BrewCoral, track=BrewBorder |
| 取消下载 | "✕" BrewCoral 14sp Bold |
| 展开/收起按钮 | 高 48dp, "SHOW ALL (N)" / "SHOW LESS", 12sp Bold, letterSpacing 2sp |
| 安装完成闪动 | `Animatable` alpha 0.25→0, 600ms tween |

### 2.11 底部导航栏

| 属性 | 值 |
|------|-----|
| 高度 | 64dp |
| 背景 | BrewPanel, 圆角 12dp |
| 边框 | 1dp BrewBorder |
| 七个按钮等宽 | weight(1f) |
| 选中态 | 背景=模块色, 文字=BrewTextBright |
| 未选中态 | 背景=BrewPanel, 文字=BrewMuted |
| 文字 | 10sp Bold, letterSpacing 1sp |

**七模块映射（水平排列，超出可左右滑动，标签文字来自 `R.string.nav_xxx` 资源，随语言切换自动变化）：**

| 页面 | label | color |
|------|-------|-------|
| STORE | "应用商店" | BrewCoral |
| SCREEN_MIRROR | "屏幕镜像" | BrewCyan |
| PHONE_MIRROR | "手机投屏" | BrewPurple |
| FILE_MANAGER | "文件管理" | BrewAmber |
| ADB_TOOLS | "ADB工具" | BrewTeal |
| HID_GAMEPAD | "蓝牙手柄" | BrewSuccess |
| SETTINGS | "设置" | BrewMagenta |

**页面切换动画：** `AnimatedContent` fadeIn(200ms) + slideInHorizontally(1/4) ⨯ fadeOut + slideOutHorizontally

### 2.12 ConnectionPanel（眼镜连接面板）

| 属性 | 值 |
|------|-----|
| 形状 | Card RoundedCornerShape(12dp) |
| 背景 | BrewPanel alpha 0.78 |
| 边框 | BorderStroke 1dp BrewBorderHi alpha 0.46 |
| 内边距 | horizontal 12dp, vertical 11dp |
| 标题行 | 图标 Icons.Outlined.Visibility 20dp BrewSuccess + "眼镜连接" 15sp SemiBold |
| 状态圆点 | 6×6dp, clip 4dp, 已连接时脉冲缩放至 1.6x + alpha 混合, 400ms tween |
| 链路信息 | "CXR-L 链路 / {status}" 11sp Medium BrewMuted/状态色 |
| HostApp 图标 | 45×45dp, RoundedCornerShape(13dp) |
| HostApp 名称 | 14sp SemiBold BrewTextBright |
| HostApp 版本 | 11sp Medium BrewMuted |
| HostApp 选择器 | 水平滚动 Row, 选中色 BrewCoral |

### 2.13 退出确认对话框

| 属性 | 值 |
|------|-----|
| 形状 | AlertDialog RoundedCornerShape(12dp) |
| 容器色 | BrewPanel |
| 标题 | "退出应用" 20sp Bold BrewRed, letterSpacing 2sp |
| 内容 | 装饰线 48×4dp BrewRed + "确定要退出吗？" 14sp BrewText + "退出后所有投屏连接将断开。" 12sp BrewMuted |
| 退出按钮 | 120×44dp, 背景 BrewBg, 边框 1dp BrewRed 12dp, 文字 "退出" 14sp Bold BrewRed |
| 取消按钮 | 120×44dp, 背景 BrewBg, 边框 1dp BrewBorder 12dp, 文字 "取消" 14sp Bold BrewText |

### 2.14 ConnectionInfoCard（设备信息卡片）

ADB 工具页面的眼镜连接信息卡片。

| 属性 | 值 |
|------|-----|
| 背景 | BrewPanel, 圆角 12dp |
| 边框 | 1dp BrewBorder, 圆角 12dp |
| 内边距 | 16dp |
| 标签 | "ADB 连接" 10sp Bold uppercase, BrewMuted, letterSpacing 3sp |
| 标签下线 | 32×3dp BrewTeal 色块 |
| 设备 IP | 18sp Bold, BrewTeal, letterSpacing 1sp |
| 状态 | 14sp, 已连接→BrewSuccess "已连接" / 未连接→BrewError "未连接" |
| 连接按钮 | BrutalButton "🔗 连接眼镜" / "断开" |

### 2.15 AppMgrListItem（ADB 应用列表项）

| 属性 | 值 |
|------|-----|
| 圆角 | 12dp |
| 边框 | 1dp BrewBorder |
| 内边距 | 12dp |
| 包名 | 14sp Bold BrewTextBright, fontFamily=JetBrains Mono |
| 选中标记 | 左侧 ✓ 图标 14sp Bold BrewSuccess 或选中背景色 |
| 冻结标记 | ❄️ 14sp 尾缀 |
| 行高 | 约 44dp，多行自动折叠 |
| 列表最大高度 | 420dp，超出可滚动 |

### 2.13 SourceLine（来源行 — 应用详情页）

定义：[store/DetailInfoSections.kt](file:///d:/rokidapp/cxrl/RokidLab/phone-app/src/main/java/com/rokidlab/phone/store/DetailInfoSections.kt) `SourceLine`

| 属性 | 值 |
|------|-----|
| 高度 | 50dp |
| 宽度 | fillMaxWidth |
| 圆角 | 12dp |
| 背景 | BrewPanelAlt alpha 0.88 |
| 边框 | 1dp BrewBorder alpha 0.52, 圆角 12dp |
| 图标容器 | 30×30dp, RoundedCornerShape(17dp), background=BrewPanel |
| 图标 | 19dp, 根据 sourceUrl 动态选择 Gitee（ic_gitee_mark）或 GitHub（ic_github_mark） |
| 来源名称 | "Gitee" / "GitHub", 15sp SemiBold BrewTextBright |
| 作者 | 12sp BrewMuted, maxLines=1 |
| 域名 | 11sp BrewCoral, maxLines=1, 仅显示域名部分（移除 https://） |
| 箭头 | Icons.Outlined.KeyboardArrowRight, 21dp BrewMuted |

**动态判断逻辑**：当 `app.sourceUrl` 包含 `gitee.com` 时显示 Gitee 图标 + 文字，否则显示 GitHub 图标 + 文字。

### 2.14 MirrorSourceDialog（商店源切换对话框）

定义：[app/MainActivity.kt](file:///d:/rokidapp/cxrl/RokidLab/phone-app/src/main/java/com/rokidlab/phone/app/MainActivity.kt) `MirrorSourceDialog`

| 属性 | 值 |
|------|-----|
| 形状 | Dialog RoundedCornerShape(20dp) |
| 容器色 | BrewBg |
| 内边距 | 24dp |
| 标题 | "切换源" 20sp Bold BrewTextBright |
| 源列表项 | 每项高约 60dp, RoundedCornerShape(12dp) |
| 选中态 | 背景 BrewCoral alpha 0.12, 边框 1dp BrewCoral, 附加 ✓ 图标 |
| 未选中态 | 背景透明 |
| 源图标 | 28×28dp 圆角 6dp, 半透明白色背景 |
| 源名称 | 16sp SemiBold, 选中=BrewCoral / 未选中=BrewTextBright |
| 源描述 | 13sp BrewMuted |

**源列表**：
| 源 | 图标资源 | 图标内容 |
|:---|:--------:|:--------|
| Gitee | `ic_gitee_mark` | 红色圆形背景 + 白色 G 字 |
| GitHub | `ic_github_mark` | 白色 GitHub Octocat |

### 2.15 全局错误提示组件

定义：[design/DesignComponents.kt](file:///d:/rokidapp/cxrl/RokidLab/phone-app/src/main/java/com/rokidlab/phone/design/DesignComponents.kt)

**BrewErrorCard — 错误提示卡片**

| 属性 | 值 |
|------|-----|
| 背景 | BrewRed alpha 0.08, 圆角 8dp |
| 边框 | 1dp BrewRed alpha 0.3, 圆角 8dp |
| 内边距 | 12dp |
| 标签 | "错误" 10sp Bold BrewRed |
| 消息 | 12sp BrewRed alpha 0.9 |
| 重试按钮 | BrewRed alpha 0.15 背景, "重试" 11sp Bold BrewRed, 圆角 6dp |

| 使用场景 | 显示位置 |
|---------|---------|
| 屏幕镜像连接失败 | 按钮上方 inline |
| 手机投屏启动失败 | 按钮上方 inline |
| 文件管理连接失败 | 按钮上方 inline |
| 商店刷新失败 | 搜索栏下方 inline |
| 设置重装 ScreenStream 失败 | 按钮下方 inline |
| 蓝牙重连失败 | 连接状态卡片 |

**BrewWarningCard — 警告提示卡片**

| 属性 | 值 |
|------|-----|
| 背景 | BrewWarning alpha 0.08, 圆角 8dp |
| 边框 | 1dp BrewWarning alpha 0.3, 圆角 8dp |
| 内边距 | 12dp |
| 标签 | "注意" 10sp Bold BrewWarning |
| 消息 | 12sp BrewWarning alpha 0.9 |
| 操作按钮 | 可选, 同色系 |

**BrewLoadingCard — 加载中卡片**

| 属性 | 值 |
|------|-----|
| 背景 | BrewPanel, 圆角 8dp |
| 边框 | 1dp BrewBorder, 圆角 8dp |
| 内边距 | 24dp |
| 文字 | 13sp BrewMuted, 居中 |

**BrewResultCard — 操作结果卡片（成功/失败）**

| 属性 | 值 |
|------|-----|
| 背景 | BrewSuccess/BrewRed alpha 0.08, 圆角 8dp |
| 边框 | 1dp 对应色 alpha 0.3, 圆角 8dp |
| 内边距 | 12dp |
| 标签 | "成功"/"失败" 10sp Bold |
| 消息 | 12sp 对应色 alpha 0.9 |
| 详情 | 可选, 10sp BrewMuted |

---

## 三、页面构成

### 3.1 Store 页面（应用商店）

```
Rokid Lab（36sp Bold BrewCoral） + Lab（36sp Bold BrewCyan）
by DLOVER（12sp BrewMuted）

→ [搜索栏 直接输入 BasicTextField]
→ 精选应用 "精选" 列表 [可关✕]
→ 分类标签水平滚动行 CategoryChip [红选中/灰未选中, 12dp 圆角]
→ 应用列表 AppListItem [安装/进度/闪动]
→ SHOW ALL / SHOW LESS 展开按钮 [12dp]
```

### 3.2 ScreenMirror 页面（屏幕镜像）

```
ModuleHeader "屏幕镜像" / "眼镜屏幕实时同步到手机" [BrewCyan #5B8FB9]
→ IpAddressInputCard IP地址 [BrewCyan]
→ BrutalButton "▶ 开始镜像" [BrewCyan]
↑ 若连接失败时替换为错误提示 + "重试连接" 按钮 + "返回设置" 文字按钮

镜像中:
  顶部栏 [返回箭头 ←] [状态文字 weight(1f)]
  画面区域（缩放、双指平移）
  触控：单指点击/双击返回/滑动
```

### 3.3 PhoneMirror 页面（手机投屏）

```
ModuleHeader "手机投屏" / "手机屏幕投射到眼镜" [BrewPurple #D4A85C]

投屏中时:
  "投屏中" 20sp Bold BrewSuccess
  连接状态 14sp BrewMuted
  BrutalButton "■ 停止投屏" [BrewRed]

未投屏时:
  → IpAddressInputCard IP地址 [BrewPurple]
  → ScreenStreamStatusCard 安装状态
  → BrutalButton "▶ 开始投屏" [BrewPurple]
  → UsageInstructionsCard [BrewPurple]
```

### 3.4 FileManager 页面（文件管理）

```
ModuleHeader "文件管理" / "管理眼镜中的文件" [BrewAmber #A78BFA]
→ IpAddressInputCard IP地址 [BrewAmber]
→ ScreenStreamStatusCard 安装状态
→ BrutalButton "▶ 打开文件管理器" [BrewAmber]
→ BrutalButton "安装本地 APK" [BrewInfo #5B8FB9]
→ UsageInstructionsCard [BrewAmber]
```

### 3.5 ADB 工具页面

```
ModuleHeader "ADB 工具" / "眼镜应用管理与系统工具" [BrewInfo #5B8FB9]

→ ConnectionInfoCard [BrewInfo]
  IP 地址 | 状态 | 连接/断开按钮

→ BrutalButton "📱 应用管理" [BrewCyan]
    弹出 AppMgrDialog:
    ┌─────────────────────────────────────┐
    │ [第三方/全部]  [搜索...]       [↻]   │
    ├─────────────────────────────────────┤
    │ ✓ com.xxx.app                      │
    │   com.yyy.service                  │
    │   com.zzz.game  ❄️                 │
    │ ...（最多显示 10 项，超出可下滑）     │
    ├─────────────────────────────────────┤
    │ 选中后: [▶启动] [🗑卸载] [❄冻结] [📦提取] │
    └─────────────────────────────────────┘

→ BrutalButton "⏱ 定时功能" [BrewPurple]
    弹出 TimerDialog（窗口占 80%，可上下滑动）:
    ┌──────────────────────────────────────┐
    │ ── 定时消息 ──                       │
    │ [输入消息内容...]                     │
    │ 间隔(秒) [5] × 次数 [10]             │
    │ [▶ 启动] 已发送: 0/10                │
    │ ── 定时打开应用 ──                   │
    │ [选择应用...] → 展开列表包名选择      │
    │ 间隔(秒) [30] × 次数 [5]             │
    │ [▶ 启动] 已执行: 0/5                 │
    └──────────────────────────────────────┘

→ BrutalButton "ℹ 设备信息" [BrewInfo]
→ BrutalButton "🔌 系统属性" [BrewAmber]
→ BrutalButton "🔋 电量信息" [BrewSuccess]
→ UsageInstructionsCard [BrewInfo]
```

### 3.6 蓝牙手柄页面

```
ModuleHeader "蓝牙手柄" / "通过蓝牙控制眼镜光标和按键" [BrewSuccess #4ADE80]

未连接时:
→ BrutalButton "🔍 扫描设备" [BrewCyan]  / "■ 停止扫描" [BrewRed]
→ 已配对设备列表 [可点击连接]

连接状态指示:
  "已连接" → BrewSuccess 绿色
  "连接中..." → BrewWarning 橙色
  "未连接" → BrewMuted 灰色
  "重连失败，请重启眼镜蓝牙" → BrewRed 红色（智能重试 5 次失败后显示）

连接成功后:
→ [鼠标模式] / [游戏手柄] 切换按钮
  选中: BrewSuccess 背景 + 白色文字
  未选中: BrewPanel 背景 + BrewMuted 文字

鼠标模式:
  触控板区域: 320×240dp, BrewPanel 背景
  → 触摸移动 = 鼠标移动
  → 点击 = 左键
  → 双指 = 右键
  → 返回按钮/Home 按钮

游戏手柄模式:
  左半边: 摇杆区（触摸 = 方向）
  右半边: ABXY 按键区（网格划分）
```

### 3.7 Settings 页面（设置）

```
ModuleHeader "设置" / "应用配置" [BrewMagenta #8A8780]

→ SettingCard "应用版本" [BrewCoral]
→ SettingCard "主机应用" [BrewCyan] 可点击跳引导
→ SettingCard "更新状态" / BrutalButton "有更新可用" [BrewCoral]
→ BrutalButton "切换商店源" [BrewCyan]

  → ── 眼镜端服务 ──
    → SettingCard "ScreenStream" 已安装(绿)/未安装(黄)
    → BrutalButton "重装眼镜端" [BrewWarning #F0A050] 停止→等待800ms→推送安装
      安装中时: label 变为 "正在安装中..."，enabled=false 半透明不可点击

  → ── 语言 ──
    → LanguageSwitcher:
      背景 BrewPanel, 圆角 12dp, 边框 1dp BrewBorder
      标签 "语言" 10sp Bold uppercase BrewMuted letterSpacing 3sp
      装饰线 32×3dp BrewCyan
      当前语言文字 18sp Bold BrewCyan letterSpacing 1sp
      可点击弹出 LanguagePickerDialog:
        Dialog RoundedCornerShape(16dp), 背景 BrewPanel
        标题 "选择语言" 18sp Bold BrewTextBright
        选项: "简体中文" / "English"
        选中项 → ✓ 图标 BrewSuccess + 文字 BrewCoral
        未选中 → 文字 BrewTextBright
        点击后立即切换语言，对话框自动关闭，界面即时刷新

  → 开发者卡片: BrewPanel + 1dp BrewBorder 12dp 内 16dp padding
    标签 "开发者" 10sp Bold BrewDim letterSpacing 2sp
    装饰线 32×3dp BrewCoral
    文字 "DLOVER" 24sp Bold BrewCoral
```

---

## 四、辅助页面

### 4.1 GuideScreen（引导页）

| 组件 | 规格 |
|------|------|
| 主标题 | "欢迎使用 Rokid Lab" 32sp Black(900) BrewCoral letterSpacing 2sp |
| 副标题 | "by DLOVER" 14sp BrewMuted |
| 进度指示器 | 水平 Row, 四个圆点 12dp, 已完成→BrewSuccess/当前→BrewCoral/未完成→BrewDim |
| HostApp 选择卡片 | 56dp 高, 12dp 圆角, 选中→BrewCoral 背景 文字 BrewBg, 未选中→BrewPanel 文字 BrewText, 边框 1dp |
| 商店源按钮 | 56dp 高, BrewCoral 背景 12dp 圆角, "选择商店源" 16sp Bold BrewBg |
| 授权按钮 | 56dp 高, BrewCoral/BrewSuccess 背景 12dp 圆角, "点击授权"/"已授权 ✓" 16sp Bold BrewBg |
| 步骤说明 | BrewPanel 背景 12dp 圆角, 1dp BrewBorder, 16dp padding, 提示文字 14sp BrewMuted |

### 4.2 UpdateDialog（更新对话框）

| 属性 | 值 |
|------|-----|
| 形状 | Card RoundedCornerShape(20dp) |
| 容器色 | BrewPanelAlt |
| 边框 | BorderStroke 1dp BrewBorderHi |
| 标题 | "有可用更新" / "下载中..." titleLarge, BrewCoral |
| 内容 | "RokidLab {version} 已准备好安装。" / "RokidLab {version}（{percent}%）", bodyMedium, BrewText |
| 进度条 | LinearProgressIndicator, BrewCoral, track=BrewPanel |
| 取消按钮 | TextButton "取消" BrewCoral 12sp |
| 稍后按钮 | TextButton "稍后" BrewDim |
| 更新按钮 | Button containerColor=BrewCoral, 文字 "更新" BrewBg |

### 4.3 DetailInfoPanel（应用详情面板）

| 属性 | 值 |
|------|-----|
| 形状 | Card RoundedCornerShape(12dp) |
| 背景 | BrewPanel alpha 0.76 |
| 边框 | BorderStroke 1dp BrewBorderHi alpha 0.46 |
| 内边距 | 14dp |

### 4.4 PhoneMirrorActivity（投屏画面）

| 属性 | 值 |
|------|-----|
| 主题 | RokidLabTheme |
| 连接中 | CircularProgressIndicator BrewCoral + 状态文字 16sp BrewTextBright |
| 连接失败 | "连接失败" 20sp Bold BrewRed + 状态 14sp BrewMuted + 重试/返回按钮 |
| 投屏中 | "投屏中" 24sp Bold BrewSuccess + 状态 16sp BrewTextBright + "停止投屏" Button BrewCoral |

### 4.5 StoreComponents Header（商店页顶栏）

| 属性 | 值 |
|------|-----|
| Logo | BrandTitle 24sp（点击回首页） |
| 搜索按钮 | 38×38dp, 圆角 12dp, Search icon 24dp |
| 更新按钮 | 38×38dp, 圆角 12dp, SystemUpdateAlt icon 24dp, 红点角标 (有更新时) |
| 刷新按钮 | 38×38dp, 圆角 12dp, Refresh icon 25dp, 旋转动画 (refreshing 时) |
| 菜单按钮 | 38×38dp, 圆角 12dp, MoreVert icon 24dp |
| 下拉菜单 | "切换源" / "安装 APK 到眼镜" |

### 4.6 AppMgrDialog（应用管理对话框）

| 属性 | 值 |
|------|-----|
| 形状 | Dialog RoundedCornerShape(12dp) |
| 容器色 | BrewPanel |
| 宽度 | fillMaxWidth, padding horizontal 16dp |
| 模式切换 | TextButton "第三方" / "全部", 选中态 BrewCyan Bold, 未选中 BrewMuted |
| 搜索栏 | BasicTextField, 14sp BrewTextBright, placeholder "搜索包名..." |
| 刷新按钮 | 点击 → 图标旋转动画 |
| 列表 | Column + verticalScroll, maxHeight 420dp |
| 列表项 | 包名 14sp Bold + 选中标记 ✓ / 冻结标记 ❄️ |
| 选中操作栏 | Row, 四个按钮: ▶启动/🗑卸载/❄冻结/📦提取, text 11sp Bold |
| 间距 | 组件间 12dp, 列表项 8dp |

### 4.7 TimerDialog（定时功能对话框）

| 属性 | 值 |
|------|-----|
| 形状 | Dialog, 占屏幕约 80% 高度 |
| 容器色 | BrewPanel |
| 可滑动 | verticalScroll(rememberScrollState()) |
| 内边距 | 24dp |

**模块1 — 定时消息：**
- 标签 "定时消息" 16sp Bold BrewPurple, letterSpacing 1sp
- 输入框: OutlinedTextField, 消息内容, placeholder color=BrewDim
- 行: "间隔(秒)" + OutlinedTextField 60dp 宽 + "×" + "次数" + OutlinedTextField 60dp 宽
- BrutalButton "▶ 启动" [BrewPurple] / "■ 停止" [BrewRed]
- 已发送计数: "已发送: {n}/{total}" 12sp BrewMuted

**模块2 — 定时打开应用：**
- 标签 "定时打开应用" 16sp Bold BrewCyan, letterSpacing 1sp
- 应用选择器: 点击展开完整可滚动列表（显示包名）
- 间隔/次数行同上
- BrutalButton "▶ 启动" [BrewCyan] / "■ 停止" [BrewRed]
- 已执行计数: "已执行: {n}/{total}" 12sp BrewMuted

---

## 五、动画体系

| 动画 | 触发条件 | 实现 | 参数 |
|------|---------|------|------|
| 按钮按压缩放 | BrutalButton 按下 | spring scale 1→0.98 + alpha 1→0.85 | MediumBouncy + Medium |
| 分类标签弹性 | CategoryChip 选中 | spring scale 1→1.04 | MediumBouncy + Low |
| 页面切换 | 底部导航切换 | AnimatedContent fadeIn+slideIn ⨯ fadeOut+slideOut | 200ms, 1/4 offset |
| 安装中脉冲 | ScreenStreamStatusCard 安装中 | 偏移线 0↔8dp | infiniteRepeatable 800ms |
| 安装完成闪动 | AppListItem 安装完毕 | alpha 0.25→0 | tween 600ms |
| 连接庆祝脉冲 | 眼镜已连接 | 状态点 scale 1→1.6 + alpha 混合 | tween 400ms |
| 刷新旋转 | Header/ADB 刷新按钮 | rotationZ 0→360 | infiniteRepeatable 800ms |

---

## 六、本地化系统

### 6.1 架构

```
[ strings.xml（values/ 简体中文 默认）] ←→ [ values-en/strings.xml（English）]
        │                                           │
        └────────── AppCompatDelegate ──────────────┘
                .setApplicationLocales()
                        │
                LocalizationManager
                  ├─ 首次启动检测系统语言（zh→中文，其他→English）
                  ├─ 持久化到 SharedPreferences
                  └─ 运行时切换后立即重建 Activity
```

### 6.2 实现方式

| 组件 | 说明 |
|------|------|
| `LocalizationManager` | 单例，封装语言检测/切换/持久化逻辑 |
| `LabApplication.onCreate()` | 初始化 `LocalizationManager.init(this)` |
| `MainActivity` | 监听 `currentLocale` 状态，提供 `onSwitchLanguage` 回调 |
| `AppCompatDelegate.setApplicationLocales()` | AndroidX 官方 API，支持 Android 5.0+ 运行时切换 |
| Compose UI | 通过 `LocalContext.current.getString(R.string.xxx)` 引用资源 |

### 6.3 字符串约定

- 所有 UI 文字必须通过 `R.string.xxx` 引用，禁止硬编码
- 默认语言（`values/strings.xml`）为简体中文
- 英文翻译放在 `values-en/strings.xml`
- 新增语种：在 `res/` 下新建 `values-{lang}/strings.xml`，Crowdin 自动同步

### 6.4 底部导航标签映射

| 页面 | 资源 ID | 中文 | English |
|------|---------|------|---------|
| STORE | `nav_store` | 应用商店 | App Store |
| SCREEN_MIRROR | `nav_screen_mirror` | 屏幕镜像 | Screen Mirror |
| PHONE_MIRROR | `nav_phone_mirror` | 手机投屏 | Phone Cast |
| FILE_MANAGER | `nav_file_manager` | 文件管理 | File Manager |
| ADB_TOOLS | `nav_adb_tools` | ADB工具 | ADB Tools |
| HID_GAMEPAD | `nav_hid_gamepad` | 蓝牙手柄 | Gamepad |
| SETTINGS | `nav_settings` | 设置 | Settings |

### 6.5 Crowdin 工作流

1. 开发者修改 `values/strings.xml`
2. 同步到 Crowdin 平台（`crowdin upload sources`）
3. 翻译人员在 Crowdin 上完成翻译
4. 下载翻译成果（`crowdin download`），自动生成 `values-{lang}/strings.xml`
5. 编译验证后提交代码

---

## 七、代码质量与架构

### 7.1 配置管理

项目采用集中式配置管理，所有硬编码的配置参数统一在 `AppConfig.kt` 中定义：

```kotlin
object AppConfig {
    /** ADB 默认端口 */
    const val DEFAULT_ADB_PORT = 5555

    /** 手机投屏服务默认端口 */
    const val DEFAULT_MIRROR_PORT = 7654

    /** 投屏基准分辨率（短边） */
    const val MIRROR_BASE_SIZE = 480

    /** ADB 连接超时时间（毫秒） */
    const val ADB_CONNECT_TIMEOUT_MS = 10000

    /** ADB Socket 超时时间（毫秒） */
    const val ADB_SOCKET_TIMEOUT_MS = 3000

    /** 投屏 Socket 连接超时时间（毫秒） */
    const val MIRROR_CONNECT_TIMEOUT_MS = 3000

    /** 投屏 Socket 重连最大尝试次数 */
    const val MIRROR_MAX_RECONNECT_ATTEMPTS = 3

    /** 蓝牙 HID 快速断连最大重试次数 */
    const val BLUETOOTH_MAX_QUICK_DISCONNECT_RETRIES = 5

    /** 投屏空闲超时时间（毫秒） */
    const val MIRROR_IDLE_TIMEOUT_MS = 180_000L

    /** scrcpy 视频流超时时间（毫秒） */
    const val SCRCPY_STREAM_TIMEOUT_MS = 80

    /** ADB 单个包最大负载（字节） */
    const val ADB_MAX_PAYLOAD = 1024 * 1024

    /** ADB 流缓冲区最大大小（字节） */
    const val ADB_MAX_STREAM_BUFFER_SIZE = 10 * 1024 * 1024
}
```

**优势**：
- 集中管理所有配置参数，便于维护和修改
- 避免在多个文件中重复定义相同的常量
- 统一命名规范，提高代码可读性
- 便于后续添加配置验证和文档

### 7.2 线程安全

项目采用多种机制确保线程安全：

#### 7.2.1 同步锁保护

关键操作使用 `synchronized` 块保护：

```kotlin
private val operationLock = Any()

private fun maybeRunPendingOperation() {
    synchronized(operationLock) {
        // Critical section - prevent concurrent modification
        if (isOperationInProgress) {
            return
        }
        isOperationInProgress = true
        // Perform operation
    }
}
```

#### 7.2.2 @Volatile 注解

确保多线程环境下的变量可见性：

```kotlin
@Volatile
var connectionState: Int = STATE_DISCONNECTED
    private set

@Volatile
private var connectedDeviceInternal: BluetoothDevice? = null
```

#### 7.2.3 协程安全

协程间通信使用 Channel 和 Flow，避免竞态条件：

```kotlin
private val _connectionEvents = Channel<Int>(Channel.CONFLATED)
val connectionEvents: Flow<Int> = _connectionEvents.receiveAsFlow()
```

### 7.3 资源管理

项目重视资源管理，防止内存泄漏和资源泄漏：

#### 7.3.1 自动资源释放

所有网络连接、文件流、ADB 连接等资源在使用后正确关闭：

```kotlin
fun disconnect() {
    try { inputStream?.close() } catch (_: Exception) {}
    try { outputStream?.close() } catch (_: Exception) {}
    try { socket?.close() } catch (_: Exception) {}
}
```

#### 7.3.2 投屏服务资源释放

投屏服务停止时释放所有相关资源：

```kotlin
fun stopMirror() {
    imageHandlerThread?.quit()
    imageHandlerThread?.join(1000)
    imageReader?.close()
    surface?.release()
    virtualDisplay?.release()
    mediaProjection?.stop()
}
```

#### 7.3.3 线程管理

使用 HandlerThread 管理后台线程，避免主线程阻塞：

```kotlin
private val imageHandlerThread = HandlerThread("PhoneMirrorImageThread").apply { start() }
private val imageHandler = Handler(imageHandlerThread.looper)
```

### 7.4 安全性

项目注重安全性，保护用户数据和系统安全：

#### 7.4.1 ADB 密钥保护

生成的 RSA 私钥文件权限设置为仅应用可读写：

```kotlin
privKeyFile.setReadable(false, false)
privKeyFile.setReadable(true, true)
privKeyFile.setWritable(false, false)
privKeyFile.setWritable(true, true)
```

#### 7.4.2 输入验证

所有用户输入都经过验证，防止注入攻击：

```kotlin
private fun validateIpAddress(ip: String): Boolean {
    val ipPattern = "^((25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.){3}(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)$"
    return ip.matches(Regex(ipPattern))
}
```

#### 7.4.3 网络安全

使用 HTTPS 连接，支持证书验证：

```kotlin
val connection = url.openConnection() as HttpsURLConnection
connection.sslSocketFactory = sslContext.socketFactory
connection.hostnameVerifier = hostnameVerifier
```

### 7.5 错误处理

项目采用完善的错误处理机制：

#### 7.5.1 异常捕获

所有可能失败的操作都使用 try-catch 包裹：

```kotlin
runCatching {
    cxrL.installApk(apkFile) { installed ->
        // Handle installation result
    }
}.onFailure { error ->
    log(getString(R.string.apk_install_failed, error.message ?: error.javaClass.simpleName))
}
```

#### 7.5.2 用户友好提示

错误信息通过 Toast 和状态卡片显示，便于用户理解：

```kotlin
BrewStateCard(
    type = StateCardType.ERROR,
    title = "安装失败",
    message = error.message ?: "未知错误",
    actionButton = {
        BrewCompactButton(
            text = "重试",
            onClick = { retry() }
        )
    }
)
```

#### 7.5.3 日志记录

详细的日志记录便于问题排查和调试：

```kotlin
private const val TAG = "PhoneMirrorService"

Log.d(TAG, "Starting mirror service on port $port")
Log.e(TAG, "Mirror failed: ${error.message}", error)
Log.w(TAG, "Connection timeout, retrying...")
```

### 7.6 性能优化

项目注重性能优化，提升用户体验：

#### 7.6.1 灰度转换优化

使用整数运算和缓冲区复用减少 GC 压力：

```kotlin
val gray = ((299 * r + 587 * g + 114 * b + 500) / 1000).toByte()
val grayData = reusableGrayData?.takeIf { it.size == w * h } ?: ByteArray(w * h).also { reusableGrayData = it }
```

#### 7.6.2 图片加载优化

使用 Coil 库进行异步图片加载和缓存：

```kotlin
AsyncImage(
    model = ImageRequest.Builder(LocalContext.current)
        .data(app.iconUrl)
        .crossfade(true)
        .build(),
    contentDescription = app.name,
    modifier = Modifier.size(64.dp)
)
```

#### 7.6.3 协程优化

使用协程进行异步操作，避免阻塞主线程：

```kotlin
lifecycleScope.launch {
    withContext(Dispatchers.IO) {
        val apps = loadAppsFromRegistry()
        withContext(Dispatchers.Main) {
            appList = apps
        }
    }
}
```

---

## 八、版本历史

### v1.0.0 (2024-06-17)

**初始版本**

- 实现所有核心功能：应用商店、蓝牙手柄、ADB工具、屏幕镜像、手机投屏、文件管理
- 完整的 Velvet Dark 设计系统
- 多语言支持（简体中文/English）
- 眼镜端 RokidLink 自动集成
- 完善的错误处理和日志系统
- 配置集中管理（AppConfig）
- 线程安全和资源管理优化
- 性能优化（灰度转换、图片加载、协程）
