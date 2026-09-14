# 小白教程：动动嘴皮子，让 Trae AI 帮你改 RokidLab

> 你只需要亲手做 **2 件事**：装 Trae、点几次「允许」。
> 其余所有事（装环境、下载项目、构建、装到手机）全部**复制提示词发给 Trae AI**，它自己干。

---

## 第 1 步：亲手安装 Trae（唯一的安装活）

1. 打开邀请链接，注册账号：

   ```
   https://www.trae.cn/events/code-fission/CZZAK6ENXXCR?utm_source=copy_link&utm_medium=code_fission
   ```

2. 在页面下载 **Windows 版**，双击安装，打开后登录。

---

## 第 2 步：给 AI 一个工作台（半分钟）

1. 在 D 盘新建一个**空文件夹**，命名 `projects`
2. 打开 Trae → 「文件」→「打开文件夹」→ 选 `D:\projects` → 弹窗点「信任」

---

## 第 3 步：装环境 + 下载项目（复制发给 AI）

把下面整段复制，粘贴到 Trae 右侧 AI 对话框，发送：

```
请帮我在电脑上完成以下两件事，遇到选择就用默认值，需要执行命令时我会点允许：
1. 安装开发环境：安装 Git 和 JDK 17（Temurin），装好后确保命令行执行 java -version 能显示 17；
   再下载 Android SDK 命令行工具，安装到 D:\android-sdk，接受全部许可，
   装好 platform-tools、platforms;android-34、build-tools;34.0.0。
2. 用 Git 把 https://gitee.com/dlover1314/RokidLab 克隆到 D:\projects\RokidLab。
全部完成后，把每一步的结果告诉我。
```

AI 会自动执行命令，**每次弹确认就点「允许」**。等它回复全部完成即可（下载需要几分钟）。

---

## 第 4 步：创建配置 + 第一次构建（复制发给 AI）

```
项目 docs/build-config/ 目录里已经准备好了 4 个配置文件，请复制到指定位置：
- settings.gradle.kts、build.gradle.kts、local.properties → 复制到项目根目录
- libs.versions.toml → 复制到 gradle 文件夹里
如果 Android SDK 不是装在 D:\android-sdk，把 local.properties 里的路径改成实际路径。

完成后，在 D:\projects\RokidLab 目录下执行：
.\gradlew.bat :phone-app:assembleDebug
把构建结果告诉我。
```

第一次构建要联网下载很多东西，**等 5~10 分钟，别关窗口**。
看到 AI 回复 BUILD SUCCESSFUL 就成功了，安装包在
`D:\projects\RokidLab\phone-app\build\outputs\apk\debug\` 里。

---

## 第 5 步：装到手机上（复制发给 AI）

先亲手做两件小事：

1. 手机：设置 → 关于手机 → 连点「OS 版本」7 次 → 返回 → 更多设置 → 开发者选项 → 打开「**USB 调试**」
2. 数据线连电脑，手机弹出「允许 USB 调试」点**允许**

然后发给 AI：

```
我已经用数据线连好手机并打开了 USB 调试。
请用 adb devices 确认手机已连接，然后把 phone-app\build\outputs\apk\debug 目录下的
apk 安装到手机并启动 RokidLab，把结果告诉我。
```

手机上打开 RokidLab，按提示授权（手机要先装好并登录 **Rokid AI App**），再蓝牙连接眼镜。

---

## 第 6 步：改功能（还是复制发给 AI）

直接用大白话说需求，例如：

```
请给应用商店列表加一个按大小排序的按钮，中英文文案都要同步修改，改完重新构建。
```

```
请改成：眼镜电量低于 20% 时，聊天页面顶部显示红色提醒，中英文都加，改完重新构建。
```

AI 改完后，把新生成的 apk 装进手机（重复第 5 步的提示词），手机上看效果，不满意继续说。

---

## 第 7 步：改眼镜端（选做）

```
请构建 .\gradlew.bat :RokidLink:assembleDebug，然后把 RokidLink-debug.apk 安装到眼镜上并启动。
眼镜用数据线连电脑，如果 adb devices 里没有，先执行 adb connect 192.168.49.1。
注意手机和眼镜同时在线时，adb install 必须带 -s 设备号。
```

---

## 万能兜底：报错了怎么办

把报错文字全选复制，发给 AI 这一句：

```
刚才的命令报错了，报错信息如下，请修复后重新执行：
（这里粘贴报错内容）
```

**三条铁律**（跟 AI 强调也行）：

1. 不要升级 Gradle、AGP、Kotlin 版本，项目锁死了，升了必报错
2. 改界面文字时提醒 AI「中英文都要同步」，否则构建会被拦截
3. 构建第一次很慢是正常的，中途别关窗口
