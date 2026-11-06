# 路线图

> 状态：☑️ 完成 · 🔄 进行中 · ⬜ 未开始。每项完成时同步补测试。
> **2026-11-05 按代码实况校订**：早期版本把 M1 之后几乎全写成 ⬜，与代码严重不符。
> 一律以代码为准；发现不一致请直接修文档，不要把已落地的功能写回「未开始」。

## 当前工作重点：修 dsh / dsh bot 设置相关的 bug 🔄

设置与引擎参数是目前的 bug 高发区，排查按这条调用链自上而下：

```
ui/settings/*Screen ──> EngineSettingsViewModel / SettingsViewModel
   ──> DshEngineSettingsRepository ──> DshClient.rpc("settings/describe" | "settings/mutate" | ...)
   ──> PRoot 内的 dsh web ──> EngineSettingsModels（schema / namespace / op 解析）
```

| 关注点 | 文件 |
|---|---|
| 设置 schema 解析、namespace / op / ProviderSettings | `data/remote/EngineSettingsModels.kt` |
| 真实 RPC：describe / mutate、插件、权限预设、凭据、语音 | `data/remote/DshEngineSettingsRepository.kt` |
| 页面状态、变更提交、revision 乐观锁 | `ui/settings/EngineSettingsViewModel.kt` |
| 各设置子页 | `ui/settings/ModelProvidersScreen.kt`、`PluginsScreen.kt`、`EngineSettingsScreen.kt`、`PermissionScreen.kt`、`VoiceSettingsScreen.kt`、`WebSearchScreen.kt` 等 |
| HTTP / cookie / SSE 与错误语义 | `data/remote/DshClient.kt`、`data/remote/DshRemoteApi.kt` |
| 表单与弹层渲染 | `ui/settings/EngineSettingsForm.kt`、`SettingsSheets.kt` |
| bot 侧的业务动作 | `data/remote/DsBotRepository.kt`、`DsBotParser.kt`、`BotCache.kt` |

### 可疑点复核结果（2026-11-05 20:40 更新）

| # | 现象 | 状态 | 修复 / 结论 | 回归测试 |
|---|---|---|---|---|
| 1 | 引擎重启后 cookie 失效不会自动重登，设置请求一直失败到 App 重启 | ✅ 已修 | `DshClient.withAuthRetry`：401/403 清 cookie → 重新 `login()` → 重试一次；业务错误不重试，避免重复写 | `DshClientContractTest` |
| 2 | 引擎未运行时「保存 / 清除 API Key」静默成功 | ✅ 已修 | `saveCredential` / `clearCredential` 先判空并回调「本机引擎未运行」。复核确认 `saveProvider` 走 `applyWrite`，本来就会报错，不受影响 | `EngineSettingsViewModelTest` |
| 3 | SSE 流末尾事件丢失（服务端结尾没补空行） | ✅ 已修 | `feed()` 读流结束时 flush 缓冲区；顺带补断线退避，避免流正常关闭后立刻重连形成忙循环 | `DshClientContractTest` |
| 4 | revision 冲突可能静默失败 | ❌ 排除（误报） | `applyWrite` 里 `fresh == null` 会走 `else` 设置错误，不存在静默成功 | — |
| 5 | 冷启动 `fetch()` 抛错终止整个 flow，主界面不会自愈 | ✅ 已修 | `fetchWithRetry()` 有限重试 3 次（500ms 退避）；耗尽仍把异常抛给 flow，保留界面错误态语义 | `DsBotRepositoryTest` |
| 6 | `BotCache` 不校验引擎 / 清单版本 | 🔵 设计取舍，暂不动 | 需要 Android Context 与版本协商协议，先记为后续项 | — |

**验证通道**：本环境里 Gradle 无法 fork Test Executor（stdin 管道被关闭），
改用 `build/dump-test-cp.gradle` 导出 classpath，再用 `java -cp ... org.junit.runner.JUnitCore` 直接跑；
脚本已把主源码产物目录提到最前并排除 `kotlinx-coroutines-android`。
上述修复后 **19 个测试类 / 97 个用例全绿**，`compileDebugKotlin` / `lintDebug` / `assembleDebug` 均通过。

**仍待确认的结构改动**：`EngineSettingsViewModel.setEndpoint()` 内部直接
`DshEngineSettingsRepository(DshClients.of(it))`，Repository 无法替换成假实现。
本次 #2 靠「引擎未运行」的同步回调路径覆盖了，但 `applyWrite` 的 revision 冲突分支、
凭据写入成功路径仍写不了 JVM 单测。建议加一个可注入的 repository 工厂，再补契约测试；
这属于架构级改动，确认后再动。

约定：修 bug 先补一条能复现的 JVM 测试（`app/src/test/.../data/remote/`、`.../ui/settings/`），再改实现；
涉及 UI 的补插桩测试。不要只改页面文案绕过协议问题。

## M0：工程化跑通（☑️）

