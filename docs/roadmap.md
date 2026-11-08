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
- ☑️ 审批卡片原生渲染 + 应答接线（见「审批显示专项」）
- ⬜ 提问卡片：已显示问题本身，但还不能作答（选项 UI + `userQuestions/*`）
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

**第 1 批剩余**：消息行的 Compose 渲染组件、提问卡片的作答接线
（`session/follow` 的 mux 流与审批卡片的应答接线已在「审批显示专项」完成）。

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

## 审批显示专项（2026-11-07，☑️）

现象：私聊里同一次审批会冒出两张卡（一张能点、一张写着「去 DSH 里答」的死路），
而别的 Bot 的审批会串进当前对话 —— 点「允许一次」放行的是错误 Bot 的命令。

根因（读 `DS-Bot/ds-bot/src/host/approvals.js` 定位，不是界面问题）：

- DS Bot 已经在 `approval/request` 里把 DSH 的 `agentId` 关联成 `botId` / `roomId`，
  并写进 `state.work.approvals`；官方客户端**只渲染这一份**（`client/member-status.js`）。
- 私聊：DS Bot 直接 `next()` 交给 DSH 自己应答，条目的 `answerable` 恒为 `false`
  —— 移动端没有 DSH 卡片，App 就是那张卡片，应答必须回 `$events/result`。
- 群聊：成员会话是隐藏的，DS Bot 自己 hold 住，应答只能走 `approval-answer`；
  这时若再回 `$events/result` 就是两个入口抢同一次请求。
- 原实现把两个来源各画一张卡，且 `mergeApprovals()` 末尾的过滤是恒真式（去重没生效）；
  `belongsToThisChat()` 一度被改成恒 `true`（且没有调用点）。

落地：

- `ui/chat/ApprovalMerge.kt`：把合并规则抽成纯函数 `mergeChatApprovals()` ——
  显示身份只认 DS Bot 的条目，事件流只补应答要的 `eventId`；
  按 `sessionId` 认领（DS Bot 条目给的 sessionId == 事件流的 agentId），
  `load()` 还没带回 sessionId 时靠条目自带的 sessionId 认领，不会一直灰着。
- `ChatApproval`：`answerable: Boolean` → `action`（`ANSWER` / `WAIT_ENGINE` /
  `ANSWER_ELSEWHERE`）+ `kind`（审批 / 提问）+ `eventId`，界面不再自己猜布尔组合。
- `ApprovalCard`：`WAIT_ENGINE` 时按钮置灰并在卡下说明卡在哪（`approvalWaitReason`），
  取代顶栏胶囊下面那个只在瞬态说话的悬浮提示；连接状态直接从 `engineLink` 推导。
- `BotChatViewModel`：mux 的 state / hostEvents 收进同一个 launch；
  事件只写缓存（`Ready` 清空、`cancel` 删除、上限 32 防漏 cancel），卡片一律重算；
  应答按 `source` 严格分流，群聊绝不发 `$events/result`。
- 多条请求改在输入框上方竖排（每张卡各自 `align(BottomCenter)` 会完全重叠）。
- `DshMuxTest` 补上 `DshMuxLog.sink` 的换入换出：mux 加了 logcat 埋点后，
  JVM 里 `android.util.Log` 未 mock 会让 8 个用例挂掉。

验证：**45 测试类 / 283 用例全绿**（新增 `ApprovalMergeTest` 13 条，覆盖双卡片回归、
跨 Bot 串号、群聊不抢应答、sessionId 未知时的认领、提问不静默丢弃）；
`compileDebugKotlin` / `lintDebug` / `assembleDebug` 均通过。

**必须真机验证的一条**：群聊里 App 若同时收到 DS Bot 条目和事件流请求，
现在只走 `approval-answer`；`$events` 的投递与 cancel 时序在真机上是否如
dsh-Go 注释所述（pending 会带同样 eventId 重新投递、答完发 cancel），
需要连引擎跑一轮确认。

## 对话顶栏胶囊状态后续（2026-11-07，⬜ 暂不处理）

范围**只看 DS Bot**，不把 DeepSearch 的名字胶囊状态行口径混进来：

