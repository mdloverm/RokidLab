# Lab 宿主运行须知（开发前必读）

本文是 RokidLab 自托管 AIUI 宿主的硬性约束，官方 SKILL.md 为基础语法与 API 权威。两者冲突时以本文为准。官方参考文件（components.md / apis-*.md / wxss.md）缺失的细节按本文件规则推理，禁止编造。

## 0. 开发必读顺序（每次生成/修改 AIUI 代码都执行）

1. 本须知全文已在 load_skill 返回中，优先级最高。
2. 动手写代码前，用 load_skill_section(name="aiui-dev", section=…) 读官方主指南相关章节，不要凭印象写 SFC：
   - 写任何 .ink 前读第 2 章「SFC .ink Specification」；处理按键读第 4 章「Events」。
   - 组件拿不准查 components.md；API 不确定查对应 apis-*.md（wx.* → apis-wx.md）。

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
- ⇒ 宽固定 **480**，高按 **≤400** 设计（两宿主都放得下）；写死 `height="640"` 只在宿主①成立。别照搬手机端 750/640。

### 2.3 网格/棋盘/列表用 `<view>`+`<text>`，不要用 `<canvas>` 画界面
- **界面一律 DOM 布局**，不要用 `<canvas>`：canvas 尺寸不合会被裁、文字要自己算坐标、单色屏下彩色全退化成亮度。（canvas **能画**且会上屏，要真用就必须把尺寸写成 `width`/`height` **属性**，缺省仅 300×150 会被整块裁掉，别只给 CSS。）
- ⇒ 棋盘/网格/列表：
  - `.board { display: grid; grid-template-columns: 72px 72px 72px 72px; grid-template-rows: 72px 72px 72px 72px; gap: 6px; }`，16 个 `<view>` 作**直接子节点**（顺序即行列，不要嵌套 4 个 row）。
  - 每格：`<view class="{{ c00 }}"><text class="num">{{ v00 }}</text></view>`；`data` 里为每格声明 `cNN`（class 串）与 `vNN`（文字）两字段，onLoad 里先初始化。
  - 配色靠 class，用官方透明度阶梯：空 `rgba(64,255,94,.06)` → `.24` → `.48` → `.72` → 最大 `#40ff5e`（如 2048 瓦片）；一格一个 class，**不要用 style 内联绑定**。
  - 刷新：全部格子的 class/文字一次 `setData`（32 个字段无压力）。**状态变化一律由按键事件驱动**——本宿主**定时器不执行**（见 §9.0），别用 `setInterval`/`setTimeout` 做动画或轮询。

## 3. 渲染宿主差异（自托管 AiuiLinkActivity）

- 本宿主只渲染全屏页，会话卡不可用；生成的页面一律是全屏交互页。
- 页面输入唯一可靠通道 = Page 级 onKeyDown/onKeyUp；bindfocus/触摸/悬停/拖拽不可靠，不要依赖。
- 官方环境有默认行为（Backspace=返回、↑↓=滚动、Enter=激活），自托管没有默认行为、页面全包；键处理函数第一行都 `event.preventDefault()`。
- 文字是第一反馈通道；任何按键后必须 setData 刷新文字/高亮/计分。

## 4. 宿主键码契约（Lab 手柄物理键 → 页面 event.code）

方向键 ↑↓←→→`ArrowUp/Down/Left/Right`；Select/Start→`Enter`；A/B/C→`KeyZ/KeyX/KeyC`；X/Y/Z→`KeyA/KeyS/KeyD`；L/R→`KeyQ/KeyW`；眼镜 BACK/Esc→`Backspace`/`Escape`；镜腿物理键→`GlobalHook`。

## 5. 手柄语义标准

- 十字=导航/移动；Enter：菜单=确认、游戏=暂停、暂停面板=确认。
- KeyZ(主确认/跳跃)最顺手、KeyX(取消)次之、KeyC(道具)第三；KeyA/S/D 扩展位；KeyQ/W 肩键=翻页。
- 每页都能退出：保留「退出/返回」项，首页 onKeyDown 处理 Backspace。
- 需要输入文字时做「字母宫格」，方向+Enter 选中。禁止假设眼镜有输入法。
- 操作项 ≤ 10、层级 ≤ 2；长按=重复（keydown 置位 + keyup 复位）。

## 6. 页面硬性规范（保存前自检，缺一块会被拒收）

1. `<script def>` 页面级 JSON 配置（navigationBarTitleText）。
2. `<script setup>` 唯一逻辑区，export default 页面对象（data/onLoad/onShow/方法/setData）；**禁止 `onReady`**（本引擎不派发，写了初始化静默不执行）。禁止裸 `<script>`。
3. `<page>` 根标签，禁止 `<template>`。
4. `<style>` class 样式。
5. 用到的每键在 onKeyDown/onKeyUp 都处理；每键动作即时反馈（setData 刷新文字）。

