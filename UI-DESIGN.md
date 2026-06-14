# RokidLab UI Design Reference

## 一、全局设计系统

### 1.1 设计风格

**Velvet Dark（丝绒暗调）** — 画廊暗室 × 油画颜料

- 底画布：温暖的丝绒暗色（`#0B0B0E`），绝非纯黑
- 配色理念：每个颜色都从油画色板取色，带温度和深度
- 克制而有质感的对比，不使用纯三原色
- 全站统一 12dp 圆角，柔和而不失几何感
- 统一 1dp 边框宽度
- 无硬阴影、无装饰性底纹线条
- 以留白和色彩本身构成画面

### 1.2 配色方案

```
┌──────────────────────────────────────────────────────────────────┐
│  底色系统 — 丝绒暗调（温暖暗底）                                      │
├────────────┬──────────────────────────────────────────────────────┤
│  BrewBg        │  #0B0B0E   丝绒炭黑（微微偏暖）                      │
│  BrewPanel     │  #151518   暗灰板                                  │
│  BrewPanelAlt  │  #1C1C21   亮灰板                                  │
│  BrewPanelHi   │  #24242A   高亮面板                                │
├────────────┼──────────────────────────────────────────────────────┤
│  文字系统 — 暖白至冷灰（画廊标牌）                                     │
├────────────┼──────────────────────────────────────────────────────┤
│  BrewTextBright│  #F2EFEA   暖羊皮白 — 主正文                       │
│  BrewText      │  #D4D0CA   沙石灰 — 次要文字                       │
│  BrewMuted     │  #8A8780   风化石 — 辅助文字                        │
│  BrewDim       │  #5C5952   深石色 — 禁用/淡出                      │
├────────────┼──────────────────────────────────────────────────────┤
│  七模块七色 — 取自油画色板                                             │
├────────────┼──────────────────────────────────────────────────────┤
│  BrewGreen     │  #E85D3F   商店 — 朱砂红（温暖主导）                  │
│  BrewCyan      │  #5B8FB9   屏幕镜像 — 静谧蓝（冷调克制）              │
│  BrewPurple    │  #D4A85C   手机投屏 — 画廊金（暖而有质感）             │
│  BrewAmber     │  #A78BFA   文件管理 — 雾紫（柔和区分）                │
│  BrewInfo      │  #5B8FB9   ADB工具 — 静谧蓝（与Cyan同值）            │
│  BrewSuccess   │  #4ADE80   蓝牙手柄 — 翡翠绿                        │
│  BrewMagenta   │  #8A8780   设置 — 石灰色（最低调）                   │
├────────────┼──────────────────────────────────────────────────────┤
│  功能色                                                             │
├────────────┼──────────────────────────────────────────────────────┤
│  BrewSuccess   │  #4ADE80   成功 — 翡翠绿                            │
│  BrewWarning   │  #F0A050   警告 — 暖琥珀                            │
│  BrewInfo      │  #5B8FB9   信息 — 静谧蓝                            │
│  BrewError     │  #E85D3F   错误 — 朱砂红                            │
│  BrewCoral     │  #E85D3F   主强调色（与 Green 同值）                 │
├────────────┼──────────────────────────────────────────────────────┤
│  边框（几乎融入背景）                                                  │
├────────────┼──────────────────────────────────────────────────────┤
│  BrewBorder    │  #2C2C33                                          │
│  BrewBorderHi  │  #3F3F49                                          │
└────────────┴──────────────────────────────────────────────────────┘
```

### 1.3 字体系统

| 层级 | 字体 | 字重 | 大小 | 颜色 | 用途 |
|------|------|------|------|------|------|
| H0 | JetBrains Mono | Bold(700) | 36sp | BrewGreen+Cyan | Store 页面大标题 "Rokid Lab" |
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
| 圆角 | **12dp**（全站统一） |
| 边框宽度 | **1dp**（全站统一） |
| 模块间距 | 24dp |
| 组件间距 | 16dp |
| 小间距 | 8dp / 12dp |
| 内边距 | 16dp |
| 页面水平 padding | 16dp |

---

## 二、全局组件

### 2.1 BrutalButton（主按钮）

