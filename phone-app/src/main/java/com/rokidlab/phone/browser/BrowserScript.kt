package com.rokidlab.phone.browser

import org.json.JSONObject

/**
 * 浏览器「页面侧脚本」与快照解析（纯逻辑，不依赖 Android，可直接单测）。
 *
 * ## 为什么 JS 与解析要独立成文件
 * 这两件事都是**纯函数**：`LIB + 表达式` 拼出一段可注入的 JS，把 evaluateJavascript 的
 * 返回值解析成给模型看的文本。它们与 WebView 生命周期、Activity、线程桥无任何关系，
 * 抽出来才能被单测钉住 —— 网页快照的格式一变，模型侧的"编号 → 点击哪个元素"就整体错位，
 * 而这种错位在真机上只表现为"点了没反应"。
 *
 * ## 页面侧的两条实现纪律
 * 1. **编号表每次快照整体重建**（`window.__labRefs = map`）：页面重渲染后旧编号立即失效，
 *    点击一个已脱离文档的元素会如实返回"页面已变化"，而不是点到一个看起来对、实则无关的元素。
 * 2. **React 受控输入必须走原生 value setter**（见 [LIB] 里的 `L.type`）：
 *    直接 `el.value = x` 不会触发 React 的 onChange，页面看着有字、提交上去却是空的。
 */
internal object BrowserScript {

    /** 单次快照最多列出的可交互元素数 */
    private const val MAX_ITEMS = 80

    /** 正文最长字符数（超出截断并注明） */
    private const val MAX_TEXT = 6000

    /** 拼给模型看的整段快照上限 */
    private const val MAX_OUTPUT = 5000

