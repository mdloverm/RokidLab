# RokidLab UI Design Reference

## 一、全局设计系统

### 1.1 设计风格

**Neo Brutalism（新粗野主义）** — 粗边框、高对比度、无圆角、偏移阴影、装饰性色块

### 1.2 配色方案（60-30-9-1 法则）

```
┌─────────────────────────────────────────────────────────────┐
│  60% — 深邃午夜蓝（背景色系）                                  │
├───────────┬─────────────────────────────────────────────────┤
│  BrewBg        │  #0A1420   主背景色                        │
│  BrewPanel     │  #101D2D   面板/卡片背景                    │
│  BrewPanelAlt  │  #162536   替代面板（分类标签未选中）         │
│  BrewPanelHi   │  #1C3048   高亮面板（按钮禁用态）            │
├───────────┼─────────────────────────────────────────────────┤
│  30% — 柔和燕麦白（文字/内容色系）                             │
├───────────┼─────────────────────────────────────────────────┤
│  BrewTextBright│  #F5F0E8   亮白文字                        │
│  BrewText      │  #E8E0D3   正文文字                        │
│  BrewMuted     │  #B5AD9E   次要/灰色文字                    │
│  BrewDim       │  #7A7366   禁用/最淡文字                    │
├───────────┼─────────────────────────────────────────────────┤
│  9% — 跃动珊瑚橘（强调色）                                    │
├───────────┼─────────────────────────────────────────────────┤
│  BrewCoral     │  #FF6B5B   主强调色（按钮/标题/模块色）       │
│  BrewCoralDim  │  #D95A4C   次要强调                         │
├───────────┼─────────────────────────────────────────────────┤
│  1% — 一抹薄荷绿（点缀色）                                    │
├───────────┼─────────────────────────────────────────────────┤
│  BrewMint      │  #7BECB8   成功/积极状态/Logo              │
├───────────┼─────────────────────────────────────────────────┤
│  边框                                                     │
├───────────┼─────────────────────────────────────────────────┤
│  BrewBorder    │  #1E3048   默认边框                        │
│  BrewBorderHi  │  #2A4260   高亮边框（搜索栏/选中态）         │
├───────────┼─────────────────────────────────────────────────┤
│  语义别名                                                   │
├───────────┼─────────────────────────────────────────────────┤
│  BrewGreen     │  #7BECB8 → BrewMint     成功/运行中        │
│  BrewCyan      │  #FF6B5B → BrewCoral    屏幕镜像模块        │
│  BrewPurple    │  #FF6B5B → BrewCoral    手机投屏模块        │
│  BrewAmber     │  #FF6B5B → BrewCoral    文件管理/警告       │
│  BrewMagenta   │  #FF6B5B → BrewCoral    设置/运行中         │
│  BrewRed       │  #FF4444              错误/危险/停止       │
│  BrewSuccess   │  #7BECB8 → BrewMint     已安装             │
│  BrewError     │  #FF4444              错误                 │
│  BrewWarning   │  #FFB347              橙色警告             │
│  BrewInfo      │  #64B5F6              信息提示（安装APK按钮）│
│  BrewOrange    │  #FFB347              橙色警告             │
└───────────┴─────────────────────────────────────────────────┘
```

### 1.3 字体

| 用途 | 字体 | 说明 |
|------|------|------|
| 全局 | JetBrains Mono | 等宽字体，Regular/Medium/Bold |
| 默认字号 | 13-14sp | 正文 |
| 标题 | 32sp Bold | 模块标题 |
| 小标签 | 10-12sp Bold | 字母间距 1-3sp |
| 状态数字 | 18-28sp Bold | 设置/眼镜IP |

### 1.4 通用组件样式

**BrutalButton（主按钮）**
- 高 56dp、填满宽度
- 背景: BrewBg
- 边框: 4dp, 颜色跟随语义色
- 内层: 语义色 15% 透明度底色
- 文字: 14sp Bold, letterSpacing 2sp

**SettingCard（设置卡片）**
- 背景: BrewPanel
- 边框: 3dp BrewBorder
- 标签: 10sp 大写, BrewMuted, letterSpacing 3sp
- 内容: 18sp Bold, 语义色
- 标签下: 32x3dp 语义色装饰线

**IpAddressInputCard（IP 输入卡片）**
- 背景: BrewPanel
- 边框: 3dp 语义色
- 标签: 12sp Bold, letterSpacing 1sp
- 输入框: OutlinedTextField, focusBorderColor=语义色

