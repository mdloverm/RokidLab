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

配色接口 `BrewColors` 定义 **17 色**（4 底色 + 4 文字 + 1 边框 + 8 模块色），新增主题只需实现此接口：

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
│  八模块八色 — 撞色方案                                                │
├────────────┼─────────────────────────┼───────────────────────────────┤
│  store      │  #E85D3F   朱砂红         │  #E85D3F   珊瑚红 (撞色)          │
│  chat       │  #6EE7B7   青翠绿         │  #00D2D3   蓝绿                   │
│  mirror     │  #5B8FB9   静谧蓝         │  #00B894   翡翠绿                 │
│  projection │  #D4A85C   画廊金         │  #6C5CE7   明媚紫                 │
│  fileManager│  #A78BFA   雾紫           │  #F39C12   琥珀金                 │
│  adbTools   │  #00CEC9   薄荷青         │  #0984E3   深海蓝                 │
│  hidGamepad │  #FD79A8   玫瑰粉         │  #E17055   珊瑚橙                 │
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
| `BrewChat` | chat | 乐奇聊天 |
| `BrewCyan` | mirror | 屏幕镜像 |
| `BrewPurple` | projection | 手机投屏 |
| `BrewAmber` | fileManager | 文件管理 |
| `BrewTeal` | adbTools | ADB 工具 |
| `BrewPink` | hidGamepad | HID 手柄 |
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
| 商店 (朱砂红) | `BrewCoral=#E85D3F` 朱砂红 | `BrewCyan=#5B8FB9` 静谧蓝 | 暖红 ↔ 冷蓝 |
| 乐奇聊天 (青翠绿) | `BrewChat=#6EE7B7` 青翠绿 | `#00D2D3` 蓝绿（浅色主题） | 冷调主色 |
| 屏幕镜像 | `BrewCyan=#5B8FB9` 静谧蓝 | 暖琥珀 | 冷调主色 |
| 手机投屏 | `BrewPurple=#D4A85C` 画廊金 | 紫色调 | 暖而有质感 |
| 文件管理 | `BrewAmber=#A78BFA` 雾紫 | 金色调 | 柔和区分 |
| ADB 工具 | `BrewTeal=#00CEC9` 薄荷青 | 深海蓝 | 冷调清新 |
| HID 手柄 | `BrewPink=#FD79A8` 玫瑰粉 | 珊瑚橙 | 暖色活泼 |
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

**模块色边框配色（八模块）：**

| 模块 | color | 用途 |
|------|-------|------|
| 商店 | `BrewCoral` | 应用安装/更新对话框 |
| 乐奇聊天 | `BrewChat` | 聊天设置 / 技能 / 本地模型 / 知识库 / 工具管理弹窗 |
| 屏幕镜像 | `BrewCyan` | 镜像状态/应用管理弹窗 |
| 手机投屏 | `BrewPurple` | 定时功能弹窗 |
| 文件管理 | `BrewAmber` | 文件操作确认/Shell 命令弹窗 |
| ADB 工具 | `BrewTeal` | ADB 工具主色 |
| HID 手柄 | `BrewPink` | 手柄相关弹窗 |
| 设置 | `BrewMagenta` | 设置相关弹窗 |

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

**颜色规则：`moduleColor` 参数传入各自导航栏色（BrewCoral/Chat/Teal/Pink/Magenta），未安装和安装中统一使用商店色 BrewCoral**

| 场景 | 状态条/图标/文字色 | 按钮色 |
|------|-------------------|--------|
| 运行中 | `moduleColor`（各自导航栏色） | 停止按钮 → `BrewCoral`（商店色） |
| 已安装 | `moduleColor` | 启动按钮 → `moduleColor` |
| 安装中 | `BrewCoral` | —（仅显示安装中动画） |
| 未安装 | `BrewCoral` | 安装按钮 → `BrewCoral` |

**模块调用示例（五 Tab 改版后）：**

| 模块 | moduleColor | 运行中/已安装 | 未安装/安装中/停止 |
|------|-----------|-------------|-----------------|
| 乐奇聊天 | `BrewChat` | 青翠绿 | 珊瑚红 |
| 乐奇工具（投屏/文件/ADB 合并页） | `BrewTeal` | 薄荷青 | 珊瑚红 |
| HID 手柄 | `BrewPink` | 玫瑰粉 | 珊瑚红 |

