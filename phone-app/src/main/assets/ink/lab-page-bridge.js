/*
 * AIUI 页面 realm 的 Lab 工具桥（自动注入，非页面代码）。
 *
 * ⚠️ 为什么不是 Promise 版 —— 2026-09-17 真机实测定案，**别改回去**：
 *   ink 页面的 QuickJS 沙箱**不驱动事件循环**：微任务/宏任务/定时器回调全部不执行。
 *   实测（宿主① AiuiLinkActivity）：onLoad 里同时放
 *     Promise.resolve().then(...) / setTimeout(...,100) / setInterval(...,200)
 *   眼镜端只输出同步的两行，异步回调一个都没有。
 *   只有 onLoad / onShow / onKeyDown / onMessage 这类**事件回调**会被派发（各自是新的同步执行块）。
 *
 *   ⇒ `fetch(...).then(...)` / `await fetch(...)` 的 Promise **永远不会 resolve/settle**，
 *     任何基于它的桥都会卡死（现象：页面 loading 不结束、调用无结果、且零报错）。
 *   ⇒ 因此本桥是**回调式**：
 *       - 上行：fire-and-forget `fetch(__lab/tool_call?...)` —— 只把请求发出去，**不 await、不 .then**
 *       - 下行：宿主 dispatchHostMessage → 页面 onMessage → `Lab.onHostMessage(e)` 同步派发结果
 *   ⇒ **页面必须在 onMessage 里调用 `globalThis.Lab.onHostMessage(e)`**，否则永远收不到结果。
 *   ⇒ 也**不要**用 setTimeout/setInterval 做超时或重试 —— 定时器不执行。
 *      超时保护在宿主/手机侧（手机端 ToolGateway 15s、眼镜端同步端点 25s）。
 *
 * 背景：AIUI 页面运行在 quickjs-wasm 沙箱里，与 WebView 主 realm 隔离 ——
 * host.js 在 WebView 主 realm 定义的 window.Lab / window.Android 在页面 realm 完全不可见。
 * 本桥被 AiuiLinkActivity 在装配 bundle 时前置注入到 app.js，从而与页面同 realm。
 * 注意：ink 页面 realm 的裸标识符解析不走 globalThis，页面必须写
 *       globalThis.Lab.callTool(...) 或 window.Lab.callTool(...)，不能写 Lab.callTool(...)。
 */
(function () {
  if (typeof globalThis === 'undefined') return;

  var seq = 0;
  var BASE = 'https://ink.local/';

  /** cbId -> 页面回调。结果经 onMessage 回来时按 cbId 匹配后同步调用。 */
  var pending = {};

  /** 把结果交给登记的回调（同步）。返回 true 表示确有等待者。 */
  function finish(cbId, ok, result, error) {
    var f = pending[cbId];
    if (!f) return false;
    delete pending[cbId];
    try {
      f(ok ? result : null, ok ? null : (error || 'tool failed'));
    } catch (e) {
      // 回调自身抛错不能反过来破坏桥：这里只能忽略（沙箱无 setTimeout 兜底）
    }
    return true;
  }

  /**
   * 调用手机端工具。**回调式，不返回 Promise**（原因见文件头）。
   *
   * @param name   工具名，必须与 ToolRegistry 注册名逐字一致
   * @param args   参数对象（可为 null）
   * @param onDone function(result, error)。成功时 error 为 null、result 为结果字符串；
   *               失败时 result 为 null、error 为错误描述。
   */
  function callTool(name, args, onDone) {
    if (typeof name !== 'string' || name.length === 0) {
      if (typeof onDone === 'function') onDone(null, 'empty tool name');
      return;
    }
    if (typeof fetch !== 'function') {
      if (typeof onDone === 'function') onDone(null, 'tool bridge unavailable: no fetch');
      return;
    }
    var cbId = 'cb' + (++seq) + '_' + Date.now();
    if (typeof onDone === 'function') pending[cbId] = onDone;

    var q = encodeURIComponent(JSON.stringify(args == null ? {} : args));
    var url = BASE + '__lab/tool_call?name=' + encodeURIComponent(name) +
      '&args=' + q + '&cbId=' + cbId;

    // fire-and-forget：请求发出去就返回。
    // 绝不 await / .then —— 沙箱不跑微任务，那会让整段页面逻辑永久挂起。
    try {
      fetch(url);
    } catch (e) {
      finish(cbId, false, null, 'fetch failed: ' + ((e && e.message) || e));
    }
  }

  /**
   * 页面 `onMessage` 里必须转发进来。返回 true 表示这条消息是工具结果、已被消费。
   *
   * ⚠️ ink 派发到页面的消息形态实测为 `{ data: <payload 对象> }` —— **e.data 是对象、不是字符串**
   *   （真机日志：`onMessage` 里 `'' + e.data` 得到 `[object Object]`）。
   *   所以这里字符串与对象都要接受；**不要**对 e.data 无条件 JSON.parse（会抛错）。
   */
  function onHostMessage(e) {
    if (!e) return false;
    var raw = (typeof e === 'string') ? e : (e.data != null ? e.data : e);
    var m = raw;
    if (typeof raw === 'string') {
      if (raw.length === 0) return false;
      try {
        m = JSON.parse(raw);
      } catch (err) {
        return false;
      }
    }
    if (!m || m.type !== 'toolResult') return false;
    return finish(m.cbId, !!m.ok, m.result, m.error);
  }

  globalThis.Lab = {
    callTool: callTool,

    /** 回调式列出可用工具：onDone(toolsArray, error)，tools 形如 [{name, description}] */
    listTools: function (onDone) {
      callTool('list_tools', {}, function (res, err) {
        if (typeof onDone !== 'function') return;
        if (err) { onDone(null, err); return; }
        var parsed;
        try {
          parsed = (typeof res === 'string') ? JSON.parse(res) : res;
        } catch (e) {
          parsed = null;
        }
        onDone((parsed && parsed.tools) || [], null);
      });
    },

    onHostMessage: onHostMessage,
  };
})();