**UsageInstructionsCard（使用说明卡片）**
- 背景: BrewPanel
- 边框: 2dp BrewBorder
- 标题: 12sp Bold, 语义色, letterSpacing 1sp
- 说明: 12sp BrewMuted, 行高 20sp

**ModuleHeader（模块标题）**
- 标题: 32sp Bold, 语义色, letterSpacing 4sp
- 副标题: 14sp BrewMuted

---

## 二、眼镜端（glasses-screen-service）

### 2.1 MainActivity — 状态面板

**布局** (`activity_main.xml`)

```
┌──────────────────────────┐
│                          │
│           ●              │  ← 状态灯 16x16dp
│                          │
│        已就绪             │  ← 状态文字 16sp #AAAAAA
│                          │
│          IP              │  ← 标签 11sp #555555
│    192.168.1.168         │  ← IP 地址 28sp Bold #E0E0E0
│                          │
└──────────────────────────┘
```

| 属性 | 值 |
|------|-----|
| 背景色 | `#0A0A0F`（深蓝黑） |
| 布局 | LinearLayout vertical, gravity center |
| 内边距 | 40dp |
| 状态灯变化色 | 灰 `#555555` → 橙 `#FFA500` → 绿 `#4CAF50` → 红 `#FF5722` |

### 2.2 PhoneMirrorActivity — 投屏画面

**纯代码创建，无 XML 布局**

```
┌──────────────────────────┐
│                          │
│    [投屏画面 全屏]         │
│    FIT_CENTER            │
│    背景 #000000           │
│                          │
└──────────────────────────┘
```

| 属性 | 值 |
|------|-----|
| 背景色 | `#000000`（纯黑） |
| 内容 | 单一 ImageView, scaleType FIT_CENTER |
| 交互 | 点击 → Toast 提示双击退出 |
| 断开连接 | 自动 finish() |
| 屏幕方向 | portrait（竖屏锁定） |
| 无标题 | 无任何文字/状态显示 |

---

## 三、手机端（phone-app）— Jetpack Compose

### 3.1 MainActivity — 主界面（包含导航）

**背景**: BrewBg `#0A1420`

#### 导航结构
```
┌──────────────────────────────────┐
│  Header (搜索/刷新/更多)           │
│  搜索栏 (可选)                     │
│  分类标签栏 (横向滚动)             │
│  眼镜连接面板                     │
├──────────────────────────────────┤
│  功能模块 (底部导航切换)            │
│  ┌────┬────┬────┬────┐          │
│  │商店│镜像│投屏│文件│          │
│  └────┴────┴────┴────┘          │
└──────────────────────────────────┘
```

#### Header 区域
| 元素 | 样式 |
|------|------|
| Logo 文字 | "Rokid" BrewGreen + "Lab" BrewCyan, 24sp SemiBold |
| 搜索图标 | 38x38dp, RoundedCornerShape(12dp), 选中变 BrewGreen |
| 更新图标 | 38x38dp, 有更新时琥珀色+红点指示 |
| 刷新图标 | 38x38dp, 旋转动画 800ms |
| 更多菜单 | 38x38dp, DropdownMenu: "切换源"/"安装 APK 到眼镜" |

#### SearchBar
| 属性 | 值 |
|------|------|
| 背景 | BrewPanel alpha 0.88 |
| 边框 | 1dp BrewBorderHi alpha 0.42, RoundedCornerShape(14dp) |
| 文字色 | BrewTextBright 15sp |
| 占位符 | "搜索" BrewMuted alpha 0.75 |

#### CategoryChip（分类标签）
| 属性 | 值 |
|------|------|
| 高度 | 34dp |
| 圆角 | 17dp（完全圆角） |
| 选中态 | 背景 BrewGreen, 文字 BrewBg |
| 未选中态 | 背景 BrewPanelAlt alpha 0.86, 边框 BrewBorderHi alpha 0.44 |
| 文字 | 13sp SemiBold |

#### ConnectionPanel（眼镜连接面板）
| 属性 | 值 |
|------|------|
| 卡片背景 | BrewPanel alpha 0.78 |
| 边框 | 1dp BrewBorderHi alpha 0.46 |
| 圆角 | 15dp |
| 标题 | "眼镜连接" 15sp SemiBold, 图标 BrewGreen |
| 状态指示 | 6x6dp 圆点 + "CXR-L链路" 11sp |
| 状态颜色 | 已连接→BrewCyan, 已授权→BrewGreen, 未安装→BrewAmber |
| 主机应用卡片 | 45x45dp 图标, RoundedCornerShape(13dp) |
| 授权按钮 | StoreActionButton, 文字 BrewGreen 13sp |

