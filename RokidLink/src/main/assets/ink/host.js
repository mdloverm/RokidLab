// RokidLink AIUI 宿主桥：
// 官方 @yodaos-pkg/ink（browser-integration）运行在 WebView 内，
// Java 侧解包 .aix 后经 https://ink.local/bundle.json 提供文件清单，
// 本模块负责 createInkView → openBundle → startRendering，
// 并把眼镜系统按键（含 Lab 蓝牙手柄转成的 KeyEvent）注入为 DOM 键盘事件，
// 同时支持 hostMessage 以 dispatchMessageEvent 方式驱动页面（onMessage 协议）。
import { createInkView } from './index.js';

const canvas = document.getElementById('ink-root');
const loading = document.getElementById('loading');
let view = null;
let openStarted = false;

function notify(fn, ...args) {
  try {
    if (window.Android && typeof window.Android[fn] === 'function') {
      window.Android[fn](...args);
    }
  } catch (e) { /* ignore */ }
}

/** 宿主日志 → logcat（WebView 无远程调试，AIUI 链路排查全靠它） */
function log(msg) {
  try { console.log(msg); } catch (e) { /* ignore */ }
  try {
    if (window.Android && typeof window.Android.log === 'function') {
      window.Android.log(String(msg));
    }
  } catch (e) { /* ignore */ }
}

/** 归一化文件 map：'app.json' 与 '/app.json' 兼容，二进制 b64 → Uint8Array */
function toFiles(map) {
  const out = {};
  for (const k in map) {
    const v = map[k];
    if (!v) continue;
    const key = k.replace(/^\/+/, '');
    if (v && v.b64) {
      try {
        const bin = atob(v.b64);
        const arr = new Uint8Array(bin.length);
        for (let i = 0; i < bin.length; i++) arr[i] = bin.charCodeAt(i);
        out[key] = arr;
      } catch (e) {
        console.warn('[aiui-host] b64 fail:', key);
      }
    } else {
      out[key] = (typeof v === 'string') ? v : (v.text || '');
    }
  }
  return out;
}

/** 眼镜系统按键注入（action: 'down' | 'up'），code 与 AssistServer AiuiKeyMapper 键名一致 */
function key(code, action) {
  console.log('[aiui-host] key() code=' + code + ' action=' + action + ' view=' + !!view);
  if (!view) return;
  try {
    const type = action === 'up' ? 'keyup' : 'keydown';
    const ev = new KeyboardEvent(type, {
      code: code || 'Unidentified',
      key: code || 'Unidentified',
      bubbles: true,
      cancelable: true,
      view: window,
    });
    window.dispatchEvent(ev);
    console.log('[aiui-host] key dispatched ' + type + ' code=' + ev.code +
      ' defaultPrevented=' + ev.defaultPrevented);
  } catch (e) {
    console.warn('[aiui-host] key inject fail:', e);
  }
}

/** 手机侧经 CXR 下发的 host 消息 → dispatchMessageEvent（页面 onMessage 接收） */
function hostMessage(jsonOrPayload) {
  let payload;
  try {
    payload = (typeof jsonOrPayload === 'string') ? JSON.parse(jsonOrPayload) : jsonOrPayload;
  } catch (e) {
    console.warn('[aiui-host] hostMessage parse fail:', e);
    return;
  }
  // 诊断：确认 Java 侧确实把消息投进来了（toolResult 收不到时先看这一行有没有）
  log('[aiui-host] hostMessage type=' + (payload && payload.type) +
    ' cbId=' + (payload && payload.cbId) + ' viewReady=' + !!view);
  // 工具调用结果：优先尝试兑现外层 window.Lab.callTool 的 Promise（若页面能访问到 Lab），
  // 同时把结果作为 onMessage 派发给 ink 页面。ink 沙箱内 Lab 不可见，页面必须靠 onMessage 接收。
  if (payload && payload.type === 'toolResult') {
    try {
      if (window.Lab && window.Lab.onToolResult) window.Lab.onToolResult(payload);
    } catch (e) { /* ignore */ }
  }
  if (!view) {
    log('[aiui-host] hostMessage dropped: view not ready');
    return;
  }
  try {
    view.dispatchMessageEvent(payload, 'rokidlink-host');
  } catch (e) {
    log('[aiui-host] hostMessage fail (engine may not support dispatchMessageEvent): ' + e);
  }
}