- DS Bot 的名字胶囊内只有短暂「记忆已更新」提示；思考短语、工具文案、等待回答和计时属于独立的
  `ActivityIndicator`（对话流里的活动行），不是胶囊里的常驻状态行。边界见
  [reference-baselines.md](reference-baselines.md)。
- 当前简化实现：`BotChatViewModel.refreshBotStatus()` 只看 `memoryNews[botId].at` 是否在近 5 分钟内，
  每次状态刷新都可能重放同一条旧 news；群聊没看成员；提示期间点击胶囊仍打开快捷设置。
  `BotTitleCapsule` 也只有文字，没有记忆图标与展开/收回动画。
- 待补：按 bot / 群聊成员取最新 `seen`，只在 `at` 变化时触发一次且页面刚打开不重放历史；
  3.2s 倒计时、记忆图标与动画、点击打开对应记忆、`roster.memory === false` 时不显示；
  再核对胶囊小头像的 `idle / thinking / searching / working / sending / waiting / alert` 状态。
- 若以后决定把「思考 / 工具 / 等待审批」常驻放进胶囊，那是**本项目自定义设计**，需单独确认，
  不能写成「对齐 DS Bot」。

## 对话底部 Bot 面板（2026-11-07，🔄 本轮修正）

- 点击名字胶囊弹出的 `BotSettingsSheet` 顶部按 DS Bot 改成「详情 / 浏览器」同一组分类栏，
  不再是两颗并列跳转按钮。浏览器当前版本未做，点它只给「当前版本未做」提示，不跳到假的 DSH Web。
- 「详情」分类下保留真实入口：点「设置」行关闭面板并进入 `BotDetailsScreen` 完整设置页。
- 模型选择改为乐观回显：点中即显示新模型，后台仍走 `update-bot` 校验并 `load()`；失败回滚并显示错误。
  名称 / 角色 / 形象 / 工作文件夹 / 说明也同步乐观回显。
- 权限改走 DS Bot 的 `update-bot {permission}`，不再用旧的 `commands/execute /permission`；
  档位和文案从 `state.permissions.options` 读取，缺省回退到本地三档。
- 面板接线核对：模型、权限、工作目录、说明、记忆查看/删除、用量、定时任务查看/新建/删除都走真实
  `/api/bot`。**仍未接**：浏览器标签页、记忆新增/编辑（`memory-add` / `memory-edit`）、
  文件夹选择器（`folder-list` / `folder-pick`）和运行统计（`run-stats`）。
- 以后做浏览器要按 DS Bot `DetailsDrawer` / `BotPanel` 的「Bot 专属浏览器标签页」口径，
  不是把当前 `WebHostScreen` 的 general DSH Web 当浏览器入口。

### 底部面板待做清单（⬜ 暂不处理）

- ⬜ **浏览器标签页**：做 Bot 专属标签页状态（打开/关闭/切换、当前页、恢复上次标签），按 DS Bot
  `DetailsDrawer` 的 `browser` tab 与 `browsers.tabs` 口径；不要复用现在整页 DSH Web 的 `onOpenBrowser`。
- ⬜ **记忆新增 / 编辑**：接 `memory-add` / `memory-edit`，补新增、修改、scope/topic 选择和失败提示；
  现在只有 `memory-view` 查看与 `memory-forget` 删除。
- ⬜ **文件夹选择器**：接 `folder-list` / `folder-pick`，用目录列表选工作文件夹；
  现在是手填 `workDir` 文本，走 `update-bot` 时由 DS Bot 校验。
- ⬜ **运行统计**：接 `run-stats`，在面板顶部或详情卡显示本轮耗时/模型用量摘要；
  现在只有 `usage-summary` 的当天总量。

### Bot 完整设置页接线审计（2026-11-07）

> 单 Bot 完整设置页与群聊设置页已分流；此节只记录单 Bot 页面的接线审计。

