# Lab 宿主运行须知（开发前必读）

本文是 RokidLab 自托管 AIUI 宿主的硬性约束，官方 SKILL.md 为基础语法与 API 权威。两者冲突时以本文为准。官方参考文件（components.md / apis-*.md / wxss.md）缺失的细节按本文件规则推理，禁止编造。

## 0. 开发必读顺序（每次生成/修改 AIUI 代码都执行）

1. 本须知全文已在 load_skill 返回中（本宿主硬约束，优先级最高）。
2. 动手写代码前，必须用 load_skill_section(name="aiui-dev", section=…) 读取官方主指南相关章节，不要凭印象写 SFC：
   - 写任何 .ink 前：读第 2 章「SFC .ink Specification」。
   - 处理按键/输入时：读第 4 章「Events」。
   - 用组件拿不准时：查 components.md 对应章节。
   - API 不确定时：查对应 apis-*.md（wx.* → apis-wx.md，canvas → apis-canvas.md）。

## 1. 运行链路与规模上限

1. save_code_file 写项目文件（app.json + pages/index/index.ink，可加页；新页面必须登记 app.json 的 pages 路由）。
2. 用户要装时 install_aiui_project 打包推送（只推不自动开）。
3. open_aiui_app 打开演示；stop_aiui_app 关闭；list_my_aiui_apps 查历史。
4. **修改已有项目**必须先 read_code_file 读回真源码（只传 project 返回文件清单，带 file 读单文件全文），只改受影响部分后同名覆盖写回，再 install_aiui_project 重装。禁止凭印象整页重编（VERSION 内容指纹会自动触发眼镜重新解压）。

### 单次输出硬上限（★大文件出错根因）

- 一次 save_code_file **只写一个文件**，content ≤ 8000 字符（≈300 行）。写不下就**拆页面**（menu/play/help 各自独立 .ink）分多次写，每次只写一个、写完再写下一个。
- **截断信号**：save_code_file 报「参数不是合法 JSON」＝内容太长被 max_tokens 截断——拆成更小的文件或页面，**不要原样重试**。
- 扩展方式＝拆页面；每页都要登记进 app.json 的 pages 路由，漏登记不会被框架注册。

## 2. 图形界面与配色约束（游戏/棋盘/列表必读）★

### 2.1 单色绿光波导：只有绿通道亮度可见（最高频翻车点）
- 眼镜屏是**单色绿通道**（官方 design-system-green.md：palette = 单一绿通道，基色 `#40ff5e`，background `#000000`）。**亮度即信息**：红/蓝/洋红/橙紫（绿通道低的颜色）几乎不可见；深蓝灰（`#0d1117`/`#161d2b`/`#1e2430`/`#2b3446`）≈ 黑 ≈ 不可见。
- ⇒ 不要用「深底 + 亮字 + 彩色块」的常规暗色主题来划分区块 —— 深色块之间完全分不出边界。要用**绿通道亮度分层**：结构线 24–48%、正文 72%、数值/选中 100%（`#40ff5e`）、背景 `#000000`。
- 实测事故（2048）：背景 `#0d1117` + 瓦片 `#2b3446`/`#35415a`，眼镜上只剩数字可见，整个棋盘消失。

### 2.2 视口：宽 480，高按 ≤400 设计
- 两个宿主实测不一致：自托管 AiuiLinkActivity 报 `screen 480x640`；`wx.getWindowInfo()` 在 AssistServer 宿主返回 **480×400**（去掉状态栏等）。
- ⇒ 宽固定 **480**，高按 **≤400** 设计（两宿主都放得下）；写死 `height="640"` 只在宿主①成立。别照搬手机端 750/640，优先 flex + 小固定值。