/**
 * 启动参数下发：手机端 open 命令携带的 launchParams，在页面渲染完成后作为首条消息投递。
 * 时序要求：必须在 boot 内 view 就绪之后调用 —— hostMessage 首行 `if (!view) return`，
 * 早于此处调用会被静默丢弃（页面永远收不到启动参数）。
 */
function deliverLaunchParams(config) {
  const raw = config && config.launchParams;
  if (!raw) return;
  try {
    const params = (typeof raw === 'string') ? JSON.parse(raw) : raw;
    hostMessage({ type: 'launch', params });
    log('[aiui-host] launch params delivered: ' + raw);
  } catch (e) {
    log('[aiui-host] bad launch params: ' + e);
  }
}

/**
 * 视口高度链兜底 —— 手机端容器里 `html { height: 100% }` 会结算成 0，而且是**自锁**的
 * （实测：`window.innerHeight` 是 320，`html` 的 computed height 和 `100vh` 都是 0）。
 * `#ink-root` 的 `height: 100%` 跟着变 0；更要命的是 index.html 里 `html, body` 带
 * `overflow: hidden`，于是 canvas 被整个裁掉 —— **引擎画得再好也没用**：canvas 位图里
 * 明明有完整画面（`toDataURL` 能导出来），屏幕却是一块纯黑，而且连 `body{background:red}`
 * 都不显示（整页根本没被绘制）。logcat 全绿，因为引擎侧完全成功。
 *
 * 修法：按实测视口尺寸把 `html` / `body` 的高度钉成 px，canvas 回到 100%。
 * **只在真的塌陷时才动手** —— 眼镜端 WebView 就是整屏、从不塌陷，一次都不会触发，
 * 行为与改动前完全一致（两端共用这份资产，这个安全边界是刻意的）。
 */
let sizePatched = false;
// auto-height 布局下引擎会把 body 设为 height:auto、canvas 收缩到内容高，
// 此时 body.clientHeight=0 / canvas 高度≠视口高是**预期状态**，
// 绝不能让塌陷兜底把 canvas 钉回 100%×100%（那会毁掉 auto-height 布局）
let autoHeightMode = false;
function fixCollapsedViewport() {
  if (autoHeightMode) return;
  const vw = window.innerWidth | 0;
  const vh = window.innerHeight | 0;
  // 视口还没量出来（0），等 resize 再来一次，别把高度钉成 0
  if (!vw || !vh) return;
  const collapsed = document.body.clientHeight <= 0 || canvas.clientHeight <= 0;
  if (!collapsed && !sizePatched) return;
  document.documentElement.style.height = vh + 'px';
  document.body.style.height = vh + 'px';
  canvas.style.width = '100%';
  canvas.style.height = '100%';
  sizePatched = true;
}
window.addEventListener('resize', fixCollapsedViewport);

// ⚠️ 视口量不出来（vw/vh 为 0）时**不能只等 resize**——Chromium 在 WebView 里
// 首帧的 innerHeight 可能是 0，而 resize 未必还会再来，于是 canvas 一直 0 高
// ⇒ 表现为「黑屏，要手动按一下才出画面」（手动操作触发了 layout/resize 才补上）。
// ⇒ 补一个短周期轮询兜底：连续若干次都量不到就放弃，避免长期占用定时器。
(function pollViewport() {
  let tries = 0;
  const tick = function () {
    fixCollapsedViewport();
    if (sizePatched || autoHeightMode || ++tries >= 20) return;
    setTimeout(tick, 100);
  };
  tick();
})();

// ── 传感器桥（IMU）─────────────────────────────────────────────
// 官方 AIUI 页面用 W3C Generic Sensor API（new Gyroscope({frequency:60}).start()）
// 做头控。引擎经 hostCapabilities.handleRequest 通知宿主 start/stop（请求字段
// snake_case：{target_id, session_id, frequency}），宿主把读数以 CustomEvent
// 派发到 view.hostCapabilitiesTarget（detail 字段 camelCase：targetId/sessionId），
// 由 index.js #bindHostCapabilityEvents 转成 IPC 事件喂回页面沙箱。
// Android 侧链路：sensorStart/sensorStop（JavascriptInterface）→ SensorManager
// 监听 → pushSensor 回注本模块（路由到活动会话并派发 reading）。
const SENSOR_CAPS = ['gyroscope', 'accelerometer', 'absoluteOrientation', 'magnetometer'];
/** 能力名 → Android 桥类型码（AiuiLinkActivity.sensorStart/Stop 约定） */
const SENSOR_ANDROID_TYPE = {
  gyroscope: 0, accelerometer: 1, absoluteOrientation: 2, magnetometer: 3,
};
/** 活动会话：能力名 → {targetId, sessionId}（Java 推读数时按此路由） */
const sensorSessions = {};

