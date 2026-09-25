# Lab 宿主运行须知（开发前必读）

本文是 RokidLab 自托管 AIUI 宿主的硬性约束；与官方 SKILL.md 冲突时以本文为准。官方参考文件缺失的细节按本文件推理，禁止编造。

## 0. 开发必读顺序

1. 本须知全文已在 load_skill 返回中，优先级最高。
2. 写代码前用 load_skill_section(name="aiui-dev", section=…) 读官方章节，不要凭印象写 SFC：写 .ink 读 framework.md 的 SFC 章节，按键/生命周期查 events.md，组件查 components.md，样式查 wxss.md，API 先查 apis.md 索引再进对应域（wx.* → apis-wx.md），交付前过一遍 checklist.md。
3. 本技能快照基于官方 main（2026-09-23，对应运行时 ink v0.18.0，RokidLink 内嵌运行时已同步升级）。可用新能力：`<streamdown>` / `<table>` / `<video>` 流式渲染、自定义组件、OPFS 持久化、流式 fetch（TextDecoder stream:true）、Web Audio、Widgets、后台 Agent Workers（app.json 用 `agentWorkers`，**`workers` 字段已废弃**）。⚠️ 新能力按需使用：页面若用了 0.16+ 的 API，在老固件眼镜的官方通道（AiuiActivity）可能渲染不出，不确定时优先用基础档（0.14）能力。

## 1. 运行链路与规模上限

1. save_code_file 写项目文件（app.json + pages/index/index.ink，可加页；新页面必须登记进 app.json 的 pages 路由）。
2. open_aiui_app 打开（target=phone 在手机上演示，target=glasses 送到眼镜）；stop_aiui_app 关闭；list_my_aiui_apps 查历史。
   - **手机上先看不用装**：刚写完的项目直接 open_aiui_app(target=phone, appName=项目名) 就会就地打包并浮出演示卡片 —— 改一版看一眼只要几秒，确认满意了再 install_aiui_project 送到眼镜。
   - install_aiui_project 只负责打包推送到眼镜（只推不自动开）。
3. **改已有项目**先 read_code_file 读回真源码（只传 project 返回清单，带 file 读全文），只改受影响处后同名覆盖写回再重装。禁止凭印象整页重编。

### 单次输出硬上限（★大文件出错根因）

- 一次 save_code_file **只写一个文件**，content ≤ 8000 字符（≈300 行）。写不下就**拆页面**分多次写，一次一个、写完再写下个。
- **截断信号**：save_code_file 报「参数不是合法 JSON」＝被 max_tokens 截断 → 拆更小的文件或页面，**不要原样重试**。

## 2. 图形界面与配色约束（游戏/棋盘/列表必读）★

### 2.1 单色绿光波导：只有绿通道亮度可见（最高频翻车点）
- 眼镜屏是**单色绿通道**（官方 design-system-green.md：基色 `#40ff5e`，background `#000000`）。**亮度即信息**：红/蓝/洋红/橙紫几乎不可见；深蓝灰（`#0d1117`/`#161d2b`/`#2b3446`）≈ 黑 ≈ 不可见。
- ⇒ 别用「深底 + 亮字 + 彩色块」的常规暗色主题分区。用**绿通道亮度分层**：结构线 24–48%、正文 72%、数值/选中 100%（`#40ff5e`）、背景 `#000000`。
- 实测事故（2048）：背景 `#0d1117` + 瓦片 `#2b3446`，眼镜上只剩数字可见，整个棋盘消失。

### 2.2 视口：宽 480，高按 ≤400 设计
- 两宿主实测不一致：自托管 AiuiLinkActivity 报 480x640；AssistServer 宿主 `wx.getWindowInfo()` 返回 **480×400**。
- ⇒ 宽固定 **480**、高按 **≤400** 设计（两宿主都放得下）；别照搬手机端 750/640。

