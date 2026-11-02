# 第三方来源与许可

本项目（DSHM Bot）以 **AGPL-3.0** 发布，源码公开。

## 参考与移植来源

| 项目 | 许可 | 使用方式 |
|---|---|---|
| [dsh-Go](https://github.com/ccch1mneyyy/dsh-Go) | AGPL-3.0 | 产品定位、移动端设计、DSH 客户端协议与后续 Kotlin 引擎运行时的参考基线 |
| [DS-Bot](https://github.com/Fei2411/DS-Bot) | Apache-2.0 | Bot 团队的产品语义与接口契约参考；`BotInks` 颜色算法移植自其 `src/client/inks.js` |
| [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness) | 以官方仓库为准 | 底层 Agent 引擎 |
| [Lucide](https://lucide.dev) | ISC | 界面图标；由 `tools/fetch_lucide_icons.py` 转成 Android VectorDrawable（版本钉死 1.66.0） |

## 说明

- `BotInks` 的 `defaultColor` / FNV-1a 算法移植自 DS-Bot（Apache-2.0, Copyright 2026 Fei2411），
  并与 dsh-Go 的 Dart 实现对照验证（`BotInkTest` 内置参考向量）。
- 当前仓库尚未包含 dsh-Go 的源码；M3 移植其 Kotlin 运行时层时，会在对应文件头保留
  AGPL-3.0 许可声明，并在此更新来源说明。
- 主界面的占位头像使用 Lucide 图标（ISC），非 DS-Bot 的角色美术。
