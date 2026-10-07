# Lab 宿主运行须知（开发前必读）

本文是 RokidLab 自托管 AIUI 宿主的硬性约束；与官方 SKILL.md 冲突时以本文为准。官方参考文件缺失的细节按本文件推理，禁止编造。

## 0. 开发必读顺序

1. 本须知全文已在 load_skill 返回中，优先级最高。
2. 写代码前用 load_skill_section(name="aiui-dev", section=…) 读官方章节，不要凭印象写 SFC：写 .ink 读 framework.md 的 SFC 章节，按键/生命周期查 events.md，组件查 components.md，样式查 wxss.md，API 先查 apis.md 索引再进对应域（wx.* → apis-wx.md），交付前过一遍 checklist.md。
3. 本技能快照基于官方 main（2026-09-23，对应运行时 ink v0.18.0，RokidLink 内嵌运行时已同步升级）。可用新能力：`<streamdown>` / `<table>` / `<video>` 流式渲染、自定义组件、OPFS 持久化、流式 fetch（TextDecoder stream:true）、Web Audio、Widgets、后台 Agent Workers（app.json 用 `agentWorkers`，**`workers` 字段已废弃**）。⚠️ 新能力按需使用：页面若用了 0.16+ 的 API，在老固件眼镜的官方通道（AiuiActivity）可能渲染不出，不确定时优先用基础档（0.14）能力。
4. ⚠️ **同步覆盖边界（改技能前必读）**：官方文件（SKILL.md / checklist.md / framework / components / wxss / design-system-green / apis*）会被官方同步**整文件覆盖**（SkillFetcher.OFFICIAL_AIUI_DEV_FILES），**Lab 特有规则只准写在本文**（LOCAL_COMPANION_SKILL_FILES 管辖、同步天然保留，冲突以本文为准）——需要改官方行为时写在本文并声明覆盖，**禁止直接改官方文件**（改了会在下次同步时静默丢失）。由此派生两条无条件规则，不依赖官方文件是否提及：
   - 用户明确说"彩色/全彩/带颜色" ⇒ 直接按 §2.4 走（页面写全彩 + open_aiui_app(target=phone,color=true)，两步缺一不可）；
   - 彩色项目交付自检 ⇒ §8 的彩色项。

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

### 2.1 单色绿光波导：只有绿通道亮度可见（最高频翻车点）★仅单色绿屏适用
- ⚠️ **本节只适用于默认的单色绿屏项目**；用户明确要彩色时**跳过本节**，走 §2.4 全彩路径，不要拿绿色阶梯去写「彩色」项目。
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
  - 配色靠 class，用官方透明度阶梯（**仅单色绿屏**；彩色项目改用 §2.4 的全彩 token）：空 `rgba(64,255,94,.06)` → `.24` → `.48` → `.72` → 最大 `#40ff5e`（如 2048 瓦片）；一格一个 class，**不要用 style 内联绑定**。
  - 刷新：全部格子一次 `setData`（32 个字段无压力）；Lab 自托管宿主上**状态变化一律按键驱动**（该宿主定时器不执行，见 §9.0）；手机预览/官方宿主可用 `setInterval` 做动画（见 §9.0.1）。

### 2.4 彩色显示（yodaos-sprite-full）★仅手机预览可用
- 眼镜屏是**单色绿光波导**，物理上没有彩色通道，**永远不支持彩色**；彩色只对手机预览有意义。
- **默认绝不写 app.json 的 theme 字段**（打包时也会强制剥离，保眼镜安全）。
- **只有用户明确说出"彩色/全彩/带颜色"时**才做彩色版：
  1. 页面按全彩配色写（深底亮字、彩色 token 都可以，不再受 §2.1 绿通道约束）；
  2. 打开用 open_aiui_app(**target=phone, color=true**)——打包自动注入 `"theme":"yodaos-sprite-full"`，只进手机包。
- ⚠️ **两步缺一不可**：`color=true` 只改包级 theme 字段，**救不了已经写绿的页面 CSS**。生成/修改阶段就要把每个页面的颜色写成全彩；对已按 §2.1 写过的绿色项目做彩色版，必须逐页重写配色后再开，只补 `color=true` 是无效的。
- 全彩配色参考（手机预览，深色底；色彩只起语义/分区作用，状态仍需文字或图标冗余）：
  - 背景 `#0d1220`，卡片/分区面 `#1a2233`，边框 `#2b3a55`；
  - 正文 `#e8eef7`，次要文字 `#9fb0c7`；
  - 语义色：成功/主色 `#34d17b`、警示 `#f5a623`、危险 `#ef5350`、信息 `#4da3ff`、强调 `#b57bff`；
  - 选中/高亮＝主色底 + 深色字（`#0d1220`），**不要再沿用 `rgba(64,255,94,…)` 绿色阶梯**。
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

