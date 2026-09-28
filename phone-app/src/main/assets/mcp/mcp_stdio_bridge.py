#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""RokidLab MCP stdio 桥：把一个「stdio 传输的 MCP server」包成本地 Streamable HTTP 端点。

用法（由 RokidLab 经 proot 拉起，$PREVIEW_PORT 由宿主侧注入）：
    python3 mcp_stdio_bridge.py --port "$PREVIEW_PORT" -- <启动 server 的命令与参数>

协议面（只覆盖 RokidLab 客户端真正用到的那条路）：
  - POST /            JSON-RPC 请求/通知。带 id 的请求转发给子进程并等配对响应；
                      无 id 的通知只转发，回 202。
  - GET / 其他方法     405（客户端从不 GET 长连接）。
  - 子进程崩了        返回 JSON-RPC error（code -32000），错误文本可读。

实现约束：
  - 只用 Python 标准库（容器里没有 pip 生态可指望）；
  - stdio MCP 的帧格式 = 按行分隔的 JSON-RPC（逐行 write + flush）；
  - 子进程 stdout 用独立读线程收进队列，请求线程按 id 配对 —— 避免「管道没数据时
    阻塞读永不超时」；通知/未知 id 的消息直接丢弃（本桥无会话语义）；
  - 子进程的 stdin 读写全局串行（stdio server 本就是单线程 JSON-RPC）；
  - 子进程 stderr 不接管（继承本进程 stderr → 进 proot 日志，起不来时能定位）。
"""

import argparse
import json
import queue
import subprocess
import sys
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

CHILD = None
CHILD_LOCK = threading.Lock()
READ_TIMEOUT_SEC = 60.0


def spawn_child(cmd):
    global CHILD
    CHILD = subprocess.Popen(
        cmd,
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        stderr=None,
        text=True,
        encoding="utf-8",
    )


def read_loop():
    """子进程 stdout → 队列（每项一个已解析的 JSON 对象，或 None = 流结束）。"""
    q = queue.Queue()
    def run():
        try:
            for line in CHILD.stdout:
                line = line.strip()
                if not line:
                    continue
                try:
                    q.put(json.loads(line))
                except ValueError:
                    pass  # 非 JSON 行（个别 server 往 stdout 打日志）直接丢
        except Exception:
            pass
        q.put(None)
    t = threading.Thread(target=run, daemon=True)
    t.start()
    return q


OUT = None  # read_loop 的队列，spawn 后赋值


def forward(obj):
    """把一条 JSON-RPC 消息写给子进程 stdin。返回错误文本或 None。"""
    if CHILD is None or CHILD.poll() is not None or CHILD.stdin is None:
        return "MCP server 进程已退出（命令启动失败或崩了，见容器日志）"
    try:
        with CHILD_LOCK:
            CHILD.stdin.write(json.dumps(obj, ensure_ascii=False) + "\n")
            CHILD.stdin.flush()
    except Exception as e:
        return "写 MCP server stdin 失败：%s" % e
    return None


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        pass  # 静默：访问日志对排障没用，反而冲掉 server 的 stderr

    def do_GET(self):
        self._reply(405, {"jsonrpc": "2.0", "error": {"code": -32000,
                     "message": "GET not supported (stdio bridge)"}})

    def do_POST(self):
        try:
            length = int(self.headers.get("Content-Length", "0"))
            body = self.rfile.read(length) if length > 0 else b""
            msg = json.loads(body.decode("utf-8"))
        except Exception as e:
            self._reply(400, {"jsonrpc": "2.0", "error": {"code": -32700,
                         "message": "bad JSON body: %s" % e}})
            return

        # 通知（无 id）：只转发，不应答内容
        if "id" not in msg:
            err = forward(msg)
            if err:
                self._reply(200, {"jsonrpc": "2.0", "error": {"code": -32000, "message": err}})
            else:
                self.send_response(202)
                self.send_header("Content-Length", "0")
                self.end_headers()
            return

        err = forward(msg)
        if err:
            self._reply(200, {"jsonrpc": "2.0", "id": msg["id"],
                              "error": {"code": -32000, "message": err}})
            return

        # 等配对响应：跳过通知与别人的响应
        deadline = READ_TIMEOUT_SEC
        remaining = deadline
        import time
        start = time.monotonic()
        while True:
            if time.monotonic() - start > deadline:
                self._reply(200, {"jsonrpc": "2.0", "id": msg["id"],
                                  "error": {"code": -32000,
                                            "message": "MCP server %.0fs 内没有返回 id=%s 的响应"
                                                       % (deadline, msg["id"])}})
                return
            if CHILD is not None and CHILD.poll() is not None and OUT.empty():
                self._reply(200, {"jsonrpc": "2.0", "id": msg["id"],
                                  "error": {"code": -32000,
                                            "message": "MCP server 进程在等待响应时退出（退出码 %s）"
                                                       % CHILD.returncode}})
                return
            try:
                item = OUT.get(timeout=0.5)
            except queue.Empty:
                continue
            if item is None:
                self._reply(200, {"jsonrpc": "2.0", "id": msg["id"],
                                  "error": {"code": -32000,
                                            "message": "MCP server 输出流已关闭（进程退出码 %s）"
                                                       % (CHILD.returncode if CHILD else "?")}})
                return
            if isinstance(item, dict) and item.get("id") == msg["id"] and (
                    "result" in item or "error" in item):
                self._reply(200, item)
                return
            # 其它消息（通知/乱序）：丢弃，继续等

    def _reply(self, code, obj):
        data = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)


def main():
    global OUT
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, required=True)
    ap.add_argument("cmd", nargs=argparse.REMAINDER)
    args = ap.parse_args()
    cmd = args.cmd
    if cmd and cmd[0] == "--":
        cmd = cmd[1:]
    if not cmd:
        print("mcp_stdio_bridge: missing command after --", file=sys.stderr)
        sys.exit(2)
    try:
        spawn_child(cmd)
    except Exception as e:
        print("mcp_stdio_bridge: spawn failed: %s" % e, file=sys.stderr)
        sys.exit(1)
    OUT = read_loop()
    srv = ThreadingHTTPServer(("127.0.0.1", args.port), Handler)
    srv.daemon_threads = True
    srv.serve_forever()


if __name__ == "__main__":
    main()