### 2.3 网格/棋盘/列表用 `<view>`+`<text>`，不要用 `<canvas>` 画界面
- **界面一律 DOM 布局**，不要用 `<canvas>`：尺寸不合会被裁、文字要自己算坐标、单色屏下彩色全退化成亮度。（canvas **能画**且会上屏，真要用必须把尺寸写成 `width`/`height` **属性**，缺省仅 300×150。）
- ⇒ 棋盘/网格/列表：
  - `.board { display: grid; grid-template-columns: 72px 72px 72px 72px; grid-template-rows: 72px 72px 72px 72px; gap: 6px; }`，16 个 `<view>` 作**直接子节点**（顺序即行列，不要嵌套 4 个 row）。
  - 每格：`<view class="{{ c00 }}"><text class="num">{{ v00 }}</text></view>`；`data` 里为每格声明 `cNN`（class 串）与 `vNN`（文字）两字段，onLoad 里先初始化。
  - 配色靠 class，用官方透明度阶梯：空 `rgba(64,255,94,.06)` → `.24` → `.48` → `.72` → 最大 `#40ff5e`（如 2048 瓦片）；一格一个 class，**不要用 style 内联绑定**。
  - 刷新：全部格子一次 `setData`（32 个字段无压力）；**状态变化一律由按键驱动** —— 本宿主**定时器不执行**（见 §9.0），别用 `setInterval`/`setTimeout`。

### 2.4 彩色显示（yodaos-sprite-full）★仅手机预览可用
- 眼镜屏是**单色绿光波导**，物理上没有彩色通道，**永远不支持彩色**；彩色只对手机预览有意义。
- **默认绝不写 app.json 的 theme 字段**（打包时也会强制剥离，保眼镜安全）。
- **只有用户明确说出"彩色/全彩/带颜色"时**才做彩色版：
  1. 页面按全彩配色写（深底亮字、彩色 token 都可以，不再受 §2.1 绿通道约束）；
  2. 打开用 open_aiui_app(**target=phone, color=true**)——打包自动注入 `"theme":"yodaos-sprite-full"`，只进手机包。
- 同一个项目推眼镜时颜色会退化为绿通道亮度、效果不可控（深蓝灰≈黑）。要上眼镜必须按 §2.1 绿色规范重写配色，并提醒用户彩色版上眼镜会走样。

## 3. 渲染宿主差异（自托管 AiuiLinkActivity）

- 本宿主只渲染全屏页（会话卡不可用）。
- 输入唯一可靠通道 = Page 级 onKeyDown/onKeyUp；bindfocus/触摸/悬停/拖拽不可靠。
- 官方环境有默认行为（Backspace 返回、↑↓ 滚动、Enter 激活），自托管没有、页面全包；键处理首行 `event.preventDefault()`。
- 文字是第一反馈通道；任何按键后必须 setData 刷新文字/高亮/计分。

## 4. 按键输入契约（⚠️ 全部宿主统一：手机全屏预览 = 眼镜手柄，同一套）

**唯一正确写法**（两种宿主都这样收）：

```js
// Page 对象上定义 onKeyDown（不是 onKey，不是 @keydown，不是 window 事件）
onKeyDown(e) {
  e.preventDefault();
  switch (e.code) {            // 读 e.code 字符串，不是 e.keyCode / e.which 数字
    case 'ArrowUp':   this.move(0, -1); break;
    case 'ArrowDown': this.move(0, 1); break;
    case 'ArrowLeft': this.move(-1, 0); break;
    case 'ArrowRight': this.move(1, 0); break;
    case 'Enter': case 'KeyZ': this.confirm(); break;
    case 'Backspace': case 'Escape': this.exit(); break;
  }
},
```