#### SectionHeader（分段标题）
| 属性 | 值 |
|------|------|
| 标题 | 18sp SemiBold BrewTextBright |
| 操作按钮 | 13sp SemiBold BrewGreen, 箭头 18dp |

---

### 3.2 Store 页面（应用商店）

**AppListItem（应用列表项）**
| 属性 | 值 |
|------|------|
| 背景 | BrewPanel |
| 边框 | 2dp BrewBorder |
| 图标 | 56x56dp, RoundedCornerShape(14dp) |
| 名称 | BrewTextBright 16sp Bold |
| 描述 | BrewMuted 13sp, 最多2行 |
| 安装按钮 | StoreActionButton, 高28dp |

**StoreActionButton**
| 属性 | 值 |
|------|------|
| 圆角 | 8dp |
| 主按钮 | 背景 语义色, 文字 BrewBg |
| 次按钮 | 背景透明, 文字/边框 语义色 |
| 危险按钮 | 颜色 BrewRed |
| 禁用态 | 背景 BrewPanelHi alpha 0.45 |
| 文字 | 13sp SemiBold |

**EmptyState（空状态）**
| 属性 | 值 |
|------|------|
| 高度 | 132dp |
| 背景 | BrewPanel, 边框 BrewBorder, 圆角 14dp |
| 文字 | "未找到应用" BrewMuted 14sp Bold |

**BrandTitle** — "Rokid" BrewGreen + " Lab" BrewCyan, 可变字号 SemiBold

---

### 3.3 ScreenMirror 页面（屏幕镜像模块）

页面构成：
```
ModuleHeader "屏幕镜像" / "眼镜屏幕实时同步到手机"  [BrewCyan]
→ IpAddressInputCard (IP 地址)
→ ScreenStreamStatusCard (安装状态)
→ BrutalButton "▶ 开始镜像" [BrewCyan]
→ UsageInstructionsCard (5步说明)
```

### 3.4 PhoneMirror 页面（手机投屏模块）

页面构成：
```
ModuleHeader "手机投屏" / "手机屏幕投射到眼镜"  [BrewPurple]

未投屏状态:
→ IpAddressInputCard (IP 地址)
→ ScreenStreamStatusCard (安装状态)
→ BrutalButton "▶ 开始投屏" [BrewPurple]
→ UsageInstructionsCard (4步说明)

投屏中状态:
→ 居中 "投屏中" 20sp Bold [BrewGreen]
→ 状态文字 14sp [BrewMuted]
→ BrutalButton "■ 停止投屏" [BrewRed]
```

### 3.5 FileManager 页面（文件管理模块）

页面构成：
```
ModuleHeader "文件管理" / "管理眼镜中的文件"  [BrewAmber]
→ IpAddressInputCard (IP 地址)
→ ScreenStreamStatusCard (安装状态)
→ BrutalButton "▶ 打开文件管理器" [BrewAmber]
→ BrutalButton "安装本地 APK" [BrewInfo]
→ UsageInstructionsCard (5步说明)
```

### 3.6 Settings 页面（设置模块）

页面构成：
```
ModuleHeader "设置" / "应用配置"  [BrewMagenta]

→ SettingCard "应用版本" [BrewGreen]
→ SettingCard "主机应用" [BrewCyan] 可点击跳引导
→ BrutalButton "有更新可用" / SettingCard "暂无更新" [BrewMuted]
→ BrutalButton "切换商店源" [BrewMagenta]
→ 开发者卡片: "DLOVER" 24sp [BrewGreen]
```

### 3.7 退出确认对话框

| 属性 | 值 |
|------|------|
| 容器色 | BrewPanel |
| 标题色 | BrewTextBright |
| 文字色 | BrewText |
| 确认按钮 | 120x44dp, 边框 3dp BrewRed, 背景 BrewBg |
| 取消按钮 | 120x44dp, 边框 3dp BrewBorder, 背景 BrewBg |
| 按钮文字 | 14sp Bold, letterSpacing 2sp |

---

### 3.8 ScreenStreamStatusCard（ScreenStream 安装状态卡片）

Neo Brutalist 风格，状态驱动的分段布局：