### 7.0 `<page>` 根元素**必须**显式定尺寸 + 纯黑底（与官方对齐的硬约束）

**每个页面的 `<style>` 里第一条规则必须是 `page`，且必须同时写 `width` / `height` / `background-color: #000000FF`。**

为什么：
- `<page>` 是 ink 的根元素，**不写样式就继承引擎内置默认值**。实测引擎日志会打出
  `App Show color: white; padding: 0 3px; background: green` —— 即默认带白色文字 + 绿色背景 + 左右 3px 内边距。
  这层默认背景会跟着页面尺寸走，落在 canvas 位图边缘。
- **官方每个页面都写了这条规则**，一个不落（反编译 `builtin.rokid.ride.aix` 实测）：
  ```css
  page, .scr { width: 450px; height: 206px; background-color: #000000FF; }
  page        { overflow: hidden; position: relative; }
  ```
  官方 `page` 宽 **450px < 视口**，左右各留 15px 黑边⇒ 最外圈天然落在纯黑区。

正确写法（把 450/206 换成你自己的尺寸，但 `page` 必须显式写、且宽度留边）：
```css
page { width: 450px; height: 360px; background-color: #000000FF; overflow: hidden; position: relative; }
.screen { display: flex; align-items: center; justify-content: center; width: 450px; height: 360px; background-color: #000000; box-sizing: border-box; }
```
- 尺寸建议：内容区用 **≤ 470px 宽**（视口 480，留 ≥5px 边距），高度按内容自定。
- 与 §2.4 彩色版**不冲突**：`page` 是根容器、必须纯黑不透明，页面内部的卡片/分区仍可用 §2.4 的彩色 token 自由配色。
- 这条规则同时解决手机端预览与眼镜端表现不一致的问题（两端共用同一份 ink 源码）。

> **历史归因纠错（2026-10-04，勿再据旧说法排查）**：本节早前版本写
> 「缺 `page` 样式 => 默认背景落在位图最外圈 => 眼镜上出现两条横贯整屏的亮线」。
> 该归因**已证伪**：canvas 位图与 CSS 盒**完全 1:1**（实测 `bitmap=480x360` /
> `style=480pxx360px`），最外 4 行与末 4 行**全部纯黑 `0,0,0,255`**，内容占满 `0..359`
> ⇒ 位图内一根亮线都没有。
> **真正根因（已修复）**：宿主 Activity 的**窗口没铺满屏幕** —— 主题 parent
> `Theme.NoTitleBar.Fullscreen` 只去标题栏、不保证全屏尺寸 ⇒ 窗口小于屏幕，
> 窗口边界处由系统合成层填出两条亮线。已用 `FLAG_FULLSCREEN` 修复
> （见 `AiuiLinkActivity.onCreate`）。**与页面 CSS 无关**。
> 本节规则**仍要遵守**（与官方对齐、避免默认样式污染），但它**不是**亮线的原因。
> ⚠️ 另一条硬约束：**页面必须显式写 `width`/`height`，不要写 `height: 100%` 之类**
> —— 眼镜屏是 480x640，写 100% 会让页面高 640，与官方 `fillxwrap` 语义不符。

## 7.1 配色
- 背景用 `#000000`（透明底）：光波导对大面积高亮内容会产生光学鬼影（右上角倒置虚影），低亮度页面可显著抑制；**不要大面积铺 24% 以上的绿色填充**（泛光并遮蔽内容，官方 luminance rules 明令禁止）。
- 避免纯白全屏闪烁与超大纯白字体；正文用 72% 绿。

## 8. 交付纪律

- 只通过 save_code_file 落盘；回复 ≤ 3 句、纯文本、无代码围栏、无源码转述。
- 用户只说「做个支持手柄的 XX」时直接套官方模板/设计规范产出，不必再询问。
- 彩色项目交付自检（官方 checklist 不含此项，随同步会丢，故钉在本文）：**每页 CSS 已按 §2.4 全彩 token 重写** + open_aiui_app(target=phone,color=true) 两步都做了才算完成；对旧绿色项目做彩色版必须逐页重写配色，**只补 color=true 不算合格**。

## 9. 调用手机端工具（Lab 工具口）