**手机投屏模块布局规范：** 与其他模块一致，投屏中状态不隐藏 ROKIDLINK 状态卡和 IP 输入框。投屏前显示"开始投屏"按钮，投屏中仅按钮切换为"■ 停止投屏"（红色），其余元素保持不变。

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
| 按钮宽度 | 80dp 固定宽等距排列；超出可左右滑动（horizontalScroll） |
| 选中态 | 背景=模块色, 文字=BrewTextBright（`BrewAmber` 特判为 `BrewBg`） |
| 未选中态 | 背景=BrewPanel, 文字=BrewMuted |
| 文字 | 10sp Bold, letterSpacing 1sp |

**五 Tab 映射（水平排列，超出可左右滑动，标签文字来自 `R.string.nav_xxx` 资源，随语言切换自动变化）：**

| 页面 | label | color |
|------|-------|-------|
| STORE | "应用商店" | BrewCoral |
| CHAT | "乐奇聊天" | BrewChat |
| LEQI_TOOLS | "乐奇工具" | BrewTeal |
| HID_GAMEPAD | "蓝牙手柄" | BrewPink |
| SETTINGS | "设置" | BrewMagenta |

> `NavPage` 枚举实测仅 5 项（STORE / CHAT / LEQI_TOOLS / HID_GAMEPAD / SETTINGS）。MIRROR_PAIR（双向投屏）/ FILE_MANAGER（文件管理）/ ADB_TOOLS（ADB 工具）三页已合并进 LEQI_TOOLS 单页，`nav_screen_mirror` / `nav_phone_mirror` / `nav_mirror_pair` / `nav_file_manager` / `nav_adb_tools` 等资源已不再用于底部导航。

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

### 2.16 SourceLine（来源行 — 应用详情页）

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

### 2.17 MirrorSourceDialog（商店源切换对话框）

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

### 2.18 全局错误提示组件

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

