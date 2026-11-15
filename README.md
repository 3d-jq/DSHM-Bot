# DSHM Bot

<p align="center">
  <img src="docs/images/app-icon-preview.png" width="128" alt="DSHM Bot 图标">
</p>

<p align="center">
  <a href="https://github.com/3d-jq/DSHM-Bot/actions/workflows/app.yml"><img src="https://github.com/3d-jq/DSHM-Bot/actions/workflows/app.yml/badge.svg" alt="app"></a>
  <a href="https://github.com/3d-jq/DSHM-Bot/actions/workflows/runtime.yml"><img src="https://github.com/3d-jq/DSHM-Bot/actions/workflows/runtime.yml/badge.svg" alt="runtime"></a>
</p>

一个原生 Android 客户端，把 [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness) 上的
[DS Bot](https://github.com/Fei2411/DeepSeek-Bot) 搬到手机上：Bot 团队、群聊、浏览器操控与手机操控，
全部在一台手机里完成。

**引擎跑在手机本地**（内置 Node 运行时，经 PRoot 启动），不需要额外的服务器或电脑配合，
也不需要 Root。

## 功能

**对话**

- 流式回复，长文档、表格、代码块与数学公式都能正常排版；代码块带语法高亮和一键复制。
- 回复里交付的文件显示为卡片，点开可查看改动详情（逐行 diff、多文件切换、复制补丁）。
- 审批、提问、只读视图、密钥请求都作为卡片出现在对话里，直接作答。
- 支持图片与文件附件、长文折叠、排队发送与插话、引用回答。

**Bot 团队与群聊**

- 多个 Bot 各自有独立对话；群聊里能看到成员分工、谁在进行中、谁在等批准。
- Bot 之间可以互相派活、传递消息；群聊一轮结束后有本轮交付的产物卡。
- 群聊触发中转上限会自动暂停，等你确认继续。

**浏览器**

- Bot 可以打开网页、读取内容、点击、输入、滚动，并支持多标签、下载。
- 遇到支付、登录等高风险操作会先征求同意；页面缩略图会贴回对应那一轮对话。

**手机操控**

- Bot 可以读屏、点击、输入，用无障碍服务实现，不需要 Root。
- 悬浮胶囊显示当前动作，随时可停止；密码、验证码、支付类控件拒绝代填。

**设置**

- 模型与渠道、插件、权限、定时任务、记忆、通知、外观与个性化、语音、隐私、诊断、账号余额。
- 引擎状态与日志可直接在应用内查看，便于反馈问题。

## 系统要求

- Android 8.0（API 26）及以上，ARM64 设备。
- 首次启动需要联网下载运行时（约数百 MB），之后可离线使用。
- 需要自备 DeepSeek API Key。

## 构建

需要 **JDK 21** 与 Android SDK（在 `local.properties` 中写 `sdk.dir`，不要提交）。

```bash
./gradlew :app:assembleDebug        # 调试 APK
./gradlew :app:testDebugUnitTest    # 单元测试
./gradlew :app:lintDebug            # Lint（warning 也视为错误）
```

宿主侧插件（`app/src/main/assets/ds-bot/`）的测试：

```bash
cd app/src/main/assets/ds-bot
node --test --import ./test/stub-resolve.mjs test/*.test.js
```

## 目录

```
app/                     Android 应用
  src/main/java/         Kotlin 源码（按 core / data / domain / service / ui 分层）
  src/main/assets/ds-bot/宿主插件源码（Node）
  src/test/              JVM 单元测试
  src/androidTest/       插桩测试
runtime/                 随应用分发的 Node 运行时与 PRoot 补丁
design/  tools/          图标资源与生成脚本
docs/                    设计与实现文档
```

## 许可

本项目基于 dsh-Go（AGPL-3.0）与 DS-Bot（Apache-2.0）的设计与协议实现二次开发，
以 **AGPL-3.0** 发布。来源与第三方许可见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。
