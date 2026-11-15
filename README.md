# DSHM Bot

[![app](https://github.com/3d-jq/DSHM-Bot/actions/workflows/app.yml/badge.svg)](https://github.com/3d-jq/DSHM-Bot/actions/workflows/app.yml)
[![runtime](https://github.com/3d-jq/DSHM-Bot/actions/workflows/runtime.yml/badge.svg)](https://github.com/3d-jq/DSHM-Bot/actions/workflows/runtime.yml)

**DS Bot 的安卓版**：把 [DeepSeek Harness (DSH)](https://github.com/deepseek-ai/deepseek-harness) 与
[DS Bot](https://github.com/Fei2411/DeepSeek-Bot) 的整套能力搬到手机上的原生 Android 客户端 ——
引擎跑在本机（PRoot），Bot 团队、群聊、浏览器操控、手机操控全在同一台手机上完成，不依赖外部服务器。

> **状态（2026-11-15，v0.2.0）**：功能与稳定性均已收口。
> 时间线已换成「durable 事件日志 → 全量重算」的地基（[四期设计](docs/phase4-event-subscription.md)），
> 产物卡/改动卡即时、历史不再错位；CI 双流水线（app / runtime）全绿。
> 尚未做的能力与**有意取舍**都记在 [docs/parity-checklist.md](docs/parity-checklist.md)。

## 三条对齐基准

| 基准 | 用途 |
|---|---|
| **DS Bot 网页端 + DSH** | **能力/信息**基准：官方显示什么信息、能做什么操作，我们要有 |
| **dsh-Go**（Flutter 移动端） | **形态**基准：交互怎么放、手机上怎么好用 |
| **平台取舍** | 网页特有（hover / 右键 / 多窗口 / 桌面快捷键）有意不做，用 Android 等价交互替代 |

## 功能

### 对话

- **时间线以 durable 事件日志为唯一事实源**：显示永远由日志重算（对齐 dsh-Go 的一致性模型），
  丢帧能自愈；增量帧只当「有新行」的通知。
- **流式输出**：按块冻结 + 后台解析 live tail；节流按实测渲染耗时自适应（对齐 DeepSeek 官方 demo）；
  收尾不跳动（活动行不闪回、落定行到位即收流式、滚动跟随布局）。
- **Markdown**：排版对齐官方 token（正文行高 1.75、段落 16dp、标题 24/22/20/16），代码块高亮 + 复制。
- **产物卡**：`presented` 交付卡（轮末固定）+ **代码产物卡**（`workspace/changes`）；点文件行打开
  **改动详情页**（文件胶囊、左右滑切、带行号 diff、换行开关、复制补丁，对齐 dsh-Go `ChangeDiffPage`）。
- **交互卡**：审批、提问、只读视图、密钥请求；回答留档（`answered` 行）。
- **附件 / 长文 / 图片**：本地缓存 + FileProvider 打开；长文折叠。
- 排队 / 插话（`session/updateQueue`）、本地乐观气泡认领（`requestId`）。

### 多 Bot 与群聊

- 群聊：成员 dock、轮次状态、**群产物卡按轮持久**、群被中转上限暂停后的「继续」、单独停下某成员。
- Bot 间往来：点事件行打开双方对话；互答轮按官方口径隐藏。
- 名字胶囊 / 头像 / 状态扫光（用户定的偏离项，见对齐清单）。

### 浏览器（Bot 操控）

WebView 池 + 多标签、下载、`read_browser` 读页与点击/输入/滚动、危险操作拦截、
缩略图卡（按截图时刻的 `seq` 贴回它那一轮）。

### 手机操控

无障碍服务（不需要 Root）：读屏定位、动作前校验窗口/包名/节点身份、优先 `ACTION_SET_TEXT`、
悬浮胶囊（停止/继续/允许/拒绝）、eta 风格结果卡；密码/验证码/支付类控件拒绝代填。

### 设置与运维

20+ 设置子页：模型与渠道、插件市场、权限预设、定时任务、记忆、通知、外观与个性化、语音、隐私、
诊断、账号与余额；引擎状态页与开屏安装引导。

**日志**：宿主 `engine.log` + 客户端 `client.log` 分段轮转落盘（不少 ROM 过滤第三方 logcat，
诊断页把两边拼起来看）—— 排查「产物卡不及时 / 行错位」时的第一现场。

## 架构

```
┌──────────────────────────────────────────────────────────┐
│ Android 客户端（Kotlin + Compose，单 Activity）          │
│                                                          │
│  ui/chat/    显示 = TimelineFold（事件日志 → 行）        │
│                    + 更早页（宿主分页行）                │
│                    + 队列行（agent inbox）               │
│  data/remote/  DshClient：/api/bot RPC + /api/bot/feed   │
└───────────────▲──────────────────────────────────────────┘
                │ HTTP / SSE（127.0.0.1，dsh-auth cookie）
┌───────────────┴──────────────────────────────────────────┐
│ 本机 PRoot + Node 运行时（runtime/，CI 构建 rootfs）     │
│   DSH 内核（Session / 事件日志 / 投影 / 工具）           │
│   └─ DS Bot 插件（app/src/main/assets/ds-bot/）          │
│        projection.js：官方 displayItems + 行投影         │
│        feed.js：SSE + 原始事件转发（带连续 seq）         │
└──────────────────────────────────────────────────────────┘
```

**四期「事件订阅」的要点**（[设计稿](docs/phase4-event-subscription.md)）：

1. 宿主把每个 durable `session/event` 带**连续 seq** 转发（折叠需要的类型带载荷，其余占位），
   并新增 `events` 端点按区间补洞（等价 dsh-Go `_fill`）；
2. 客户端维护**每会话事件日志** + 连续游标：重复丢弃、连续追加、跳号补洞；
3. 私聊显示 = **客户端折叠**（事件日志派生）+ **更早页（宿主分页行）** + **队列行（宿主）**，
   三源互不重叠；宿主行投影降级为事件日志不可用时的兜底；
4. 群聊**不走** seq 日志（产物卡数据在成员各自会话里、条目是位置序），继续用服务端条目模型。

折叠与宿主投影的一致性由 **oracle 差分**锁住：期望值由宿主真实现生成
（`ds-bot/test/gen-display-items.mjs` 真跑 `displayItems` + `composeTimelineRows`），
Kotlin 测试逐行比 `key/kind/seq/turn/timeMs/payload`；另有真机真实会话日志的离线差分。

## 技术栈

| 层 | 选型 |
|---|---|
| UI | Kotlin 2.2.20 + Jetpack Compose（Material 3，BOM 2025.09.00），单 Activity，手写整屏 push/pop 路由 |
| 状态 | ViewModel + `StateFlow<UiState>` 单向数据流；`DshRemoteApi` 接口隔离 HTTP 实现（便于假实现测试） |
| 引擎 | 本机 PRoot + Node 运行时；`DshClient` 用 `HttpURLConnection` 换 `dsh-auth-*` cookie、发 `/api/bot` 与 RPC、读 SSE |
| 宿主插件 | `app/src/main/assets/ds-bot/`（ESM Node，随 APK 分发） |
| 构建 | Gradle 8.14 + AGP 8.11.1，`compileSdk 36` / `minSdk 26` / **`targetSdk 28`**（PRoot 取舍） |
| 测试 | JUnit4（JVM，704 例）+ 宿主 JS `node --test`（63 例）+ Compose 插桩 + Python 图标/运行时管线测试 |

## 目录

```
app/                          Android 单模块
  src/main/java/com/dshm/bot/
    core/design/              设计令牌（颜色/间距/圆角/字体/动效/主题）
    domain/                   模型、Bot 颜色与形状算法、Repository 与设置接口
    data/runtime/             本机引擎：manifest、安装、PRoot 启动、日志（engine/client）、诊断
    data/remote/              DSH HTTP/SSE 客户端、时间线行/事件、DS Bot 解析、语音、账号、缓存
    data/settings/            SharedPreferences、API Key（加密存储）、个性化
    service/                  无障碍手机操控
    ui/home/                  主界面、搜索、列表行、顶栏
    ui/chat/                  对话（时间线/折叠/Markdown/diff 页/浏览器）、Bot 详情
    ui/engine/                开屏安装引导、引擎状态页、诊断页
    ui/settings/              设置首页 + 20 余个子页
    ui/account/               账号与余额
  src/main/assets/ds-bot/     宿主插件源码（含 test/ 与 oracle 生成器）
  src/test/                   JVM 单元测试
  src/androidTest/            Compose 插桩测试
runtime/                      Node/JS 运行时：PRoot 补丁、桥接、插件、依赖锁
design/  tools/               图标源文件与生成脚本（含测试）
docs/                         架构、路线图、对齐清单、研究文档
```

## 构建与测试

需要 **JDK 21** 与 Android SDK（`local.properties` 写 `sdk.dir`，不入库）。

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21.0.10'

.\gradlew.bat :app:assembleDebug                # 调试 APK
.\gradlew.bat :app:testDebugUnitTest            # JVM 单元测试
.\gradlew.bat :app:lintDebug                    # Android Lint（warning 也算 error）
.\gradlew.bat :app:connectedDebugAndroidTest    # 插桩测试（需模拟器/真机）
```

宿主插件（`app/src/main/assets/ds-bot/`）的 JS 测试：

```bash
cd app/src/main/assets/ds-bot
node --test --import ./test/stub-resolve.mjs test/*.test.js
```

> 部分 Windows 环境下 Gradle 无法 fork Test Executor（stdin 管道被关闭），可用
> `org.junit.runner.JUnitCore` 直跑编译产物；CI 侧走标准 `:app:testDebugUnitTest`。

### 约定

- **测试覆盖**：新功能至少覆盖正常/边界/异常与回归用例；测试与 lint 全绿才提交。
- **回归必须可证伪**：修 bug 时配的测试要验证「回退修复即变红」。
- **设计令牌**：界面代码不写颜色/间距/圆角/字号字面量，统一 `Dshm.colors/spacing/radius/typography`；
  数值以 `core/design` 与 [docs/design/design-system.md](docs/design/design-system.md) 为准。
- **versionCode**：取构建时间（`dshm.versionCodeZoneOffset` 控制时区），保证覆盖安装并刷新启动器缓存。
- **lint**：`abortOnError = true`、`dshm.lintWarningsAsErrors = true`（零 warning 才过）；
  故意保留的告警在 `app/lint.xml` 按文件定向豁免并写明理由。版本更新类检查（`GradleDependency` /
  `NewerVersionAvailable` / `AndroidGradlePluginVersion`）已关闭 —— 依赖版本是**故意钉死**的。
- **targetSdk 28**：为绕开 Android 10+ 对 PRoot 执行原生二进制的限制而有意保留，
  lint 显式豁免 `ExpiredTargetSdkVersion`。**上架 Play 或提高 targetSdk 前必须重新评估本机引擎方案**。

## 文档索引

| 文档 | 内容 |
|---|---|
| [docs/parity-checklist.md](docs/parity-checklist.md) | **能力对齐总纲**：已对齐 / 必修 / 有意不做 / 平台取舍 |
| [docs/phase4-event-subscription.md](docs/phase4-event-subscription.md) | 四期：事件日志 + 客户端折叠 + oracle 差分 |
| [docs/dsh-study.md](docs/dsh-study.md) · [docs/ds-bot-study.md](docs/ds-bot-study.md) | DSH 与 DS Bot 的系统研究（事件模型 / 端点 / 渲染） |
| [docs/dsh-go-parity-design.md](docs/dsh-go-parity-design.md) | 时间线一致性设计与分期 |
| [docs/architecture.md](docs/architecture.md) | 分层、运行时、PRoot 与 targetSdk 取舍 |
| [docs/roadmap.md](docs/roadmap.md) | 里程碑与当前重点 |
| [docs/design/design-system.md](docs/design/design-system.md) | 设计系统（界面布局的唯一依据） |
| [docs/reference-baselines.md](docs/reference-baselines.md) | 参考基线（dsh-Go / DS-Bot）本地存放说明 |

## 已知取舍（摘录，完整见对齐清单）

- **侧聊 / BTW 旁路问答**：官方有整套侧栏，用户定为**不做**。
- **`present` 原生打开**：用「下载 → FileProvider → 系统应用打开」替代，够用且更简单。
- **群聊队列胶囊**：官方网页端不显示 queued 项，已删；排队状态由本地乐观气泡承担。
- **工具过程**：按官方网页端隐藏命令与输出，只画社交工具事件行与产物卡。
- **群里没有「暂停某个成员」**：上游也没有；只有群级暂停（中转上限）与该成员的「停下工作」。

## 图标

```powershell
python tools/generate_app_icons.py            # 启动图标（legacy + adaptive + 圆形）
python tools/fetch_lucide_icons.py            # 界面图标（Lucide，版本钉死）
python -m unittest discover -s tools -p "test_*.py"
```

## 许可

本项目基于 dsh-Go（AGPL-3.0）与 DS-Bot（Apache-2.0）的设计与协议实现二次开发，
以 **AGPL-3.0** 发布。来源与第三方许可见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。
