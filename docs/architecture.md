# DSHM Bot 架构

> 更新：2026-11-05（按代码实况校订）。早期版本停在 M0「演示数据」阶段，现已接入本机引擎与真实 DS Bot。
> 后续接入 / 修 bug 时在此文档追加，不另起炉灶。文档以代码为准。

## 定位

- **底层是 DSH**：Agent 的会话、模型、工具、消息都由 DSH 引擎负责；App 是原生客户端，不自己跑模型循环。
- **界面与定位是 Bot**：主界面是 Bot / 群聊列表，进而是与某个 Bot 的私聊、群聊、设置与卡片。
  产品语义参考 DS-Bot（长驻 Bot、群聊、记忆、审批），交互参考 dsh-Go 的移动端设计。
- **原生 Android**：Kotlin + Compose 重写界面；不复用 Flutter 界面，只复用 dsh-Go 的服务端协议、
  引擎运行时与产品设计文档。
- **本机引擎**：PRoot 内跑 Node 版 `dsh web`，App 通过 `127.0.0.1` 的 HTTP/SSE 与它通信，
  不依赖外部主机。`runtime/` 存放运行时补丁、原生桥接与依赖锁。

两条参考基线不要混用，见 [reference-baselines.md](reference-baselines.md)：
做 DS Bot 界面看 `DS-Bot/` / `DS-Bot-mobile-api/`，做 DSH 引擎与协议看 `dsh-Go/`。

## 分层

```
ui/           只渲染 + 收集状态；不调接口、不做业务判断
state/        ViewModel：持有状态、响应事件、把领域数据格式化为 UiState
domain/       模型、颜色/形状算法、Repository 与设置接口（业务规则）
data/         Repository 实现：runtime/（本机引擎）、remote/（HTTP/SSE/RPC）、settings/（持久化）
core/design/  设计令牌：颜色、间距、圆角、字体、动效、主题
```

规则：

1. **单一数据源**：界面只消费 `StateFlow<UiState>`；UI 事件回调到 ViewModel。
2. **令牌强制**：界面代码不允许出现颜色 / 间距 / 圆角 / 字号字面量，统一 `Dshm.colors/spacing/radius/typography`。
   有 `DesignTokensTest` 守护可读性与尺寸一致性；文档只引用令牌名，不重复抄数值。
3. **可测**：时间、时钟、时区、语言全部注入；Repository 是接口，测试用假实现。
   `DshRemoteApi` 把 `DshClient` 的具体 HTTP 实现挡在 ViewModel 之外。
4. **资源释放**：协程绑定 `viewModelScope`；`feed` 的 `Job` 在页面退出时取消，流式连接断开显式取消。
5. **密钥不外显**：`ApiKeyStore` 加密存储；`TextMasking` 在日志展示前打码 token / API Key。

## 已落地的 DSH / DS Bot 接入

| 能力 | 实现 | 说明 |
|---|---|---|
| HTTP API / cookie | `data/remote/DshClient.kt` | `GET /?token=` 换 `dsh-auth-*` cookie；`POST /api/bot` 与 `rpc()` 发业务请求 |
| 会话流 | `DshClient.feed()` | `GET /api/bot/feed` 的 SSE，事件 `hello` / `revision` / `live` / `message` / `stream` / `room` |
| Bot 团队 | `DsBotRepository` + `DsBotParser` + `BotCache` | `state` 快照 + `feed` 增量触发重取；冷启动先发本地缓存，界面不空转 |
| 引擎设置 | `DshEngineSettingsRepository` + `EngineSettingsModels.kt` | `settings/describe` 拿 schema/value/base/user/secrets，`settings/mutate` 带 `expectedRevision` 提交；另有插件、权限预设、凭据、语音 |
| 审批 / 提问 | `DshMuxHub` + `ApprovalMerge.kt` | 显示身份只认 DS Bot 的 `state.work.approvals`（`agentId` 已由插件关联成 `botId`/`roomId`）；`$events` 的 `approval/request` 只补应答要的 `eventId`。群聊走 `approval-answer`，私聊走 `$events/result`，两条路不混用 |
| 本机引擎 | `data/runtime/` + `runtime/` | manifest 校验 → 下载/解压 rootfs → `ProotCommand` 拼 argv → `dsh web` 输出解析出 `endpoint + token` |
| 远程连接 | ⬜ 未做 | dsh-Go `packages/dsh_remote`（Noise IK + Relay）后续按需移植 |

DS Bot 的颜色算法（`BotInks`）与默认形状（`BotShapeDefaults`）已按 DS-Bot / dsh-Go 移植，
并有参考向量测试，保证同一个 Bot 在所有客户端颜色一致。

## 数据流

```
PRoot 内的 dsh web ──HTTP/SSE──> DshClient ──> DsBotRepository ──Flow──> HomeViewModel ──StateFlow──> HomeScreen
                          │                                                    ▲
                          └──> DshEngineSettingsRepository ──> EngineSettingsViewModel ──> 设置子页
                                                                                          │
                                                            用户操作（发送 / 改设置 / 新建）┘
```