    /**
     * 注入到页面的 helper（幂等）。
     *
     * 每次调用都重定义 `window.__lab`，但**不动** `window.__labRefs` —— 编号表跨调用保留，
     * 由 `L.snapshot()` 整体替换。定义在每次调用前是刻意的：SPA 换页、跨域跳转后
     * `window` 上的旧定义可能已随文档销毁，重定义比"装一次然后祈祷它还在"可靠。
     */
    private val LIB = """
(function(){
  var W = window;
  var L = W.__lab || (W.__lab = {});
  L.label = function(el){
    var t = el.getAttribute('aria-label') || el.getAttribute('title') || el.getAttribute('placeholder') || '';
    if (!t) {
      var tag = el.tagName.toLowerCase();
      var itype = (el.getAttribute('type') || '').toLowerCase();
      if (tag === 'select' && el.selectedIndex >= 0 && el.options[el.selectedIndex]) {
        t = el.options[el.selectedIndex].text || '';
      } else if (itype === 'password') {
        // ★ 绝不回读密码框的 value：快照会进模型上下文、对话历史与日志。
        //   用户在我们鼓励的流程里"自己在页面上登录"，那些字符不该被抄走。
        t = el.getAttribute('name') || '密码';
      } else if (tag === 'input' || tag === 'textarea') {
        t = el.value || el.getAttribute('name') || '';
      } else {
        t = el.innerText || el.textContent || '';
      }
    }
    return ('' + t).replace(/\s+/g, ' ').trim().slice(0, 60);
  };
  L.visible = function(el){
    if (!el || !el.isConnected) return false;
    var r = el.getBoundingClientRect();
    if (r.width < 4 || r.height < 4) return false;
    var s = W.getComputedStyle(el);
    return s.display !== 'none' && s.visibility !== 'hidden' && s.opacity !== '0';
  };
  L.snapshot = function(){
    var map = {};
    var items = [];
    var nodes = document.querySelectorAll('a[href],button,input,textarea,select,[role=button],[contenteditable=true]');
    var n = 0;
    for (var i = 0; i < nodes.length && n < $MAX_ITEMS; i++) {
      var el = nodes[i];
      var tag = el.tagName.toLowerCase();
      var type = (el.getAttribute('type') || '').toLowerCase();
      if (tag === 'input' && type === 'hidden') continue;
      if (el.disabled || !L.visible(el)) continue;
      var t = L.label(el);
      var href = el.getAttribute('href') || '';
      if (!t && !href && type !== 'password') continue;
      n++;
      map[n] = el;
      items.push({ref: n, tag: tag, type: type, text: t, href: href.slice(0, 160)});
    }
    W.__labRefs = map;
    var root = document.querySelector('main') || document.querySelector('article') || document.body;
    var text = root ? (root.innerText || '') : '';
    text = text.replace(/[ \t\u00a0]+/g, ' ').replace(/\n{3,}/g, '\n\n').trim().slice(0, $MAX_TEXT);
    var docH = document.documentElement ? document.documentElement.scrollHeight : 0;
    var top = W.scrollY || 0;
    return {
      url: location.href, title: document.title || '', text: text, items: items,
      y: Math.round(top), h: Math.round(docH), vh: Math.round(W.innerHeight)
    };
  };
  L.inspect = function(ref){
    var el = W.__labRefs && W.__labRefs[ref];
    if (!el || !el.isConnected) return {ok: false};
    return {ok: true, tag: el.tagName.toLowerCase(), type: (el.getAttribute('type') || '').toLowerCase(), text: L.label(el)};
  };
  L.click = function(ref){
    var el = W.__labRefs && W.__labRefs[ref];
    if (!el || !el.isConnected) return {ok: false, err: '页面已变化，请重新获取页面内容'};
    var tag = el.tagName.toLowerCase();
    var label = L.label(el);
    var type = (el.getAttribute('type') || '').toLowerCase();
    if (tag === 'a' && el.getAttribute('target')) el.removeAttribute('target');
    try { el.scrollIntoView({block: 'center'}); } catch (e) { /* 元素不可见时忽略 */ }
    try { el.focus(); } catch (e) { /* 不可聚焦的元素忽略 */ }
    el.click();
    return {ok: true, tag: tag, type: type, text: label};
  };
  L.type = function(ref, text, clear){
    var el = W.__labRefs && W.__labRefs[ref];
    if (!el || !el.isConnected) return {ok: false, err: '页面已变化，请重新获取页面内容'};
    var tag = el.tagName.toLowerCase();
    var old = (el.value === undefined || el.value === null) ? '' : ('' + el.value);
    var val = (clear === false) ? (old + text) : text;
    if (tag === 'input' || tag === 'textarea') {
      try {
        var proto = (tag === 'textarea') ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
        var d = Object.getOwnPropertyDescriptor(proto, 'value');
        if (d && d.set) d.set.call(el, val); else el.value = val;
      } catch (e) { el.value = val; }
      el.dispatchEvent(new Event('input', {bubbles: true}));
      el.dispatchEvent(new Event('change', {bubbles: true}));
    } else if (el.isContentEditable) {
      el.textContent = val;
      el.dispatchEvent(new Event('input', {bubbles: true}));
    } else {
      return {ok: false, err: '该元素不是可输入的输入框'};
    }
    var now = (el.value === undefined || el.value === null) ? el.textContent : el.value;
    return {ok: true, tag: tag, value: ('' + now).slice(0, 200)};
  };
  L.scrollBox = function(){
    var el = document.elementFromPoint(Math.round(W.innerWidth / 2), Math.round(W.innerHeight / 2));
    while (el && el !== document.body && el !== document.documentElement) {
      var s = W.getComputedStyle(el);
      if (/(auto|scroll)/.test(s.overflowY) && el.scrollHeight > el.clientHeight + 20) return el;
      el = el.parentElement;
    }
    return null;
  };
  L.scroll = function(dir){
    var inner = L.scrollBox();
    var box = inner || W;
    var step = Math.max(240, Math.round(W.innerHeight * 0.85));
    if (dir === 'top') {
      if (box === W) W.scrollTo(0, 0); else box.scrollTop = 0;
    } else if (dir === 'bottom') {
      if (box === W) W.scrollTo(0, document.documentElement.scrollHeight); else box.scrollTop = box.scrollHeight;
    } else {
      var dy = (dir === 'up') ? -step : step;
      if (box === W) W.scrollBy(0, dy); else box.scrollTop = box.scrollTop + dy;
    }
    var y = (box === W) ? (W.scrollY || 0) : box.scrollTop;
    var h = (box === W) ? (document.documentElement ? document.documentElement.scrollHeight : 0) : box.scrollHeight;
    var vh = (box === W) ? W.innerHeight : box.clientHeight;
    return {ok: true, y: Math.round(y), h: Math.round(h), vh: Math.round(vh), inner: !!inner};
  };
})();
""".trimIndent()