- 顶栏「设置」下面的 Bot 名字副标题已去掉，标题栏只保留「设置」。
- **真实可用**：名称 / 角色 / 模型 / 工作目录 / 说明 / 形象走 `update-bot`；隐私 Bot 走 `set-flags`；
  设为主 Bot 走 `set-main`；复制走 `duplicate-bot`；删除走 `delete-bot`；用量走 `usage-summary`；
  记忆查看/删除走 `memory-view` / `memory-forget`；定时任务列表/新建/删除走
  `schedules` / `schedule-create` / `schedule-delete`。模型等字段现在点选即乐观回显。
- **半成品**：工作目录是手填文本，没有 `folder-list` / `folder-pick` 选择器；记忆只能查看和删除，
  不能 `memory-add` / `memory-edit`；用量只有当天 token 摘要，没有 `run-stats`。
- **失败反馈缺失**：`setHidden` / `setMain` / `duplicate` / 定时任务 / 记忆删除的 `onDone(error)` 在详情页大多没接，
  失败只写进 ViewModel 或不提示；后续统一补 Snackbar/错误行。
- **当前完整页缺项**：权限设置（底部面板已有）、浏览器标签页、运行统计、记忆新增/编辑、文件夹选择器。
  这些都不是可点按钮，不能靠假跳转补齐。
- **群聊已分流**：不再把 `roomId` 当 `botId` 写；群聊详情走独立 `RoomSettingsScreen`，
  底部面板走独立 `RoomSettingsSheet`，单 Bot 继续走 `BotDetailsScreen` / `BotSettingsSheet`。

## 群聊专项（2026-11-07，🔄 本轮启动）

- ☑️ 新建群聊：从主页「＋ → 新建群聊」打开，选管理员 + 至少一位成员，支持自定义群名；
  走 `create-room {members, admin, name?, greet:true}`。创建成功后打开群设置页。
- ☑️ 群聊详情分流：`room-details:<roomId>` 走 `RoomSettingsScreen`，不再误用单 Bot 的
  `BotDetailsViewModel`；群聊名字胶囊弹 `RoomSettingsSheet`，按 `GroupOverview` 显示群头像、
  公告、管理员、回答方式、权限、成员头像/点击、群记忆与群用量。
- ☑️ 群设置真实接线：`state.rooms[]` 读取；`update-room` 改名称 / 公告 / 回答方式 / 管理员 /
  成员增删 / 权限 / 工作目录；`set-flags` 改置顶 / 隐藏；`delete-room` 删除；
  `memory-view` / `memory-forget` 按 `roomId` 查看和删除群记忆。
- ☑️ 群对话既有链路：`send` 用群 id 当 `sessionId`，历史走 `room-chat`，实时用 `room` feed 刷新，
  审批按 `roomId` 认领；本轮未改这些路径，继续用 `state.work.rooms` 判断运行状态。
- ☑️ 群头像：`RoomAvatar` 按 DS Bot 把成员头像堆叠到主页 / 对话顶栏 / 群面板 / 群设置页，
  超过四个显示 `+N`。
- ☑️ 成员卡片：群面板与完整设置页都带头像、角色/管理员标记；点击打开对应单 Bot 对话，
  设置页还可设管理员 / 移出成员。
- ☑️ 群用量：读 `usage` 的 `owners` / `rows`，按 `room:<roomId>` 汇总 token 与缓存命中率；
  群面板和设置页都显示。
- ☑️ 新建 Bot：主页「＋ → 新建 Bot」进入整屏 `NewBotScreen`，接真实 `create-bot`，
  可选形象 / 颜色 / 模型 / 角色 / 开场消息，校验重名与非法名字；创建成功后打开 Bot 设置页。
- ☑️ 模型选择：新建 Bot、Bot 底部面板、Bot 完整设置页统一用 `BotModelComboBox` 下拉选择，
  不支持搜索输入；选中立即走 `update-bot` 或写进 `create-bot`。
- ⬜ 群聊后续：`folder-list` / `folder-pick` 群目录选择器、`memory-add` / `memory-edit` 群记忆新增编辑、
  `room-continue` / 群接力进度、浏览器标签页。

## 已知工程债（与修 bug 并行清）