function dispatchSensorEvent(cap, eventType, detail) {
  if (!view) return false;
  try {
    view.hostCapabilitiesTarget.dispatchEvent(
      new CustomEvent(cap + '.' + eventType, { detail })
    );
    return true;
  } catch (e) {
    log('[aiui-host] sensor event fail: ' + e);
    return false;
  }
}

function sensorControl(cap, method, request) {
  // 诊断：wasm 侧生成的请求原文（真机确认字段名用，保留）
  log('[aiui-host] sensor request: ' + JSON.stringify(request));
  // 字段名兼容：serde 侧可能发 snake_case（target_id）或 camelCase（targetId）
  const targetId = String((request && (request.target_id || request.targetId)) || '');
  const sessionId = String((request && (request.session_id || request.sessionId)) || '');
  const type = SENSOR_ANDROID_TYPE[cap];
  const bridgeReady = type != null && window.Android &&
    typeof window.Android.sensorStart === 'function';
  if (!bridgeReady) {
    // 桥不可用：发 error 让页面 onerror 降级（官方页面会回退方向键控制）
    dispatchSensorEvent(cap, 'error', {
      targetId: targetId, sessionId: sessionId,
      error: 'NotSupportedError', message: 'sensor bridge unavailable',
    });
    return;
  }
  if (method === 'start') {
    const freq = Number((request && request.frequency) || 60);
    sensorSessions[cap] = { targetId: targetId, sessionId: sessionId };
    const ok = window.Android.sensorStart(type, freq, targetId, sessionId);
    if (ok && targetId) {
      // activate 必须在 start 成功后发：页面侧 sensor.activated 依赖它；
      // targetId 为空时事件会被引擎 requireStringField 拒收，跳过等 reading 侧观察
      dispatchSensorEvent(cap, 'activate', { targetId: targetId, sessionId: sessionId });
      log('[aiui-host] sensor start ok: ' + cap + ' freq=' + freq +
        ' target=' + targetId + ' session=' + sessionId);
    } else if (!ok) {
      delete sensorSessions[cap];
      dispatchSensorEvent(cap, 'error', {
        targetId: targetId, sessionId: sessionId,
        error: 'NotReadableError', message: 'sensor not available',
      });
      log('[aiui-host] sensor start failed: ' + cap);
    } else {
      log('[aiui-host] sensor started but targetId empty, raw=' + JSON.stringify(request));
    }
  } else {
    window.Android.sensorStop(type, targetId, sessionId);
    delete sensorSessions[cap];
    log('[aiui-host] sensor stop: ' + cap);
  }
}

/** hostCapabilities：四传感器 start/stop 钩子（默认 serializer 把结果转成功响应） */
function buildHostCapabilities() {
  const caps = {};
  for (const cap of SENSOR_CAPS) {
    caps[cap] = {
      start: function (req) { sensorControl(cap, 'start', req); },
      stop: function (req) { sensorControl(cap, 'stop', req); },
    };
  }
  return caps;
}

/** Java 侧传感器读数入口：路由到活动会话并派发 reading 事件（w 仅 absoluteOrientation 有效） */
function pushSensor(cap, x, y, z, w, ts) {
  const s = sensorSessions[cap];
  if (!s || !view) return;
  // targetId 为空时引擎会拒收事件（requireStringField），跳过避免刷错误日志
  if (!s.targetId) return;
  const detail = {
    targetId: s.targetId, sessionId: s.sessionId,
    timestamp: Number(ts) || 0,
    x: Number(x) || 0, y: Number(y) || 0, z: Number(z) || 0,
  };
  if (cap === 'absoluteOrientation') detail.w = Number(w) || 0;
  dispatchSensorEvent(cap, 'reading', detail);
}

// TODO(音乐无声)：ink 的 AudioPlayer/Sound 是 runtime 内建组件，官方原生宿主有
// 对应实现，Web 宿主（browser-integration）无默认 AudioPlayer 后端 → 页面播放无声。
// 待办：确认能否经 hostCapabilities 或自定义 media 组件补齐（官方文档
// jsar-project.github.io/ink/zh/api/media/ 未提供 browser 侧播放 hook）。
// ✅【2026-10-05 已定位并修复，勿再按"缺后端"方向排查】
//   wasm 里的后端**齐全**（`__wbg_createAudioContext` / `__wbg_audioDecode` /
//   `"Web audio playback started for id="`）。真凶是宿主解包器把大音频**丢掉了**：
//   `AixBundleReader.MAX_BINARY_B64 = 1.5MB` ⇒ `assets/bgm.wav`(1.84MB) 被跳过
//   ⇒ wasm 打 `Web audio bundle lookup miss for src=`。
//   已改成音频走 8MB 上限（`MAX_AUDIO_B64`）。若又无声，先查 `skipped=` 是否为 0。