### 2.3 网格/棋盘/列表用 `<view>`+`<text>`，不要用 `<canvas>` 画界面
- canvas 在本宿主**能画**（`wx.createCanvasContext('id')` 返回非 null，`fillStyle`/`fillRect`/`getImageData` 均正常、内容会上屏），但**不适合做界面**：尺寸不合会被裁、文字要自己算坐标、单色屏下彩色全退化成亮度。真要用就必须把尺寸写成 `width`/`height` **属性**（缺省仅 300×150，会被整块裁掉），别只给 CSS。
- ⇒ 棋盘/网格/列表一律 DOM 布局：
  - `.board { display: grid; grid-template-columns: 72px 72px 72px 72px; grid-template-rows: 72px 72px 72px 72px; gap: 6px; }`，16 个 `<view>` 作**直接子节点**（顺序即行列，不要嵌套 4 个 row）。
  - 每格：`<view class="{{ c00 }}"><text class="num">{{ v00 }}</text></view>`；`data` 里为每格声明 `cNN`（class 串）与 `vNN`（文字）两字段，onLoad 里先初始化。
  - 配色靠 class，用官方透明度阶梯：空 `rgba(64,255,94,.06)` → `.24` → `.48` → `.72` → 最大 `#40ff5e`（如 2048 瓦片）；一格一个 class，**不要用 style 内联绑定**。
  - 刷新：全部格子的 class/文字一次 `setData`（32 个字段无压力）；动画用 `setInterval` 改 `setData` + class。


## 3. 渲染宿主差异（自托管 AiuiLinkActivity）

- 本宿主只渲染全屏页，会话卡不可用；生成的页面一律是全屏交互页。
- 页面输入唯一可靠通道 = Page 级 onKeyDown/onKeyUp；bindfocus/触摸/悬停/拖拽不可靠，不要依赖。
- 官方环境有默认行为（Backspace=返回、↑↓=滚动、Enter=激活），自托管没有默认行为、页面全包；键处理函数第一行都 `event.preventDefault()`。
- 文字是第一反馈通道；任何按键后必须 setData 刷新文字/高亮/计分。

## 4. 宿主键码契约（Lab 手柄物理键 → 页面 event.code）

| Lab 手柄键 | HID 通道 | 页面收到的 code |
|---|---|---|
| ↑ / ↓ / ← / → | Consumer | ArrowUp/Down/Left/Right |
| Select / Start | Consumer | Enter |
| A / B / C | Keyboard | KeyZ / KeyX / KeyC |
| X / Y / Z | Keyboard | KeyA / KeyS / KeyD |
| L / R | Keyboard | KeyQ / KeyW |
| 眼镜 BACK / 返回 | 系统键 | Backspace |
| 镜腿物理键 | 系统键 | GlobalHook |
| Esc（长按返回） | 系统键 | Escape |

## 5. 手柄语义标准

- 十字=导航/移动；Enter：菜单=确认，游戏=暂停，暂停面板=确认菜单项。
- KeyZ(主动作=跳跃/攻击/确认) 最顺手、KeyX(取消/返回) 次之、KeyC(道具) 第三；KeyA/S/D 扩展功能位；KeyQ/W 肩键=翻页/场景切换。
- 每页都要能退出：保留高亮「退出/返回」项，首页 onKeyDown 处理 Backspace 返回上一级。
- 输入字母/数字（记单词/答题/搜索）：页面内做「字母宫格」，每键可高亮，方向+Enter 选中。禁止假设眼镜有输入法。
- 操作项 ≤ 10 / 层级 ≤ 2；纵向列表上下移，横向选项左右移。长按=重复：连续移动用 keydown 置位 + keyup 复位，不要等系统重复。

## 6. 页面硬性规范（保存前自检，缺一块会被拒收）

1. `<script def>` 页面级 JSON 配置（navigationBarTitleText）。
2. `<script setup>` 唯一逻辑区，export default 页面对象（data/onLoad/onShow/方法/setData）；**禁止 `onReady`（`ink_web_bg.wasm` 字符串表里 onReady 零命中、真机日志只记录 onLoad/onShow，写了初始化会静默不执行）**。禁止裸 `<script>`。
3. `<page>` 根标签，禁止 `<template>`。
4. `<style>` class 样式。
5. 用到的每键在 onKeyDown/onKeyUp 都处理；每键动作即时反馈（setData 刷新文字）。

