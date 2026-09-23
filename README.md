# RokidLab

Rokid 眼镜配套手机应用，提供应用商店、乐奇 AI 聊天、乐奇工具（双向投屏 / 文件管理 / ADB 工具）、蓝牙手柄等功能。

> 设计师请参考 [UI-DESIGN.md](./UI-DESIGN.md)，开发者请参考 [DEV_GUIDE.md](./DEV_GUIDE.md)。

> ⚡ 爱发电赞助主页：[https://ifdian.net/a/rokidlab](https://ifdian.net/a/rokidlab)

> 🏪 商店 GitHub 源项目地址：[https://github.com/Anezium/RokidBrew](https://github.com/Anezium/RokidBrew)

## 主要功能

### 蓝牙手柄
- 通过蓝牙 HID 协议将手机模拟为键盘/鼠标/游戏手柄
- **鼠标模式**：手机屏幕作为触控板，控制眼镜光标
- **按键模式**：14 个可自定义位置的按键（↑↓←→ + A/B/C/X/Y/Z + L/R + Select/Start）
- **键盘输入**：鼠标模式下触控板区域下方提供键盘按钮，点击弹出原生输入法对话框
  - 用户输入文字后通过三层方案写入眼镜当前焦点 App：
    1. **TCP 直连眼镜 TextInputService** — 设置系统剪贴板（主方案）
    2. **ADB 原始协议粘贴** — 通过 TCP 连接眼镜 ADB 端口 5555，以 shell 身份执行 `input keyevent KEYCODE_PASTE`
    3. **HID Ctrl+V 兜底** — 通过蓝牙 HID 键盘报告发送 Ctrl+V（HID 修饰键 `0x08` + 按键码 `0x19`）
  - 自动从全局 `phoneMirrorIp` 获取眼镜 IP，无需手动输入
  - 三种方案依次尝试，前一种失败则自动降级到后一种，始终确保文字能送达
- 按键通过双 HID 通道发送：
  - **Consumer Control**：↑↓←→ / Select / Start → 眼镜端 `KEYCODE_DPAD_*` / `KEYCODE_DPAD_CENTER`
  - **Keyboard**：A/B/C/X/Y/Z/L/R → 眼镜端 `KEYCODE_Z/X/C/A/S/D/Q/W`
- 智能重连：检测到快速断连时自动等待重试（3s→6s→9s→12s→15s），5 次上限后停止并提示用户重启眼镜蓝牙
- 安全退出：退出 App 时先 `disconnect()` 再 `unregisterApp()`，确保眼镜 HID Host 正确清理状态
- 自动扫描并列出已配对的蓝牙设备
- 支持按键位置自定义（拖拽调整布局）

### ADB 工具
- 通过 WiFi 直接连接眼镜 ADB（无需 adb.exe），自实现完整 ADB TCP 协议
- **四功能按钮**：使用非当前 ADB 导航栏色的其他四个导航栏色（Coral/Cyan/Purple/Amber），分别对应系统信息/应用管理/定时功能/Shell 命令
- **应用管理**：
  - 查看第三方/全部应用列表（可搜索过滤）
  - 启动应用、卸载应用、冻结/解冻应用
  - 提取应用 APK 到手机
  - 四个快捷按钮使用非标题导航栏色（Coral/Purple/Amber/Pink/Teal）
- **定时功能**：
  - 定时消息：设定间隔和次数，通过 ADB 推送通知到眼镜（`cmd notification post` + 文件写入）
  - 定时打开应用：周期性在眼镜上启动指定应用
  - **TTS 语音播报**：到点通过 CXR-L 通道让眼镜语音播报提醒内容（语音说"5分钟后提醒我喝水"自动创建，App 退后台也能到点触发）
- **系统信息**：查看眼镜设备属性、电量信息，三大块（设备/存储/电池）使用不同导航栏色区分
- **Shell 命令**：Shell> 前缀和回车键使用非标题色
- **输入模拟**：发送文本、按键、点击、滑动事件
- **资源管理优化**：修复 ADB Shell Client 的流泄漏问题，确保连接断开时正确释放所有资源
- **统一对话框样式**：所有 ADB 工具弹窗（系统信息/定时器/Shell/应用管理）均使用 BrewDialog RokidLink 卡片样式
- **按键设置**：自定义眼镜功能键短按/长按启动第三方应用，下拉列表选择，60 秒缓存加速
- **ADB 协议修复**：修复 sync 协议 CLSE 误判导致文件上传回退到 shell 慢速方式，修复流关闭协议错误导致 daemon 无限重传 CLSE 包

### 应用商店
- 浏览和搜索 Rokid 眼镜应用
- 安装应用到手机或眼镜端
- 应用更新管理
- 多商店源切换（Gitee / GitHub，含对应图标标识）
- 应用详情页来源行动态显示 Gitee 或 GitHub 图标及域名
- **下拉刷新**：商店页面支持下拉刷新，手动获取最新应用列表
- **精选应用横向滑动**：精选应用改为横向滑动展示全部，浏览更流畅
- **分类标签本地化**：22 种应用分类支持中英文标签，随语言切换自动变化
- **本地 APK 安装**：支持从文件管理器选择本地 APK 文件安装到眼镜
- **安装状态优化**：改进安装状态显示和错误处理，提供更清晰的安装反馈

### 乐奇 AI 聊天
- **文字对话**：与 AI 文字聊天，回复通过眼镜语音播报（OpenAI 兼容协议，默认 DeepSeek，可自由配置 baseUrl / apiKey / model 切换通义千问、Kimi、智谱、本地 Ollama 等任意服务商）
- **AI Agent 模式**：乐奇以 Agent 形态工作，按人设（Rokid 眼镜 AI 助理）与工具准则播报（≤3 句、纯文本、实时信息必须查证后回答）
- **多轮会话记忆 + 滚动摘要**：跨请求多轮历史，自动携带上下文（理解「再来一首」「它是什么」等指代）；10 分钟无活动自动清空，最长 12 条 / 6000 字符；**新版改用滚动摘要**——超长上下文被裁剪时，前缀折叠为 ≤800 字的 pinned system message `[summary of earlier conversation]`，跨数小时长会话不再失忆；可在聊天设置 → Agent 设置页查看/清空
- **长期记忆（SQLite 持久化）**：跨会话记住用户称呼与偏好（说「以后叫我周哥」「我喜欢周杰伦」自动记忆），每次对话注入 system prompt，重启 App 不丢失；**新版改用 SQLite 存储**，FIFO 上限 200 条、90 天过期，首次启动自动迁移旧 SharedPreferences 数据；注入策略从全量改为基于中文 bigram 重叠度评分的 top-12 检索，长记忆库增长后不再撑爆 system prompt；支持在 Agent 设置页开关、查看条数、一键清空（禁止存储密码/账号等敏感信息）
- **知识库 RAG 评分升级**：本地知识库采用 BM25-style IDF 评分，AI 回答可引用命中的 `docName/chunkIdx` 来源；检索精度大幅提升
- **SSE 重连更稳**：OpenAI 兼容客户端的 SSE 重连条件从「已开始」收紧为「已发出内容」（无内容时重放是安全的，工具增量可重发），新增指数退避 500ms × 2^n 上限 4s，远程重试 2 → 3 次
- **多步任务与中间状态播报**：工具循环最多 6 轮，支持「查电量 → 打开应用」等多步任务；执行工具时眼镜实时播报中间状态（如「正在查询眼镜电量…」）
- **眼镜语音播报**：AI 回复经 CXR-L 通道发送到眼镜端语音朗读
- **拍照问 AI**：镜腿按键 / 手机按钮双入口触发 —— 眼镜拍照 → 本地 OCR 识别题目文字 → 知识库检索（RAG）→ AI 生成答案并语音播报（v3.5 已抽离为独立 `PhotoQuizFlow` 类，会话层只做委派）
- **本地 OCR**：完全离线识别（PP-OCRv4 模型 + ONNX Runtime 推理），无网络、无 GMS 依赖，16KB 页面设备兼容
- **本地知识库**：支持导入 txt 文档，自动分块入库（SQLite）并关键词检索，为 AI 提供参考资料（RAG）
- **按键答题开关**：开启后短按镜腿按键直接触发拍照问 AI，覆盖原自定义按键短按，长按不受影响
- **AI 工具（语音 + 手机能力，34 个）**：v3.5 新增 12 个手机域工具，一句话直达能力（另有独立 `manage_memory` 长期记忆通道，不计入工具表）：
  - **打开应用**：`launch_glasses_app`（自动名称匹配已装应用）
  - **定时任务**：`set_timer` / `list_timers` / `cancel_timer` ——「5分钟后提醒我喝水」「明早8点叫我」「17点打开小智」（到点眼镜语音播报，可同时打开应用）
  - **查询类**：眼镜电量、系统信息、存储空间、已装应用、当前时间、知识库检索、**天气（Open-Meteo，免 API Key）**、**手机状态**、**日历查询**
  - **手机域（v3.5 新增）**：`calculate`（递归下降表达式解析器）、`search_contacts` / `call_phone`（电话拨打）、`set_phone_alarm`、`open_phone_app`、`set_phone_volume`、`add_calendar_event` / `query_calendar`、`get_phone_status`
  - 工具开关在聊天设置 →「管理 AI 工具」子页面统一管理，支持中英文；新增 24 条中英字符串，AndroidManifest 申请 READ_CONTACTS / READ_CALENDAR / WRITE_CALENDAR / SET_ALARM 权限
- **本地模型支持**：内置 Ollama 连接器，无需云端 API 即可在手机端调用本地大模型对话（设置 → AI 服务 → 本地模型）；**v3.5 修复**：从本地模型页返回才触发自动启用，避免打开设置弹窗时残留的 `ai_local_model` 强制覆盖已选在线服务
- **聊天界面**：AI 消息支持长按选择复制；顶部可一键清空对话（同时重置 Agent 上下文）；消息跨页面切换不丢失；**v3.5 修复**：取消/重放流的响应不再让消息卡在「sending」状态
- **会话稳定性（v3.5 全面加固）**：
  - `ASR_READY` 控制信号避免眼镜语音会话在识别完成前被过早打断
  - 眼镜端本地接管负责打断/打开/文字显示，杜绝双 open 竞态
  - **ASR 防抖升级**：去重条件从「3 秒内同文」改为「同文 + 上一条仍在处理中」，用户连说两次「停止播放」会被执行两次而非吞掉；`asrHandling` 标志 + 90 秒兜底超时，防止异常路径下永远吞指令
  - **推送断连→恢复补读**：推送通道断连期间眼镜写文件的 ASR 文字以前会永久丢失，新版在 `AsrPushClient.onConnected` 回调置位 `catchUpRequested`，唤醒兜底轮询立刻补读积压文字
  - **start() 防抖**：1.5 秒内重入直接忽略，避免重复创建 push client 抢同一条 RFCOMM 通道把通道搞断
  - **socket 连接时序修复**：`AsrPushClient` 必须在 `connect()` 成功后才置 `socket`，避免握手期间 `isConnected` 短暂 true 让上层跳过文件兜底轮询
  - **轻量配置下发复用链路**：保存按键答题开关时若链路已就绪，直接 `sendCustomCmd`，不再走 `cleanup()` 全链路重建——重建期间 ASR 推送通道与 ADB 隧道均不可用，保存设置后紧接着说话的第一条语音必然丢失
  - 长工具输出自动截断防上下文膨胀
- **代码清洗**：生成 AIUI 代码后自动剥除 markdown 围栏代码块，将超长/形似代码的回复收敛为短结论，避免源码通过 TTS_Result 下发到眼镜

### AIUI 智能体生成（对话即开发）
v3.4 全新功能 / v3.5 大幅增强 —— 与乐奇对话，直接在眼镜上生成和运行 AIUI 智能体应用，无需写代码、无需 IDE。

- **对话生成**：对乐奇说「做个番茄钟」「做一个汇率换算器」「做一个歌词显示界面」，AI 自动生成完整 AIUI 项目代码（.ink 页面 + app.json 清单），打包为 .aix 推送到眼镜渲染运行
- **技能体系（Skill）**：内置 AIUI 开发技能文档（Skill），AI 在生成代码前自动加载官方组件规范、API 参考和设计指南，确保生成的代码符合眼镜渲染引擎要求：
  - **自动加载**：首次提到 AIUI 相关需求时自动拉取技能全文注入上下文
  - **按章节精读**：需要特定组件/API 时按章节读取（components.md / apis-*.md），避免全量注入浪费 token
  - **本地缓存**：技能文件内置在 assets/skills/ 目录，无需网络
- **代码修改（先读再改）**：对已生成的 AIUI 不满意？直接说「把上次那个番茄钟颜色改成红色」「加一个暂停按钮」——AI 先读取项目现有源码（read_code_file），基于真实代码精准修改，再覆盖写回并重装到眼镜，不凭记忆整文件重编
- **项目管理**：设置 → AI 服务 → 管理 AIUI 程序页面统一管理所有已生成的智能体应用：
  - 查看应用列表（名称、项目名、更新时间、来源）
  - 打开/关闭应用（直接在眼镜上演示）
  - 重新安装（修改后一键重装）
  - 删除应用（支持同时删除源码文件 + 眼镜端文件，带确认弹窗）
- **本地上传 .aix**：支持从手机本地选择 .aix 文件上传到眼镜，享受与对话生成相同的安装/管理流程
- **语音指令修改**：通过语音对话即可修改已生成的 AIUI 程序，无需重新描述整个项目
- **多线路推送**：AIUI 项目上传优先使用 WiFi 链路（眼镜 IP:8848），失败时自动回退至蓝牙隧道，确保推送成功率
- **WebServer 预热**：上传前通过 ADB-over-隧道发广播拉起眼镜端 WebServer（8848 端口），并等待端口就绪，避免空闲超时导致上传失败
- **系统命令直通**：通过反射绕过 CXR-L SDK 内置 cmd 黑名单，支持直接发送 Sys_AIUI_Start/Sys_AIUI_Stop 等系统命令控制官方渲染层
- **退出 AIUI**：支持主动退出正在运行的 AIUI 程序（stop_aiui_app 工具），而非仅能退出已启动的程序
- **内容指纹版本控制**：VERSION 字段使用内容指纹，代码有变化时眼镜端自动重新解压渲染，无需手动清缓存

#### v3.5 新增：Lab 工具桥（AIUI 页面可调手机端 34 个工具）
AIUI 页面（`.ink` 智能体）运行在眼镜端 ink 沙箱里，原本只能渲染不能调用外部能力。v3.5 新增 Lab 工具桥，让页面通过 `await globalThis.Lab.callTool(name, args)` 调用手机端工具（ToolRegistry 全量 39 个中除 `open_aiui_app`（防自指递归）、会话查询三件套（防批量导出历史）与 `research_subtask`（会触发多次模型调用且无取消通道）外全部开放，即 34 个），涵盖音乐 / 天气 / 搜索 / 提醒 / 设备信息 / 电话 / 日历 等，与外部世界交互：

- **页面侧 API**：
  ```js
  // 返回 Promise<string>，结果是工具返回的文本
  const text = await globalThis.Lab.callTool('play_song', { songName: '西厢' });
  // 能力发现：返回 [{name, description}]
  const tools = await globalThis.Lab.listTools();
  ```
  ⚠️ 必须写 `globalThis.Lab` 或 `window.Lab`——ink 沙箱页面 realm 不走 globalThis 解析裸标识符，直接写 `Lab.callTool` 会 ReferenceError
- **协议链路**：页面 → `__lab/tool_call_sync` fetch 拦截 → `AiuiLinkActivity` 后台线程 → `AsrPushServer.pushControl(__LAB_TOOL__ + JSON)` 上行 RFCOMM → `AsrBridgeCoordinator` 分流 → `CxrLHiRokidSession.handleAiuiToolCall` → `ToolGateway.call` → `ToolRegistry.execute`，结果按原路回传兑现 Promise（同步阻塞 ≤15s 超时，结果截断 8000 字符）
- **双桥冗余**：`host.js` 主 realm 提供 `window.Lab`（适配可访问 window 的页面），`lab-page-bridge.js` 自动注入到 `app.js` 前置（解决 ink 沙箱页面 realm 隔离看不到主 realm `window.Lab` 的问题，靠 fetch 拦截同步阻塞）
- **启动参数下发**：用户说「用 AIUI 播放西厢」时，`open_aiui_app` 工具的 `params` 字段携带 `{"songName":"西厢"}` 经 CXR 下发到眼镜端，页面 boot 完成后作为首条 `hostMessage` (`type=launch`) 投递，页面在 `onMessage` 里接收
- **安全约束**（⚠️ 下面第 1、2 条已随后续版本收窄，括号内为当前状态）：
  - `DENY_TOOLS`（v3.5 时仅 `open_aiui_app`）现为 5 项：`open_aiui_app`（防自指递归启动）+ 会话查询三件套（`list_sessions` / `read_session` / `session_trace`，防批量导出历史）+ `research_subtask`（一次 `callTool` 会触发多次模型调用且无取消通道）
  - `ALLOWED_DOMAINS` 基线全开（用户 2026-09-09 拍板），**2026-09-22 起摘除 `shell` 域**（本机执行＝任意命令执行原语，不开放给第三方页面）
  - `isEnabled` 总开关作为唯一安全兜底（默认开启）
- **生成方模型须知**：lab-runtime.md 第 8 章约束生成的 AIUI 代码——必须 try/catch、必须有 loading 态、结果按字符串解析、一次只调一个工具（蓝牙通道串行）

### 屏幕镜像
- 将眼镜屏幕实时显示在手机上（ADB over TCP 自定义实现）
- 基于 scrcpy-server 通过 ADB tunnel_forward 获取 H.264 硬件编码流，手机端 MediaCodec 零拷贝解码渲染
- 首次连接时自动推送 scrcpy-server.jar 到眼镜（仅一次，后续复用），无需每次推送
- 支持缩放、双指平移查看
- 眼镜端 ADB TCP 断线后自动重连（最多 3 次）
- **性能优化**：优化灰度转换算法，使用整数运算和缓冲区复用减少 GC 压力
- **配置集中管理**：通过 `AppConfig` 统一管理镜像端口、超时时间等配置参数
- **v3.5 修复**：`AdbScreenMirrorClient` + `ScreenStreamDecoder` 对 late/partial H.264 帧加固，丢关键帧不再卡死 surface；`ScreenMirrorActivity` lifecycle / surface teardown 修复；`ConnectionRouteManager` 重连时失效缓存路由，避免复用死路由

### 手机投屏
- 将手机屏幕投射到眼镜上（基于 CXR-L + MediaProjection）
- 一键启动/停止，眼镜端自动接收
- 投屏后眼镜端全屏显示画面，无 UI 干扰
- Socket 传屏协议：`[1B方向][2B宽LE][2B高LE][N*1B灰度]`，3 秒连接超时
- **帧健康看门狗**：30 秒无帧时重建 ImageReader 恢复画面
- **ImageReader 自恢复**：方向切换导致帧暂停时，自动重建 ImageReader + 更新 VirtualDisplay Surface，无需重建 MediaProjection
- **Socket 无限重连**：断连后异步自动重试（独立线程池），使用 `isReconnecting` 标记防止重连循环
- **方向缓存优化**：使用 `currentOrientation` 缓存取代每帧查询 `DisplayManager`，减少 IPC 开销
- **线程安全同步锁**：`mirrorLock` 保护 ImageReader 和 VirtualDisplay 切换，防竞态
- **全品牌兼容**：华为 EMUI 12+ / 小米 MIUI 14+ / OPPO ColorOS 13+ / vivo Funtouch OS 13+ 投屏黑屏自动修复（HWC 禁用 + 软件渲染强制 + 分辨率降级）
- **悬浮窗权限引导**：OPPO/vivo 设备投屏前自动检测并引导开启悬浮窗权限
- **眼镜端 Server 自愈**：Activity 被系统重新拉起时自动重启 Server
- **停止投屏行为**：停止投屏仅断开 Socket 连接，眼镜端 RokidLink 退回后台保持运行，下次可直接恢复（不再 `stopApp`）
- **旋转不中断**：MainActivity 设置 `configChanges="orientation|screenSize"`，旋转时不重建 Activity，投屏持续流畅
- 眼镜端 RokidLink APK 自动随手机端构建（build.gradle.kts 集成），始终保持同步

### 全品牌兼容性
- **投屏黑屏修复**：华为 EMUI 12+、小米 MIUI 14+、OPPO ColorOS 13+、vivo Funtouch OS 13+ 自动禁用 HWC + 强制软件渲染
- **芯片适配**：联发科/麒麟芯片自动降级投屏分辨率（640×480 → 320×426），编码器回退到 OMX.google.h264.encoder
- **后台保活**：前台服务（通知栏常驻）保进程，国产 ROM 自动引导电池优化白名单 + 自启动权限 + vivo 10分钟后台硬限制提示
- **三星适配**：Deep Sleep 检测日志，避免前台服务超 15 分钟被降权
- **蓝牙兼容**：QTI（高通）蓝牙栈 HID sendReport 自动降级、HOGP 手动开启引导
- **悬浮窗检测**：OPPO/vivo 悬浮窗权限检测，投屏前自动引导开启

### 文件管理
- 通过 ADB 浏览和管理眼镜上的文件
- 上传、下载、删除、重命名文件
- 新建文件夹、复制/剪切/粘贴
- 支持图片预览和文本查看（shell cat / 本地缓存）
- **APK 安装功能**：支持直接从文件管理器安装 APK 到眼镜，集成安装状态显示
- **排序功能**：支持按名称/大小/日期排序，点击切换升序/降序，带 ↑↓ 指示器
- **刷新动画**：刷新按钮点击时带旋转动画反馈
- **中文目录上传修复**：修复 ADB 文件管理器中创建中文目录和上传中文文件名文件时的超时问题
- **配置集中管理**：通过 `AppConfig` 统一管理 ADB 端口、连接超时等配置参数
- **v3.5 修复**：`AdbFileManagerClient` 在 CUT (close-wait) 路径上正确 drain socket 后再结束传输，杜绝截断/丢数据；`FileManagerActivity` 并发安装不再 spam toast

### 设置
- 眼镜端 RokidLink 服务管理（安装/重装/启动/停止）
- 商店源切换
- 主机应用切换
- **多语言切换**：支持简体中文/English 随时切换，首次启动自动检测系统语言
- 应用版本和更新
- **系统日志面板**：提供实时日志查看功能，便于调试和问题排查
- **提交应用**：开发者直接在设置页填写应用信息表单（图标/截图/版本/下载地址等），一键通过 Gitee API 提交到商店注册表，无需手动编辑 JSON
- **后台保活开关**：前台服务（通知栏常驻）保证语音助手/蓝牙键盘/ADB 工具/定时任务在 App 退后台或 Activity 销毁后持续运行，防止系统回收（START_STICKY 自愈）

## 技术栈

- **语言**: Kotlin
- **UI**: Jetpack Compose (Material 3)
- **本地化**: Android 原生资源系统（`values/` + `values-en/`），运行时 `AppCompatDelegate.setApplicationLocales()` 切换，Crowdin 云端翻译管理
- **设计风格**: 双主题配色系统 — 丝绒炭黑（Velvet Dark，暖暗调） + 冰蓝冰川（Cool Blue，浅蓝冷调），模块色配色体系，详见 [UI-DESIGN.md](./UI-DESIGN.md)
- **通信**:
  - CXR-L SDK（手机-眼镜通信，用于安装/启动/卸载应用、AI 语音播报）
  - ADB over TCP（自定义协议实现，无需 adb.exe，用于文件管理、ADB工具和屏幕镜像）
  - 蓝牙 HID Device 协议（手机模拟键盘/鼠标/游戏手柄）
  - 蓝牙隧道（RFCOMM 转发）与 WiFi 直连双线路，`ConnectionRouteManager` 统一路由管理；同设备同 SCN 单通道约束由 `ChannelArbiter` 按优先级租约仲裁
- **AI**:
  - OpenAI 兼容协议客户端（`OpenAiService`，可切换任意服务商；SSE 重连指数退避 + 远程重试 3 次）
  - 工具注册与执行（`ToolRegistry` 39 个工具，执行分支按域拆到 `ai/tools/`：12 个 `ToolProvider` + `ToolProvider` 接口 + `ToolEntry` 声明结构体）
  - 工具审批闸门（`ai/approval/`：`ApprovalGate` 唯一入口 + 5 个 `ToolGuard` 策略源；READ_ONLY / LOCAL_SIDE_EFFECT / EXTERNAL_SIDE_EFFECT 三档风险 + per-source 限流 + 眼镜端确认通道 `GlassToolConfirmChannel` + 审计日志）
  - 模型能力接缝（`ai/llm/`：`LlmRegistry` 能力解析 + 客户端构造）与上下文压缩接缝（`ai/compaction/`：`CompactionEngine` + `BasicCompactionEngine`）
  - Agent 会话层（`AgentSessionManager`：滚动摘要 ≤800 字 pinned system message；`LongTermMemoryManager`：SQLite + FIFO 200 条 + 90 天过期 + bigram 评分 top-12 检索；`KnowledgeBase`：BM25 IDF + 命中 provenance）
  - 本地 OCR（RapidOCR / PP-OCRv4 + ONNX Runtime，完全离线）
  - 本地知识库 RAG（txt 导入，SQLite 分块检索）
  - AIUI 开发 Skill 体系（内置官方组件规范 + API 参考 + 设计指南，`SkillRegistry` 自动加载/按章节读取）
  - 本地模型支持（`LocalOllamaManager`，Ollama 协议直连）
  - 内置计算器（`Calculator`：递归下降表达式解析器，纯 Kotlin 无依赖，供 `calculate` 工具调用）
- **投屏**: MediaProjection API + Socket 传输
- **统一 HTTP 工具**: `HttpClient` 对象封装（v3.5 切换到 OkHttp 4.12.0，连接池 + HTTP/2 复用，替代裸 `HttpURLConnection` 重新握手）
- **统一交互组件**: `BrewButton`/`BrewOutlineButton`/`BrewCompactButton`/`BrewIconButton` 标准按钮系统、`BrewDialog` 标准对话框（RokidLink 卡片样式：彩色标题栏 + 装饰分隔线 + 彩色边框）、`BrewStatusDot`/`BrewStatusPill`/`BrewStateCard` 标准状态指示器（详见 [UI-DESIGN.md](./UI-DESIGN.md)）

### 国际化（i18n）

- **完整中英文支持**: 所有用户可见文本均使用 `values/strings.xml`（中文）和 `values-en/strings.xml`（英文）管理，无硬编码字符串
- **当前规模**: 中文 1100 条 / 英文 1100 条，**双语 key 完全对齐**（此前英文侧缺 5 条，已于 2026-09-12 补齐）
- **构建期门禁**: `checkI18nKeysSynced` 任务（挂 `preBuild`）强制 phone-app / RokidLink 两模块的 zh↔en key 集合相等，不一致直接构建失败
- **运行时语言切换**: 通过 `LocalizationManager` + `AppCompatDelegate.setApplicationLocales()` 实现无需重启的语言切换
- **首次启动自动检测**: 自动检测系统语言并应用对应翻译
- **例外**: 品牌名（ROKIDLAB、ROKIDLINK）、技术术语（ADB、TCP、HTTP）、作者信息等保持原文
- **最低版本**: Android 10 (API 29)（手机端 minSdk 29；眼镜端 RokidLink minSdk 28，两者 compileSdk / targetSdk 均为 34）

## 代码质量与安全

### 配置管理
- **AppConfig 统一配置**：所有硬编码的配置参数集中管理（端口、超时、重试次数等）
- **易于维护**：修改配置无需在多个文件中查找，统一在 `AppConfig.kt` 中调整

### 线程安全
- **同步锁保护**：关键操作使用 `synchronized` 块保护，防止并发修改
- **@Volatile 注解**：确保多线程环境下的变量可见性
- **协程安全**：协程间通信使用 Channel 和 Flow，避免竞态条件

### 资源管理
- **自动资源释放**：所有网络连接、文件流、ADB 连接等资源在使用后正确关闭
- **内存泄漏预防**：投屏服务停止时释放 ImageReader、VirtualDisplay、MediaProjection 等资源
- **线程管理**：使用 HandlerThread 管理后台线程，避免主线程阻塞

### 安全性
- **ADB 密钥保护**：生成的 RSA 私钥文件权限设置为仅应用可读写
- **输入验证**：所有用户输入都经过验证，防止注入攻击
- **网络安全**：使用 HTTPS 连接，支持证书验证

### 错误处理
- **异常捕获**：所有可能失败的操作都使用 try-catch 包裹
- **用户友好提示**：错误信息通过 Toast 和状态卡片显示，便于用户理解
- **日志记录**：详细的日志记录便于问题排查和调试；**四类关键链路**（ASR 补读 / RFCOMM 隧道 / ADB sync / AIUI 工具网关）的 catch 分支强制落 App 内日志面板 `LogCollector`，且必须带异常对象（只记 `e.message` 无法区分「对端未启动」与「RFCOMM 被栈拒绝」）
- **构建期门禁**：`checkKeyPathEmptyCatch` 任务（挂 `preBuild`）扫描上述 6 个关键链路文件，空 catch 必须带 `// catch-ok: <原因>` 标注（把"默默吞掉"变成"显式声明的决策"），否则构建失败 —— 见 `RULES.md` §12.14

## ADB 自实现协议

项目从零实现了 ADB TCP 通信协议，不依赖 adb.exe，可直接通过 WiFi 连接眼镜：

- **TCP Socket 连接**: 连接眼镜的 ADB 端口（默认 5555）
- **RSA 密钥认证**: 生成 RSA 密钥对，通过 AUTH/SIGNATURE/RSA_PUBLIC 消息完成 ADB 认证握手
- **shell service**: 通过 `"shell:<command>\u0000"` 格式执行远程 shell 命令
- **sync 协议**: RECV/SEND/DATA/DONE/FAIL 命令实现文件上传下载
- **流关闭处理**: 所有命令都正确消费服务端的 CLSE 响应，避免协议状态不同步
- **ReentrantLock 保护**: 多协程并发执行 shell 命令时串行化，避免 socket 冲突

## 蓝牙 HID 自实现协议

项目使用 Android `BluetoothHidDevice` API 将手机模拟为蓝牙 HID 设备：

- **HID 描述符**: 自定义报表描述符，支持 3 个 Report ID：Consumer Control（导航键）、Keyboard（功能键）、Mouse（触控板）
- **双通道按键**: 方向键/Select/Start 走 Consumer Control 通道，A/B/C/X/Y/Z/L/R 走 Keyboard 通道
- **自动重连**: 检测到 HID 连接秒断时，智能等待递增间隔后自动重试，最多 5 次
- **安全退出**: App 退出或 `onDestroy()` 时按序执行 `disconnect()` → `unregisterApp()` → `closeProfileProxy()`，确保眼镜 HID Host 正确清理连接状态
- **鼠标模式**: 触控板区域拖拽发送相对位移报表，灵敏度可调

## 项目结构

```
RokidLab/
├── phone-app/                             手机端应用
│   ├── src/main/
│   │   ├── java/com/rokidlab/phone/
│   │   │   ├── app/         主入口 / 手动 DI 容器（L5）
│   │   │   │   ├── MainActivity.kt        主入口、投屏控制、状态管理、权限请求、镜像源对话框
│   │   │   │   ├── LabApplication.kt     全局 Application 状态、HID Manager
│   │   │   │   ├── AppContainer.kt        手动 DI 容器（集中装配 L0/L1/L3 长生命周期对象，不引 Hilt）
│   │   │   │   ├── MainDialogs.kt         主界面弹窗集合
│   │   │   │   └── MainHelpers.kt         主界面辅助函数
│   │   │   ├── platform/    能力与 hook 适配（L0：全仓唯一反射边界）
│   │   │   │   ├── CapabilityProbe.kt     启动期能力探测快照（sawGranted 等）
│   │   │   │   ├── Capability.kt          能力枚举
│   │   │   │   ├── SdkBridge.kt           CXR-L SDK 私有 API 桥
│   │   │   │   ├── SdkFieldMap.kt         SDK 字段映射表
│   │   │   │   ├── HidBridge.kt           HID 隐藏 API 唯一反射边界
│   │   │   │   ├── AdbTransport.kt        全 App 共享 ADB shell 会话的唯一所有者
│   │   │   │   ├── ShellOps.kt            shell 命令封装
│   │   │   │   ├── RomAdapter.kt          ROM 差异适配
│   │   │   │   └── AvrcpLyricBridge.kt    蓝牙 AVRCP 歌词推送边界
│   │   │   ├── connection/  通道与仲裁（L1）
│   │   │   │   ├── ChannelArbiter.kt      通道租约仲裁（BACKGROUND / NORMAL / LONG_LIVED 优先级）
│   │   │   │   └── ConnectionRouteManager.kt  WiFi 直连 / 蓝牙隧道双线路路由管理（v3.5 重连时失效缓存路由，避免复用死路由）
│   │   │   ├── glasses/     眼镜会话层（L2）
│   │   │   │   ├── CxrLHiRokidSession.kt        薄路由 + 兼容门面（六层重构拆分 god class，现 887 行；保留全部 public method 作委派 facade；改持有 app context 替代 Activity，WeakReference 修 leak）
│   │   │   │   ├── LinkProtocol.kt              双端协议常量收敛（与眼镜端逐字节同源，checkProtocolSynced 构建期守护）
│   │   │   │   ├── GlassesHandshake.kt          能力握手三态（null=乐观 / false=快速降级 / legacy=4s 超时判旧版）
│   │   │   │   ├── AiChannel.kt                 双端 CXR 频道/话题常量（与眼镜端同源）
│   │   │   │   ├── AsrBridgeCoordinator.kt      ASR 双通道协调器（push + 文件轮询双通道；onAsrText 仅「同文 + 上一条仍在处理中」才丢弃；推送恢复 catchUpRequested 立即补读积压文字；__LAB_TOOL__ 分流到 ToolGateway）
│   │   │   │   ├── AiuiFrontendController.kt    AIUI 微前端 pipeline 协调器（pushAixToRokidLinkHost / openAiuiHost 支持 launchParams）
│   │   │   │   ├── PhotoQuizFlow.kt             拍照问答流程（capture → OCR → RAG → AI 答案）
│   │   │   │   ├── AsrPushClient.kt             ASR 推送客户端（RFCOMM 长连接，v3.5 修复 socket 在 connect() 成功后才置位 + 新增 onConnected 回调）
│   │   │   │   ├── GlassProxyRelay.kt           眼镜端代理中继
│   │   │   │   ├── GlassesModels.kt             会话数据模型
│   │   │   │   ├── GlassesHelpers.kt            会话辅助函数
│   │   │   │   ├── ConnectionPanel.kt           连接状态面板
│   │   │   │   ├── GuideScreen.kt               引导界面
│   │   │   │   ├── FullCXRLinkCallback.kt       CXR-L 连接回调
│   │   │   │   └── PhoneInstallResultReceiver.kt 安装结果接收器
│   │   │   ├── domain/      领域服务（L3，自 CxrLHiRokidSession 拆分）
│   │   │   │   ├── ConnectionService.kt      连接编排
│   │   │   │   ├── AuthorizationService.kt   眼镜端权限补齐
│   │   │   │   ├── DeviceControlService.kt   设备控制
│   │   │   │   ├── AiConfigService.kt        AI 配置
│   │   │   │   ├── AiConversationService.kt  AI 对话
│   │   │   │   ├── AiuiHostService.kt        AIUI 宿主
│   │   │   │   ├── MirrorCoordinator.kt      投屏/长连接通道协调
│   │   │   │   ├── FileTransferService.kt    文件传输
│   │   │   │   └── PhotoQuizService.kt       拍照问答
│   │   │   ├── feature/     UI 功能控制器（L5）
│   │   │   │   ├── MainScreen.kt              主界面骨架
│   │   │   │   ├── RokidLinkController.kt     眼镜端生命周期控制
│   │   │   │   ├── AppUpdateController.kt     应用更新
│   │   │   │   ├── StoreActionsFactory.kt     商店动作工厂
│   │   │   │   ├── StoreInstallStateHolder.kt 商店安装状态
│   │   │   │   ├── FileManagerStateHolder.kt  文件管理状态
│   │   │   │   └── ScreenMirrorStateHolder.kt 屏幕镜像状态
│   │   │   ├── ai/          AI 能力（L4 agent）
│   │   │   │   ├── OpenAiService.kt        OpenAI 兼容服务（可切换任意服务商；SSE 重连指数退避 + 远程重试 3 次）
│   │   │   │   ├── ToolRegistry.kt         AI 工具注册表（39 个工具 / 11 个域：info / knowledge / glasses / timer / media / display / web / files / aiui / phone / research；execute 按 toolNames 路由到 tools/ 的 Provider）
│   │   │   │   ├── tools/                  工具域提供者（Phase 4 拆分巨型 when）
│   │   │   │   │   ├── ToolProvider.kt             提供者接口（toolNames + execute）
│   │   │   │   │   ├── ToolSchemas.kt              工具 JSON Schema 声明
│   │   │   │   │   ├── InfoToolProvider.kt         基础信息 / KnowledgeToolProvider.kt 知识库 / GlassesToolProvider.kt 眼镜设备
│   │   │   │   │   ├── TimerToolProvider.kt        定时 / MediaToolProvider.kt 音乐 / DisplayToolProvider.kt 屏幕展示
│   │   │   │   │   ├── WebToolProvider.kt          联网 / FilesToolProvider.kt 文件产出 / AiuiToolProvider.kt AIUI 应用
│   │   │   │   │   └── PhoneToolProvider.kt        手机域
│   │   │   │   ├── approval/               工具审批接缝（ApprovalGate 唯一入口 + ToolGuards 策略源 + PseudoTools + PageScope）
│   │   │   │   ├── llm/                    模型能力接缝（LlmRegistry + 能力探测/缓存 + ModelPresets）
│   │   │   │   ├── compaction/             上下文压缩接缝（CompactionEngine + BasicCompactionEngine + CompactionPolicy）
│   │   │   │   ├── ToolRisk.kt             工具风险分级（READ_ONLY / LOCAL_SIDE_EFFECT / EXTERNAL_SIDE_EFFECT）
│   │   │   │   ├── GlassToolConfirmChannel.kt 副作用工具眼镜端确认通道（下行 TOPIC_TOOL_CONFIRM / 上行 TOPIC_TOOL_CONFIRM_RESULT）
│   │   │   │   ├── ToolGateway.kt          AIUI 页面工具网关（15s 超时 + 8000 字截断 + DENY open_aiui_app 防自指递归）
│   │   │   │   ├── Calculator.kt           递归下降表达式解析器（v3.5 新增，供 calculate 工具调用，纯 Kotlin 无依赖）
│   │   │   │   ├── WeatherTools.kt         天气工具（v3.5 新增，Open-Meteo，免 API Key）
│   │   │   │   ├── PhoneTools.kt           手机域工具（v3.5 新增，电话/日历/闹钟/音量/打开应用/联系人搜索）
│   │   │   │   ├── LocationTools.kt        定位工具
│   │   │   │   ├── MusicPlayerController.kt 音乐播放控制
│   │   │   │   ├── KuwoMusicApi.kt         酷我音源 API
│   │   │   │   ├── WebTools.kt             AIUI 项目文件读写工具（save_code_file / read_code_file）
│   │   │   │   ├── AiuiProject.kt          AIUI 项目打包/校验（.aix 生成 + VERSION 内容指纹）
│   │   │   │   ├── AiuiAppRegistry.kt      AIUI 应用注册表（项目记录持久化）
│   │   │   │   ├── SkillRegistry.kt        技能文档注册表（AIUI 开发 Skill 加载/章节读取）
│   │   │   │   ├── SkillMarkdown.kt        Skill Markdown 解析器
│   │   │   │   ├── SkillFetcher.kt         技能文件获取器
│   │   │   │   ├── AgentSessionManager.kt  Agent 会话记忆（多轮历史，10 分钟自动清空；v3.5 滚动摘要 ≤800 字 pinned system message）
│   │   │   │   ├── LongTermMemoryManager.kt 长期记忆（v3.5 改 SQLite，FIFO 200 条 / 90 天过期 / bigram 评分 top-12 检索，自动迁移旧 SharedPreferences；独立 manage_memory 通道）
│   │   │   │   ├── KnowledgeBase.kt        本地知识库 RAG（txt 导入、SQLite 分块检索；v3.5 BM25 IDF 评分 + 命中 provenance）
│   │   │   │   ├── LocalOcr.kt             本地 OCR（PP-OCRv4 + ONNX Runtime）
│   │   │   │   ├── LocalOllamaManager.kt   本地模型连接器（Ollama 协议）
│   │   │   ├── adb/         ADB 协议实现
│   │   │   │   ├── AdbShellClient.kt        Shell 命令/应用管理/定时功能
│   │   │   │   ├── AdbFileManagerClient.kt  文件管理 ADB 客户端
│   │   │   │   ├── AdbScreenMirrorClient.kt 屏幕镜像 ADB 客户端
│   │   │   │   ├── ScreenStreamDecoder.kt   H.264 流解码（late/partial 帧加固）
│   │   │   │   ├── AdbPasteCompat.kt        ADB 原始协议粘贴工具（TCP 连接眼镜 ADB 5555 端口执行粘贴）
│   │   │   │   ├── AdbKeyManager.kt         RSA 密钥持久化管理（所有 ADB Client 共享）
│   │   │   │   ├── TimerScheduler.kt        定时任务调度器（持久化 + 常驻触发 + TTS 播报）
│   │   │   │   └── ui/                      ADB 工具页与子功能弹窗（AdbToolsScreen / AdbDialogContent / SysInfoDialog / AppMgrDialog / TimerDialog / ShellDialog / KeyButtonDialog）
│   │   │   ├── design/      设计系统
│   │   │   │   ├── StoreTheme.kt         配色/字体/主题（主入口，主题管理 + 全局颜色）
│   │   │   │   ├── DesignComponents.kt   全局 UI 组件（错误/警告/加载/结果卡片）
│   │   │   │   ├── RokidHostApp.kt       HostApp 枚举
│   │   │   │   └── theme/
│   │   │   │       ├── BrewColors.kt         配色接口定义
│   │   │   │       ├── BrewThemeManager.kt   主题管理器（mutableStateOf）
│   │   │   │       ├── VelvetDarkColors.kt   默认主题（丝绒炭黑）
│   │   │   │       └── CoolBlueColors.kt     第二主题（冰蓝冰川）
│   │   │   ├── filemanager/  文件管理
│   │   │   │   └── FileManagerActivity.kt  文件管理器界面/组件（v3.5 并发安装不再 spam toast；页面已并入「乐奇工具」的文件分组）
│   │   │   ├── hid/         蓝牙 HID
│   │   │   │   ├── BluetoothHidManager.kt   HID 设备管理（注册/连接/报表发送）
│   │   │   │   ├── BtHidCompat.kt           蓝牙 HID 兼容降级（QTI 栈 sendReport 降级等）
│   │   │   │   ├── GamepadActivity.kt       手柄按键/鼠标模式 Activity
│   │   │   │   └── HidGamepadScreen.kt      手柄按键自定义布局界面
│   │   │   ├── keepalive/   后台保活
│   │   │   │   └── LabKeepAliveService.kt   前台保活服务（通知栏常驻 + START_STICKY 自愈）
│   │   │   ├── mirror/      投屏模块
│   │   │   │   ├── PhoneMirrorActivity.kt     手机投屏页面
│   │   │   │   ├── PhoneMirrorService.kt      投屏前台 Service（独立线程池异步重连、方向缓存、mirrorLock 同步锁、isReconnecting 防循环、帧健康看门狗30s）
│   │   │   │   ├── MirrorCompat.kt            全品牌投屏兼容修复（HWC 禁用 / 软件渲染 / 分辨率降级）
│   │   │   │   ├── PhonePackageInstallHelper.kt APK 安装工具
│   │   │   │   └── ScreenMirrorActivity.kt    屏幕镜像画面
│   │   │   ├── model/       数据模型
│   │   │   │   ├── Models.kt     应用/商店/更新数据模型
│   │   │   │   └── UserInstallCache.kt 安装记录缓存
│   │   │   ├── music/       媒体按键
│   │   │   │   └── LabMediaButtonReceiver.kt   媒体按键广播接收器
│   │   │   ├── network/     网络层
│   │   │   │   ├── ApkDownloader.kt   APK 下载（基于 HttpClient）
│   │   │   │   ├── IconLoader.kt      图标加载（基于 HttpClient）
│   │   │   │   └── MediaLoader.kt     媒体加载（基于 HttpClient）
│   │   │   ├── settings/    设置页
│   │   │   │   ├── SettingsScreen.kt   设置界面
│   │   │   │   └── DeveloperScreen.kt  开发者提交应用（Gitee API 直提注册表）
│   │   │   ├── store/       商店与主界面 UI（L5）
│   │   │   │   ├── StoreHomeScreen.kt    主界面入口（底部 5 Tab：STORE / CHAT / LEQI_TOOLS / HID_GAMEPAD / SETTINGS）
│   │   │   │   ├── HomeModels.kt         NavPage 枚举（MIRROR_PAIR / FILE_MANAGER / ADB_TOOLS 已合并进 LEQI_TOOLS）
│   │   │   │   ├── HomeWidgets.kt        主界面通用组件
│   │   │   │   ├── LeqiToolsModule.kt    乐奇工具页（双向投屏 / 文件管理 / ADB 工具单页分组）
│   │   │   │   ├── StoreModule.kt        商店模块装配
│   │   │   │   ├── SettingsModule.kt     设置模块装配
│   │   │   │   ├── ChatScreen.kt         乐奇聊天界面（对话/拍照问 AI/知识库管理/AI 设置；v3.5 修复取消/重放流时消息卡 sending）
│   │   │   │   ├── ChatBubble.kt         聊天气泡组件
│   │   │   │   ├── ChatHeader.kt         聊天顶部栏
│   │   │   │   ├── ChatSettingsDialog.kt 聊天设置弹窗（AI 服务/模型/Agent 设置；v3.5 修复本地模型 LaunchedEffect 误覆盖在线选择）
│   │   │   │   ├── ChatFormat.kt        聊天文本格式化
│   │   │   │   ├── ChatImageCache.kt    聊天图片缓存
│   │   │   │   ├── ConfirmClearChatDialog.kt 清空对话确认弹窗
│   │   │   │   ├── KbManageDialog.kt    知识库管理弹窗
│   │   │   │   ├── SkillDialogs.kt      技能相关弹窗
│   │   │   │   ├── AiuiManagePage.kt    AIUI 程序管理页面（列表/打开/重装/删除）
│   │   │   │   ├── SkillsManagePage.kt  技能管理页面
│   │   │   │   ├── ToolsManagePage.kt   AI 工具管理页面（开关/排序）
│   │   │   │   ├── LocalModelPage.kt    本地模型配置页面（Ollama）
│   │   │   │   ├── AgentSectionPage.kt  Agent 设置页（记忆/长期记忆管理）
│   │   │   │   ├── ChatStateHolder.kt    聊天消息单例状态（跨页面切换不丢失；v3.5 修复取消/重放流时消息卡在 sending）
│   │   │   │   ├── StoreComponents.kt    通用 UI 组件
│   │   │   │   ├── StoreInstallState.kt  安装状态组件
│   │   │   │   ├── StoreMedia.kt         媒体/图标组件
│   │   │   │   ├── AppDetailScreen.kt    应用详情
│   │   │   │   ├── DetailInfoSections.kt 详情信息块
│   │   │   │   ├── DetailScreenshots.kt  详情截图
│   │   │   │   ├── DetailTargetTags.kt   详情目标标签
│   │   │   │   └── UpdateDialog.kt       更新对话框
│   │   │   └── util/        工具
│   │   │       ├── AppConfig.kt           全局配置常量
│   │   │       ├── HttpClient.kt          统一 HTTP 请求工具
│   │   │       ├── LocalizationManager.kt  语言切换管理
│   │   │       ├── ImageDecoder.kt         图片解码工具
│   │   │       ├── ManufacturerUtils.kt    全品牌兼容性检测工具
│   │   │       ├── RomFingerprint.kt       ROM 指纹识别
│   │   │       └── LogCollector.kt         日志收集器
│   │   ├── res/             资源文件
│   │   │   ├── values/strings.xml   简体中文（默认语言）
│   │   │   └── values-en/strings.xml  English
│   │   └── assets/          内置资源
│   │       ├── skills/                    AIUI 开发技能文档（Skill）
│   │       │   └── aiui-dev/             AIUI 开发指南（SKILL.md + components.md + apis-*.md + lab-runtime.md）
│   │       ├── apps.json                   商店应用列表（gitee 分支）
│   │       └── scrcpy-server.jar           ADB 推送到 /data/local/tmp/，供 scrcpy-server 启动使用
│   └── build.gradle.kts
│
├── RokidLink/                            眼镜端配套应用（RokidLink，自动打包到 phone-app assets）
│   ├── src/main/
│   │   ├── java/com/rokidlab/rokidlink/
│   │   │   ├── MainActivity.kt              WiFi/ADB 状态面板 + ADB TCP 自动开启
│   │   │   ├── BtTunnelServer.kt            蓝牙隧道服务（RFCOMM 转发，双线路通信）
│   │   │   ├── BtTunnelService.kt            蓝牙隧道前台 Service
│   │   │   ├── AiChannel.kt                 AI 指令通道（ASR/工具/接管串行化；与手机端同源）
│   │   │   ├── LinkProtocol.kt              双端协议常量收敛（v3.5 新增，与手机端逐字节同源）
│   │   │   ├── AiuiLinkActivity.kt          AIUI 渲染宿主 Activity（v3.5 大幅增强：Lab 工具桥入口 callTool @JavascriptInterface + deliverToolResult + __lab/ 探测端点 ping/tool_result/tool_call_sync/tool_call/list_tools + pageBridgeJs 自动注入 app.js 前置 + 同步工具队列 ArrayBlockingQueue + 启动参数 EXTRA_LAUNCH_PARAMS）
│   │   │   ├── AsrPushServer.kt             ASR 推送服务（v3.5 新增 CTRL_TOOL_CALL = "__LAB_TOOL__" 工具调用前缀常量）
│   │   │   ├── AiuiPackageServer.kt         AIUI .aix 包上传/解压/渲染服务
│   │   │   ├── AixBundleReader.kt           .aix 包读取器（清单解析 + 文件提取）
│   │   │   ├── TtsPlaybackHelper.kt          TTS 语音播报辅助
│   │   │   ├── KeyButtonBridgeActivity.kt   按键事件桥接
│   │   │   ├── KeyButtonService.kt          按键服务（v3.5：持久化短命 destroy 计数器 KEY_SHORT_LIVED_DESTROY_COUNT 防崩溃循环；RECEIVER_NOT_EXPORTED 注册屏幕亮起接收器；CMD_AIUI_OPEN 支持 caps[2] 启动参数）
│   │   │   ├── SelfRestartReceiver.kt       自重启广播接收器
│   │   │   ├── ServiceHelpers.kt           服务辅助工具集
│   │   │   ├── PhoneMirrorActivity.kt       投屏接收画面（onResume 自愈 + singleTask 重新拉起）
│   │   │   ├── PhoneMirrorServer.kt         Socket 服务端（灰度图接收 + Bitmap 双缓冲 + 线程安全锁）
│   │   │   ├── TextInputService.kt          TCP 文字输入服务（监听 7656 端口，接收手机文字 → 设置剪贴板 → 尝试粘贴）
│   │   │   └── ScreenMirrorIntentActivity.kt scrcpy 启动中转 Activity（ADB 就绪等待）
│   │   ├── res/layout/activity_main.xml     状态面板布局
│   │   └── AndroidManifest.xml
│   └── build.gradle.kts                     v3.5：删除本地 libs/*.aar 改用 Maven com.rokid.cxr:cxr-service-bridge:1.0；启用 R8（13.8MB → 8.7MB）；proguard-rules.pro 已 keep com.rokid.cxr.** 因 CXR SDK 走 JNI 反射
│
├── RokidLink/src/main/assets/ink/          AIUI 渲染宿主资源（**双端共用**；phone-app 用 assets.srcDir 挂载本目录，仓库里只存一份）
│   ├── host.js                              宿主 JS（v3.5：新增 log() / deliverLaunchParams() / window.Lab.callTool/listTools/onToolResult 主 realm 桥）
│   └── lab-page-bridge.js                  v3.5 新增：页面 realm 的 Lab 工具桥，自动注入到 app.js 前置，靠 fetch + __lab/tool_call_sync 同步阻塞调用
│
├── phone-app/src/main/assets/              内置资源
│   ├── RokidLink.apk                       通过 CXR-L SDK 安装到眼镜（自动从 RokidLink 构建同步）
│   └── scrcpy-server.jar                   通过 ADB 推送到 /data/local/tmp/，供 scrcpy-server 启动使用
│
├── phone-app/src/debug/                    debug 专属源集（release 包完全不含）
│   ├── AndroidManifest.xml                 仅 debug 合并 AUDIO_SPIKE 调试广播 receiver
│   └── java/com/rokidlab/phone/debug/AudioSpikeReceiver.kt  M0 音频流 spike 调试广播接收器
│
├── crowdin.yml                             Crowdin 翻译管理配置
├── UI-DESIGN.md                            UI 设计参考文档
└── apps/                                   应用数据示例
```

## 工作区规范

项目遵循 `.traelink/rules.md` 中的开发规范，包括：

- **多语种规则**: 所有用户可见文本必须使用 `R.string.xxx` 引用语言包，禁止硬编码（例外：品牌名、技术术语、作者信息）
- **弹窗样式规范**: 所有弹窗统一使用 `BrewDialog`，传入模块色 `color` 参数，自动应用彩色标题栏 + 装饰线 + 彩色边框
- **Gradle 配置**: 仓库根 `d:\rokidapp\settings.gradle.kts` 中模块路径为 `include(":cxrl:RokidLab:phone-app")` 和 `include(":cxrl:RokidLab:RokidLink")`
- **资源管理**: 定期清理未使用的资源文件，避免打包冗余

## 构建

```powershell
# 全部命令在仓库根 d:\rokidapp 执行
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.11.9-hotspot"

# 构建手机应用（自动同步最新 RokidLink APK 到 assets）
D:\gradle-8.7\bin\gradle.bat :cxrl:RokidLab:phone-app:assembleDebug

# 仅构建眼镜端服务
D:\gradle-8.7\bin\gradle.bat :cxrl:RokidLab:RokidLink:assembleDebug

# 仅同步 RokidLink APK 到 phone-app assets（不重新构建手机端）
D:\gradle-8.7\bin\gradle.bat :cxrl:RokidLab:phone-app:buildRokidLinkDebug
```

### 单元测试

```powershell
# 双端 JVM 单测（当前 14 个测试类 / 155 个用例）
D:\gradle-8.7\bin\gradle.bat :cxrl:RokidLab:phone-app:testDebugUnitTest :cxrl:RokidLab:RokidLink:testDebugUnitTest --offline
```

覆盖 ADB sync 协议帧、`pullFile` FAIL 分支、HID 描述符字节与 `normalize`、`AiChannel` 跨端载荷矩阵、`ToolRiskMap` 完整性、聊天历史落盘格式（JSONL / 旧格式迁移）。

> ℹ️ 仓库根是 `d:\rokidapp`（`RenewCXRLSample`），`cxrl\RokidLab` 只是模块目录、**不再是独立 Gradle 根**；所有任务名都带 `:cxrl:RokidLab:` 前缀。

### 构建期门禁与发布闸门

四道门禁挂在 `preBuild`（本地每次构建即触发，无需 CI）：

| 任务 | 位置 | 拦住什么 |
|---|---|---|
| `checkProtocolSynced` | `phone-app/build.gradle.kts` | 双端 `AiChannel.kt` / `LinkProtocol.kt` 不同源；裸协议字面量（CXR 频道名 / `__LAB_*`） |
| `checkI18nKeysSynced` | `phone-app/build.gradle.kts` | 两模块 zh↔en key 集合不一致（phone-app 1100 / RokidLink 22） |
| `checkKeyPathEmptyCatch` | `phone-app/build.gradle.kts` | 6 个关键链路文件出现未标注的空 `catch` |
| `checkNoBareCatch` | `phone-app/build.gradle.kts` | 全仓未标注空 `catch` 超出棘轮预算（基线 47，只降不升） |

出 release 包另有**两道发布闸门**（`gradle/local-gates.gradle.kts`，双端各自 `apply`，挂在 `packageRelease`）：

- `checkGitClean` —— `git status --porcelain` 非空即构建失败（阻止"发布的不是仓库里的东西"；本地临时验证可加 `-PallowDirtyWorktree=true`，禁止用于正式出包）
- `packageRelease` 依赖 `testDebugUnitTest` —— 单测没绿出不了包

```powershell
# 出 release 包（工作区必须已全部提交）
D:\gradle-8.7\bin\gradle.bat :cxrl:RokidLab:phone-app:assembleRelease
```

## 安装

### 手机应用
```powershell
adb install phone-app/build/outputs/apk/debug/RokidLab-v4.0-debug.apk
```

### 眼镜端服务
通过手机应用中的设置页 → "重装眼镜端" 自动推送到眼镜。也可手动安装：
```powershell
adb install RokidLink/build/outputs/apk/debug/RokidLink-debug.apk
```

## v3.5 更新总览

- **CXR-L SDK 升级 1.1.0 → 1.1.2**，pin `cxr-service-bridge:1.0-20260715.121510-107`
- **RokidLink 迁移到 Maven bridge**：删除本地 libs/*.aar，使用 `com.rokid.cxr:cxr-service-bridge:1.0`
- **R8 启用**：RokidLink release 13.8MB → 8.7MB
- **HTTP 切换 OkHttp 4.12.0**：连接池 + HTTP/2 复用
- **AI Agent 核心升级**：
  - 长期记忆：SharedPreferences → SQLite，FIFO 200 条 / 90 天过期 / bigram 评分 top-12 检索（自动迁移旧数据）
  - 会话记忆：截断 → 滚动摘要 ≤800 字 pinned system message
  - 知识库：BM25 IDF 评分 + 命中 provenance
  - SSE 重连：条件收紧 + 指数退避 + 远程重试 2→3
  - 新增内置计算器（递归下降表达式解析器）
- **12 个新手机域工具，工具总数达 34**：新增 DOMAIN_PHONE 域，含天气 / 电话 / 日历 / 闹钟 / 计算 / 联系人搜索等
- **手机端六层架构重构（L0~L5）**：`platform`(L0) / `connection`(L1) / `glasses`(L2 session) / `domain`(L3) / `ai`(L4 agent) / `feature`+`store`+`app`(L5)，每层只依赖下一层；`AppContainer` 承担手动 DI 装配（不引 Hilt）
  - `CxrLHiRokidSession` 拆 god class（**现 887 行**）：连接编排 / 授权 / 设备控制 / AI 配置 / AI 对话 / AIUI 宿主 / 投屏协调 / 文件传输 / 拍照问答 全部下沉到 `domain/` 服务，`CxrLHiRokidSession` 退化为薄路由 + 委派 facade，改持有 app context 替代 Activity + WeakReference 修 leak
  - `ai/ToolRegistry` 的巨型 `when` 按域拆为 10 个 `ToolProvider`（`ai/tools/`），新增工具只需在 `ToolRegistry` 注册一行
  - 新增 `ai/ToolPolicy` + `ai/ToolRisk`：工具副作用三档分级（READ_ONLY / LOCAL_SIDE_EFFECT / EXTERNAL_SIDE_EFFECT）+ per-source 限流（AIUI 页面 30/min、对话 120/min）+ 审计日志；EXTERNAL_SIDE_EFFECT 走 `GlassToolConfirmChannel` 眼镜端确认闸门
  - 新增 `connection/ChannelArbiter`：同设备同 SCN 单通道约束收敛为「按优先级持有租约」（BACKGROUND / NORMAL / LONG_LIVED），`acquire` 非阻塞必成功、`shouldYield` 判断让路，LONG_LIVED 超 10 分钟打疑似泄漏告警
  - 新增 `glasses/LinkProtocol`（v2，双端逐字节同源 + `checkProtocolSynced` 构建期守护）与 `glasses/GlassesHandshake`（能力握手三态：未知=乐观 / 已确认不支持=快速降级 / 4s 超时判旧版）
- **Lab 工具桥（AIUI 页面调手机端 34 工具）**：
  - 眼镜端：`AiuiLinkActivity` + `host.js` + `lab-page-bridge.js`（页面 realm 桥，靠 fetch + `__lab/tool_call_sync` 同步阻塞）
  - 手机端：`ToolGateway.kt`（DENY 仅 `open_aiui_app` 防自指递归，15s 超时 + 8000 字截断）
  - `AsrPushServer.CTRL_TOOL_CALL = "__LAB_TOOL__"` 复用 RFCOMM 通道上行
  - `AsrBridgeCoordinator` 新增 `__LAB_TOOL__` 分流
  - `CxrLHiRokidSession.handleAiuiToolCall` 委派 ToolGateway
- **`open_aiui_app` 工具新增 `params` 参数**：用户说「用 AIUI 播放西厢」时携带 `{"songName":"西厢"}` 经 CXR 下发到眼镜端，页面 boot 完成后作为首条 `hostMessage` (`type=launch`) 投递
- **ASR 防抖 + 推送恢复补读**：
  - `onAsrText` 改为「同文 + 上一条仍在处理中」才丢弃，用户连说两次「停止播放」执行两次
  - `asrHandling` 标志 + 90s 兜底超时，防异常路径永远吞指令
  - `AsrPushClient` 新增 `onConnected` 回调，置位 `catchUpRequested` 立即补读推送断连期间积压文件
  - `start()` 1.5s 防抖，避免重复创建 push client 抢同一条 RFCOMM 通道
  - `AsrPushClient` 修复：socket 必须在 `connect()` 成功后才置位，避免握手期间 `isConnected` 短暂 true 让上层跳过文件兜底轮询
- **大量 bug 修复**：
  - `KeyButtonService` 持久化短命 destroy 计数器防崩溃循环 + `RECEIVER_NOT_EXPORTED` 注册屏幕亮起接收器
  - `AdbFileManagerClient` 在 CUT 路径 drain socket 防丢数据
  - `AdbScreenMirrorClient` + `ScreenStreamDecoder` 对 late/partial 帧加固
  - `ChatStateHolder` + `ChatScreen` 消息不再卡在「sending」
  - `Models` 容错坏 apps.json 条目
  - `ApkDownloader` 真正取消下载 + 删除临时文件
  - `IconLoader` 5min TTL 缓存
  - `LocalModelPage` 预检查磁盘空间 + 可取消进行中拉取
  - `FileManagerActivity` 并发安装不再 spam toast
  - `ConnectionRouteManager` 重连时失效缓存路由
  - `ChatSettingsDialog` 本地模型 LaunchedEffect 不再误覆盖在线选择
  - `saveAiConfig` 切在线时清掉残留 `ai_local_model`
  - `sendKeyQuizConfig` 链路就绪时复用现有连接直接 `sendCustomCmd`，不再走 `cleanup()` 全链路重建
- **新测试**：共 **14 个测试类 / 155 个 `@Test`**（v3.5 新增 `GoldenAgentEvalTest` 20 个 golden case 覆盖 SSE 重放语义 / 工具声明完整性 / prompt 路由；2026-09-12 补两批回归测试：`AdbSyncProtocolTest` / `AdbFileManagerSyncTest` / `HidReportTest` / `ToolRiskMapTest` / `ChatHistoryStoreTest`，并扩 `AiChannelTest` / `RokidLink/AiChannelProtocolTest`，详见「构建 → 单元测试」）

## v3.9 更新总览

- **眼镜端连续对话（多轮免唤醒）**：首轮唤醒后可连续追问，无需反复说唤醒词，一问一答更自然
- **AIUI 页面 ↔ Lab 工具桥正式打通**：`.ink` 智能体页面内可直接调用手机端 Lab 工具能力（回调式桥 + WebView 兜底投递），如音乐播放器页面内完成点歌、取封面、取播放状态
- **全新音乐播放器 AIUI 应用**（`music-player.aix`，在 Gitee Release 附件单独提供）：
  - 专辑封面从屏幕顶端全屏铺满（`aspectFill` 保比例不变形），底部三行滚动歌词（上句 / 当前高亮 / 下句）
  - 封面区与歌词区物理分区 + 2px 分隔线，绝不重叠；流体布局适配不同分辨率，`env(safe-area-inset-*)` 安全区避让
  - 移除全部播放控件（进度条 / 时间 / 按键提示），界面只保留封面与歌词；语音点歌、快捷键控制仍在
- **音乐播放稳定性修复**：
  - 修复播放失败却提示成功——音频先下载到本地再播放，规避酷我 CDN 返回 `application/octet-stream` 导致 NuPlayer 选不出解码器的问题
  - 新增 `get_cover_image` 工具：封面在手机侧转成 data URL 下发，眼镜无网络也能显示专辑封面
  - AIUI 启动参数必定执行（补 `onMessage` / `launch` 范式，`play_song` 必填 `songName`），「用 AIUI 播放西厢」直达播放
- **AI 回复与官方回声彻底区分**：
  - 显式来源标记区分 Lab 回复与官方回声，修复首轮回复漏出官方答案
  - Lab 文字落在独立干净气泡（ASR_End 渲染闸门），官方文案闪现从约 1s 压到约 0.15s
  - 自动清洗模型偶发输出的 `<answer>` 异常包裹标签
- **AIUI 生成侧渲染规范修正**：统一官方单色绿屏配色规范；修复技能文档同步失效
- **版本**：手机端 3.9（versionCode 24）/ 眼镜端 RokidLink 3.9（versionCode 17），内嵌眼镜端为 R8 混淆 release 包

## v4.0 更新总览

- **会话架构接缝化重构**（对齐 DeepSeek Harness「能力可插拔 + 全程可回放」）：
  - 新增 `approval` / `compaction` / `llm` / `session` / `subagent` 能力包，删除硬编码 `ToolPolicy`
  - 会话事件流全程记录（SessionGraph / SessionTrace / SessionLog），模型可见内容可回放导出
  - **多会话管理**：新建 / 重命名 / 删除 / 切换 + 持久化；新增对话记录图、执行轨迹、上下文占用栏、本会话提示词与记忆管理 UI
  - `ModelCapabilityProbe` 按模型上下文窗口动态调整压缩阈值；只读子代理支持历史会话归因查询
- **IMU 头动规则（v2 规则编程层）**：
  - 眼镜端 `HeadImuService` 20Hz 上报六轴 + 四元数，全部判定在手机端（`MotionBuffer` 环形缓冲 + `MotionRules` / `MotionRuleEngine`）
  - 支持点头 / 摇头 / 静止 / 持续抬头等规则，**动作放开为任意工具调用（含 MCP）**，可用语音直接建规则
  - 审批弹窗支持头动手势应答（点头确认 / 摇头取消）；新增边沿闸门防「保持静止」重复触发，参数越界自动收敛
  - 新增 `stop_tts` 工具：「别说了」可打断播报，也可作头动规则动作
- **外部 MCP 工具集**：新增 MCP 客户端（`ai/mcp` + `McpToolProvider` + 设置页「外部 MCP」），MCP 工具与内置工具统一进风险档与审批闸门
- **知识库混合检索**：BM25 + 向量语义双路召回（RRF 融合，k=60），支持模型自动探测、增量索引与分批回填；语义路失败自动退回纯 BM25
- **引导与权限收敛**：
  - 删除启动期通知 / 悬浮窗 / 电池优化 / 自启动弹窗，统一由引导「开启全部权限」步逐项检测并拉起
  - `AppPermission` 补登记蓝牙（minSdk 门控），日历读 / 写标签拆开，权限步并入电池优化与自启动说明
  - 「安装眼镜端」「配置眼镜 WiFi」两步改为**每次启动都出现**（不落盘），换网不再被进度挡住
  - 修复 WiFi 列表在外层滚动容器里被量成 0 高度而不显示；自启动跳转改为应用信息页
- **AI 回复时序修复**：等眼镜 AI 场景打开后再下发 ASR，修复冷启动首条文字丢失；对齐官方下行时序，消除双气泡竞态
- **安全与工程**：签名材料出库（去掉明文回退，缺失即中止 release 构建）；调试广播接收器收拢进 debug 源集；工具精简（电量 / 设备信息 / 存储合并为 `get_glasses_status`）；OCR 模型按需下载降低包体
- **版本**：手机端 4.0（versionCode 25）/ 眼镜端 RokidLink 4.0（versionCode 18），内嵌眼镜端为 R8 混淆 release 包

## 使用指南

1. 确保眼镜已连接到与手机相同的 WiFi 网络
2. 在手机端打开 RokidLab，按照引导授权并连接眼镜
3. 底部导航分为五个模块（`NavPage`）：
   - **应用商店**（STORE）：浏览并安装应用到手机或眼镜
   - **乐奇聊天**（CHAT）：与 AI 文字聊天（回复眼镜语音播报）、拍照问 AI、本地知识库、**对话生成 AIUI 智能体应用**
   - **乐奇工具**（LEQI_TOOLS）：单页分组——**双向投屏**（眼镜屏幕→手机 / 手机画面→眼镜）、**文件管理**（浏览和管理眼镜文件）、**ADB 工具**（应用管理、定时消息/启动、系统信息、Shell 命令，各子功能使用不同导航栏色区分）
   - **蓝牙手柄**（HID_GAMEPAD）：鼠标/游戏手柄模式控制眼镜
   - **设置**（SETTINGS）：应用配置、服务管理、**提交应用**、**语言切换**

> 原独立的 MIRROR_PAIR（双向投屏）、FILE_MANAGER（文件管理）、ADB_TOOLS（ADB 工具）三个导航项已合并进 LEQI_TOOLS 单页；相关功能入口均已保留。

> **RokidLink 卡片色彩规则**：已安装/运行中 → 各自模块导航栏色；未安装/安装中/停止按钮 → 应用商店色（BrewCoral）；启动按钮 → 各自导航栏色。

## 多语言支持

- **默认语言**：简体中文（`values/strings.xml`）
- **支持语言**：English（`values-en/strings.xml`）
- **首次启动**：自动检测手机系统语言，zh 显示中文，其他语言显示英文
- **运行时切换**：设置 → 语言 → 选择后立即生效，无需重启
- **扩展语言**：通过 Crowdin 管理翻译，在 `res/` 下新建 `values-{lang}/strings.xml` 即可

## 依赖

- **CXR-L SDK** (`com.rokid.cxr:client-l:1.1.2`，Maven) — 手机端与眼镜通信，内置 16KB 对齐 so，兼容 Android 16；v3.5 从 1.1.0 升级，pin cxr-service-bridge:1.0-20260715.121510-107 timestamped snapshot（与 1.1.2 POM 指向一致，区别于 release 1.0 二进制）
- **CXR-S SDK** (`com.rokid.cxr:cxr-service-bridge:1.0`，Maven) — 眼镜端桥接服务；v3.5 RokidLink 删除 1MB 本地 libs/*.aar 改用 Maven 在线依赖（与本地字节级一致：16 类含 ReplyImpl + 10 so）
  - ⚠️ 眼镜端**绝不能**切换到 client-l 1.1.0+，其 fat aar 缺 `ReplyImpl` 类，native 层反射加载会 SIGABRT（已在 2026-09-08 真机验证）
- Jetpack Compose (Material 3) — 现代 UI 框架
- 蓝牙 HID Device Profile — 系统 API（Android 9+）
- RapidOCR (`rapidocr4j-android` + OpenCV 4.12 + ONNX Runtime 1.22) — 本地离线 OCR
- OkHttp 4.12.0 — v3.5 替代裸 HttpURLConnection，连接池 + HTTP/2 复用避免每次重新握手
- R8 — v3.5 RokidLink release 启用（13.8MB → 8.7MB），proguard-rules.pro 已 keep `com.rokid.cxr.**` 因 CXR SDK 通过 JNI 反射调用自身

## UI 设计

详见 [UI-DESIGN.md](./UI-DESIGN.md)

设计风格：**双主题配色系统**
- **丝绒炭黑（Velvet Dark）**：暖暗调默认主题（`#0B0B0E` 底色）
- **冰蓝冰川（Cool Blue）**：浅蓝冷调主题（`#F0F5FF` 底色）
- 全站 JetBrains Mono 等宽字体
- 统一 12dp 圆角、1dp 边框
- 模块色撞色方案
- 设置中可随时切换主题

## 许可

MIT License

## 作者

**DLOVER**
