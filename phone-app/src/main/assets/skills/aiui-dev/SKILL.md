---
name: "aiui-dev"
description: "当用户要求在 Rokid 眼镜上生成/开发/修改 AIUI 智能体应用（.aix：会话卡、全屏交互页、答题器、小游戏等），需要 jsui/wx 组件与 API 参考、调试 AIUI 应用，或按官方设计规范对齐视觉时使用。命中词：AIUI、智能体应用、做个卡片、做个页面、眼镜小游戏、.aix。"
---

# AIUI Development

Build AIUI agents from the current contracts in this skill. Do not assume an API, component, CSS property, browser behavior, or lifecycle merely because it exists on the Web or in WeChat Mini Programs.

## Load Only What the Task Needs

- Read [framework.md](./framework.md) for project structure, `app.json`, Page, Widget, Agent Worker, SFC, modules, resources, packaging, or TypeScript.
- Read [events.md](./events.md) for lifecycle, interaction, key, focus, voice wakeup, head gesture, and environment-awareness events.
- Read [components.md](./components.md) before using or reviewing built-in components and their attributes or events.
- Read [wxss.md](./wxss.md) before writing styles, layout, selectors, animation, or custom fonts.
- Read [monochrome-green.md](./design-system-green.md) only for the monochrome-green Rokid Glasses visual language.
- Read [APIs index](./apis.md), then its matching domain file, before using runtime APIs.
- For Widget or Agent Worker instance methods, read [Widget API](./apis-widget.md) or [Agent Worker API](./apis-agent-worker.md).
- Read and apply [delivery checklist](./checklist.md) before declaring a generated or modified AIUI agent complete.

Do not load every reference for a narrow task. For example, an Agent Worker normally needs `framework.md`, `events.md`, and `apis/agent-worker.md`, but not Canvas or the full component catalog.

## Core Authoring Rules

- Register App, Page, Widget, Component, and Agent Worker logic with `export default { ... }`; do not use `App()`, `Page()`, `Widget()`, or similar registration functions.
- Use either a multi-file Page or a single `.ink` Page for one route, never both.
- A `.ink` Page uses `<script def>`, `<script setup>`, `<page>`, and `<style>`. A Widget replaces `<page>` with `<widget>`.
- Declare every Page, Widget, Agent Worker, and custom component in the appropriate configuration before using it.
- Declare required capabilities in `app.json.permissions`; handle device authorization failures separately.
- Treat Widget `family` as a size category. Use relative layout and never hardcode the root to the current Glasses pixel size.
- Use `agentWorkers`, not the removed `workers` field. Extend asynchronous `onOpen` work synchronously with `event.waitUntil(promise)`.
- Use data binding for rendered state and `this.setData()` to update it.
- Resolve packaged resources from project paths; do not invent filesystem or network access that the target does not expose.
- Treat conversation-flow cards as display-only unless the task explicitly targets an interactive full-screen Page or Widget.

## Target and Design Choice

Before styling, determine the intended surface:

- Conversation-flow card: compact, display-only information.
- Full-screen Page: interactive agent UI.
- Widget: compact `1x1` or `1x2` surface with Widget lifecycle.
- Monochrome-green Rokid Glasses: apply `design-system-green.md` after the general WXSS rules.
- Other displays: use theme tokens and task requirements; do not automatically apply the monochrome-green visual language.

## Runtime API Workflow

1. Open the [APIs index](./apis.md) and select the domain.
2. Confirm the exact constructor or method, parameters, return type, events, errors, permissions, and lifecycle in that domain reference.
3. If the API is absent or the requested overload is not documented, inspect current Ink types or implementation rather than guessing.
4. Keep capability checks and cleanup close to the code that acquires the resource.

## Completion Checks

Run the [AIUI agent delivery checklist](./checklist.md). Report which executable checks ran, what they proved, and any target-device behavior that remains unverified. Do not call an agent runnable only because its files look structurally correct.