引擎 `endpoint` 由 `DshmBotApp` 统一注入 `HomeViewModel` / `EngineSettingsViewModel` / `AccountViewModel`；
引擎未启动时 repository 落到 `EmptyBotRepository`，界面显示引擎关闭态而不是假数据。

## 主界面

对应设计稿：顶部个人资料 / 搜索 / 新建；列表行 = 头像（DS Bot 颜色 + 形状 + 自定义图）、名字、时间、最后一句。
时间规则：今天 `21:53`、昨天 `昨天`、一周内 `周一`、今年 `10/21`、更早 `2025/10/21`；
规则集中在 `RelativeTimeFormatter`，有边界测试。详见 [design/main-screen.md](design/main-screen.md)。

## 测试策略

| 层 | 测试 |
|---|---|
| 领域算法 | `BotInkTest`（参考向量）、`BotShapeDefaultsTest`、`RelativeTimeFormatterTest`（时区/边界/未来时间）、`ThemeModeTest` |
| 设置协议 | `EngineSettingsModelsTest`、`SpeechCatalogTest`、`MarkdownParserTest`、`ComposerRulesTest` |
| 运行时 | `ProotCommandTest`、`RuntimeManifestTest`、`TextMaskingTest`、`DsBotParserTest` |
| 状态 | `HomeViewModelTest`（排序、搜索、空态、错误、未读） |
| 审批合并 | `ApprovalMergeTest`（双卡片回归、跨 Bot 串号、群聊不抢应答、sessionId 未知时的认领、提问不静默丢弃） |
| 设计令牌 | `DesignTokensTest`（对比度、尺寸关系、宠物挂件边距） |
| 资源 | `AppIconResourcesTest`、`LucideIconResourcesTest`、`tools/test_generate_app_icons.py` |
| UI | `HomeScreenTest`、`SettingsScreenTest`、`ChatComposerTest`、`DshmBotAppNavigationTest`（插桩） |

约定：改动必须配套测试，覆盖正常 / 边界 / 异常 / 回归；`testDebugUnitTest` 与 `lintDebug` 全绿才提交。
缺口：MockWebServer 契约测试（正常 / 断线 / 超大消息 / 取消）尚未建立，`data/runtime` 与 `ui/settings` 覆盖仍偏薄。

`BotChatViewModel` / `EngineSettingsViewModel` 自己在构造里 `DshClients.of(endpoint)` 造依赖，
Repository 换不掉：纯规则已抽成 `ApprovalMerge` 等函数覆盖 JVM 单测，接线部分仍需插桩测。
用 `build/dump-test-cp.gradle` 导 classpath 手跑单测时要先 `cd app`，
否则 `AppIconResourcesTest` / `LucideIconResourcesTest` 会因相对路径假报缺资源。

## 构建与签名

- 当前只有 debug 构建；正式发布前必须配独立 release keystore（`key.properties`，不入库），
  并把 `versionCode` / `versionName` 纳入构建流水线。
- `versionCode` 取构建时间，时区由 `gradle.properties` 的 `dshm.versionCodeZoneOffset` 控制（默认 `+08:00`）；
  目的是让每次打包都被系统当成新版本，刷新启动器图标缓存。
- `minSdk 26` / `compileSdk 36` / **`targetSdk 28`**：28 是为绕开 Android 10+ 对 PRoot 执行原生二进制
  （W^X、exec 限制）而有意保留，并在 lint 中显式豁免 `ExpiredTargetSdkVersion`。
  **风险**：Play 新应用要求 targetSdk 34+，且长期停留低 target 会累积兼容性与权限模型差异。
  提升 targetSdk 前必须重新验证本机引擎能否启动，不要只改数字。
- 本机 NDK 若缺 `source.properties`，`stripDebugDebugSymbols` 会跳过 `libproot.so` 等 native 库，
  APK 偏大；这是本机 SDK 安装问题，补齐 NDK 即可，不要在构建脚本里屏蔽告警。
- **引擎常驻前台服务**（`service/RuntimeForegroundService`）：引擎是 PRoot + Node 子进程，
  Android 12+ 会把它们登记成 `PhantomProcessRecord`；父 App 落进 cached 后，系统裁剪幽灵进程时
  引擎可能被**单独杀掉**（表现为切出去回来又得冷启动十几秒）。
  挂上前台服务后 App importance 停在 `FOREGROUND_SERVICE`（实测 `adj=200`，子进程不再进幽灵列表），
  引擎就稳了。对齐 dsh-Go 的 `runtime.DshService`：通知渠道 `dshm-runtime`、标题 = App 名、
  正文 = 动态状态、点通知回 App、「停止引擎」动作走 `RuntimeLauncher.stopAll()`（按 `/proc` 反查，
  不依赖内存里的 Process 句柄）。
  代价是一条常驻通知（IMPORTANCE_LOW + 静音）；targetSdk 28 让它不需要运行时通知授权。
