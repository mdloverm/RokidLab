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
  if (!view) return;
  try {
    const payload = (typeof jsonOrPayload === 'string') ? JSON.parse(jsonOrPayload) : jsonOrPayload;
    view.dispatchMessageEvent(payload, 'rokidlink-host');
  } catch (e) {
    console.warn('[aiui-host] hostMessage fail:', e);
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
    console.log('[aiui-host] view focused');
    notify('ready');
  } catch (e) {
    console.error('[aiui-host] boot fail:', e);
    notify('error', String((e && e.message) || e));
  }
}

window.__aiuiHost = { key, hostMessage, boot };
notify('hostReady');
