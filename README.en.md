# RokidLab

[简体中文](./README.md) | [English](./README.en.md)

RokidLab is a companion **Android app for Rokid AR glasses**. It turns your phone into the glasses' control center: an app store, a voice AI assistant, two-way screen mirroring, device management tools and more.

## Features

- **App Store (RokidBrew)** — browse, install and update glasses apps; multi-source support (Gitee / GitHub)
- **Leqi AI Assistant** — OpenAI-compatible chat (DeepSeek, Qwen, Kimi, Zhipu, Ollama and any custom provider), 34+ built-in tools (timers, weather, phone control, glasses status, shell, knowledge search…), multi-step agent tasks, long-term memory, knowledge base RAG
- **Photo Q&A** — take a photo through the glasses, on-device OCR (PP-OCRv4, fully offline) and AI answers over voice
- **Two-way screen mirroring** — phone screen on the glasses, glasses view on the phone
- **File manager** — wireless file transfer between phone and glasses (ADB sync protocol)
- **ADB toolkit** — connect the glasses over Wi-Fi directly (no adb.exe): app management, shell, system info, timers, input simulation
- **Bluetooth gamepad** — phone as HID keyboard/mouse/gamepad for the glasses, with on-screen keyboard input
- **On-device Linux** — optional proot Ubuntu environment for running Python/Node/MCP servers locally
- **Glasses side (RokidLink)** — embedded companion app for the glasses, built and shipped inside the phone APK

## Download

| Channel | Link |
|---------|------|
| GitHub Releases | <https://github.com/mdloverm/RokidLab/releases> |
| Gitee Releases (fast in China) | <https://gitee.com/dlover1314/RokidLab/releases> |

Grab `RokidLab-v*-release.apk` — the glasses-side app (RokidLink) is bundled inside and can be installed from within the app.

## Build

Requirements: Android Studio (or CLI only), JDK 17, Gradle 8.7 wrapper (AGP 8.5.0, Kotlin 2.0.21, compileSdk 34).

```bash
git clone https://github.com/mdloverm/RokidLab.git
cd RokidLab
./gradlew :phone-app:assembleDebug
```

Release builds require signing properties (`RELEASE_KEYSTORE_*`, see `gradle.properties.example`) and will run unit tests + verification gates first. CI does this automatically:

- **CI** (`.github/workflows/ci.yml`) — debug build + unit tests on every push/PR
- **Release** (`.github/workflows/release.yml`) — pushing a `v*` tag builds signed release APKs and publishes a GitHub Release automatically

## Documentation (Chinese)

- [DEV_GUIDE.md](./DEV_GUIDE.md) — developer guide
- [UI-DESIGN.md](./UI-DESIGN.md) — UI design reference

## Sponsors

- 爱发电 (Afdian): <https://ifdian.net/a/rokidlab>