- 测试覆盖：`data/runtime`、`data/remote`、`ui/settings` 相对 UI 偏薄，补 JVM 测试与契约测试
- 本机 NDK 目录缺 `source.properties`，导致 `.so` 无法 strip、APK 偏大；
  属 SDK 安装问题，用 SDK Manager 重装该版本 NDK 或补齐 `source.properties`，不要在构建脚本里屏蔽告警
- `dshm.lintWarningsAsErrors` 目前为 `false`：跑一次 `:app:lintDebug`，清完存量 warning 后置 `true`
- 文档与令牌数值的漂移（已改为只引用令牌名，不抄数字）
- `BotChatViewModel` 仍然自己 `RemoteDshApi(DshClients.of(endpoint))`，依赖不能注入：
  审批的合并规则已经抽成纯函数 `ApprovalMerge` 覆盖到了 JVM 单测，
  但「事件流 → 缓存 → 卡片」的接线部分还是要靠插桩测；和 `EngineSettingsViewModel`
  是同一个问题，一起做可注入工厂。
- 提问卡片已接选项与 `userQuestions/answer`；`ViewCard` / `BtwCard` 仍未接。
- 用 `java -cp` 手跑单测时**必须先 `cd app`**：`AppIconResourcesTest` /
  `LucideIconResourcesTest` 按相对路径找 `src/main/res`，在仓库根目录跑会假报缺资源。

### 对话消息层（2026-11-08，本轮）

- ☑️ 底部「＋」菜单：添加文件 / 添加图片 / 拍照；走 DSH `POST /api/session/uploadFileBinary`
  取 `receiptId`，再 `session/prompt` 带 file block 发送；附件先在输入框上方暂存。
- ☑️ Bot 反问选项：`ApprovalCard` 接 `userQuestions/answer`，单选取值即回执，多选可勾选提交。
- ☑️ 消息事件行：`done/stopped/working/approval/failed/missed/lookup/resumed/cut/schedule`
  以及单聊的 `request/reply/report/post/resume/retry/secret` 映射成 DS Bot 风格事件文案。
- ☑️ 长文折叠：气泡内底部「显示全部 · N 字 / 收起」；长按气泡复制文本。
- ☑️ 群聊 @：输入 `@` 触发成员面板，支持「所有人」；不再用独立 @ 按钮。
- ⬜ **时间/耗时显示**：本轮按用户要求先隐藏（私聊、群聊、事件行都不显示时间）；
  后续要按 DS Bot 的 `bt-group-time` / `bt-utime` / `bt-time-sep` 补回。
- ⬜ **工具调用事件（Messaged / Created group / Posted in）**：数据来自 `session/follow`
  的 `tool/call`，当前只接了部分事件；要补需要打开并按会话合并 `session/follow` 流。

### 交付文件链路（2026-11-08，本轮）

「让 Bot 做 PPT / 做文件」这条链路：DSH 的 `present` 工具在对话记录里追加一条持久事件
`deliverables/presented`（`{turn, callId, files:[{path, description?}]}`），官方网页前端
（`@deepseek-ai/dsh-client-ui-deliverables`）据此在一轮结束处画产物卡。我们之前既没有这张卡，
也没有把文件取出手机的路径。

- ☑️ `Deliverables.kt`：解析 `deliverables/presented`，校验口径照 dsh-Go
  `packages/dsh_client/lib/src/deliverables.dart`（`turn` 必须是 >= 1 的整数、`callId` 非空、
  `files` 是数组；坏条目跳过但不影响其他条目）；单测 `DeliverablesTest`。
- ☑️ 产物卡 `ArtifactCard.kt`：`▣ 交付了 N 个文件` + 每行类型图标 / 文件名 / 说明，
  收起时最多 4 个、多出来的走「全部 N 个文件 / 收起」。
- ☑️ 取文件：`WorkspaceFilesApi`（`workspaceFiles/stat` → 绝对路径与大小）+
  `DshClient.readFileBytes`（`GET /api/file?path=<绝对路径>`，路由由
  `@deepseek-ai/dsh-api-session-controller` 提供，路径必须绝对、超限回 413）。
  字节落到 `cacheDir/artifacts/`，FileProvider 已加该路径。
