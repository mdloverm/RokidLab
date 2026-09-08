# Lab 宿主运行须知（开发前必读）

本文是 RokidLab 自托管 AIUI 宿主的**硬性约束**，官方 SKILL.md（主指南）为基础语法与 API 权威。两者冲突时以本文为准（宿主为自托管 WebView 渲染，行为与官方 AgentStore 环境有差异）。官方 SKILL.md 与参考文件（components.md / apis-*.md / wxss.md / design-system-green.md）中缺失的细节，按本文件规则推理或向用户确认，禁止编造。

## 0. 开发必读顺序（每次生成 AIUI 代码都执行）

1. 本须知全文已在 load_skill 返回中（本宿主硬约束，优先级最高）。
2. **动手写代码前**，必须用 `load_skill_section(name="aiui-dev", section=…)` 读取官方主指南相关章节，不要凭印象写 SFC：
   - 写任何 .ink 前：先读第 2 章「SFC .ink Specification」。
   - 处理按键/输入时：再读第 4 章「Events」（尤其 Key Events 与默认行为）。
   - 使用组件拿不准时：查参考文件 components.md 对应组件章节。
   - 排版样式拿不准时：查第 5 章 WXSS 或参考文件 wxss.md / design-system-green.md。
   - API 不确定时：查对应 apis-*.md（wx.* → apis-wx.md 等）。
3. 读完后按本文 + 官方规则产出；官方文档与本文件冲突处一律以本文件为准。

## 1. 运行链路（Lab 专用工具编排）

1. `save_code_file` 写项目文件（app.json + pages/index/index.ink，可加页；新页面必须登记 app.json 的 pages 路由）。
2. 用户要装时 `install_aiui_project` 打包推送（只推不自动开）。
3. `open_aiui_app` 打开演示（本地 .aix 推宿主渲染）；`stop_aiui_app` 关闭；`list_my_aiui_apps` 查历史；**修改已有项目必须先 `read_code_file` 读取当前源码**（只传 project 返回文件清单，带 file 读单个文件全文），再基于真实源码用 `save_code_file` 覆盖写回同一 project 的同一路径，最后 `install_aiui_project` 重装（同名覆盖，VERSION 内容指纹自动触发眼镜重新解压加载）。
4. **单次输出硬上限**：一次 save_code_file 的代码参数 ≤ 3000 字符（≈120 行，JSON 转义后接近模型单轮输出预算）；单个 .ink ≤ 120 行。超过就**拆页面**（menu/play/help 各自独立 .ink）分多次调用写，每次只写一个文件、写完再写下一个——绝不能一次调用塞超过上限的文件（会被输出截断成废代码）。

## 2. 渲染宿主差异（自托管 AiuiLinkActivity，官方 ink 引擎 WebView 版）

- 本宿主**只渲染全屏页**，会话卡（只读展示）不可用；生成的页面一律是全屏交互页。
- 页面输入**唯一可靠通道 = Page 级 onKeyDown/onKeyUp**；bindfocus / 触摸 / 悬停 / 拖拽在自托管不可靠，不要依赖。
- 官方环境有默认行为（Backspace=返回、↑↓=滚动、Enter=激活焦点），自托管**没有默认行为、页面全包**；为兼容两者，键处理函数第一行都 `event.preventDefault()`（Backspace 在首页需自行返回上一级或允许宿主双击返回逃生）。
- 文字是第一反馈通道，语音不做主反馈；任何按键后必须 `setData` 刷新文字/高亮/计分。

## 3. 宿主键码契约（Lab 手柄物理键 → 页面 event.code）★权威表

| Lab 手柄键 | HID 通道 | 页面收到的 code |
|---|---|---|
| ↑ / ↓ / ← / →（十字） | Consumer | ArrowUp / ArrowDown / ArrowLeft / ArrowRight |
| Select | Consumer | Enter |
| Start | Consumer | Enter |
| A | Keyboard | KeyZ |
| B | Keyboard | KeyX |
| C | Keyboard | KeyC |
| X | Keyboard | KeyA |
| Y | Keyboard | KeyS |
| Z | Keyboard | KeyD |
| L | Keyboard | KeyQ |
| R | Keyboard | KeyW |
| 眼镜 BACK / 返回 | 系统键 | Backspace |
| 镜腿物理键 | 系统键 | GlobalHook |
| Esc（长按返回） | 系统键 | Escape |

## 4. 手柄语义标准（生成"支持手柄"内容时按此分配）

- 十字 = 导航/移动；Enter 语义随场景（Select/Start 同发 Enter）：菜单=确认，游戏=暂停，暂停面板=确认菜单项。
- KeyZ(主动作=跳跃/攻击/确认) 最顺手、KeyX(取消/返回/防御) 次之、KeyC(道具/开火) 第三；KeyA/S/D 扩展功能位；KeyQ/W 肩键=翻页/场景切换。
- 每页都要能退出：保留高亮「退出/返回」项，首页 onKeyDown 处理 Backspace 返回上一级。
- 输入字母/数字（记单词/答题/搜索）：页面内做「字母宫格」，每键可高亮，方向+Enter 选中。禁止假设眼镜有输入法。
- 操作项 ≤ 10 / 层级 ≤ 2；纵向列表上下移，横向选项左右移。长按=重复：连续移动/加速用 keydown 置位 + keyup 复位，不要等系统重复。
- 网格移动：行列坐标入 data，越界钳制，每步渲染唯一高亮格。

## 5. 页面硬性规范（保存前自检，缺一块会被拒收）

1. `<script def>` 页面级 JSON 配置（navigationBarTitleText）。
2. `<script setup>` 唯一逻辑区，export default 页面对象（data/onLoad/方法/setData）。禁止裸 `<script>`。
3. `<page>` 根标签，禁止 `<template>`。
4. `<style>` class 样式。
5. 用到的每键在 onKeyDown/onKeyUp 都处理；每键动作即时 setData 反馈。
6. 写完自检四块结构后，再按第 1 节纪律拆文件交付。

## 6. 视觉与防鬼影

- AIUI 界面**默认深色背景**（如 #0d1117/#1e2430 系）：Rokid 眼镜光波导对大面积高亮白内容会产生光学鬼影（右上角出现倒置虚影），深色低亮度页面可显著抑制。
- 避免纯白全屏闪烁与超大号纯白字体；正文文字用浅灰系（#cfd6e4 等）而非纯白。

## 7. 交付纪律

- 只通过 save_code_file 落盘，回复只报项目名与文件数；回复 ≤ 3 句、纯文本、无代码围栏、无源码转述。
- 用户只说「做个支持手柄的 XX」时直接套官方模板/设计规范产出，不必再询问。
- 用户要求「修改/微调/对之前的不满意」时：先 `read_code_file` 读现网源码（文件清单或单文件全文）再动手，只重写受影响的文件，禁止凭印象整页重编；改完同样用同一 project 名重存，回复仍 ≤ 3 句。
- 需要组件/API 细节时，用 load_skill_section 读取同目录参考文件（components.md / apis-*.md）对应章节；官方 SKILL.md 正文里的相对链接（如 [components.md](./components.md)）即指向这些文件。