- ☑️ 原生 Android 工程：Kotlin + Compose、单 Activity、Gradle 版本目录
- ☑️ 设计令牌（颜色 / 间距 / 圆角 / 字体 / 主题）+ 对比度测试
- ☑️ 主界面：Bot / 群聊列表、搜索、新建面板、空态 / 错误态
- ☑️ Bot 对话占位页与导航
- ☑️ 启动图标：由 `design/app-icon-source.png` 生成 legacy + adaptive + 圆形
- ☑️ 单元测试（时间、颜色算法、状态、令牌、图标资源）
- ☑️ Compose 插桩测试
- ☑️ APK 可构建、可安装

## M1：接入 DSH（🔄 主体已落地）

- ☑️ 主机连接与登录：`GET /?token=` 换 `dsh-auth-*` cookie（`DshClient.login`），引擎状态与错误提示（`EngineViewModel` / `EngineScreen`）
- ☑️ 会话列表 / 会话详情 / Bot 详情（`DsBotRepository`、`BotDetailsScreen`）
- ☑️ 聊天：发送、SSE 流式输出、思考状态、记忆提示（`BotChatViewModel` + `feed` 的 `message` / `stream` / `room`）
- ☑️ 模型选择、引擎参数设置（`ModelProvidersScreen` / `ProviderSettings` / `EngineSettingsForm`）
- ☑️ 本机 PRoot 引擎：下载安装 rootfs、启动 `dsh web`、日志、guest 初始化（`data/runtime/`、`runtime/`）——原计划 M3，提前完成
- ⬜ 停止生成、历史分页（当前一次拉全量）
- ⬜ 审批 / 提问卡片原生渲染
- ⬜ 远程主机（局域网 / SSH / Noise Relay）
- ⬜ 契约测试：MockWebServer 覆盖正常 / 断线 / 超大消息 / 取消

## M2：DS Bot 团队（🔄 部分落地）

- ☑️ `BotRepository` 接 DS Bot `state` + `feed`（`revision` / `live` 增量，`BotCache` 冷启动快照）
- ☑️ 私聊 / 群聊（`chat` / `room-chat`，群聊走 room id）
- ☑️ Bot 详情、Bot 内嵌浏览器、网页模式（`InterfaceMode.WEB` + `WebHostScreen`）
- ☑️ 账号与余额（`DshAccountRepository` / `AccountScreen`）
- 🔄 设置体系：20 余个子页已接真实 RPC，仍在修 bug（见「当前工作重点」）
- ⬜ 新建 Bot / 新建群聊：`DshmBotApp.kt` 的 `onCreateBot` / `onCreateGroup` 仍是空回调
- ⬜ @ 成员 / 未读与通知 / 群详情成员管理 / 公告审批
- ⬜ 与 DS Bot 手机接口版本协商（`hello` / `capabilities`）

## M3：本机引擎与多主机（🔄）

- ☑️ 运行时层：PRoot + Node 运行时、dsh-go-bridge 原生桥接、dsh-media 插件、依赖锁（`runtime/`）
- 🔄 引擎就绪通知（`EngineNotifier`）；后台保活、通知直达会话未做
- ⬜ 主机切换（本机 / SSH / Remote）

## M4：体验与发布（⬜）

- 🔄 深色主题（`ThemeMode`）、动效（`DshmMotion` + `reduceMotion`）、无障碍（`DshmAccessibilityService`）
- ⬜ 诊断日志与崩溃上报（目前只有本地 `EngineLog`）
- ⬜ 正式签名（release keystore）、应用内更新、发布流程
- ⬜ `targetSdk` 升级评估：当前 28 是 PRoot 的取舍，见架构文档

## DSH 完整化（2026-11-05 起，按 dsh-Go 对齐）

范围：DSH 层照 dsh-Go 完整化，Bot 能力层照 DS-Bot 补齐（边界见 [reference-baselines.md](reference-baselines.md)）。

| 批次 | 内容 | 状态 |
|---|---|---|
| 1 | 会话协议核心：`session/*` unary RPC、消息 Part / 日志事件 / follow 帧模型、`$events` waterfall、审批与提问、流式渲染 | 🔄 进行中 |
| 2 | 模型/凭据/设置/插件/权限/语音补全（`appAuth/*`、订阅、`pluginManager` 安装卸载、namespace 全覆盖） | ⬜ |
| 3 | 工作区/文件/终端/Jobs/子代理/技能/目标/定时任务 | ⬜ |
| 4 | 远程主机（SSH / DSH Remote / Noise / Relay）+ 运行时运维（安全模式、重置/卸载、诊断包、会话导出） | ⬜ |

**第 1 批已完成（本轮，纯新增、未动现有页面）**：

- `SessionModels.kt`：对齐 `models.dart` 的 tolerant 解析 —— `DshPart`（text / reasoning / image /
  file / tool-call / Unknown）、`DshAttachment`、`DshStreamChunk`（7 类）、
  `DshFollowFrame`（snapshot / event / assistant-stream / Unknown）、`DshLogEvent`、`DshSessionSummary`。
  **未知类型保留 raw 不丢弃**，这是修「服务端卡片静默消失」的地基。
