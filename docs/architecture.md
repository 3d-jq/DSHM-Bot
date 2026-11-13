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
| 远程连接 | 🚫 不做 | 2026-11-13 用户明确：远程控制不做；SSH / DSH Remote / Noise Relay / 主机切换均不排期，只保留本机 PRoot 引擎 |

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
| 状态 | `HomeViewModelTest`（排序、搜索、空态、错误、未读）、`EngineSettingsViewModelInjectionTest`（revision 冲突重试 / 凭据写入 / 刷新）、`BotChatViewModelWiringTest`（提问卡 / feed 消息 / 流式侧通道） |
| HTTP / SSE | `DshClientMockWebServerTest`（正常 cookie / envelope、SSE 分发、断线重连、超大事件、取消） |
| 审批合并 | `ApprovalMergeTest`（双卡片回归、跨 Bot 串号、群聊不抢应答、sessionId 未知时的认领、提问不静默丢弃） |
| 设计令牌 | `DesignTokensTest`（对比度、尺寸关系、宠物挂件边距） |
| 资源 | `AppIconResourcesTest`、`LucideIconResourcesTest`、`tools/test_generate_app_icons.py` |
| UI | `HomeScreenTest`、`SettingsScreenTest`、`ChatComposerTest`、`DshmBotAppNavigationTest`（插桩） |

约定：改动必须配套测试，覆盖正常 / 边界 / 异常 / 回归；`testDebugUnitTest` 与 `lintDebug` 全绿才提交。
`lintDebug` 自 2026-11-13 起 `warningsAsErrors = true`（`dshm.lintWarningsAsErrors`），
**warning 也算 error**；故意保留的告警按文件写在 `app/lint.xml`，每条带理由。
`data/remote` 已有 MockWebServer 契约测试（`DshClientMockWebServerTest`：正常 / 断线 / 超大消息 / 取消）；
`data/runtime` 与 `ui/settings` 覆盖仍偏薄。

`BotChatViewModel` / `EngineSettingsViewModel` 的依赖已通过 `BotChatDependencies` /
`EngineSettingsRepositoryFactory` 注入（`data/remote/ViewModelContracts.kt`），
生产默认值仍是 `DshClients.of(endpoint)`，测试传假实现；接线由
`BotChatViewModelWiringTest` 与 `EngineSettingsViewModelInjectionTest` 覆盖。
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
- **版本跟随**：本项目同时受「自身发版 / 上游 DSH / 上游 DS Bot 插件」三条线牵动，
  任何一条都要我们自己适配（补丁重打 + 插件 merge + 界面契约回归）。
  现状、待办与已知的升级丢数据风险见 `docs/roadmap.md` 的「更新与版本跟随」一节。
  ⚠️ 方向已调整（2026-11-13）：不再以「打补丁适配上游」为主，而是**插件化接管、让 DSH / DS Bot 适配我们**；
  插件模型与可行性见 [dsh-plugin-architecture.md](dsh-plugin-architecture.md)，分阶段见
  `docs/roadmap.md` 的「🎯 大目标：让 DSH / DS Bot 适配我们」。

## 「给用户看的结果」的统一写法（2026-11-13 讨论结论，**未决定做**）

用户提出：网页（浏览器页）和产物卡都是"给用户看的结果"，能不能统一写法？讨论结论记在这里，
属于**取舍备忘**，没有排期，也没有改动代码。

### 现状：5 条独立的路，共用很少

