/*
 * AIUI 页面 realm 的 Lab 工具桥（自动注入，非页面代码）。
 *
 * 背景：AIUI 页面（模型生成的 .ink）运行在 @yodaos-pkg/ink 的 quickjs-wasm 沙箱里，
 * 与 WebView 主 realm 隔离——host.js 在 WebView 主 realm 定义的 window.Lab / window.Android
 * 在页面 realm 完全不可见（实测 typeof=undefined）。因此页面若写 Lab.callTool 会立刻
 * ReferenceError，所有对话生成的 AIUI 智能体（含"音乐播放"）工具调用全部静默失败。
 *
 * 本桥被 AiuiLinkActivity 在装配 bundle 时前置注入到 app.js，从而与页面同 realm，
 * 让 Lab.callTool 真正可用。实现只依赖 ink 沙箱里可用的 fetch：
 *   callTool → fetch('https://ink.local/__lab/tool_call_sync?name=...') 同步阻塞拿结果。
 * 该请求被 WebView shouldInterceptRequest 拦截，宿主在后台线程等手机端执行完毕
 * 后一次性返回 JSON 结果。页面侧无需轮询、无需 setTimeout、无需 onMessage 处理。
 *
 * 注意：ink 页面 realm 的裸标识符解析不走 globalThis，页面代码必须写
 *       globalThis.Lab.callTool(...) 或 window.Lab.callTool(...)，不能写 Lab.callTool(...)。
 */
(function () {
  if (typeof globalThis === 'undefined') return;

  var seq = 0;
  var BASE = 'https://ink.local/';

  /**
   * 页面侧兜底超时（毫秒）。必须 **大于** 宿主同步端点的 SYNC_TOOL_TIMEOUT_MS（25s）：
   * 宿主本来就会在 25s 内给出结果或超时错误，这里只是防「拦截线程异常导致 fetch 永不返回」
   * ——那种情况下 Promise 会永久 pending，页面按规范写的 loading 态就永远转不完。
   */
  var CALL_TIMEOUT_MS = 30_000;

  function parseResult(cbId, text) {
    var parsed;
    try {
      parsed = (typeof text === 'string') ? JSON.parse(text) : text;
    } catch (e) {
      return { ok: false, error: 'bad result: ' + String(text) };
    }
    return {
      ok: !!parsed.ok,
      result: parsed.result,
      error: parsed.error || (parsed.ok ? null : 'tool failed')
    };
  }

  function callTool(name, args) {
    return new Promise(function (resolve, reject) {
      if (typeof fetch !== 'function') {
        reject(new Error('tool bridge unavailable: no fetch'));
        return;
      }
      var cbId = 'cb' + (++seq) + '_' + Date.now();
      var q = encodeURIComponent(JSON.stringify(args == null ? {} : args));
      var url = BASE + '__lab/tool_call_sync?name=' +
        encodeURIComponent(name) + '&args=' + q + '&cbId=' + cbId;

      var settled = false;
      // 只在沙箱确实提供 setTimeout 时才挂兜底计时器（特性探测，避免依赖不存在的 API）
      if (typeof setTimeout === 'function') {
        setTimeout(function () {
          if (!settled) {
            settled = true;
            reject(new Error('tool call timed out: ' + name));
          }
        }, CALL_TIMEOUT_MS);
      }

      fetch(url).then(function (res) {
        return res.text();
      }).then(function (text) {
        if (settled) return;
        settled = true;
        var r = parseResult(cbId, text);
        if (r.ok) resolve(r.result);
        else reject(new Error(r.error || 'tool failed'));
      }).catch(function (e) {
        if (settled) return;
        settled = true;
        reject(e instanceof Error ? e : new Error(String(e)));
      });
    });
  }

  function listTools() {
    return callTool('list_tools', {}).then(function (raw) {
      try {
        var parsed = (typeof raw === 'string') ? JSON.parse(raw) : raw;
        return (parsed && parsed.tools) || [];
      } catch (e) {
        return [];
      }
    });
  }

  globalThis.Lab = { callTool: callTool, listTools: listTools };
})();