## 7. 视觉与防鬼影

- 背景用 `#000000`（透明底）：光波导对大面积高亮内容会产生光学鬼影（右上角倒置虚影），低亮度页面可显著抑制；**不要大面积铺 24% 以上的绿色填充**（泛光并遮蔽内容，官方 luminance rules 明令禁止）。
- 避免纯白全屏闪烁与超大纯白字体；正文用 72% 绿（`rgba(64,255,94,.72)`）而非纯白。

## 8. 交付纪律

- 只通过 save_code_file 落盘；回复 ≤ 3 句、纯文本、无代码围栏、无源码转述。
- 用户只说「做个支持手柄的 XX」时直接套官方模板/设计规范产出，不必再询问。

## 9. 调用手机端工具（Lab 工具口）

页面可调用手机端全部工具（音乐/天气/搜索/提醒/设备信息等），这是页面与外部世界交互的唯一通道——不要在页面里 fetch 外网。

### 9.0 ⚠️ 本宿主无事件循环 —— 工具调用必须回调式（最高频翻车点）
ink 沙箱**只执行同步代码**：`Promise.then` / `await` / `setTimeout` / `setInterval` 的回调**一个都不执行**（真机实测零输出）；只有 `onLoad`/`onShow`/`onKeyDown`/`onMessage` 这类**事件回调**有效。
- ✅ 唯一正确写法：`globalThis.Lab.callTool(名字, 参数对象, function (结果, 错误) { … })`
- ❌ 禁止 `await globalThis.Lab.callTool(...)` / `.then(...)` / `Promise.all` —— **永不返回，页面当场卡死**。
- ❌ 禁止用 `setTimeout`/`setInterval` 做超时、重试、动画（超时保护在宿主侧，页面拿不到）。
- 必须写 `globalThis.Lab` 或 `window.Lab`（裸 `Lab` 在页面 realm 不可用）。

### 9.1 页面必须把 onMessage 转发给桥（否则永远收不到结果）
```js
onMessage(e) {
  if (globalThis.Lab && globalThis.Lab.onHostMessage(e)) return;   // 工具结果，已被桥消费
  // 启动参数在 e.data 里，**是对象**（{type:'launch', params:{…}}），不要再 JSON.parse
  if (e && e.data && e.data.type === 'launch') { /* e.data.params 是启动参数 */ }
}
```

### 9.2 调用范式
```js
// 在按键处理里发起；回调里同步刷新界面（不要 await、不要 setTimeout）
globalThis.Lab.callTool('play_song', { songName: '西厢' }, function (res, err) {
  self.setData({ tip: err ? ('失败: ' + err) : String(res).substring(0, 30) });
});
```
- 工具名**逐字照抄**，写错只会收到 `unknown tool: xxx`：`play_song` `stop_music` `get_now_playing` `get_weather` `search_web` `fetch_webpage` `set_timer` `get_current_time` `calculate` `search_knowledge_base` `save_summary_txt` `show_image` `get_glasses_battery` `get_phone_status`；不确定就先 `globalThis.Lab.listTools(function (tools, err) { … })`。
- 参数名按常规直觉（songName/keyword/city）；结果是字符串，自己 `JSON.parse` 处理。
- **一次只发起一个调用**（蓝牙通道串行，并发只会都超时）；**失败不要自动重试**（会绕过去重机制，造成重复拨号等真实副作用）。
- 界面刷新一律由**按键事件**驱动；每次调用都要有 loading 文案（往返 1~3 秒，最长 15 秒）。
- 启动参数只在渲染完成后经 onMessage 投递一次，onLoad 里拿不到。

### 9.3 音乐播放器取数
`play_song` 只回纯文本、不含封面歌词；素材必须再调 `get_now_playing`（JSON 文本）：
`{"playing":true,"title":"西厢","artist":"后弦","album":"九公主","durationMs":240000,"positionMs":12345,"lineIndex":5,"cover":"https://…","lyrics":[{"timeMs":0,"text":"…"}]}`
- **一次取数、本地推进**：拿 `positionMs` 用 `Date.now()` 差值自己算当前行；**页面每分钟仅 30 次调用额度，轮询几十秒即耗尽、之后全被拒**（现象：歌在放，歌词封面永远空白）。
- **别拿 `playing` 当闸门**：`play_song` 后立刻取数已为 true；false 即无曲目，显示「未在播放」。
- `cover` 给 `<image src="{{ cover }}"></image>`，空则隐藏该节点。