/** Java 侧解包完成后调用 */
async function boot(config) {
  if (openStarted) return;
  openStarted = true;
  const width = (config && config.width) || canvas.clientWidth || 640;
  const height = (config && config.height) || canvas.clientHeight || 400;
  canvas.width = width;
  canvas.height = height;
  autoHeightMode = true;
  fixCollapsedViewport();
  try {
    view = await createInkView({
      width,
      height,
      // 官方机制：宽度约束、高度随内容自适应（引擎自调 canvas 高度并对内容居中
      // 由 CSS 承担，见 index.html #ink-root 的 absolute + 定位）。
      // 对齐官方 AssistServer 的 AiuiInkViewportResolver / AutoHeight 行为。
      // ⚠️ `scaleFactor: 1` 是**刻意的**，勿改成 devicePixelRatio（引擎的缺省值就是 dpr）：
      // 引擎按 `canvas.width = nextWidth × scaleFactor` 分配**位图**，而 CSS 尺寸固定
      // `nextWidth px`（见 index.js #syncHtmlCanvasBindingSize）。眼镜端 1 CSS px = 1 物理 px
      // （index.html 的 viewport width=480），所以只有 scaleFactor=1 时**位图与 CSS 1:1**、
      // 浏览器无需重采样；一旦取 dpr=1.5，位图变成 CSS 的 1.5 倍就必须重采样，
      // 位图最外圈会被双线性插值出混色边。此处保持 1 才是 1:1 无重采样。
      layoutMode: 'width-constrained-auto-height',
      scaleFactor: 1,
      appFps: 30,
      surface: { type: 'html-canvas', canvas },
      hostCapabilities: buildHostCapabilities(),
      onContentSizeChanged: function (size) {
        log('[aiui-host] content size ' + JSON.stringify(size));
        // JS 显式垂直居中（px）：新 Chromium 把 abspos 的 top:50% 按 auto→0px 解析
        // （旧版才相对视口解析），CSS 百分比定位在两代 WebView 间行为不一致，
        // 必须用 innerHeight 与内容高的显式像素计算——两端（手机/眼镜）统一走这里。
        // 语义：**窗口 = 整个屏幕**，页面是窗口里居中的内容（窗口本身已由
        // FLAG_FULLSCREEN 铺满整屏，见 AiuiLinkActivity.onCreate）。
        try {
          const top = Math.max(0, Math.round((window.innerHeight - size.height) / 2));
          canvas.style.top = top + 'px';
          canvas.style.transform = 'none';
          // 这里**不要**加边缘遮罩 / clip-path / 负边距（都试过并否决）：
          // 会改变 AIUI 的可见尺寸（480x360 -> 478x358），违反"保持原有尺寸"硬要求；
          // 且遮罩必须逐页跟随内容宽高同步，页面尺寸一变就错位。
          //
          // 【2026-10-04】卡片上下那两条横贯 480px 的亮线**不在本层**，已定位为
          // Chromium 给「获得焦点的 canvas」画的**默认焦点环**：
          // `getComputedStyle(canvas).outline` = `rgb(229,151,0) auto 1px`，
          // 与截图中线色 `rgb(229,151,0)` 逐通道吻合、位置正在盒外 1px。
          // 已由 index.html 的 `#ink-root { outline: none }` 消除。
          // 本层（JS/CSS/页面）无需再为它做任何补偿。
          log('[aiui-host] center canvas: top=' + top + 'px (innerH=' + window.innerHeight + ')');

        } catch (e) { /* 布局失败不影响渲染 */ }
      },
    });
    view.setInteractive(true);
    // syncFocus: 让 canvas 的 DOM focus/blur 与 view.focus()/blur() 联动，
    // 引擎元素级焦点导航要求实例处于 focus 态（官方 web demo 靠点击 canvas
    // 聚焦，眼镜上无人点击，必须程序化激活）。
    canvas.setAttribute('tabindex', '0');
    view.bindDomEvents({ canvas, keyboardTarget: window, focusTarget: canvas, syncFocus: true });
    view.setOnCloseRequested((ctx) => {
      notify('closeRequested');
      return true;
    });
    view.setOnClosed(() => {
      view = null;
      notify('closed');
    });

    const resp = await fetch((config && config.bundleUrl) || 'bundle.json');
    const json = await resp.json();
    const files = toFiles(json.files || {});
    view.openBundle({
      appId: json.appId || (config && config.appId) || 'rokidlab.aiui',
      files,
      initialPage: json.initialPage || (config && config.initialPage) || null,
      // 全屏独立场景 = _blank；开局即 focus，让元素级焦点系统从一开始就激活，
      // 否则方向键 keydown 虽被 dispatchInput 送入引擎却不驱动焦点移动。
      hostOptions: {
        initialFocus: 'focus',
        initialTarget: '_blank',
        openBlurTimeoutMs: 5000,
      },
    });
    view.startRendering();
    // 引擎起渲染后再兜一次：万一它按自己的逻辑把 canvas 的 CSS 盒重写成百分比，
    // 高度链会再次塌回 0（塌陷是自我锁死的，重写一次就复现一次）。
    fixCollapsedViewport();
    // 双保险：DOM focus 经 syncFocus → view.focus()；再显式补一次引擎 focus。
    canvas.focus();
    view.focus();
    // ⚠️ loading 必须**等startRendering 之后**再隐藏（2026-10-05 改）。
    // 原先在 boot() 开头就`display:none` ⇒ 音频 bundle 变大后 openBundle 变慢，
    // 那段时间里屏幕上什么都不剩⇒ 表现为「黑屏，要手动按一下才出画面」。
    // 放在这里= 引擎已接管渲染，loading 只覆盖真正的加载期。
    loading.style.display = 'none';
    log('[aiui-host] view focused');
    notify('ready');
    deliverLaunchParams(config);
  } catch (e) {
    log('[aiui-host] boot fail: ' + String((e && e.message) || e));
    notify('error', String((e && e.message) || e));
  }
}