页面可调用手机端绝大部分工具（音乐/天气/搜索/提醒/设备信息等），这是页面与外部世界交互的唯一通道——不要在页面里 fetch 外网。
**不开放的只有 6 个**：`open_aiui_app`（页面打开自己＝自指递归）、`list_sessions` / `read_session` / `session_trace`（会读到用户的全部会话与完整事件流，页面是第三方制品不该能批量枚举导出）、`research_subtask`（只读子代理）/ `execute_subtask`（**write 型子代理**，能写本机状态——比 research_subtask 更危险，2026-10-02 加入）。调它们只会收到明确的拒绝文案，**别把它当成工具名写错**。
⚠️ `shell` 域与 `screen` 域是**带闸门开放**（2026-10-01 起）：能调用，但**每一次都会先向用户弹确认**，用户同意才执行、问不到用户（眼镜/手机都无确认通道）直接拒。页面里调用它们**不会**立刻执行——回调会等到用户点头或超时拒绝；⚠️ 超时可能较长（等用户确认），不要在页面里连续重试刷确认框。
　· **`shell` 域全部 14 个**：`run_shell`、`install_packages`、`restore_container`、`snapshot_container`、`save_script`、`list_scripts`、`run_script`、`delete_script`、`start_web_preview`、`stop_web_preview`、`list_runs`、`read_run_output`、`write_run_input`、`stop_run`
　· **`screen` 域全部 5 个**：`read_screen`、`tap_screen`、`swipe_screen`、`press_key`、`type_text`
　　（按域表述而非逐个点名，是为了以后新增工具时不会再漂——逐个列举的那一版已经漏过6 个。）
　　⚠️ `delete_knowledge_doc`、`manage_glasses_app` 是**确认闸门 BLOCK 级**工具：它们出现在清单里，但页面路径必然被拒（不可恢复的操作只对对话中本人在场开放）。不确定现在能调什么，调 `list_tools` 拿一份实时清单。

### 9.0 ⚠️ 宿主差异：定时器与事件循环（2026-10-02 修正）
**官方宿主（assistserver / AiuiInkRenderEngine）定时器完全可用**：官方《闪电战机》实机以 `setInterval(() => this.tick(), FRAME_MS)` 驱动弹幕射击主循环、全页数十处 `setTimeout`（aix 实包解析验证）。**Canvas 2D（`getContext('2d')` + `fillRect`/`drawImage`）+ `setInterval` 帧循环就是官方实时游戏的标准范式**（见 §9.0.1）。
**Lab 自托管宿主（AiuiLinkActivity）**：此前真机实测 `setTimeout`/`setInterval`/`Promise.then` 回调零执行——在该宿主上工具调用**必须回调式**：
- ✅ 唯一正确写法：`globalThis.Lab.callTool(名字, 参数对象, function (结果, 错误) { … })`
- ❌ 禁止 `await globalThis.Lab.callTool(...)` / `.then(...)` / `Promise.all` —— **永不返回，页面当场卡死**。
- 必须写 `globalThis.Lab` 或 `window.Lab`（裸 `Lab` 在页面 realm 不可用）。
**手机预览（AiuiDemoActivity）**：同款 wasm ink 跑在普通 WebView，定时器大概率可用；用 `open_aiui_app(target=phone)` 装一个 `setInterval` 探针页即可一测定论。

### 9.0.1 官方实时游戏范式（《闪电战机》2026-10 实包解析）
- 主循环：页面主 realm `setInterval(tick, FRAME_MS)`，`onHide` 时 `clearInterval` 防重复 tick。
- 渲染：`<canvas>` + `getContext('2d')` 逐帧重绘（星空/弹幕/飞机全用 `fillRect`/`drawImage`）；`setData` 只管 level、statusText 等 UI 状态，**不进帧循环**。
- 结构：单页 `index.ink` 实测 **144KB** + 逻辑拆 `assets/*.js`（弹幕模式库 53KB、常量 25KB）——运行时无体积上限，§1 的 8000 字符是**生成可靠性软规则**（大型游戏按模块分文件即可）。
- 输入：`onKeyDown`/长按 + `absolute-orientation`（头部姿态传感器控制飞机移动）。
- 音频：BGM/SFX 走 Web Audio（mp3/wav/ogg 放 assets）。
- 官方包元数据：viewport device-width（实渲染 480×640）、`inkVersion >=0.14.0`——未用 0.18 worker。

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
- 工具名**逐字照抄**，写错只会收到 `unknown tool: xxx`：`control_music` `get_now_playing` `get_weather` `search_web` `fetch_webpage` `manage_timer` `get_current_time` `calculate` `search_knowledge_base` `save_summary_txt` `show_image` `get_glasses_status` `get_phone_status`；不确定就先 `globalThis.Lab.listTools(function (tools, err) { … })`。
  📌 `listTools` 给出的是**当前页面可调的权威清单**——工具开关（设置页/开发者页）随时会变，**它才是唯一准确的来源**，本行只给常用范例。
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