🚫 **禁止的反面写法**（引擎不派发这些事件，写了页面永远收不到按键）：
- `onKey(e)` / `onkeydown` / `@keydown` / `window.addEventListener('keydown', …)` —— 本引擎只认 Page 级 `onKeyDown`/`onKeyUp`；
- 读 `e.keyCode` / `e.which` / `e.key` 数字键码（19/20/21/22…）—— 契约是 **`e.code` 字符串**（`'ArrowUp'`、`'Enter'`、`'KeyZ'`…），`keyCode` 恒为 0/undefined。

键值映射：↑↓←→→`ArrowUp/Down/Left/Right`；Select/Start→`Enter`；A/B/C→`KeyZ/KeyX/KeyC`；X/Y/Z→`KeyA/KeyS/KeyD`；L/R→`KeyQ/KeyW`；眼镜 BACK/Esc→`Backspace`/`Escape`；镜腿物理键→`GlobalHook`。

> 自检口诀：写完页面搜一遍 `onKey`、`keyCode` 两个词——出现即错，必须改成 `onKeyDown` + `e.code`。

## 5. 手柄语义标准

- 十字=导航/移动；Enter：菜单=确认、游戏=暂停。
- KeyZ(主确认)最顺手、KeyX(取消)次之、KeyC(道具)第三；KeyA/S/D 扩展位；KeyQ/W 肩键=翻页。
- 每页都能退出：保留「退出/返回」项，首页处理 Backspace。
- 需输入文字时做「字母宫格」（方向+Enter 选中）；眼镜没有输入法。
- 操作项 ≤ 10、层级 ≤ 2；长按=重复（keydown 置位 + keyup 复位）。

## 6. 页面硬性规范（保存前自检，缺一块会被拒收）

1. `<script def>` 页面级 JSON 配置（navigationBarTitleText）。
2. `<script setup>` 唯一逻辑区，export default 页面对象（data/onLoad/onShow/方法/setData）；**禁止 `onReady`**（本引擎不派发）。禁止裸 `<script>`。
3. `<page>` 根标签，禁止 `<template>`。
4. `<style>` class 样式。
5. 用到的每键在 onKeyDown/onKeyUp 都处理；每键动作即时反馈（setData 刷新文字）。
6. **按键 API 自检**：页面里不得出现 `onKey(`、`e.keyCode`、`e.which`、`addEventListener('keydown'` 任何一处；只允许 `onKeyDown`/`onKeyUp` + `e.code`（见 §4）。

## 7. 视觉与防鬼影

- 背景用 `#000000`（透明底）：光波导对大面积高亮内容会产生光学鬼影（右上角倒置虚影），低亮度页面可显著抑制；**不要大面积铺 24% 以上的绿色填充**（泛光并遮蔽内容，官方 luminance rules 明令禁止）。
- 避免纯白全屏闪烁与超大纯白字体；正文用 72% 绿。

## 8. 交付纪律

- 只通过 save_code_file 落盘；回复 ≤ 3 句、纯文本、无代码围栏、无源码转述。
- 用户只说「做个支持手柄的 XX」时直接套官方模板/设计规范产出，不必再询问。

## 9. 调用手机端工具（Lab 工具口）

页面可调用手机端绝大部分工具（音乐/天气/搜索/提醒/设备信息等），这是页面与外部世界交互的唯一通道——不要在页面里 fetch 外网。
**不开放的只有 5 个**：`open_aiui_app`（页面打开自己＝自指递归）、`list_sessions` / `read_session` / `session_trace`（会读到用户的全部会话与完整事件流，页面是第三方制品不该能批量枚举导出）、`research_subtask`（会另起子代理）。调它们只会收到明确的拒绝文案，**别把它当成工具名写错**。

