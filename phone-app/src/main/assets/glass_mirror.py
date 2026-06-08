#!/usr/bin/env python3
"""
Rokid Glasses Screen Mirror - Termux Service
需要先安装: pip install pillow

功能:
1. 通过 ADB 连接眼镜并执行 screencap
2. 将屏幕画面通过 TCP 流发送到手机 App
3. App 连接 localhost:6555 接收画面

使用方法:
1. 确保 adb 已连接眼镜: adb connect 192.168.49.1
2. 运行此脚本: python glass_mirror.py
3. 在 RokidBrew App 中点击"屏幕镜像"
"""

import subprocess
import socket
import sys
import os
import time
import struct
from io import BytesIO

# 配置
GLASSES_IP = "192.168.49.1"
STREAM_PORT = 6555
FPS = 15  # 每秒帧数

def log(msg):
    print(f"[Mirror] {msg}", flush=True)

def check_adb_connection():
    """检查 ADB 是否已连接眼镜"""
    try:
        result = subprocess.run(
            ["adb", "devices"],
            capture_output=True,
            text=True,
            timeout=5
        )
        devices = result.stdout.strip().split("\n")[1:]
        for device in devices:
            if GLASSES_IP in device and "device" in device:
                return True
        return False
    except Exception as e:
        log(f"ADB 检查失败: {e}")
        return False

def connect_glasses():
    """连接到眼镜"""
    try:
        log(f"正在连接眼镜 {GLASSES_IP}...")
        subprocess.run(["adb", "connect", GLASSES_IP], timeout=10)
        if check_adb_connection():
            log("眼镜连接成功!")
            return True
        else:
            log("眼镜连接失败")
            return False
    except Exception as e:
        log(f"连接失败: {e}")
        return False

def capture_screen():
    """捕获屏幕画面"""
    try:
        # 使用 screencap 获取原始 RGBA 数据
        process = subprocess.Popen(
            ["adb", "-s", GLASSES_IP, "shell", "screencap", "-p"],
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE
        )
        return process
    except Exception as e:
        log(f"启动屏幕捕获失败: {e}")
        return None

def rgba_to_jpeg(rgba_data, width=720, height=1280):
    """将 RGBA 数据转换为 JPEG"""
    try:
        # RGBA -> RGB
        rgb_data = BytesIO()
        for i in range(0, len(rgba_data), 4):
            rgb_data.write(rgba_data[i:i+3])
        rgb_bytes = rgb_data.getvalue()

        # 使用 PIL 转换并压缩为 JPEG
        from PIL import Image
        img = Image.frombytes("RGB", (width, height), rgb_bytes)
        output = BytesIO()
        img.save(output, format="JPEG", quality=70)
        return output.getvalue()
    except ImportError:
        log("PIL 未安装，尝试直接使用 PNG...")
        # 如果没有 PIL，直接返回 PNG 格式
        return rgba_data
    except Exception as e:
        log(f"图像转换失败: {e}")
        return None

def get_screen_resolution():
    """获取屏幕分辨率"""
    try:
        result = subprocess.run(
            ["adb", "-s", GLASSES_IP, "shell", "wm", "size"],
            capture_output=True,
            text=True,
            timeout=5
        )
        output = result.stdout.strip()
        if "Physical size:" in output:
            size = output.split(":")[-1].strip()
            width, height = map(int, size.split("x"))
            return width, height
    except:
        pass
    return 720, 1280  # 默认分辨率

def stream_loop(client_socket):
    """主循环：捕获屏幕并发送"""
    width, height = get_screen_resolution()
    log(f"屏幕分辨率: {width}x{height}")

    process = capture_screen()
    if not process:
        return False

    last_frame_time = 0
    frame_interval = 1.0 / FPS

    try:
        buffer = b""
        expected_size = width * height * 4  # RGBA

        while True:
            # 非阻塞读取
            chunk = process.stdout.read(4096)
            if not chunk:
                # 进程结束，重新启动
                log("屏幕捕获中断，重新启动...")
                time.sleep(0.5)
                process = capture_screen()
                if not process:
                    break
                buffer = b""
                continue

            buffer += chunk

            # 如果缓冲区足够，尝试解码
            if len(buffer) >= expected_size:
                frame_data = buffer[:expected_size]
                buffer = buffer[expected_size:]

                # 转换为 JPEG 并发送
                jpeg_data = rgba_to_jpeg(frame_data, width, height)
                if jpeg_data:
                    try:
                        # 发送帧长度 (4字节大端序)
                        length_prefix = struct.pack(">I", len(jpeg_data))
                        client_socket.sendall(length_prefix + jpeg_data)
                    except Exception as e:
                        log(f"发送帧失败: {e}")
                        break

    finally:
        process.terminate()
        process.wait()

    return True

def main():
    log("=" * 50)
    log("Rokid Glasses Screen Mirror Service")
    log("=" * 50)

    # 检查并连接眼镜
    if not check_adb_connection():
        if not connect_glasses():
            log("无法连接到眼镜，请检查:")
            log("1. 眼镜已开启 ADB 调试")
            log("2. 手机已连接眼镜热点")
            log("3. 运行: adb connect 192.168.49.1")
            sys.exit(1)

    # 检查 PIL
    try:
        from PIL import Image
        log("PIL 已安装")
    except ImportError:
        log("PIL 未安装，正在安装...")
        subprocess.run([sys.executable, "-m", "pip", "install", "pillow", "-q"])
        try:
            from PIL import Image
            log("PIL 安装成功")
        except:
            log("PIL 安装失败，将使用原始格式")

    # 创建服务器
    server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)

    try:
        server.bind(("127.0.0.1", STREAM_PORT))
        server.listen(1)
        log(f"服务已启动，监听端口 {STREAM_PORT}")
        log("请在 RokidBrew App 中点击'屏幕镜像'")
    except OSError as e:
        log(f"端口 {STREAM_PORT} 已被占用，可能是服务已在运行")
        log("如果服务未运行，可使用以下命令关闭:")
        log(f"  fuser -k {STREAM_PORT}/tcp")
        sys.exit(1)

    while True:
        try:
            log("等待 App 连接...")
            client_socket, address = server.accept()
            log(f"App 已连接: {address}")

            # 只服务一个客户端
            stream_loop(client_socket)

            client_socket.close()
            log("客户端已断开")

        except KeyboardInterrupt:
            log("\n收到中断信号，正在退出...")
            break
        except Exception as e:
            log(f"错误: {e}")
            time.sleep(1)

    server.close()
    log("服务已停止")

if __name__ == "__main__":
    main()