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

/** Java 侧解包完成后调用 */
async function boot(config) {
  if (openStarted) return;
  openStarted = true;
  loading.style.display = 'none';
  const width = (config && config.width) || canvas.clientWidth || 640;
  const height = (config && config.height) || canvas.clientHeight || 400;
  canvas.width = width;
  canvas.height = height;
  try {
    view = await createInkView({
      width,
      height,
      layoutMode: 'bounded',
      scaleFactor: 1,
      appFps: 30,
      surface: { type: 'html-canvas', canvas },
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
    // 双保险：DOM focus 经 syncFocus → view.focus()；再显式补一次引擎 focus。
    canvas.focus();
    view.focus();
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
 * 页面只写 await Lab.callTool('play_song', { songName: '西厢' })，
 * 不必关心 RFCOMM 上行、cbId 配对、结果回传等底层细节（全部在此封装）。
 *
 * 无 bridge 时（官方 Sys_AIUI_Start / AgentStore 渲染环境）callTool 会 reject，
 * 页面必须 try/catch 降级，不能让整个页面挂掉。
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

window.__aiuiHost = { key, hostMessage, boot };
notify('hostReady');