## 7. 视觉与防鬼影

- 背景用 `#000000`（透明底）：光波导对大面积高亮内容会产生光学鬼影（右上角倒置虚影），低亮度页面可显著抑制；**不要大面积铺 24% 以上的绿色填充**（泛光并遮蔽内容，官方 luminance rules 明令禁止）。
- 避免纯白全屏闪烁与超大纯白字体；正文用 72% 绿（`rgba(64,255,94,.72)`）而非纯白/灰白。

## 8. 交付纪律

- 只通过 save_code_file 落盘，回复只报项目名与文件数；回复 ≤ 3 句、纯文本、无代码围栏、无源码转述。
- 用户只说「做个支持手柄的 XX」时直接套官方模板/设计规范产出，不必再询问。

## 9. 调用手机端工具（Lab 工具口）

页面可调用手机端全部工具（音乐/天气/搜索/提醒/设备信息等），这是页面与外部世界交互的唯一通道——不要在页面里 fetch 外网。

- 调用方式：`const text = await globalThis.Lab.callTool('play_song', {songName:'西厢'});`（必须写 `globalThis.Lab` 或 `window.Lab`，裸 Lab 在 ink 页面 realm 不可用）。
- 查可用工具：`await globalThis.Lab.listTools()` 返回 `[{name, description}]`。**不要写死工具名又猜参数名**——描述只给工具名（中/英），不给参数 schema，参数名按常规直觉（songName/keyword/city）。
- **工具名必须逐字准确**：自造的名字只会收到 `unknown tool: xxx`。只允许用 listTools 返回的名字。
- 四条硬性要求：①必须 try/catch（官方环境无 bridge 会 reject）；②必须有 loading 态（蓝牙往返 1~3 秒，最长 25 秒超时）；③结果是字符串自己解析；④一次只做一件事（蓝牙通道串行，不要并发）。
- **失败不要自动重试**：一次 await 失败就是失败，把错误经 setData 显示给用户即可。页面里禁止写「失败后 setTimeout 再调一次」的重试循环 —— 那会绕过去重机制，造成重复拨号、重复安装这类真实副作用。
- **不要并发 await 两个工具**（如 Promise.all）——蓝牙通道串行，并发只会让两个都更容易超时。
- 启动参数：`onMessage` 里 `JSON.parse(e.data)`，`type==='launch'` 取 `params`；只在渲染完成后投递一次，onLoad 拿不到。
- **音乐播放器（放歌 + 歌词 + 封面）取数**：`play_song` 只回一句纯文本，**不含封面与歌词**；要渲染素材，必须再调一次 `get_now_playing`（返回 JSON 文本，`JSON.parse` 后用）：
  `{"playing":true,"preparing":false,"title":"西厢","artist":"后弦","album":"九公主","durationMs":240000,"positionMs":12345,"lineIndex":5,"cover":"https://…","lyrics":[{"timeMs":0,"text":"…"}]}`
  - **一次取数、本地推进**：拿 `positionMs` 后用本地时钟（`Date.now()` 差值）自己算当前行，**禁止用 setInterval/setTimeout 反复轮询本工具**——AIUI 页面每分钟只允许 30 次工具调用，轮询几十秒就会耗尽额度，之后每次调用都被拒（现象：歌在放，歌词与封面永远空白）。
  - **不要拿 `playing` 当渲染闸门**：`play_song` 之后立刻取数时 `playing` 已为 true（正在准备中也算），`cover`/`lyrics` 也已就绪；若为 false 就是当前根本没有曲目，显示「未在播放」即可，不要循环等待。
  - `cover` 给 `<image src="{{ cover }}"></image>`（支持远程 URL）；`cover` 空则隐藏该节点，`lyrics` 空则隐藏歌词区。
  - 换歌重走一遍 `play_song` → `get_now_playing`；只有用户主动按「刷新」时才再取一次。
