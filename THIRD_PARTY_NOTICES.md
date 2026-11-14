# 第三方来源与许可

本项目（DSHM Bot）以 **AGPL-3.0** 发布，源码公开。

## 参考与移植来源

| 项目 | 许可 | 使用方式 |
|---|---|---|
| [dsh-Go](https://github.com/ccch1mneyyy/dsh-Go) | AGPL-3.0 | 产品定位、移动端设计、DSH 客户端协议与后续 Kotlin 引擎运行时的参考基线 |
| [DS-Bot](https://github.com/Fei2411/DS-Bot) | Apache-2.0 | Bot 团队的产品语义与接口契约参考；`BotInks` 颜色算法移植自其 `src/client/inks.js` |
| [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness) | 以官方仓库为准 | 底层 Agent 引擎 |
| [Lucide](https://lucide.dev) | ISC | 界面图标；由 `tools/fetch_lucide_icons.py` 转成 Android VectorDrawable（版本钉死 1.66.0） |
| [proot](https://proot-me.github.io/) | GPL-2.0 | 本机运行时用它模拟 rootfs；`app/jniLibs/arm64-v8a/libproot*.so` 及 `app/src/main/assets/runtime/licenses/proot-COPYING.txt` |
| [talloc](https://talloc.samba.org/) | LGPL-3.0-or-later | proot 依赖；`libtalloc.so` 与 `licenses/libtalloc-notice.txt` |
| [libandroid-shmem](https://github.com/termux/libandroid-shmem) | Apache-2.0 | proot 依赖；`libandroid-shmem.so` 与版权文件 |
| [org.json](https://github.com/stleary/JSON-java) | Public Domain | 仅 JVM 单元测试使用；`app/libs/json-20231013.jar`（Android 运行时用系统自带实现）；SHA-256 `0f18192df289114e17aa1a0d0a7f8372cc9f5c7e4f7e39adcf8906fe714fa7d3`，升级须同步更新 |
| dsh-Go 宠物美术（Deepy 小鲸鱼 / 鲸娘） | 作者授权 dsh-Go 使用（见 dsh-Go `app/assets/pets/*/pack.json`） | 宠物设置与 App 内浮层；`app/src/main/res/drawable-nodpi/pet_*_idle.png` 取自 dsh-Go |

## 说明

- 本机引擎运行时清单（`app/src/main/assets/runtime/manifest.json`）与 proot 二进制取自
  dsh-Go v1.0.9（AGPL-3.0），rootfs（Ubuntu 24.04 + Node + DSH 0.2.1-rc.4）在首次启动时下载，
  不含在仓库里。
- `BotInks` 的 `defaultColor` / FNV-1a 算法移植自 DS-Bot（Apache-2.0, Copyright 2026 Fei2411），
  并与 dsh-Go 的 Dart 实现对照验证（`BotInkTest` 内置参考向量）。
- 主界面的占位头像使用 Lucide 图标（ISC），非 DS-Bot 的角色美术。

- rootfs 构建脚本（`runtime/build-runtime.sh、`runtime/lib/rtbuild.py、`runtime/patches/）移植自 dsh-Go v1.0.9（AGPL-3.0，Copyright 2026 ccch1mneyyy），用于构建我们自己的 Android rootfs。