- ☑️ 一行上的动作：打开 / 用其他应用打开 / 分享 / 保存到「下载」（API 29+ 走 MediaStore）/ 复制路径。
  取不到时在说明行里原地显示原因（不在工作目录、超过上限、没应用能打开…）。
- ☑️ 单聊订阅 `session/follow` 时不只收工具事件，也收交付声明；快照窗口从 50 提到 200，
  否则几轮之前的产物卡查不到（DS Bot 的 `chat` 历史不带交付声明）。
- ⬜ **群聊产物**：成员 Bot 的 `present` 落在成员自己的会话里，群会话只有 `group_relay`；
  DS Bot 网页版的群消息也不画产物卡，本轮不做。
- ⬜ **没有 `present` 的轮次**：官方还有「这一轮生成的文件」兜底（按文件变更事件推），未做。
- ⬜ **`show_view` 视图卡**：DS Bot `chat` 会返回 `kind: "view"` 条目（`show_view` 工具），
  我们仍然丢掉；要做需要画视图蓝图（text/metric/table/chart/progress/callout/sources/html）。

### 运行状态与事件行（2026-11-08，第二轮）

- ☑️ 去掉对话里的「新消息」分隔（用户要求；`chat_new_messages` 字符串一并删掉）。
- ☑️ **一轮结束的判定**：改用 DSH 的会话状态。mux 的 `api-session/status [sessionId, running]`
  是权威信号（DS Bot 网页端的活动行也读它：`useSessionStatus(map.get(sessionId)?.running)`），
  另有 `api-session/added`（会话摘要带 `running`）兜底。之前只看 DS Bot `state.work.bots`，
  它没清干净时停止键和活动行会一直挂在界面上。
  第三重兜底：`session/follow` 里最后一条回合边界是 `turn/end` 时按「已结束」处理
  （重启/断线漏掉状态事件时用得上）。
- ☑️ 事件行认人：relay 事件（`role = request / reply / report / stopped`）里的 `botId` 是**对方**
  的会话 id，之前一律套本对话 Bot 的头像和名字（「回复来自 大总管」这类错法）。
  现在先用 team lookup（`DsBotParser.teamMembers(state)`，Bot id 就是会话 id）认人，
  认不出来就只显示文字；`senderName` 为 `You`（DS Bot 认不出对方时的占位）也不当名字用。
- ☑️ 补齐 DS Bot 会命名的团队级工具事件：`list_bots` / `read_own_chat` / `recall` / `list_secrets`，
  `message_bot` 文案对齐 DS Bot `toolActivity`（`Asking {name}` → 「询问 X」）。
- ☑️ **根因（2026-11-08 晚）**：`session/follow` 的网关参数只认 `{request:{address,maxMessages}}`，
  我们之前平铺 `{address,maxMessages}`，整条流被 `gateway/arguments-invalid` 打回 ——
  所以工具事件和产物卡**一次都没收到过**（`onEnd` 只把 streamId 清空，静默失败）。
  现在按 dsh-Go `SessionEventStream.follow` 的包法传 `request`，并把流结束原因写进 `DshMuxLog`。
- ☑️ follow 的快照覆盖整个会话：只把「已加载出来的这段历史」内的事件并进列表
  （按最早一条消息的 seq 截断），翻更早的页后再并一次。否则列表顶部会堆一堆更老的事件行。
- ☑️ relay 事件行可点：点开对方 Bot 的对话（DS Bot 的 `EventLine` 用 `openExchange` 打开双方来往）。

### 与 DS Bot 的差距清单（2026-11-08 全面对比）

对照 `app/src/main/assets/ds-bot/client.js` 的插槽注册（`shadow(...)`）与各组件。
状态：☑️ 已对齐 / 🟡 部分 / ⬜ 未做。

**对话消息流**
- ☑️ 「Bot 间对话」（2026-11-08 第四轮）：点事件行（消息发给了 X / 回复来自 X / 叫停了 X）
  打开 `exchange {a,b}` 的记录 —— 主机记下的每一次 Bot→Bot 投递，配 `exchange-text` 取全文，
  打开时每 2 秒刷新。壳子按本 App 习惯做**底部面板**（屏幕 80%），内容照 DS Bot
  `ExchangeDialog`（`[头像] A ⇄ [头像] B` 胶囊 + 30 分钟日期分隔 + Markdown 气泡）。