### 9.0 ⚠️ 本宿主无事件循环 —— 工具调用必须回调式（最高频翻车点）
ink 沙箱**只执行同步代码**：`Promise.then` / `await` / `setTimeout` / `setInterval` 的回调**一个都不执行**（真机实测零输出）；只有 `onLoad`/`onShow`/`onKeyDown`/`onMessage` 这类**事件回调**有效。
- ✅ 唯一正确写法：`globalThis.Lab.callTool(名字, 参数对象, function (结果, 错误) { … })`
- ❌ 禁止 `await globalThis.Lab.callTool(...)` / `.then(...)` / `Promise.all` —— **永不返回，页面当场卡死**。
- ❌ 禁止用 `setTimeout`/`setInterval` 做超时、重试、动画（超时保护在宿主侧，页面拿不到）。
- 必须写 `globalThis.Lab` 或 `window.Lab`（裸 `Lab` 在页面 realm 不可用）。

### 9.1 onMessage：既要转发工具结果，也要**执行启动参数**（两件都必须写）
```js
onMessage(e) {
  var m = (e && e.data) ? e.data : e;
  if (globalThis.Lab && globalThis.Lab.onHostMessage(e)) return;   // 工具结果，桥已消费
  if (m && m.type === 'launch') this.onLaunch(m.params || {});     // 启动参数是**对象**，勿 JSON.parse
}
```
**启动参数＝用户在语音里表达的意图，必须据此执行动作**（只显示、不执行 = 不合格）：
```js
onLaunch(p) {
  this._song = p.songName || '';               // 记住，后续按键复用
  if (this._song) this.playSong(this._song);   // 立刻播放，别让用户再按一次
}
```
键名与 `open_aiui_app` 的 params 对应：音乐 `songName`、搜索 `keyword`、城市 `city`。
- ⚠️ **别让启动动作被互斥标志挡掉**：`onLoad` 已发起的取数会把 `_loading` 占住，`onMessage` 里的启动动作（自动播放等）就被静默丢弃 → **各动作用独立标志**（实测踩过）。

### 9.2 调用范式
```js
// 在按键处理里发起；回调里同步刷新界面（不要 await、不要 setTimeout）
globalThis.Lab.callTool('control_music', { action: 'play', songName: '西厢' }, function (res, err) {
  self.setData({ tip: err ? ('失败: ' + err) : String(res).substring(0, 30) });
});
```
- 工具名**逐字照抄**，写错只会收到 `unknown tool: xxx`：`control_music` `get_now_playing` `get_cover_image` `get_weather` `search_web` `fetch_webpage` `manage_timer` `get_current_time` `calculate` `search_knowledge_base` `save_summary_txt` `show_image` `get_glasses_status` `get_phone_status`；不确定就先 `globalThis.Lab.listTools(function (tools, err) { … })`。
- 参数名按常规直觉（songName/keyword/city），但**必填参数不能省**：`control_music` 必须传 `{action:'play',songName:'…'}`（传 `{}` 会失败）；结果都是字符串，自己 `JSON.parse`。
- **一次只发起一个调用**（通道串行，并发会超时）；**失败不要自动重试**（会绕过去重，造成重复拨号等副作用）。
- 界面刷新一律由**按键事件**驱动；每次调用都要有 loading 文案（往返 1~3 秒，最长 15 秒）。
- 启动参数经 onMessage 投递一次，**onLoad 里拿不到**（要在 onMessage 里处理，见 9.1）。

### 9.3 音乐播放器取数
`control_music` 只回纯文本、不含封面歌词；素材必须再调 `get_now_playing`（JSON 文本）：
`{"playing":true,"title":"西厢","artist":"后弦","album":"九公主","durationMs":240000,"positionMs":12345,"cover":"https://…","lyrics":[{"timeMs":0,"text":"…"}]}`
- **一次取数、本地推进**：拿 `positionMs` 用 `Date.now()` 差值自己算当前行；**页面每分钟仅 30 次调用额度，轮询几十秒即耗尽、之后全被拒**（现象：歌在放但封面歌词空白）。
- **别拿 `playing` 当闸门**：`control_music` 后立刻取数已为 true；false 即无曲目，显示「未在播放」。
- `cover` 给 `<image src="{{ cover }}"></image>`，空则隐藏该节点。