    /** 读取当前页面（正文 + 可交互元素编号） */
    fun snapshot(): String = "$LIB;JSON.stringify(window.__lab.snapshot())"

    /** 点击编号为 [ref] 的元素（编号来自最近一次 [snapshot]） */
    fun click(ref: Int): String = "$LIB;JSON.stringify(window.__lab.click($ref))"

    /**
     * 只读探察编号为 [ref] 的元素（标签 / type / 可见文案），**不产生任何动作**。
     *
     * 存在意义：点击/输入前必须先知道"这一下是什么"，才能交给 [BrowserGuard] 判断
     * 要不要用户确认。若反过来"先点再判"，那一步就已经执行出去了。
     */
    fun inspect(ref: Int): String = "$LIB;JSON.stringify(window.__lab.inspect($ref))"

    /** 在编号为 [ref] 的输入框里输入 [text]；[clear] = false 时追加而不是清空重填 */
    fun type(ref: Int, text: String, clear: Boolean): String =
        "$LIB;JSON.stringify(window.__lab.type($ref,${JSONObject.quote(text)},$clear))"

    /**
     * 滚动页面。[direction] 必须是 up/down/top/bottom 之一（调用方已收敛取值，
     * 不在这里再兜底 —— 拼进来的字符串会直接进 JS 源码）。
     */
    fun scroll(direction: String): String = "$LIB;JSON.stringify(window.__lab.scroll('$direction'))"

    /**
     * 把 `snapshot()` 的返回值整理成给模型看的文本。
     *
     * [rawJson] 是 evaluateJavascript 的原始返回（JSON 字符串的字面量，可能为 null）。
     */
    fun format(rawJson: String?): String {
        val obj = runCatching { JSONObject(rawJson.orEmpty()) }.getOrNull()
            ?: return "读取页面内容失败：可能页面还在加载或已跳转。稍等一下再调 browser_snapshot"
        val title = obj.optString("title").trim()
        val url = obj.optString("url").trim()
        val text = obj.optString("text")
        val items = obj.optJSONArray("items")
        val sb = StringBuilder()
        if (title.isNotEmpty()) sb.append("标题：").append(title).append('\n')
        sb.append("网址：").append(url).append('\n')
        sb.append("页面内容：\n").append(text.ifBlank { "（正文为空）" }).append('\n')
        if (items == null || items.length() == 0) {
            sb.append("可交互元素：本页没有找到可点击/可输入的元素")
        } else {
            sb.append("可交互元素（browser_click / browser_type 用这里的编号）：\n")
            for (i in 0 until items.length()) {
                val it = items.optJSONObject(i) ?: continue
                sb.append('[').append(it.optInt("ref")).append("] ")
                    .append(elementKind(it)).append('「').append(it.optString("text")).append('」')
                val href = it.optString("href")
                if (href.isNotEmpty()) sb.append(" → ").append(href)
                sb.append('\n')
            }
        }
        // 滚动位置：模型据此判断"内容还没看全，该继续 browser_scroll"
        val docH = obj.optInt("h")
        val vh = obj.optInt("vh")
        val y = obj.optInt("y")
        if (docH > vh + 40) {
            val done = y + vh >= docH - 40
            sb.append("滚动位置：").append(y).append('/').append(docH - vh)
                .append(if (done) "（已到底）" else "（下面还有内容，可用 browser_scroll 往下看）").append('\n')
        }
        val out = sb.toString()
        return if (out.length <= MAX_OUTPUT) out else out.take(MAX_OUTPUT) + "\n…（内容过长已截断）"
    }

    /** 元素在模型眼里的种类（tag/type → 一个词） */
    private fun elementKind(item: JSONObject): String {
        val tag = item.optString("tag")
        val type = item.optString("type")
        return when {
            tag == "a" -> "链接"
            tag == "input" && type == "password" -> "密码框"
            tag == "input" && (type == "submit" || type == "button" || type == "image") -> "按钮"
            tag == "input" -> "输入框"
            tag == "textarea" -> "多行输入框"
            tag == "select" -> "下拉框"
            tag == "button" -> "按钮"
            else -> "元素"
        }
    }
}