定义：[store/StoreHomeScreen.kt](file:///d:/rokidapp/cxrl/RokidLab/phone-app/src/main/java/com/rokidlab/phone/store/StoreHomeScreen.kt) `BrutalButton`

| 属性 | 值 |
|------|-----|
| 高度 | 52dp |
| 宽度 | fillMaxWidth |
| 圆角 | 12dp |
| 边框 | 1dp, 颜色=模块色 alpha 0.5 |
| 背景 | 模块色 alpha 0.12 |
| 文字 | 14sp SemiBold, letterSpacing 1sp, 颜色=模块色 |
| 按下交互 | 缩放至 98% + 透明度 85% |
| 动画 | `spring(dampingRatio = MediumBouncy, stiffness = Medium)` |

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

### 2.5 ScreenStreamStatusCard（安装状态卡片）

| 属性 | 值 |
|------|-----|
| 容器 | clip(RoundedCornerShape(12dp)), background(BrewPanel) |
| 边框 | 1dp, statusColor alpha 0.3, 圆角 12dp |
| 顶部状态条 | 填满宽度, 背景=statusBg, padding 20dp×14dp |
| 状态图标 | 20sp Bold, color=BrewBg |
| 状态文字 | "SCREENSTREAM {状态}" 16sp Bold, BrewBg, letterSpacing 2sp |
| 副文字 | "投屏和文件管理功能必需" 11sp Medium, BrewBg alpha 0.7 |
| 偏移装饰线 | 高 4dp, BrewBorderHi 背景, 安装中时 0↔8dp 脉冲动画 |
| 安装中容器 | 高 52dp, BrewPanelAlt 背景 12dp, 1dp 边框 BrewCyan alpha 0.3 |
| 安装中文字 | "⟳ 安装中..." 14sp Bold BrewCyan letterSpacing 3sp |
| 脉冲动画 | `infiniteRepeatable(tween 800ms, LinearEasing, Reverse)` |

**四种状态：**

| 状态 | statusColor | statusBg | statusText | statusIcon |
|------|------------|----------|------------|------------|
| 运行中 | BrewWarning | BrewWarning | "运行中" | "▶" |
| 安装中 | BrewCyan | BrewCyan | "安装中" | "►" |
| 已安装 | BrewSuccess | BrewSuccess | "已安装" | "✔" |
| 未安装 | BrewRed | BrewRed | "未安装" | "✘" |

**四种操作按钮（BrutalButton）：**

| 状态 | 按钮文字 | 颜色 |
|------|---------|------|
| 运行中 | "● 停止 ScreenStream" | BrewRed |
| 未安装 | "● 安装 ScreenStream" | BrewAmber |
| 已安装 | "▶ 启动 ScreenStream" | BrewSuccess |

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
| 选中态 | 背景 BrewGreen, 文字 BrewBg, 边框 1dp BrewGreen |
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
| 安装目标标签 | 10sp Bold, INSTALLED→BrewGreen / UPDATE→BrewWarning / else→BrewText |
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

**七模块映射（水平排列，超出可左右滑动）：**

| 页面 | label | color |
|------|-------|-------|
| STORE | "应用商店" | BrewGreen |
| SCREEN_MIRROR | "屏幕镜像" | BrewCyan |
| PHONE_MIRROR | "手机投屏" | BrewPurple |
| FILE_MANAGER | "文件管理" | BrewAmber |
| ADB_TOOLS | "ADB工具" | BrewInfo |
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
| 标题行 | 图标 Icons.Outlined.Visibility 20dp BrewGreen + "眼镜连接" 15sp SemiBold |
| 状态圆点 | 6×6dp, clip 4dp, 已连接时脉冲缩放至 1.6x + alpha 混合, 400ms tween |
| 链路信息 | "CXR-L 链路 / {status}" 11sp Medium BrewMuted/状态色 |
| HostApp 图标 | 45×45dp, RoundedCornerShape(13dp) |
| HostApp 名称 | 14sp SemiBold BrewTextBright |
| HostApp 版本 | 11sp Medium BrewMuted |
| HostApp 选择器 | 水平滚动 Row, 选中色 BrewGreen |

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
| 标签下线 | 32×3dp 模块色色块 |
| 设备 IP | 18sp Bold, BrewInfo, letterSpacing 1sp |
| 状态 | 14sp, 已连接→BrewSuccess "已连接" / 未连接→BrewError "未连接" |
| 连接按钮 | BrutalButton "🔗 连接眼镜" / "断开" |

### 2.15 AppMgrListItem（ADB 应用列表项）

| 属性 | 值 |
|------|-----|
| 圆角 | 12dp |
| 边框 | 1dp BrewBorder |
| 内边距 | 12dp |
| 包名 | 14sp Bold BrewTextBright, fontFamily=JetBrains Mono |
| 选中标记 | 左侧 ✓ 图标 14sp Bold BrewGreen 或选中背景色 |
| 冻结标记 | ❄️ 14sp 尾缀 |
| 行高 | 约 44dp，多行自动折叠 |
| 列表最大高度 | 420dp，超出可滚动 |

---

## 三、页面构成

### 3.1 Store 页面（应用商店）

```
Rokid Lab（36sp Bold BrewGreen） + Lab（36sp Bold BrewCyan）
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
→ ScreenStreamStatusCard 安装状态
→ BrutalButton "▶ 开始镜像" [BrewCyan]
→ UsageInstructionsCard [BrewCyan]
```

### 3.3 PhoneMirror 页面（手机投屏）

```
ModuleHeader "手机投屏" / "手机屏幕投射到眼镜" [BrewPurple #D4A85C]

投屏中时:
  "投屏中" 20sp Bold BrewGreen
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

→ SettingCard "应用版本" [BrewGreen #E85D3F]
→ SettingCard "主机应用" [BrewCyan #5B8FB9] 可点击跳引导
→ SettingCard "更新状态" / BrutalButton "有更新可用" [BrewGreen]
→ BrutalButton "切换商店源" [BrewCyan]

→ ── 眼镜端服务 ──
  → SettingCard "ScreenStream" 已安装(绿)/未安装(黄)
  → BrutalButton "重装眼镜端" [BrewWarning #F0A050] 停止→等待800ms→推送安装
  → "正在安装中..." BrewCyan 12sp（安装中时显示）

→ 开发者卡片: BrewPanel + 1dp BrewBorder 12dp 内 16dp padding
  标签 "开发者" 10sp Bold BrewDim letterSpacing 2sp
  装饰线 32×3dp BrewGreen
  文字 "DLOVER" 24sp Bold BrewGreen
```

---

## 四、辅助页面

### 4.1 GuideScreen（引导页）

| 组件 | 规格 |
|------|------|
| 主标题 | "欢迎使用 Rokid Lab" 32sp Black(900) BrewGreen letterSpacing 2sp |
| 副标题 | "by DLOVER" 14sp BrewMuted |
| 进度指示器 | 水平 Row, 四个圆点 12dp, 已完成→BrewSuccess/当前→BrewGreen/未完成→BrewDim |
| HostApp 选择卡片 | 56dp 高, 12dp 圆角, 选中→BrewGreen 背景 文字 BrewBg, 未选中→BrewPanel 文字 BrewText, 边框 1dp |
| 商店源按钮 | 56dp 高, BrewGreen 背景 12dp 圆角, "选择商店源" 16sp Bold BrewBg |
| 授权按钮 | 56dp 高, BrewGreen/BrewSuccess 背景 12dp 圆角, "点击授权"/"已授权 ✓" 16sp Bold BrewBg |
| 步骤说明 | BrewPanel 背景 12dp 圆角, 1dp BrewBorder, 16dp padding, 提示文字 14sp BrewMuted |

### 4.2 UpdateDialog（更新对话框）

| 属性 | 值 |
|------|-----|
| 形状 | Card RoundedCornerShape(20dp) |
| 容器色 | BrewPanelAlt |
| 边框 | BorderStroke 1dp BrewBorderHi |
| 标题 | "有可用更新" / "下载中..." titleLarge, BrewGreen |
| 内容 | "RokidLab {version} 已准备好安装。" / "RokidLab {version}（{percent}%）", bodyMedium, BrewText |
| 进度条 | LinearProgressIndicator, BrewCoral, track=BrewPanel |
| 取消按钮 | TextButton "取消" BrewCoral 12sp |
| 稍后按钮 | TextButton "稍后" BrewDim |
| 更新按钮 | Button containerColor=BrewGreen, 文字 "更新" BrewBg |

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
| 投屏中 | "投屏中" 24sp Bold BrewGreen + 状态 16sp BrewTextBright + "停止投屏" Button BrewCoral |

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