> 底部导航实测 5 个 Tab：STORE / CHAT / LEQI_TOOLS / HID_GAMEPAD / SETTINGS。下方 §3.2~§3.5 的屏幕镜像 / 手机投屏 / 文件管理 / ADB 工具已不再是独立 Tab，其入口统一收敛到「乐奇工具」单页（见 §3.9）；这些子页面的内容规格仍然有效。

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
ModuleHeader "蓝牙手柄" / "通过蓝牙控制眼镜光标和按键" [BrewPink #FD79A8]

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
  ↓
  [键盘按钮 ─ ◇ 键盘]  ← 触控板区域下方，BrewSuccess 色边框
    点击后弹出系统原生输入法对话框:
    ┌─────────────────────────────────────────┐
    │ 输入文字到眼镜                              │
    │ ┌─────────────────────────────────┐      │
    │ │ 在此输入文字...                   │      │
    │ └─────────────────────────────────┘      │
    │                                         │
    │    [发送]                     [取消]      │
    └─────────────────────────────────────────┘
    发送按钮 → 三种方案依次尝试将文字写入眼镜焦点 App
    状态提示: "✅ 已发送" / "❌ 发送失败" (BrewStateCard 显示)
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

  → ── 后台保活 ──
    → SettingCard "后台保活" 已开启(BrewSuccess)/已关闭(BrewMuted) [可点击切换]
      点击后立即持久化开关并启停前台保活服务（通知栏常驻）
    → SettingCard "后台保活说明" "常驻通知栏，防止系统回收后台服务" [BrewMagenta]

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

### 3.8 乐奇聊天页面（CHAT / ChatScreen）

定义：[store/ChatScreen.kt](file:///d:/rokidapp/cxrl/RokidLab/phone-app/src/main/java/com/rokidlab/phone/store/ChatScreen.kt)、[ChatHeader.kt](file:///d:/rokidapp/cxrl/RokidLab/phone-app/src/main/java/com/rokidlab/phone/store/ChatHeader.kt)、[ChatBubble.kt](file:///d:/rokidapp/cxrl/RokidLab/phone-app/src/main/java/com/rokidlab/phone/store/ChatBubble.kt)、[ChatImageCache.kt](file:///d:/rokidapp/cxrl/RokidLab/phone-app/src/main/java/com/rokidlab/phone/store/ChatImageCache.kt)

**ChatHeader（顶栏）**
| 属性 | 值 |
|------|-----|
| 容器 | Row, fillMaxWidth, 背景 BrewPanel, padding(start 16 / end 8 / top 8 / bottom 8) |
| 标题 | "乐奇聊天" 16sp Bold `BrewChat` |
| 副标题 | 11sp `BrewMuted` |
| 操作图标 | 4 个 IconButton（tint `BrewChat`）：拍照问答 `PhotoCamera` / 知识库 `Folder` / 清空会话 `DeleteSweep` / 设置 `Settings` |

**消息列表**
- `LazyColumn`，contentPadding 14dp（横）/12dp（纵），条目间距 10dp，`key = 消息 id`
- 空态：提示文字 13sp `BrewMuted` 居中，padding 36dp
- 会话消息由 `ChatStateHolder` 全局持有，**切换 Tab 不清空**；落盘为 JSONL 增量写 + 单线程后台落盘（不阻塞 UI 线程），重启不丢历史，详见 `DEV_GUIDE.md` §8.6

**ChatBubble（气泡）**
| 类型 | 对齐 | 背景 | 圆角 |
|------|------|------|------|
| 状态消息（isStatus） | 居中 | 无气泡 | — （12sp `BrewMuted`） |
| 用户 | End | `BrewChat` | (16,16,4,16)dp |
| AI | Start | `BrewPanelAlt` | (16,16,16,4)dp |

- 气泡宽度上限 300dp，padding 横 12dp / 纵 8dp
- 文字 15sp / 行高 22sp，用户 `BrewBg` / AI `BrewTextBright`，包在 `SelectionContainer` 内可选中复制
- 时间戳 10sp（用户 `BrewBg` alpha 0.7 / AI `BrewMuted`），右对齐，上距 4dp

**图片消息（BubbleImage）**
| 属性 | 值 |
|------|-----|
| 高度 | heightIn(min 80dp, max 260dp) |
| 圆角 | 8dp |
| 底色 | `BrewPanelAlt` |
| 缩放 | ContentScale.Fit |
| 加载中 | CircularProgressIndicator 24dp / stroke 2dp / `BrewMuted` |
| 失败 | "图片加载失败" 12sp |

> 图片加载走自研轻量 `ChatImageCache`（Bitmap 内存缓存 + 同 URL 并发去重 + OkHttp 连接 8s / 读 15s + 长边缩放 600px），项目未引入 Coil/Glide。

**底部输入行**
| 属性 | 值 |
|------|-----|
| 容器 | 圆角 26dp，`BrewPanel` + 1dp `BrewBorder`（**先 clip 再 background/border**） |
| 输入框 | `BasicTextField` 15sp `BrewTextBright`，光标 `BrewChat`，maxLines 4，ImeAction.Send |
| 思考开关 | 38dp 圆；`Icons.Filled/Outlined.Psychology`；点亮底 `BrewAmber` alpha 0.18 + 边框 alpha 0.6，tint `BrewAmber` |
| 发送/停止 | `Button` 高 42dp、圆角 21dp；发送 `BrewChat` / 停止 `BrewRed`；禁用底 `BrewPanelHi` + 文字 `BrewMuted` |

- 发送走后台线程 `session.sendAiTextMessage(...)`，流式增量经 `ChatStateHolder.appendAiDelta` 追加；停止调用 `abortCurrentAi()`
- 弹层：`ChatSettingsDialog`（AI 来源/工具/技能/AIUI 管理/本地模型入口）、`ConfirmClearChatDialog`、`KbManageDialog`（见 §4.11）

### 3.9 乐奇工具页面（LEQI_TOOLS / LeqiToolsModule）

定义：[store/LeqiToolsModule.kt](file:///d:/rokidapp/cxrl/RokidLab/phone-app/src/main/java/com/rokidlab/phone/store/LeqiToolsModule.kt)

**由 MIRROR_PAIR（双向投屏）+ FILE_MANAGER（文件管理）+ ADB_TOOLS（ADB 工具）三页合并而成的单页。**

```
ModuleHeader "乐奇工具" / "投屏 · 文件 · ADB 一站式工具" [BrewTeal]
verticalScroll + 16dp padding

SectionLabel("投屏", BrewCyan)          ← 12sp Bold, letterSpacing 1sp, 底距 10dp
  → BrutalButton "开始镜像" [BrewCyan]
  → 手机投屏切换按钮: 未投屏 "开始投屏" [BrewPurple] / 投屏中 "■ 停止投屏" [BrewRed]

SectionLabel("文件", BrewAmber)
  → BrutalButton "打开文件管理器" [BrewAmber]
  → BrutalButton "安装本地 APK" [BrewInfo]（安装中 → BrewCoral）

SectionLabel("ADB 工具", BrewTeal)
  → 内嵌 com.rokidlab.phone.adb.ui.AdbToolsScreen
```

- ADB 工具区**不自建会话**，复用全 App 唯一共享 ADB 会话 `app.cxrL.getAdbShellClient()`（含同步握手，须在 `Dispatchers.IO` 调用）—— 规避 RFCOMM「同设备 + 同 SCN 仅一条通道」约束
- 每个分组的 `SectionLabel` 统一为 12sp Bold、letterSpacing 1sp、下方 10dp 间距

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

**模块3 — 定时任务动作（含 TTS 语音播报）：**
- 定时任务支持多动作：发送通知 / 打开应用 / Shell 命令 / 点击 / 按键 / **TTS 语音播报**
- TTS 播报：到点通过 CXR-L 下行 `tts_play` 通道让眼镜语音朗读提醒内容（App 退后台仍可触发）
- 语音创建的定时任务（AI 说"5分钟后提醒我喝水"）自动附带 TTS 播报 + 本地通知
- 定时任务持久化存储，保活服务重启后自动恢复运行中的任务

---

### 4.8 AI 工具管理子页面（ToolsManagePage）

| 属性 | 值 |
|------|-----|
| 容器 | Dialog 全屏（usePlatformDefaultWidth=false），背景 BrewBg |
| 入口 | 聊天设置 →「管理 AI 工具」行（右侧 › 箭头） |
| 内边距 | 20dp |

**布局：**
- 顶栏: 标题 "AI 工具" 18sp Bold BrewTextBright + "完成" 按钮 BrewChat（点击保存全部开关并返回）
- 副标题: 12sp BrewMuted（说明语）
- 工具列表（可滚动）:
  - 每个工具一张卡片: BrewPanel + 1dp BrewBorder + 12dp 圆角 + 10dp 内边距
  - 左侧: 工具名 14sp Medium BrewTextBright + 描述 11sp BrewMuted（走多语言资源）
  - 右侧: Switch（checkedTrackColor=BrewChat / unchecked=BrewPanelHi）
  - 开关状态实时修改，点"完成"统一持久化

**工具清单（34 个，默认全开，按域分组）：**
- 信息与知识：知识库检索 / 当前时间 / 天气 / 计算 / 网页搜索 / 网页抓取 / 位置 / 手机状态
- 眼镜：电量 / 系统信息 / 存储空间 / 已装应用列表 / 打开应用 / 图片显示
- 定时与媒体：定时任务 / 取消定时 / 列出定时 / 播放音乐 / 停止音乐 / 显示歌词
- 文件与 AIUI：保存摘要 / 保存代码文件 / 读取代码文件 / 生成并安装 AIUI / 启动 AIUI / 停止 AIUI / 我的 AIUI 列表
- 手机域：通讯录搜索 / 拨打电话 / 手机闹钟 / 打开手机应用 / 音量 / 日历查询 / 新增日程

> 数量口径以 `ToolRegistry.toolList` 实测为准：**34 条 ToolMeta**（`group = DOMAIN_` 与 `descriptionRes` 计数均 34）；共 10 个工具域（INFO/KNOWLEDGE/GLASSES/TIMER/MEDIA/DISPLAY/WEB/FILES/AIUI/PHONE）。

### 4.9 技能商店（SkillsManagePage / SkillDialogs）

定义：[store/SkillsManagePage.kt](file:///d:/rokidapp/cxrl/RokidLab/phone-app/src/main/java/com/rokidlab/phone/store/SkillsManagePage.kt)、[store/SkillDialogs.kt](file:///d:/rokidapp/cxrl/RokidLab/phone-app/src/main/java/com/rokidlab/phone/store/SkillDialogs.kt)

**SkillsManagePage**
- 容器：全屏 `Dialog（usePlatformDefaultWidth=false）`，背景 `BrewBg`，padding 20dp
- 顶栏：标题 "AI 技能" 18sp Bold + 返回按钮（`BrewChat` Bold）；副标题 12sp `BrewMuted`
- 总开关行：圆角 12dp + `BrewPanel` + 1dp `BrewBorder`；`Switch` 配色 `checkedTrackColor=BrewChat` / `uncheckedTrackColor=BrewPanelHi` / `checkedThumbColor=BrewBg` / `uncheckedThumbColor=BrewMuted`
- 三入口 `EntryButton`（各 `weight(1f)`，圆角 12dp + `BrewPanel`/`BrewBorder`，文字 `BrewChat` 12sp Bold）：手动填写 / 导入 zip·md / URL 下载
- 计数文案 → `Column(weight(1f).verticalScroll)` → 空态提示 或 `SkillRow`
- `SkillRow`：名称 14sp Medium + 描述 11sp（maxLines 2）+ 行内操作 11sp（编辑 / 删除 / 同步官方，仅内置技能）+ 右侧 `Switch`（同配色）
- 删除确认 `AlertDialog(containerColor = BrewPanel)`：确认按钮 `BrewChat` / 取消按钮 `chat_key_dialog_cancel`

**SkillDialogs**
| 弹窗 | 规格 |
|------|------|
| `SkillEditDialog` | 圆角 20dp + `BrewPanel` + 1dp `BrewBorder`；3 个 `OutlinedTextField`：name（自动转小写并过滤 `[a-z0-9-]`，placeholder `weather-advice`）/ description（`take(300)`）/ body（`take(SkillMarkdown.MAX_BODY_CHARS)`，`heightIn(min 180dp)`）；`focusedBorderColor=BrewChat` / `unfocusedBorderColor=BrewBorder` / 光标 `BrewChat`；保存 `Button(containerColor=BrewChat, contentColor=BrewBg)` 圆角 12dp |
| `SkillUrlImportDialog` | 多行 URL 输入（minLines 1 / maxLines 3）+ 结果框（圆角 12dp，`BrewPanel.copy(alpha=0.5f)`，12sp）；按钮 `BrewChat`/`BrewBg` |

- 导入来源分类：`SkillFetcher.classify` → ZIP / MD_FILE / REPO_PAGE / UNKNOWN；本地导入读限 512KB，按 zip 魔数（`P K`）判断走 `installFromZip` 或 `installFromMarkdown`

### 4.10 本地模型页面（LocalModelPage）

定义：[store/LocalModelPage.kt](file:///d:/rokidapp/cxrl/RokidLab/phone-app/src/main/java/com/rokidlab/phone/store/LocalModelPage.kt)（约 828 行）

- 容器：全屏 `Dialog`；顶栏 标题 "本地模型" 18sp Bold + 完成按钮（`BrewChat`）；副标题 12sp `BrewMuted`
- 卡片 `PageCard`：圆角 16dp + `BrewPanel` + 1dp `BrewBorder`，padding 横 14dp / 纵 12dp
- **服务状态卡**：`StatusDot`（0 检测中灰 / 1 运行中 `BrewSuccess` / 2 已停止 `BrewRed`）+ 状态文案（检测中 / 运行中(版本) / 停止中 / 启动中 / 已停止）
- **Termux 未安装**：提示文案 + F-Droid 安装按钮
- **启动引导** `guidance` = `PERMISSION` / `DENIED` / `TIMEOUT` → `BrewWarning` 13sp 提示（同时弹 Toast），并给出授权 / Termux 设置 / 复制安装命令（`pkg update && pkg install -y ollama`）按钮
- **对话接入卡**：`local_model_chat_title` + `local_model_use_chat`；`chatModel` 非空时展示模型名、1dp 分隔线（`BrewBorder`）与调参 `ParamChipRow`（predict：-1/128/256/512/1024，ctx：-1/2048/4096/8192，temp：-1/0.2/0.7/1.0，选中即时生效）
- **模型库**：已安装数量 + 拉取区展开/收起；`PullSection`（进度 + 状态 + 取消）+ 建议模型 `SuggestChip` + 内存提示 / 空间不足 / 完成 / 失败文案；`ModelRow`（设为对话模型 / 删除）
- 删除确认 `AlertDialog(containerColor = BrewPanel)`，确认按钮 `BrewRed`

> ⚠️ **现状说明**：本页当前仍直接使用 Material3 原生组件（`Button` / `TextButton` / `AlertDialog`）与 `RoundedCornerShape(...)` 字面量，**尚未迁移到 `Brew*` 设计系统**；且只提供 `setLocalChatModel()`（切到本地），没有反向开关。完整改进方案见 [docs/UI-IMPROVEMENT-LocalModelPage.md](file:///d:/rokidapp/cxrl/RokidLab/docs/UI-IMPROVEMENT-LocalModelPage.md)（**提案文档，尚未落地**）。

### 4.11 知识库管理弹窗（KbManageDialog）

定义：[store/KbManageDialog.kt](file:///d:/rokidapp/cxrl/RokidLab/phone-app/src/main/java/com/rokidlab/phone/store/KbManageDialog.kt)

| 属性 | 值 |
|------|-----|
| 容器 | `Dialog(usePlatformDefaultWidth=false)`，水平外边距 28dp |
| 卡片 | 圆角 20dp + `BrewPanel` + 1dp `BrewBorder`，内 padding 20dp |
| 标题 | "知识库" 16sp Bold `BrewTextBright` + 副标题 12sp `BrewMuted` |
| 导入 | 右侧 `TextButton`（导入中显示安装态），`OpenDocument()`，MIME `text/plain` |
| 空态 | 提示 13sp 居中，`height(120dp)` |
| 文档列表 | `Column(heightIn(max=320dp))`；每行 名称 14sp Medium（maxLines 1）+ 大小 11sp `BrewMuted` + `IconButton(Delete, tint BrewMuted, 18dp)` |
| 底部 | `TextButton` 取消 |

- 数据层：`KnowledgeBase.listDocs / importUri / deleteDoc`；导入内容供「拍照问 AI」检索（见 §4.12）

### 4.12 拍照问答 UI（PhotoQuizFlow）

定义：[glasses/PhotoQuizFlow.kt](file:///d:/rokidapp/cxrl/RokidLab/phone-app/src/main/java/com/rokidlab/phone/glasses/PhotoQuizFlow.kt)

- 触发：聊天页顶栏「拍照问答」图标，或眼镜端镜腿按键（两者共用同一起点）
- 阶段气泡（顺序，资源 id）：`chat_photo_status`（拍照中）→ `chat_ocr_status`（识别中）→ `chat_kb_status`（检索知识库）→ `chat_ai_status`（生成答案）
- OCR 识别出的题目文字作为「用户消息」气泡回显；知识库命中带来源标注 `（《文档名》第N块）`
- 终态：识别为空 → `chat_ocr_empty`；拍照/流程失败 → `chat_photo_failed`（并复位入口，避免永久失效）
- 一次性语义：`skipTtsAudioFinished=true` + `recordHistory=false`（不写入会话历史）；`inProgress` 标志防重入
- 无独立全屏 UI，复用聊天页的消息气泡与阶段回调

### 4.13 工具风险确认弹窗（眼镜端 / 手机端）

定义：[RokidLink/.../KeyButtonService.kt](file:///d:/rokidapp/cxrl/RokidLab/RokidLink/src/main/java/com/rokidlab/rokidlink/KeyButtonService.kt)、[phone-app/.../ai/ToolPolicy.kt](file:///d:/rokidapp/cxrl/RokidLab/phone-app/src/main/java/com/rokidlab/phone/ai/ToolPolicy.kt)、[ai/GlassToolConfirmChannel.kt](file:///d:/rokidapp/cxrl/RokidLab/phone-app/src/main/java/com/rokidlab/phone/ai/GlassToolConfirmChannel.kt)

**触发条件**：手机端工具风险档为 `EXTERNAL_SIDE_EFFECT` 时，经 `TOPIC_TOOL_CONFIRM` 下发确认请求（caps = [requestId, 工具名, 摘要]）。

**眼镜端交互**
| 操作 | 结果 |
|------|------|
| 短按（UP / CLICK） | **允许** |
| 双击（DOUBLE_CLICK） | 取消 |
| 长按（LONG_PRESS） | 取消 |
| 30s 无操作 | 超时自动取消 |

- 悬浮层文案：`⚠ {摘要}` + `[短按]允许  [双击]取消`；同时 TTS 播报「是否{摘要}？短按确认，双击取消」
- 应答后 1.5s 内吞掉按键，防误触；回传 `TOPIC_TOOL_CONFIRM_RESULT`（caps = [requestId, "yes"/"no"]）
- 手机端等待上限 35s（略大于眼镜 30s 窗口）

**能力协商与降级（如实记录当前实现）**
- 握手三态：未握手（`supports()` 返回 `null`）→ 乐观视为可用；收到 v2 通告 → 按能力位判断；4s 内无应答 → 判旧版 v1（`supports()` 返回 `false`）→ 快速降级
- **降级为 Allow（放行）**：确认通道不可用（无连接 / 旧版眼镜端）时不再硬拒，直接放行，工具侧只做无副作用动作；确认超时同样降级放行
- **拒绝**：仅当用户在眼镜端**显式取消**时为 Deny
- `LOCAL_SIDE_EFFECT` / `READ_ONLY` 工具不经此闸门，直接放行（如 `call_phone` 自 2026-09-11 起降为 `LOCAL_SIDE_EFFECT`，语音指令即授权直拨）

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

**当前规模（2026-09-12，v3.5）**：`values/strings.xml` 共 1100 条，`values-en/strings.xml` 共 1100 条，**双语 key 完全对齐**（此前缺 5 条已于 2026-09-12 补齐）。构建期由 `checkI18nKeysSynced` 任务（挂 `preBuild`）强制两模块 key 集合相等，不一致直接构建失败。

### 6.4 底部导航标签映射

| 页面 | 资源 ID | 中文 | English |
|------|---------|------|---------|
| STORE | `nav_store` | 应用商店 | App Store |
| CHAT | `nav_chat` | 乐奇聊天 | LeQi Chat |
| LEQI_TOOLS | `nav_leqi_tools` | 乐奇工具 | Leqi Tools |
| HID_GAMEPAD | `nav_hid_gamepad` | 蓝牙手柄 | Gamepad |
| SETTINGS | `nav_settings` | 设置 | Settings |

> 遗留未使用的标签资源（`nav_screen_mirror` / `nav_phone_mirror` / `nav_mirror_pair` / `nav_file_manager` / `nav_adb_tools`）仍保留在 strings.xml 中，但已不再被底部导航引用。

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

### v3.5 (2026-09-09)

**Lab 工具桥 + Agent 核心升级 + ASR 加固 + 构建迁移**

- **AIUI 工具桥**：眼镜端 AIUI 页面可回调手机端 **34 个工具**（`ToolGateway` 统一入口，域全开，仅禁 5 个：`open_aiui_app`（防自指递归）、会话查询三件套 `list_sessions`/`read_session`/`session_trace`（页面是第三方制品，不该能批量枚举并导出用户的全部会话）、`research_subtask`（一次 callTool 会触发若干次模型调用，且页面桥没有取消通道）；结果截断 8000 字符、15s 超时）
- **启动参数下发**：`open_aiui_app` 的 `params` 随 open 命令一并下发；页面侧需写 `globalThis.Lab.callTool`
- **Agent 核心升级**：长期记忆改 SQLite（FIFO 200 条 / 90 天过期 / 首启迁移旧 JSON），上下文滚动摘要，BM25/2-gram 检索，SSE 重连指数退避
- **工具策略闸门**：新增 `ToolRisk` 三级风险 + `ToolPolicy`（按来源限流 AIUI 页面 30/min、对话 120/min；确认通道缺失或超时时降级放行）
- **ASR 加固**：去重条件改为「同文 + 上一条仍在处理中」；推送客户端连上后才置 socket + 补读断连积压；90s 兜底超时
- **链路协议 v2**：新增眼镜端能力握手（`GlassesHandshake` 三态：未知乐观 / 确认支持 / 旧版降级）与工具确认弹窗（眼镜端短按允许、双击取消）；`ChannelArbiter` 通道优先级仲裁（BACKGROUND/NORMAL/LONG_LIVED）
- **构建迁移**：CXR-L 升级 1.1.2（16KB 对齐）、RokidLink 迁移 Maven bridge、启用 R8、HTTP 统一 OkHttp
- **测试**：**14 个测试类 / 155 个 `@Test`**（2026-09-12 补两批回归测试：<br>① 高危链路 —— ADB sync 帧 / `pullFile` FAIL / HID 描述符字节 / `AiChannel` v0·v1 矩阵 / `ToolRiskMap` 完整性；<br>② 聊天历史落盘 —— `ChatHistoryStore` 的 JSONL 往返 / 同 id 后写覆盖先写 / 旧格式迁移 / 崩溃截断容错）

### v3.4 (2026-09-08)

**AIUI 智能体生成（对话即开发）+ Skill 技能体系 + 本地模型**

- **AIUI 对话即开发**：对 AI 说需求即生成 AIUI 页面并推送到眼镜端渲染（独立 agentId，避免与官方智能体冲突）
- **Skill 技能体系**：技能 = 说明书（`SKILL.md`），命中描述时先加载完整步骤再调用本地工具；支持手动填写 / 导入 zip·md / URL 下载与开关管理
- **代码先读再改**：新增 `read_code_file` / `save_code_file`，修改 AIUI 代码前必须先读真实源码再覆盖写回，禁止凭印象整文件重编
- **本地模型**：新增本地模型页，通过 Termux 运行 Ollama，让眼镜对话使用手机本地离线大模型
- **眼镜端 AIUI 渲染宿主**：`AiuiLinkActivity` 承载 AIUI 微前端渲染

### v2.3-beta (2026-08-17)

**后台保活 + 语音控制工具 + 语音定时**

- **后台保活**：新增前台保活服务（通知栏常驻 + START_STICKY 自愈 + specialUse 类型），保证语音助手/蓝牙键盘/ADB 工具/定时任务在 App 退后台后持续运行；设置页新增保活开关（默认开启）
- **长驻任务解耦**：ASR 轮询/推送等从 Activity lifecycleScope 迁出到 Application 级 appScope，Activity 销毁不断链
- **AI 工具扩展**：新增「打开应用」（语音"打开小智"，自动名称匹配已装应用）与「定时任务」（语音"5分钟后提醒我喝水"）
- **语音定时**：定时任务新增 TTS 语音播报动作，到点经 CXR-L `tts_play` 通道眼镜语音提醒，持久化 + 保活常驻触发
- **AI 工具管理子页面**：聊天设置 →「管理 AI 工具」，8 个工具开关统一管理，名称/描述走多语言
- **应用名统一**：小智应用商店注册名统一为「小智AI」（手机端/注册表/RokidBrew 三方同步）
- **语音打断修复**：眼镜端下行过滤窗口仅由完整下行序列触发，打断指令不再吞掉用户提问

### v2.0 (2026-07-03)

**ADB 协议修复与眼镜长按修复**

- **ADB sync 协议修复**：修复 CLSE 误判导致文件上传回退到 shell 慢速方式
- **ADB 流关闭修复**：修复流关闭协议错误导致 daemon 无限重传 CLSE 包
- **眼镜长按修复**：修复忽略系统 LONG_PRESS 广播导致长按完全失效
- **按键持久性**：添加 WakeLock + SCREEN_ON + 心跳自检，修复按键过一会失效
- **启动兜底链**：新增 pm resolve-activity 命令行，支持非标准 Activity 应用
- **按键设置 UI**：第三方应用改为下拉列表，长列表可滚动
- **应用商店**：图标增加黑色底，视觉更统一
- **体验优化**：listPackages 60 秒缓存，二次打开秒开

### v1.7 (2026-07-01)

**ADB 文件管理修复与商店优化**

- **ADB 上传修复**：修复 ADB 文件协议中 SEND 命令的流关闭逻辑，解决中文目录上传文件超时问题
- **下拉刷新**：商店页面新增下拉刷新功能，手动获取最新应用列表
- **精选应用横向滑动**：精选应用区域改为横向滑动展示全部，浏览更流畅
- **分类标签本地化**：22 种分类标签支持中英文切换，CategoryChip 随语言自动更新
- **浮窗权限弹窗改进**：投屏悬浮窗权限弹窗增加「不再提示」复选框
- **商店加载优化**：优化商店页面数据加载和状态管理

### v1.0.1 (2026-06-22)

**Bug 修复与性能优化**

- **旋转不崩溃**：MainActivity 添加 `configChanges="orientation|screenSize"`，旋转时 Activity 不重建，投屏服务持续运行
- **Socket 重连异步化**：`reconnectSocket()` 移至独立线程池 `reconnectExecutor`，不再阻塞图像处理线程，消除画面卡死
- **重连防循环**：添加 `isReconnecting` 标记，防止多次帧发送失败触发大量重连任务
- **线程安全同步锁**：引入 `mirrorLock` 保护 ImageReader/VirtualDisplay 切换，解决 swapImageReaderSurface 与图像处理线程的竞态问题
- **方向缓存优化**：使用 `currentOrientation` 缓存取代每帧查询 `DisplayManager`，减少 IPC 开销
- **停止投屏不关 RokidLink**：`stopPhoneMirror()` 仅断开 Socket，眼镜端 `onDisconnected` → `moveTaskToBack(true)` 退回后台保持运行
- **眼镜端线程泄漏修复**：`PhoneMirrorServer.start()` 先调用 `stop()` 清理旧线程
- **眼镜端异常捕获优化**：移除 accept 异常后的 `Thread.sleep(1000)`，停止 Server 更快响应
- **多语言覆盖完成**：全面检查并修复所有用户可见区域的硬编码中文字符串

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