```
┌──────────────────────────────────────┐
│  ✘ SCREENSTREAM 未安装    [红色背景]  │
│    投屏和文件管理功能必需   [11sp半透] │
├──────────────────────────────────────┤
│  ──── 偏移装饰线 4dp ────             │
├──────────────────────────────────────┤
│  ● 安装 ScreenStream  [按钮 56dp高]   │
└──────────────────────────────────────┘
```

**状态对应色**:
| 状态 | 颜色 | 图标 |
|------|------|------|
| 未安装 | BrewRed `#FF4444` | ✘ |
| 安装中 | BrewCyan `#FF6B5B` | ► |
| 已安装 | BrewSuccess `#7BECB8` | ✔ |
| 运行中 | BrewMagenta `#FF6B5B` | ▶ |

**背景**: BrewPanel, 边框 4dp 跟随状态色

---

## 四、独立 Activity

### 4.1 ScreenMirrorActivity（屏幕镜像画面）

| 属性 | 值 |
|------|------|
| 主题 | RokidLabTheme（BrewBg 背景） |
| 画面 | BitmapImage, ContentScale.Fit, 可缩放 0.5x-4x |
| 缩放 | 双指手势缩放+平移, 复位按钮 |
| 状态文字 | 14sp [BrewText], 含分辨率信息 |
| 返回按钮 | 左上角 ArrowBack 图标 |
| 连接失败 | "重新连接" / "返回" 按钮（2dp圆角） |

### 4.2 PhoneMirrorActivity（手机投屏-旧版配置页）

| 属性 | 值 |
|------|------|
| 主题 | RokidLabTheme |
| 连接中 | 居中文字 "正在连接眼镜..." + 连接状态 |
| 连接失败 | 失败原因 + "重试"按钮 + "返回"按钮 |
| 投屏中 | StreamingUI（全屏画面） |
| 配置UI | IP输入 + 端口输入 + "开始投屏"按钮 BrewCoral |

### 4.3 FileManagerActivity（文件管理器）

| 属性 | 值 |
|------|------|
| 主题 | RokidLabTheme |
| 顶部栏 | ArrowBack 返回 + 当前路径（等宽字体 14sp BrewText） |
| 路径面包屑 | 可点击各部分快速跳转 |
| 排序按钮 | Sort 图标, 弹出菜单: 名称/大小/日期 |
| 快捷按钮 | 下载/重命名/删除/新建文件夹 |
| 文件列表 | 图标 + 名称(13sp BrewTextBright) + 大小/日期(BrewMuted) |
| 功能菜单 | 每行"..."按钮 → PopupMenu: 打开/预览/重命名/删除/属性/复制/剪切/粘贴 |
| 对话框 | NewFolderDialog / RenameDialog → BrewPanel背景, BrewCoral确认按钮 |

---

## 五、通用弹窗/组件

### 5.1 GuideScreen（引导界面）

| 属性 | 值 |
|------|------|
| 背景 | BrewBg |
| 标题 | "欢迎使用 Rokid Lab" 32sp Bold BrewGreen |
| 副标题 | "by DLOVER" 14sp BrewMuted |
| 步骤进度 | 3步圆点指示器 |
| 步骤1 | 选择主机应用（Rokid AI CN / Rokid AI Global 卡片） |
| 步骤2 | 选择商店源 |
| 步骤3 | 授权页面 |

### 5.2 SystemLogDock（系统日志面板）

| 属性 | 值 |
|------|------|
| 背景 | 半透明 BrewPanel, 圆角 12dp |
| 折叠态 | 显示最后一条日志 + 展开箭头 |
| 展开态 | 可滚动日志列表, 自动滚动到底部 |
| 文字 | 12sp BrewMuted, 可滚动到 160dp 高度 |

### 5.3 NewFolderDialog / RenameDialog

| 属性 | 值 |
|------|------|
| 背景 | BrewPanel, 圆角 16dp |
| 输入框 | OutlinedTextField, focusBorderColor=BrewCoral |
| 取消按钮 | Button, containerColor=BrewPanelHi |
| 确认按钮 | Button, containerColor=BrewCoral |
| 内边距 | 16dp |

---

## 六、连接状态指示

| 状态 | 显示文字 | 颜色 |
|------|---------|------|
| 已连接 | 已连接 | BrewCyan `#FF6B5B` |
| 已授权 | 已授权 | BrewGreen `#7BECB8` |
| 未安装 | 未安装 | BrewAmber `#FF6B5B` |
| 连接中 | 连接中 | BrewAmber `#FF6B5B` |
| 需要授权 | 需要授权 | BrewMuted `#B5AD9E` |
| 错误/危险 | — | BrewRed `#FF4444` |