/**
 * Lab 工具口：AIUI 页面调用手机端工具的唯一入口。
 *
 * ⚠️ 页面侧用的是**回调式**注入桥（lab-page-bridge，见 lab-runtime.md §9.0）：
 * 正确写法是 globalThis.Lab.callTool('control_music', { action: 'play', ... }, function (结果, 错误) { … })。
 * 注意本宿主**无事件循环**，`await` / `.then` 的回调永不执行 —— 不要照抄 Promise 写法。
 * 工具名为注册表实际名字（音乐是 `control_music`，没有 `play_song`/`stop_music`）。
 *
 * 无 bridge 时（官方 Sys_AIUI_Start / AgentStore 渲染环境）callTool 会回调报错，
 * 页面必须判错降级，不能让整个页面挂掉。
 */
(function () {
  let seq = 0;
  const pending = new Map();

  function callTool(name, args) {
    return new Promise(function (resolve, reject) {
      if (!window.Android || typeof window.Android.callTool !== 'function') {
        reject(new Error('tool bridge unavailable'));
        return;
      }
      const cbId = 'cb' + (++seq) + '_' + Date.now();
      // 浏览器侧超时略大于手机端 ToolGateway 的 15s，让手机端的错误信息有机会回传
      const timer = setTimeout(function () {
        pending.delete(cbId);
        reject(new Error('tool "' + name + '" timed out'));
      }, 20000);
      pending.set(cbId, { resolve: resolve, reject: reject, timer: timer });
      try {
        window.Android.callTool(name, JSON.stringify(args || {}), cbId);
      } catch (e) {
        clearTimeout(timer);
        pending.delete(cbId);
        reject(e);
      }
    });
  }

  /** 当前可调用工具清单：[{name, description}]（能力发现，随手机端注册变化） */
  function listTools() {
    return callTool('list_tools', {}).then(function (raw) {
      const parsed = (typeof raw === 'string') ? JSON.parse(raw) : raw;
      return (parsed && parsed.tools) || [];
    });
  }

  /** 由 hostMessage 调用：兑现对应 Promise。返回 true 表示已被消费。 */
  function onToolResult(payload) {
    const entry = pending.get(payload.cbId);
    if (!entry) return false;
    clearTimeout(entry.timer);
    pending.delete(payload.cbId);
    if (payload.ok) entry.resolve(payload.result);
    else entry.reject(new Error(payload.error || 'tool failed'));
    return true;
  }

  window.Lab = { callTool: callTool, listTools: listTools, onToolResult: onToolResult };
})();

window.__aiuiHost = { key, hostMessage, boot, pushSensor };
notify('hostReady');
