# 工作区规则

本项目的完整开发规范详见 [`RULES.md`](file:///d:/rokidapp/cxrl/RokidLab/RULES.md)。

## 核心约束

在每次修改代码时，AI 助手必须严格遵守以下规则：

### 1. 多语种规则
- **所有用户可见文本必须通过 `strings.xml` + `ctx.getString(R.string.*)` 引用**
- 禁止任何硬编码中文或英文文本在 Kotlin/XML 代码中
- 字符串必须同时在 `values/strings.xml`（中文）和 `values-en/strings.xml`（英文）中定义
- 日志信息使用英文

### 2. 设计系统规则
- 优先使用 `com.rokidlab.phone.design` 包中的现有组件
- 颜色优先使用 `Brew*` 常量（如 `BrewInfo`, `BrewGreen`），避免直接写 Color 值
- 形状优先使用 `BrewShape*` 常量

### 3. ADB 模块规则
- 子功能必须使用 `AdbDialogContent` 容器，不自行处理连接状态
- UI 层代码放在 `adb/ui/` 子包中
- 业务逻辑代码放在 `adb/` 根包中

### 4. 架构规则
- 按功能分包，UI 层放在 `ui/` 子包
- 公共组件纳入 `design/` 包
- 不要在 Composable 函数内部定义类

## 5. 纠错检查清单（修改代码后必须逐项验证）

### 5.1 导入检查
- [ ] `clip` 必须使用 `import androidx.compose.ui.draw.clip`，**禁止**使用 `import androidx.compose.foundation.clip`
- [ ] 同一行不要用换行符拼接多个 import 语句（如 `import A\nimport B` 必须分行）
- [ ] 删除重复的 import 行

### 5.2 字符串资源检查
- [ ] 新增的字符串资源 name 必须唯一，不能与已有定义重复
- [ ] 新增字符串同时添加中文（`values/strings.xml`）和英文（`values-en/strings.xml`）
- [ ] 删除旧代码时同步清理不再使用的字符串资源

### 5.3 编译错误预防
- [ ] Composable 函数内部的局部 Composable 函数必须标注 `@Composable`
- [ ] 引用的字符串资源必须确保在 `strings.xml` 中有定义
- [ ] 组件参数名必须匹配 design 模块实际定义的参数（如 `BrewCompactButton` 参数为 `text` 而非 `label`）
- [ ] lambda 参数传递函数引用时，若类型推断歧义使用 lambda 包装：`{ cb -> getOrConnect(cb) }`
- [ ] 列表构造使用 `context.getString(R.string.xxx_fmt, arg1, arg2)` 而非字符串拼接

### 5.4 函数/类定义约束
- [ ] 数据类（data class）和密封类（sealed class）定义在 Composable 函数外部（文件顶层）
- [ ] 不要在 Composable 函数内部定义 `class`、`data class`、`sealed class`
- [ ] `resetEditor` 等辅助函数必须在 `createTask` 等调用者之前定义（前向引用问题）

### 5.5 颜色与主题检查
- [ ] 使用 `design` 包的 `Brew*` 颜色常量替代 `Color(0xFF...)` 或 `Color.Red` 等硬编码
- [ ] 使用 `BrewShape*` 形状常量替代 `RoundedCornerShape` 硬编码值