| 类型 | 数据来自 | 渲染 | 交互 |
|---|---|---|---|
| 产物卡 `ARTIFACTS` | `deliverables/presented` / 本轮新建文件 / 回复里链接 / 输出目录（由会话事件推导） | `ui/chat/ArtifactCard.kt` 文件行 | `ui/chat/ChatArtifacts.kt`：下载到 `cacheDir/artifacts` → `FileProvider` → `ACTION_VIEW` 外部应用；分享 `ACTION_SEND`；另存 `MediaStore`；复制路径 |
| 视图卡 `VIEW`（含 `html` 节点） | `show_view`（`state.views`） | 蓝图递归（text / metric / table / chart / html…）+ `ChatFormula` | 选 / 填 / 确认 / 提交（回执给引擎） |
| 浏览器页 | 真网页 / `dshm://` 本地页 | `BotBrowserScreen` + `RootfsWeb`（`shouldInterceptRequest` 直接返文件流） | 真导航、下载、截图、`read_browser`（页面→markdown）、点击/输入/滚动、交接用户 |
| 浏览器缩略图卡 `BROWSER_SHOT` | 一轮结束抓图（`BrowserShotStore`） | `ui/chat/BrowserShotCard.kt` | 点开进这个 Bot 的浏览器 |
| 附件图片 | 本地附件（`ChatAttachmentCache`） | 气泡缩略图 | `ui/chat/FullscreenImageViewer.kt`（自己一套复制/分享） |

重复点：**5 处 WebView 用法**（`BotBrowserStore` / `BotBrowserScreen` / `BrowserReadBridge` /
`ChatHtmlCard` / `ui/settings/WebHostScreen`）、**2 套「打开文件」**
（`ChatArtifacts` 与 `FullscreenImageViewer`）、**3 种卡片**。

### 值得统一的（横切面）

1. **「打开 / 分享 / 另存一个资源」的动作层**：抽成一个分发器（open / share / saveToDownloads /
   copyPath / 图片全屏），产物卡、附件图、缩略图卡、视图卡里的文件都走它。
   收益：以后加 PDF / Office / 日志 / diff 预览只改一处。
2. **本地资源寻址**：项目**已有** `dshm://`（`RootfsWeb`：`workspace|attachments|dsh/...`）。
   把产物、附件也纳入同一 scheme，WebView 就能直接渲染 md / html / 图片 / 文本 ——
   「看文件」和「看网页」在实现上变成同一件事：给 WebView 一个 URL，不必先落到 `cacheDir`
   再交给外部应用。
3. **卡片外壳**（圆角 / 内边距 / 标题行 / 展开折叠 / 错误行文案）：可共享，落成
   `ui/components` 里的共享小件（先例：`SettingsAddTile`、`InitialMark`、`rememberWindowHeightDp()`）。

### 不该统一的

- **协议 / 模型层**：产物卡来自 `deliverables/presented`、视图卡来自 `show_view`、
  浏览器来自真导航 + `read_browser` —— 上游 DS Bot / DSH 本身就是三套协议，我们是对齐方。
  合成一个「ResultItem」会把「可交互表单」和「文件清单」塞进同一个抽象：产物卡的动词是
  **打开 / 分享 / 另存**，视图卡是**选 / 填 / 确认 / 提交**，浏览器是**导航 / 滚动 / 输入 / 接管**。
  磨平三种语义会把与基线的一致性一起磨掉。
- **沙箱 html 卡片 ≠ 真浏览器**：`ChatHtmlCard` 是 `loadDataWithBaseURL(null, …)` + CSP + 禁网 /
  禁文件 / 禁跳转；浏览器是真会话（还带 cookie，而 WebView 的 cookie 是进程级共享，见
  `docs/roadmap.md` 浏览器一节）。安全等级不同，不能共用同一个 WebView 实例池。

### 代价与边界

- WebView 能渲染 md / html / 图片 / 文本，**不能渲染 PDF / Office** → 统一层必须保留
  「交给外部应用」这个出口，`ChatArtifacts` 现在那条 `FileProvider` 路不能删；
- 把产物塞进 `dshm://` 需要**扩 `RootfsWeb` 的目录白名单**（现在只暴露 `.dsh` 下），
  安全边界要重审，别把整个 rootfs 暴露出去；
- 统一层要落成 `ui/components` 的共享组件，不能做成"万能渲染器"（设计令牌 + 骨架模板是硬规则）。

### 结论

**统一「资源 → 查看方式」这条横切（寻址 + 动作分发 + 卡片外壳）；不统一卡片与协议模型。**
收益是「将来加一种能看的东西只改一处」；现在做是纯重构、用户无感，属于工程债级别，
适合跟「要做 PDF 预览 / 统一长按菜单」这类需求一起做。