- `DshSessionRepository.kt`：对齐 `session_api.dart`，封装 list / create / rename / fork /
  archive / unarchive / search / prompt / cancel / updateQueue / modelCatalog / selectModel /
  listProviders / permissionPresets / describeCredentials / projections，复用 `DshClient` 的
  cookie、RPC envelope 与 401 重登。
- 测试：`SessionModelsTest`(9)、`DshSessionRepositoryContractTest`(6)；`FakeDshServer` 改成按字节读
  body（修掉中文请求体让 fake server 阻塞超时的测试工具 bug），并记录 body 供契约断言。
- 验证：21 测试类 / 112 用例全绿，`compileDebugKotlin` / `lintDebug` / `assembleDebug` 全通过。

**第 1 批续（本轮，仍为纯新增）**：

- `DshHostEvent.kt`：ready / emit / waterfall / cancel 帧模型，`DshWaterfallKind` 区分审批与提问。
- `DshSessionRepository` 追加 `$events/result` 回执（pass / result / rejected）、审批应答、提问回答、
  补答提问（`userQuestions/answer`）、权限预设（`commands/execute` 的 `/permission`）。
- `DshChatRow.kt`：把日志事件映射成可渲染行 —— 思考块从助手消息拆出、工具 / 审批 / 提问各自成行、
  未知事件保留 raw；`previewText()` 生成列表预览。纯逻辑、JVM 单测覆盖，Composable 后续只按行渲染。
- Bot 层：`DsBotHello.kt`（hello 能力协商 + notices 补读模型）、`DsBotRepository.hello()/notices()`；
  不写死协议版本号，协商失败时 `supports()` 一律返回 false。
- 测试：`SessionPresentationTest`(6)、`DsBotHelloTest`(4)、`DshSessionApprovalContractTest`(4)。
- 验证：**24 测试类 / 126 用例全绿**，compile / lint / assemble 全通过。

**第 1 批剩余**：`session/follow` 的 WebSocket mux 流（含实际订阅与断线重同步）、
消息行的 Compose 渲染组件、审批 / 提问卡片的交互接线。

## 设置页按 dsh-Go 重做（2026-11-05，☑️ 本轮）

- ☑️ 设置首页补六组分组标题，修正主题行文案误用；**不加搜索入口**
- ☑️ 13 个子页按 `dsh-Go/docs/guide/zh/10-settings.md` 重排结构与行，保留全部已接 DSH RPC 的逻辑
- ☑️ 未接入能力统一标注「当前版本不可用」，杜绝可点却无效的假开关
- ☑️ 新增 `DshGoPreferences` 本地偏好契约 + `DshGoPreferencesTest`，通知四类可真实保存
- ⬜ 老大明确**不做**：设置搜索、远程主机、图像与视频、宠物、性能（`PetsSettingsScreen` /
  `PerformanceSettingsScreen` 保持原样）
- ☑️ 收口：删净宠物/性能/图像视频的空预留键与资源；42 条内联双语文案收进 `strings.xml`，
  重写的 9 个页面不再有 `if (zh) … else …` 字面量
- 验收：25 测试类 / 130 用例全绿，compile / lint / assemble 全通过
- ☑️ 2026-11-06 账号页专项：按 dsh-Go 结构重做主卡 / 三条说明 / 卡内按钮 / API Key 单行入口，
  配色保持本项目黑白灰；登录状态机补取消、阶段、退出前任务检查、默认模型初始化
- 详情见 [design/settings.md](design/settings.md) 的「按 dsh-Go 重做」与「账号页 UI/UX 专项」两节

## 设计系统（2026-11-06，☑️）

- 新增 `docs/design/design-system.md`：颜色（浅/深 HEX + 语义名）、字体阶梯、圆角阶梯、间距与尺寸令牌、
  图标三档、动效、**页面骨架模板**（设置子页 / 表单字段块 / 列表 / 详情 / 对话）、组件清单、
  界面→组件对照表，以及今天真实踩到的四个坑（`TextButton` 40dp、`IconButton` 48dp、输入框内边距、
  `Scaffold.bottomBar` 非空参数）。
- 基准样板是**设置页**，其余页面照它对齐；所有数值都取自 `core/design/` 的真实令牌。
- 配套的验收方式：`adb screencap` 看排版 + `adb uiautomator dump` 用坐标差量间距。

## 已知工程债（与修 bug 并行清）

- 测试覆盖：`data/runtime`、`data/remote`、`ui/settings` 相对 UI 偏薄，补 JVM 测试与契约测试
- 本机 NDK 目录缺 `source.properties`，导致 `.so` 无法 strip、APK 偏大；
  属 SDK 安装问题，用 SDK Manager 重装该版本 NDK 或补齐 `source.properties`，不要在构建脚本里屏蔽告警
- `dshm.lintWarningsAsErrors` 目前为 `false`：跑一次 `:app:lintDebug`，清完存量 warning 后置 `true`
- 文档与令牌数值的漂移（已改为只引用令牌名，不抄数字）