- ☑️ 工具事件行（2026-11-08 第三轮）：`message_bot / stop_bot / create_bot / update_bot`
  带目标 Bot 的头像、可点开对方对话（DS Bot `renderCall` 的 Messaged/Stopped/Created/Updated）；
  `read_group_chat` 带群聊成员头像堆叠；`remember / forget` 用 DS Bot 的 MemoryIcon，
  文案分「团队 / 自己 / 另一个 Bot」（`ToolEventLine.kt` + `ToolEventLineTest`）。
- ☑️ 头像颜色：`state.bots[].color` 为空（没手选过颜色）时按 **Bot id 取默认色**
  （DS Bot `colorOf`：`isColor(color) ? color : defaultColor(id)`）。
  之前直接回落灰色，所以小助手在群头像堆叠 / 成员选择器里是灰的。
  修在 `DsBotParser.inkOf` 与 `parseRoomSettings`，单测覆盖。
- ⬜ 工具卡 `ToolCell`：`ask_user`（提问卡）、`request_secret`（密钥卡）、`show_view`（视图卡）
  是可交互卡片；`read_group_chat` / `remember` / `forget` 有专属样式。
- ⬜ `group_relay` 进行中的「正在发言的成员 + 当前工具」行（`GroupReplies` 的 pending）。
- ⬜ 旁路任务卡 `BtwCardView` + `BtwDock`（`state.btwCards`）。
- ⬜ 待办卡悬浮在输入框上方：`QuestionDock` / `WidgetDock` / `SecretDock`（现在塞在消息流里）。
- ⬜ 已回答提问 `AnsweredReply` / `SettledQuestion`。
- ⬜ 时间分隔 `TurnTailCell`（两轮间隔 ≥30 分钟）与定时任务触发卡 `TriggerCell`。
- 🟡 「新消息」分隔：DS Bot 用 `TurnProcessCell` 的 visit 语义；我们按用户要求先删掉，未换新语义。
- 🟡 活动行时机：DS Bot `rowVisible` 要求「没有文字流式输出」才显示；我们现在流式时也显示。
- 🟡 群聊 `paused` + 「继续」（`room-continue`）未接。
- 🟡 长文折叠：DS Bot 单聊也折叠（`FOLD_HUGE`），按用户要求目前只在群聊折叠。

**输入区**
- ⬜ 成员 dock（群聊成员在输入框上方，`MemberDock`）。
- ⬜ 模型选择 dock（输入框上方，`ModelChoiceDock`）；目前只在详情/新建页选。
- ⬜ 输入框上方的 usage dock / btw dock（DS Bot 里 activity/usage 两个 dock 被隐藏，btw 显示）。
- ⬜ 斜杠命令 / 技能引用（DSH 输入触发器）。

**顶栏 / 主界面**
- ⬜ 新回复药丸 `NewMessagePills`。
- ⬜ 主界面行状态：头像状态环（running / waiting / approval）、主 Bot 徽标、未读点、置顶标记、
  运行中那一行（`doingLine`）、Working / Waiting / Unread 文案。
- ⬜ 侧栏 workspace/team 分组、footer 的账号 / 连接插件入口（`TeamSidebar` 等）。

**详情面板**
- ⬜ `RunStats`：`N turns · xx tok/s · cache hit xx% · context xx%`。
- ⬜ 「复制整段对话为 Markdown」（`Share` 按钮）。
- ⬜ more 菜单：side chat / terminal / files（DSH 右栏标签）。
- ⬜ 浏览器 tab（Bot 专属多标签浏览器，当前仍是占位）。
- 🟡 群详情成员列表缺实时状态（working / approval）。

**其它**
- ⬜ `request_secret` 密钥卡 + 设置里的 Secrets 页（Bot 只拿到变量名）。
- ⬜ `/side` 侧聊。
- ⬜ 会话搜索（`session/search`）与命令面板 `CommandPalette`。
