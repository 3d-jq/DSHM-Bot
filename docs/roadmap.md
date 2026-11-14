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

**结构改动已落地（2026-11-13）**：`EngineSettingsViewModel` 改为注入
`EngineSettingsRepositoryFactory`（`data/remote/ViewModelContracts.kt`），
`BotChatViewModel` 改为注入 `BotChatDependencies`（remote / transport / speech / mux）。
`applyWrite` 的 revision 冲突分支、凭据写入成功路径、「事件流 → 卡片」接线现在都有 JVM 单测
（`EngineSettingsViewModelInjectionTest`、`BotChatViewModelWiringTest`）。
同轮补了 `DshClientMockWebServerTest`；验证 **74 测试类 / 428 用例全绿**（2026-11-13）。

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
- ☑️ ~~停止生成、历史分页~~ → `BotChatViewModel.stopRunning()`；`loadEarlier()` + `ChatCursor` + `hasEarlier` 已接（2026-11-13 核实）
- ☑️ 审批卡片原生渲染 + 应答接线（见「审批显示专项」）
- ☑️ ~~提问卡片~~ → 单/多选 + `answerChatQuestion` / `answerQuestion` 走 `userQuestions/answer`，已接（2026-11-13 核实）
- 🚫 远程主机（局域网 / SSH / Noise Relay）——**2026-11-13 用户明确：远程控制不做**，整条线不排期
- ☑️ 契约测试：`DshClientMockWebServerTest` 覆盖正常 cookie / envelope、SSE 分发、断线重连、
  2MB 超大事件、取消后不再重连（2026-11-13）

## M2：DS Bot 团队（🔄 部分落地）

- ☑️ `BotRepository` 接 DS Bot `state` + `feed`（`revision` / `live` 增量，`BotCache` 冷启动快照）
- ☑️ 私聊 / 群聊（`chat` / `room-chat`，群聊走 room id）
- ☑️ Bot 详情、Bot 内嵌浏览器、网页模式（`InterfaceMode.WEB` + `WebHostScreen`）
- ☑️ 账号与余额（`DshAccountRepository` / `AccountScreen`）
- 🔄 设置体系：20 余个子页已接真实 RPC，仍在修 bug（见「当前工作重点」）
- ☑️ ~~新建 Bot / 新建群聊~~ → `NewBotScreen` / `NewGroupDialog` 已接 `create-bot` / `create-room`（2026-11-13 核实老表过期）
- ☑️ @ 成员：输入 `@` 触发成员面板（`ChatComposer` 的 `mentionCandidates`），支持「所有人」
- ☑️ 群详情成员管理：`RoomSettingsScreen` 设管理员 / 增删成员 / 权限
- ☑️ 群公告编辑：`BotNoticePage`（整屏，走 `update-room {notice}`）
- 🟡 通知：`ChatNotifier` 有 DONE / ERROR / APPROVAL / QUESTION 四类；**未读计数 / 未读点 / 公告审批**仍未做（用户曾决定未读不做，见「与 DS Bot 差距清单」）
- ☑️ ~~与 DS Bot 手机接口版本协商~~ → 新增 `DsBotCapabilities`（进程内按端点缓存
  `hello` 结果），`HomeViewModel.setEndpoint()` 在引擎起来时协商一次；`room-chat`
  （群聊历史/翻页）与 `usage-summary`（Bot 详情用量）按 `supports()` 裁决，失败一律
  关闭能力、不把旧版插件的 `bot/not-found` 显示成错误。`DsBotCapabilitiesTest` 4 例。
  （2026-11-13 接线）

## M3：本机引擎（🔄，远程控制不做）

- ☑️ 运行时层：PRoot + Node 运行时、dsh-go-bridge 原生桥接、dsh-media 插件、依赖锁（`runtime/`）
- ☑️ 引擎常驻前台服务（`RuntimeForegroundService`，通知渠道 `dshm-runtime`）：
  把 App 稳在 `FOREGROUND_SERVICE`，PRoot/Node 子进程不再被当幽灵进程裁掉；
  通知带「停止引擎」动作。原来的「引擎就绪」通知（`EngineNotifier`）已删除，避免重复打扰。
- ☑️ ~~通知直达会话~~ → `ChatNotifier` 的 Intent 带 `EXTRA_OPEN_BOT_ID`，`MainActivity.consumePendingOpenBot()` → `DshmBotApp` 打开对应对话（2026-11-13 核实）
- 🚫 主机切换（本机 / SSH / Remote）——同上，远程控制不做，只保留本机引擎一条路

## M4：体验与发布（⬜）

- 🔄 深色主题（`ThemeMode`）、动效（`DshmMotion` + `reduceMotion`）、无障碍（`DshmAccessibilityService`）
- ⬜ 诊断日志与崩溃上报（目前只有本地 `EngineLog`）
- ⬜ 正式签名（release keystore）、应用内更新、发布流程
- ⬜ `targetSdk` 升级评估：当前 28 是 PRoot 的取舍，见架构文档

## 更新与版本跟随（2026-11-12，**只记录，暂不实现**）

用户明确：**现在不做更新功能**，先把事实与风险记在这里，后面单开一轮。

会牵动“更新”的是三条独立线。任何一条变动都要**我们自己适配**，不存在“装上新版就行”：

1. **本项目自身**
   - 发版有两个产物：**APK**（里面带着 `assets/ds-bot` 插件）和 **runtime zip**（rootfs）。
2. **上游 DSH 更新**
   - `assets/runtime/manifest.json` 钉住 `runtimeVersion` / `dshVersion` / `nodeVersion`，
     以及 13 个我们自己的 dsh 补丁（见 manifest 的 `dshPatches`）。
   - 上游升级 = 逐个重打这些补丁 → 重建 rootfs → 更新 manifest 与 sha256 → 发新 runtime zip。
   - 应用内下载地址：`releases/latest/download/dshm-runtime-arm64-v8a.zip`
     （`EngineScreen.DEFAULT_RUNTIME_URL`）；zip 内自带 CI 生成的 manifest，哈希校验在
     `RuntimeInstaller.installFromZip()`。
3. **上游 DS Bot 插件更新**
   - 插件打进 APK 的 `assets/ds-bot`，每次引擎启动由 `DsBotPluginInstaller` 覆盖进 rootfs
     （指纹 = 插件版本 + APK `lastUpdateTime`）→ **插件升级只能发新 APK**。
   - 我们改过插件源码，上游新版必须 merge，不能直接覆盖：
     - `src/host/browser.js`（手机浏览器自动化 / `read_browser` / `browser-*` / 截图 / 守卫 / 交接）
     - `src/host/channel.js`（`browser-wait` / `browser-result` / `phone-wait` / `phone-result` 等端点）
     - `src/host/phone.js`（手机控制，`DSH_PHONE_CONTROL` / `DSH_PHONE_OWNER`）
     - `src/host/dsh-update.js`、`src/host/update.js`（`DS_BOT_APP=1` 时视为整个 App 一起更新）
   - `client.js` 是 `npm run build` 的产物：改完源码必须重新构建，不能手改。
   - Android 侧消费的 RPC / feed 契约（`state`、`feed` 的 message/stream/revision/live/notice/room、
     `chat` / `room-chat` / `send` / `capability-*` / `memory-view` / `usage` / `browser-*` …）
     字段或语义一变，我们界面不会自动跟上，必须真机回归。

**当前状态（未实现）**
- 应用内“检查更新”只是打开 GitHub Releases 页面（`AboutScreen.RELEASES_URL`），
  没有版本比较、没有下载、没有自动更新。
- ⬜ **运行时升级会丢数据（已知，暂不修）**：`RuntimeInstaller.installFromZip()` 会
  `rootfsDir.deleteRecursively()`，而自动 `stash(KEEP_PATHS)` 没有接进 `installFromUrl`。
  已安装状态下直接重装会丢 `root/.dsh/bot`（对话 / 记忆 / Bot 设置）、
  `root/.dsh/sessions`、`root/.dsh/AGENTS.md`。
  用户明确：**数据保留升级先不做，只记录**。
- ⬜ 插件是否支持“不更新 APK 也能升级”（从远端拉新插件包）未定；目前跟随 APK。

## DSH 完整化（2026-11-05 起，按 dsh-Go 对齐）

范围：DSH 层照 dsh-Go 完整化，Bot 能力层照 DS-Bot 补齐（边界见 [reference-baselines.md](reference-baselines.md)）。

| 批次 | 内容 | 状态 |
|---|---|---|
| 1 | 会话协议核心：`session/*` unary RPC、消息 Part / 日志事件 / follow 帧模型、`$events` waterfall、审批与提问、流式渲染 | 🔄 进行中 |
| 2 | 模型/凭据/设置/插件/权限/语音补全（`appAuth/*`、订阅、`pluginManager` 安装卸载、namespace 全覆盖） | ⬜ |
| 3 | 工作区/文件/终端/Jobs/子代理/技能/目标/定时任务 | ⬜ |
| 4a | 远程主机（SSH / DSH Remote / Noise / Relay） | 🚫 不做（2026-11-13 用户明确：远程控制整条线不排期） |
| 4b | 运行时运维（安全模式、重置/卸载、诊断包、会话导出） | ⬜ |

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

- ☑️ 底部栏写法已固化到 [design/settings.md](design/settings.md) 的「底部栏写法」一节
  （`skipPartiallyExpanded = true` + 绝对高度 + `SettingsInput` + 无搜索 Combobox +
  底部按钮等宽等高），后续新面板照抄，别再各写各的。

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

### 底部面板接线（2026-11-08，第九轮，全部完成）

- ☑️ **浏览器标签页**：`BotBrowserStore` + `BotBrowserScreen` 换掉原来整页 DSH Web 的占位。
  每个 Bot 一套标签（`MAX_TABS = 6`），支持开/关/切换、地址栏、后退/前进/刷新、系统浏览器打开、
  下载（落到应用自己的 Downloads 目录）。面板的 `browser` 分类显示标签卡（`BrowserTabsCard`）。
  ⚠️ Android WebView 的 cookie 是进程级共享的，做不到桌面版「每个 Bot 独立 storage partition」。
- ☑️ 全屏浏览器顶栏统一成项目标准的悬浮圆钮：返回（左）+ 居中标题 + 历史 / 设置 / 刷新（右），
  跟 `SettingsPage` / 对话页顶栏同一套 `FloatingIconButton`，不再用自绘的扁平顶栏。
- ☑️ **浏览器控制（`read_browser`）**：`BrowserReadBridge` 长轮询 `browser-wait {clientId, open}`，
  收到读页请求后在 WebView 里跑 `READ_PAGE_SCRIPT`（可见文本 → Markdown，链接/控件标 `[#n]`，
  带 `items[{role,name,href,value,checked,disabled}]`），再回 `browser-result {id, ok, page}`。
  插件侧只在「有窗口」时才注册这个工具 —— 启动引擎时给 guest 加 `DS_BOT_APP=1`（`EngineViewModel`）。
- ☑️ **浏览器动作（2026-11-08，第十轮）**：插件侧新增 `browser_open` / `browser_click` /
  `browser_type` / `browser_scroll` / `browser_back` / `browser_forward` / `browser_reload` /
  `browser_tabs` / `browser_close_tab`，复用同一条 `browser-wait` 通道
  （`call(botId, {kind,…})`，回执带 `result`）；Android 侧把 DeepSearch 的 `BrowserUseJS`
  三个动作搬进 `BrowserActionScripts.kt`，`BrowserReadBridge` 按 `kind` 分派。
  **闭环**：`browser_open` 不要求面板已经打开 —— 通道的 `call()` 在 Bot 没有打开页面时会落到
  「最近一个还活着的窗口」（`latestClient()`），App 侧按需 `store.session(botId)` 建 WebView、
  装载完再回执；页面留在浏览器池里，用户打开面板就能看到。`open` / `navigate` 都会等装载完成
  （`awaitIdle`，12s 上限）。「没有页面」和「App 没在跑」现在是两种不同的提示
  （`no-page` → 让模型先 `browser_open`；`absent` → 请用户打开 App）。
- ☑️ **「Bot 正在操作」的显示（第十一轮）**：面板里页面压一层薄雾 + 呼吸点 +
  「<名字> 正在操作浏览器」+「接管」（接管会打断这次等待，工具立刻回执 `takenOver`）；
  对话里按 `toolEventLine` 出事件行（`browser_open` 带站点名、click/type/scroll/导航各自一句），
  这些工具也进了 `FOLLOW_TOOLS` 的订阅集合。
- ☑️ **工具面补齐（第十三轮）**：`browser_hover`、`browser_execute_js`、`browser_get_backbone`、
  `browser_fetch`（页面内 fetch，先发起再轮询取结果）、`browser_wait_for_dom_stable`（文本/节点/高度
  连续不变）、`browser_scroll_and_collect`（滚到底再整页读）、`browser_screenshot`（WebView 画到
  PNG，落在应用自己的 `browser/` 目录并回路径）、`browser_get_cookies` / `browser_set_cookie`、
  `browser_user_agent`、`browser_viewport`。
- ☑️ 下载进度横幅：`DownloadManager.enqueue` 之后每 0.5s 查一次，完成/失败留 2.5s。
- ☑️ 对话里**不再画**浏览器动作行和「更新记忆」行（每读一次、滚一下都来一行会把时间线冲乱），
  只在活动状态栏显示（`ChatActivity`：`browser_*` → 「正在操作浏览器」、`read_browser` → 「正在读网页」）。
- ☑️ **浏览历史（2026-11-08，第十五轮）**：`BotBrowserSession.history` 在页面装载完成时记录
  （同一 URL 去重、最新在前、最多 200 条），按 Bot 存在自己的 SharedPreferences 里，重开 App 还在。
  全屏浏览器顶栏「历史」进入 `BrowserHistoryPage`：标题 / URL / 时间，点一条在当前标签打开，
  底部「清空浏览历史」带确认。
- ☑️ **浏览器设置页**：`BrowserSettingsPage`（全屏顶栏的齿轮进入）：
  - UA：默认 / 移动 Chrome / 桌面 Chrome / 自定义（自定义带输入框 + 应用），写进这个 Bot 的偏好；
  - 视口：跟随设备 / 360 / 414 / 768 + 自定义 CSS 宽度；
  - Cookie：按历史里出现过的域名逐个列出（`CookieManager.getCookie`），可单域名清除 / 全部清除；
  - 站点数据：清缓存、清站点数据（cookie + WebStorage + cache）和清历史，都带确认。
- ☑️ **rootfs 本地页（`dshm://`，2026-11-10）**：按 DeepSearch 的 `minis://` 思路，不再开
  `127.0.0.1` 文件服务器。`RootfsWeb` 把 `file:///root/...`、`/root/...` 和 workspace 相对路径
  映射成 `dshm://workspace|attachments|dsh/...`，由 `WebViewClient.shouldInterceptRequest`
  直接返回文件流和 MIME；相对 JS/CSS/图片、ES module、SVG 都能加载。只暴露 `.dsh` 下目录，
  避免把整个 rootfs 暴露出去；`currentPage()` / 浏览历史 / 缩略图也认 `dshm://`，本地页下载走
  应用自己的下载目录，不再交给只支持 HTTP 的 `DownloadManager`。
- ☑️ **对话里的页面缩略图卡**：不再每步一行。一轮结束（`state.running` true→false）且这一轮
  Bot 确实操作过浏览器（`lastAgentAt` / `consumeAgentMark`）时，抓一张当前标签的缩略图，
  按 Bot + 会话存到 `filesDir/browser-shots/<botId>`，作为 `ChatEntryKind.BROWSER_SHOT` 贴在最新消息下面：
  图 + 标题 + URL，点卡片打开这个 Bot 的浏览器。缩略图整张只有一两种颜色（合成失败）时直接跳过。
- ☑️ **BrowserGuard（第十二轮）**：`type` 前先 `inspect` 目标元素 —— `type=password`、
  `autocomplete=*password*|one-time-code`、名字/占位符命中「密码/验证码/口令/OTP」直接拒绝，
  回给模型「用 browser_hand_over 交给用户」；`click` 前看按钮文案（pay/send/delete/支付/删除…）
  则弹全局确认框（`BrowserGuardStore`，画在 `DshmBotApp` 顶层，20 秒没人答按拒绝）。
  判定是纯函数（`BrowserGuardRules`），有 `BrowserGuardTest`。
- ☑️ **交接给用户（第十二轮）**：`browser_hand_over { reason }` —— 插件把请求发给窗口后**轮询**
  `handOverWait`（每 2 秒，最长 15 分钟，所以 HTTP 不会挂几分钟）；App 收到就当即将该 Bot 的
  浏览器推到前台、面板出现「轮到你操作 + 完成」横幅，用户点完成，工具返回让模型重新 `read_browser`。
  模型全程看不到用户输入的内容。
- ☑️ 引擎启动等待 120s → 180s：插件刚覆盖 + 设备忙时冷启动会超过两分钟（实测一次 120s 超时后
  才拿到 token），超时会把已经起来的引擎判成失败。
  ⚠️ 这是**我们改过的插件代码**（`assets/ds-bot/src/host/browser.js`），升级上游要合并；
  `DsBotPluginInstaller` 的指纹改成「插件版本 + APK 更新时间」，否则改了 JS 版本号不变不会覆盖 rootfs。
  ⚠️ 同一开关会让插件的 `update` / `dsh-update` 认为「整个 App 一起更新」，不再自己检查 npm 版本；
  对我们反而更安全（避免插件把 npm 包覆盖到打包副本上），但面板里的「可更新到 x.y.z」会消失。
- ☑️ **记忆新增 / 编辑**：`memory-add` / `memory-edit`，Bot 有 `own` / `team` 两个 scope，
  群只有 `group`；带 topic、按原文匹配替换，还显示 `memory-view` 的 sizes / 摘要 / 是否过时。
- ☑️ **文件夹选择器**：`folders`（已知目录 + `picker` 类型）、`folder-list`（浏览，本机是 `browse`）、
  `folder-pick`（原生选择器，本机没有）。手动输入路径保留为最后一个选项。
- ☑️ **运行统计**：`run-stats` → `N turns · xx tok/s · Cache hit xx% · Context xx%`，
  显示在详情页用量区块（DS Bot `runStatsOf` 同一套算法）。

### DS Bot 团队设置（2026-11-08，第十四轮）

- ☑️ 设置首页新增「团队」组（对齐网页端 `SETTINGS_PAGES` 的 Secrets / Connectors /
  Capabilities / Scheduled tasks / Usage），原来单独一张「团队」卡里的定时任务已并进来，
  不再出现两个同名分组：
  - **密钥**：列表（名字 / 用途 / 谁能用 / 是否用过）；新增走 `secret-set`，
    改用途与范围走 `secret-update`，换值走同名的 `secret-set`（同名覆盖），删除走 `secret-delete`。
    范围支持「所有 Bot / 指定 Bot（勾选成员）」。
  - **连接器**：`state.connectors`（GitHub 的状态 / 账号 / 错误），
    连接走 `connector-connect {id, token}`（token 存成密钥）、断开 `connector-disconnect`、出错重试
    `connector-retry`。
- ☑️ **能力（Capabilities）**：能力库列表（MCP 服务 / 技能 / 组合包 + 描述 + 已挂几个 Bot）；
  点开可给每个 Bot 开关（`bot-capability {botId, name, attached}`）、批准未批准的 stdio MCP
  （`capability-approve`）、两次点击删除（`capability-delete`）；每个 Bot 的
  `capabilityStatuses`（就绪 / 连接中 / 待批准 / 出错）也显示出来。
- ☑️ **用量（Usage）**：读 `usage` 原始行（`hour / owner / model / kind / input / cacheRead /
  cacheWrite / output / calls`），聚合放在 `TeamUsage.kt`（有单测）：
  - 范围 chip：今天 / 7 天 / 30 天，按 `now` 推起点，`hour` 是 `2026-11-08 14` 这种字符串；
  - 合计一行（tokens / 调用次数 / 缓存命中）+ 每天 token 柱状图（最近 14 天）；
  - 按归属（Bot / 群聊 / Agent / 其他，按 tokens 降序）和按模型两组明细；
  - 底部「刷新」行带「正在读历史用量…」状态。
- 样式约定：范围切换用项目标准的 `SettingsSegmentedPill`（不要再自己画灰色胶囊）；
  只读数据行不要传 `enabled = false` —— 那会让标题变成 `textTertiary` 的灰字，
  只读用「不传 onClick」表达，标题保持主文字色。完整设置页 / 群面板 / 详情页的用量行已一并改掉。
- ☑️ 刷新改成顶栏右侧悬浮圆钮（`FloatingIconButton` + `ic_lucide_refresh_cw`），扫描中禁用并在标题
  下方显示「正在读历史用量…」，页底不再留一行刷新。
- ☑️ **定时任务（Scheduled tasks）** 按 DS Bot `client/schedules.js` 补齐：
  - 任务按目标（Bot / 群聊）分组，组标题是目标名，行首是 Bot 头像或群成员头像堆叠；
  - 行内：名字、时间规则 + 内容预览（+ 群名）、下次运行 / 已完成；点行看详情，尾部垃圾桶
    「再点一次删除」（3 秒后自动取消确认）；
  - 「新建任务」内联表单：发送给（Bot + 群聊 combobox，不支持搜索）/ 名字 / 做什么 / 什么时候跑
    （一次 `at`、每天、每周 + 周几、重复 N 分钟/小时/天）；
  - 新增 `ScheduleTiming.At` → `{ kind: "at", at: ISO }`，跟宿主 `selectorOf` 一致；
    `scheduleAtIso` / `scheduleAtLabel` 做本机时间与 ISO 的互转（有单测）。
- ⬜ **待办（2026-11-08 老大明确）**：定时任务页与新建表单先保证接线和功能可用，
  UI 之后专门做一轮优化（表单排版、分组标题带头像、点击区域等都还要打磨）。
- ⬜ 还没做：**General**（主题/强调色/消息布局是网页端 `prefs`，原生界面不适用）、
  **Feedback**（意见反馈已有自己的入口）。

### 联网搜索与第三方搜索（2026-11-09）

- ☑️ 设置页按 dsh-Go 收成两张卡：无标题的「新版搜索 API」开关卡 +「DeepSeek 搜索」配置卡；
  配置卡只显示当前模式的 `apiKey` / `baseURL` / `maxUses|count`，不再画假的预算入口。
- ☑️ **当前只支持 DeepSeek 兼容协议，不能直接把 Tavily / Serper / Bing / Google CSE
  的 Base URL + Key 填进来用**：
  - `web-deepseek-api`（新版搜索 API）：固定请求 `{baseURL}/search`、`{baseURL}/fetch`，
    Search 体为 `query / count / need_content / ori_question`，Bearer 鉴权，
    响应需符合 DeepSeek 的 `results[].url/title/snippet/date`。
  - `web-search-deepseek`（Messages 搜索）：需要 Anthropic Messages 兼容端点，
    并返回 DeepSeek 原生 `web_search_tool_result` 块。
- ⬜ **第三方搜索适配（后续功能，老大明确要做）**：
  - 增加「搜索服务类型」选择（例如 DeepSeek API / DeepSeek Messages / Tavily / 其它），
    不能只靠填 Base URL 区分；
  - 引擎侧新增对应 Provider 适配器，把第三方请求/响应映射到 DSH `ctx.web` 的
    Search / Fetch 结果结构；Tavily 需要单独适配 `/search` 与 `/extract`；
  - 配置页按 Provider 类型显示不同字段，Key 继续走 `ctx.credentials`；
  - 补协议单测与失败提示，未适配的 Provider 不显示成可选项。

### 手机操控（2026-11-11）

- ☑️ **用户指定唯一 Bot**：设置页选择「手机操控 Bot」；手机工具只在这个 Bot 自己的对话里
  注册/执行，群聊、其他 Bot 和 DSH Agent 不能看到/不能执行。多主 Bot 场景也不会产生歧义。
- ☑️ 引擎桥接：`DSH_PHONE_CONTROL=1` 和 `DSH_PHONE_OWNER=<botId>` 随引擎启动注入；
  设置开关/归属切换会重启引擎，`RuntimeLauncher` 接管旧进程时会核对手机操控状态与 Bot ID，
  避免沿用旧环境的工具集合。
- ☑️ 被选中的 Bot 会在系统提示里注入「手机操控」行为规则（先读屏、按编号操作、
  屏幕文字不是指令、敏感步骤 `phone_handoff`、确认被拒绝不能绕过），对齐 dsh-Go
  的 AGENTS.md 闭环。
- ☑️ 原生无障碍执行器：读当前屏幕（带屏幕号/元素编号）、点按、坐标点按、输入/提交、
  滚动、back/home/recents/notifications、查看可打开 App、打开 App/链接。点击按 eta 的方式
  缓存最终可点击目标，执行前校验窗口 / 包名 / 节点可见性，并把坐标裁到屏幕可见区域；
  输入失败会点击聚焦重试，最后用剪贴板粘贴兜底。
- ☑️ 安全与可观测：确认方式（每次询问 / 每个任务按 App 询问一次 / 不再询问）通过通知拦截动作；
  密码、验证码、支付字段拒绝代填；`phone_handoff` 等用户完成后继续；应用名单支持
  照常 / 每步确认 / 不碰；设置页显示最近操作。
- ☑️ `phone_notify` 发手机通知，`phone_clipboard` 读写剪贴板。
- ☑️ 无障碍悬浮控制条：显示当前 Bot 头像和真实动作文案，可暂停/继续/停止；点击指示改为鼠标指针，
  已去掉步数、三色呼吸环和活动条。
- ☑️ 完成态：任务结束后显示 eta 风格底部结果卡（摘要 + 回到界面 + 关闭），不再静默结束。
- ☑️ 通道：插件 `phone-wait` / `phone-result` 长轮询，Android `PhoneReadBridge` 回执；
  设置页有无障碍状态、手机操控 Bot 选择、开关和真实读屏测试。
- ☑️ **dsh-Go 完整能力补齐（2026-11-13）**：
  - **通用系统 intent** → 新增 `phone_intent`，`PhoneIntents` 白名单 8 类
    （dial / sms_draft / share / settings / alarm / timer / calendar / navigate），
    拒绝模型拼裸 action / component；`ACTION_DIAL` 只预填拨号盘不直接拨号；
    Manifest 加 `<queries>` 解决 API 30+ 包可见性；`PhoneIntentsTest` 8 例。
  - **屏幕截图** → `phone_screenshot`，API 30+ 走 `AccessibilityService.takeScreenshot`
    （`canTakeScreenshot="true"` + `tools:targetApi="30"`），图片落 rootfs 工作区、
    回路径文本；26–29 明确返回「本机 Android 低于 11，不支持」。
  - **列表 collect** → `phone_collect`，多页滚动 + 去重 + `until` 提前停止 + 页数/字数上限；
    `PhoneCollectTest` 4 例。**顺带把 `PhoneReadBridge.answer` 从 `Dispatchers.Main`
    挪到 IO**（`DshmAccessibilityService.dispatch` 会阻塞，长动作放主线程会 ANR）。
  - **悬浮条插话** → `PhoneInterjectOverlay`（独立可聚焦窗口，胶囊本身拿不到 IME 焦点），
    点「插话」→ 预填到对应 Bot 的输入框 → 发送带 `mode:'steer'`，
    由 `channel.js` 的 `send` 透传给 `sessionController.prompt`，**插进当前正在跑的回合**
    （已核对 DSH 0.2.2 `prompt` 的 `mode==="steer" → agent.steer(message)`）；
    插话**不**调 `PhoneControlSession.beginTurn()`，避免清掉用户刚按的「停止」。
  - 插件三处清单（`PHONE_TOOL_NAMES` / `TEAM_TOOLS` / `PHONE_RULES`）与 Kotlin 的
    dispatch / `describe` / 确认门都已同步；`PhoneControlBridge` 现在 14 个 action。
- ⬜ **后台虚拟屏（不做）**：需要 shell UID（Shizuku / Sui / 无线 ADB / root）+ Android 14+
  的 trusted `OWN_FOCUS` virtual display 标志；普通 App UID 拿不到 `ADD_TRUSTED_DISPLAY`，
  也无法把别的应用 Activity 启动到副屏。`MediaProjection` 只能镜像真实屏幕，不是后台虚拟屏。
  结论：**不是"没做"，是普通 App 做不到**，除非以后引入 Shizuku/ADB 通道（另开一轮）。
- ⬜ 自动化回归测试（手机操控仍靠真机点）。

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
- ☑️ 群聊后续（2026-11-09）：`folder-list` / `folder-pick` 群目录选择器（工作文件夹改用同一个
  `FolderPickerDialog`）、`memory-add` / `memory-edit` 群记忆新增编辑（本来已接，本轮确认）、
  `room-continue`（群聊暂停后「继续」）、浏览器标签页。

## 已知工程债（与修 bug 并行清）

- 测试覆盖：`data/runtime`、`ui/settings` 仍相对 UI 偏薄；`data/remote` 已补 MockWebServer 契约测试
- 本机 NDK 目录缺 `source.properties`，导致 `.so` 无法 strip、APK 偏大；
  属 SDK 安装问题，用 SDK Manager 重装该版本 NDK 或补齐 `source.properties`，不要在构建脚本里屏蔽告警
- ☑️ `dshm.lintWarningsAsErrors` 已置 `true`（2026-11-13）：存量 1 error + 354 warning 清零，
  修了 NewApi / ObsoleteSdkInt / DefaultLocale / UseKtx（SharedPreferences.edit、Uri.toUri、
  Color.toColorInt…）/ 191 条未使用字符串 + 图标 / Typos / Compose 规范（ModifierParameter、
  ConfigurationScreenWidthHeight→`rememberWindowHeightDp()`、ComposableNaming、可变集合状态、
  offset lambda）/ 补齐过期插桩测试；故意保留的告警在 `app/lint.xml` 按文件定向豁免。
  现在「零 warning 才构建通过」。
- 文档与令牌数值的漂移（已改为只引用令牌名，不抄数字）
- ☑️ `BotChatViewModel` / `EngineSettingsViewModel` 依赖已可注入（2026-11-13）：
  `BotChatDependencies`（remote / transport / speech / mux）与 `EngineSettingsRepositoryFactory`；
  「事件流 → 缓存 → 卡片」接线已由 `BotChatViewModelWiringTest` 覆盖，不再只靠插桩测。
- 提问卡片已接选项与 `userQuestions/answer`；`ViewCard` / `BtwCard` 仍未接。
- ☐ **群聊排队项撤回未做**（2026-11-14，新管线遗留）：单聊排队项可「改为插话 / 撤回」，
  群聊 inbox 行只显示——群排队真身在插件 `roomInbox`（非 `agent.inbox`），行 key
  `inbox:roominx:<roomId>:<i>` 是按位置生成的、**没有稳定 itemId**，无法定位到具体一条做撤回。
  要做需要宿主侧给群排队项补稳定 id（`roomInbox` 存 posts 时带上）再透出行；属后续轮次。
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
  我们仍然丢掉。蓝图渲染器已经有了（见「待办卡 dock」，`PendingDock.kt` + `ChatFormula.kt`），
  ⬜ 还差把它接进消息流：答过的卡要按 `callId` 留在对话里。

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
- ☑️ 团队级工具（`list_bots` / `read_own_chat` / `recall` / `list_secrets`）**不再画成对话里的
  事件行**，只走活动状态行（`ChatActivity.SEARCH_TOOLS` 命中 → 「查看团队…」「翻看聊天记录…」
  「查找中…」「查看密钥…」），避免把只有状态价值的动作刷进时间线。
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
- ☑️ 待办卡 dock（2026-11-08 第六、七轮）：输入框上方一道 dock，顺序照 DS Bot 的 `input.dock`
  **提问 → 视图 → 密钥 → 审批**（DS Bot 把审批放在群成员条里，我们没有那一条，统一收进 dock）。
  dock 已经是输入框 chrome 的一部分：整块高度被量出来、列表留白跟着走，卡片不压消息。
  接上 DS Bot 自己的三条来源（之前完全没接）：`state.questions`（`ask_user` → `answer-question`）、
  `state.views`（`show_view` → `view-reply` / `dismiss-view`）、
  `state.secretRequests`（`request_secret` → `secret-set` / `secret-allow` / `secret-cancel`；
  答过的卡在 dock 里多留 20 秒，对齐 DS Bot 的 `SettledSecret`）。
  视图卡按蓝图**顺序递归**渲染（`stack` / `row` 各自成形），画 text / metric / progress / callout /
  table / chart / sources / html + options / confirm / form / submit，输入有 choice / toggle /
  number / slider；每次作答都按 DS Bot 带上全部 `values.inputs`。
  公式 `{"=": …}` 按 DS Bot `src/shared/formula.js` 移植（`ChatFormula.kt`：二元/三元、比较、
  12 个函数），`let` 名字 + 输入联动就地重算，`table` 单元格、`chart` 数值、`metric.value`、
  `progress.percent` 都吃公式。
  `chart` 自己画 bar / hbar / line / area / donut。
  `html` 真渲染（2026-11-08 第八轮，`ChatHtmlCard.kt`）：WebView 沙箱，禁网 / 禁文件访问 /
  禁跳转 / 只加载 `loadDataWithBaseURL(null, …)`，并照 `deep-view` 的 `frameDocument` 注入
  `--view-*` 与旧的 `--bt-*` 主题变量、CSP、JS 高度上报（默认 120，上限 640）。
  模型在 provider 改写数组参数时常用纯 CSS 的 `html` 画图，只显示 `summary` 等于看不见，所以这条必须真渲染。
  ⬜ 还差：回答过的提问卡 `AnsweredReply` / `SettledQuestion`，以及视图卡进消息流
  （DS Bot 把答过的卡留在对话里；我们答完就从 dock 收走）。
- ⬜ 已回答提问 `AnsweredReply` / `SettledQuestion`。
- ⬜ 时间分隔 `TurnTailCell`（两轮间隔 ≥30 分钟）与定时任务触发卡 `TriggerCell`。
- 🟡 「新消息」分隔：DS Bot 用 `TurnProcessCell` 的 visit 语义；我们按用户要求先删掉，未换新语义。
- ☑️ 活动行时机（2026-11-08 第五轮）：按 DS Bot `rowVisible` —— 正在写文字时不显示活动行
  （文字气泡自己就是「在做」），但这一刻有工具在跑（写入 / 搜索 / 派发）时仍然保留。
- ☑️ 修时间线排序（同轮）：`session/follow` 修通后事件合并开始真的生效，而它是**整表按 seq 排序**的 ——
  本地刚发出的用户气泡还没有真 seq（服务端回显被 `seen` 去重，要等下一次重载才有），
  会被排到列表末尾，倒序列表里就是最下面，表现成「大模型的回复跑到问题上面」。
  改成只把事件行插进有 seq 的那段历史（`TimelineMerge.kt` + `TimelineMergeTest`），不动其余顺序。
- ☑️ 群聊的一轮状态（同轮，改了两版才对）：之前群聊只看 `state.work.rooms`，
  round 完了还挂着停止键 + 状态行，`work.rooms` 里没有发言记录时状态行还会退回**群头像**。
  第一版只在 `room-chat`（加载 / `room` 事件）时刷新，round 结束后没人再读 → 还是卡。
  最终按 DS Bot 自己 `GroupReplies` 的做法：**每秒轮询 `room-progress`**
  （`[{callId,replies,speaking}]`，空数组就是权威的「没在跑」），
  群聊的 `running` / 发言人只认它（外加打开时的 `room-chat.running` 与刚发送的乐观窗口），
  不再读群会话的 `api-session/status` 和 `work.rooms`（两者都出现过没清干净）。
  轮询由「发送」「打开时发现 round 在跑」「feed 的 room 事件」启动，round 结束后自己收。
- ☑️ 乐观状态不再用时间窗口（同轮，照 dsh-Go）：dsh-Go 也做乐观更新，但都是**等主机回执就撤**
  （`PendingPrompt` 按 `requestId` 等 `UserChatItem`、`_dropEchoed`、`sessions_state` 的
  "never optimistically"），没有超时窗口。我们原来的 `RUNNING_OPTIMISTIC_NS`（30 秒）和
  刚加的 `GROUP_ROUND_GRACE_NS`（5 秒）都删了，改成 `optimisticRun` 标记：
  发送时置位，**收到任一主机回执就清**（单聊 `api-session/status`、群聊读到 `room-progress`、
  回合轮询的结论、手动停止）。判定顺序抽成 `botRunIsRunning` / `groupRunIsRunning` 两个纯函数
  并配单测（`ChatActivityTest`），顺序就是之前踩过的坑：会话状态 > 本地乐观 > 回合边界 > `work`。
  群聊轮询也瘦身：每秒只读 `room-progress`，重的 `state` 最多 3 秒一次。
- ☑️ 群聊 `paused` + 「继续」（`room-continue`）：输入框上方出「群聊暂停了…」卡片 + 「继续」，
  数据取 `state.rooms[].paused`（2026-11-09）。
- ☑️ 群详情成员的实时状态（`state.work` → 等你批准 / 正在干活），页面开着时 3 秒轻量轮询（2026-11-09）。
- ☑️ 群聊产物兜底（2026-11-09）：成员的 `present` 落在各自会话里，插件新增 `room-artifacts`
  按成员会话翻 `deliverables/presented`，本轮没人声明时退回群工作区里被改动的文件；
  结果贴成一张产物卡（重载后按固定 id 补回末尾）。
- 🟡 长文折叠：DS Bot 单聊也折叠（`FOLD_HUGE`），按用户要求目前只在群聊折叠。

**输入区**
- ☑️ 成员 dock（群聊成员在输入框上方，`MemberDock`）：成员头像堆叠 + 「X 等你批准」/
  「X · 正在写…」，数据取 `state.work`（`parseMemberStatus` 的 `here` / `speaking`，正在本群发言的人
  不画），点开进群详情（2026-11-09）。
- ☑️ 模型选择 dock（输入框上方，`ModelChoiceDock`）：Bot 没模型 / 模型没了时出卡 + 模型 chip
  （`update-bot {model}`）—— 之前新建的 Bot 没选模型会直接卡住（2026-11-09）。
- ⬜ 输入框上方的 usage dock / btw dock（DS Bot 里 activity/usage 两个 dock 被隐藏，btw 显示）。
- ⬜ 斜杠命令 / 技能引用（DSH 输入触发器）。

**顶栏 / 主界面**
- ⬜ 新回复药丸 `NewMessagePills`。
- 🟡 主界面行状态（2026-11-09）：头像状态环（running 主色 / waiting 警告色）+ 置顶标记已做；
  ⬜ 还差主 Bot 徽标、未读点、运行中那一行（`doingLine`）、Working / Waiting / Unread 文案
  （未读相关按用户决定不做）。
- ⬜ 侧栏 workspace/team 分组、footer 的账号 / 连接插件入口（`TeamSidebar` 等）。

**详情面板**
- ⬜ `RunStats`：`N turns · xx tok/s · cache hit xx% · context xx%`。
- ☑️ 「复制整段对话为 Markdown」（`Share` 按钮）：对话页按当前条目生成，Bot / 群聊面板各有一个入口（2026-11-09）。
- ⬜ more 菜单：side chat / terminal / files（DSH 右栏标签）。
- ⬜ 浏览器 tab（Bot 专属多标签浏览器，当前仍是占位）。
- 🟡 群详情成员列表缺实时状态（working / approval）。

**其它**
- 🟡 `request_secret`：密钥卡已进 dock（`secret-set` / `secret-allow` / `secret-cancel`，
  2026-11-08 第七轮）；⬜ 还差设置里的 Secrets 页（列表 / `secret-update` / `secret-delete`）。
- ☑️ 上游版本核对（2026-11-08）：本地插件 `0.2.5`，npm 最新 `0.3.1`
  （中间还有 `0.2.6` / `0.2.7` / `0.3.0`）。`0.3.1` 把视图/公式抽成 `deep-view@0.1.2`，
  它的 `normalizeView` 和 `0.2.5` 是同一套（`{item:[…]}` 递归解包 + 数字字符串归一），
  只多了「表单字段的 `value` 不当数字转」这一处 → provider 的数组编码**不是**「上游修了我们没拉」。
  ⬜ 升到 `0.3.x` 是大动作（新依赖 `deep-view`，还多了 `skills` / `goals` / `office` / `im` 等模块），单独排期。
- ⬜ `/side` 侧聊。
- ⬜ 会话搜索（`session/search`）与命令面板 `CommandPalette`。

### 启动体验与对话附件（2026-11-08，第十六轮）

- ☑️ **启动画面**（对齐 dsh-Go `LaunchOverlay` / `LaunchMotion`）：`ui/launch/LaunchOverlay.kt`
  - Android 12+ 系统启动画面（`values-v31/themes.xml`）用同一底色 + 同一图标，交接看不出硬切；
  - 0–640ms 图标从系统尺寸（276dp 画布）收到 0.62 并上移；200–640ms「DSHM Bot」字标淡入升起；
    560–1360ms 一道细光扫过图标；引擎就绪或动画播完 → 300ms 淡出；点屏幕跳过；关动画时静止；
  - 每个进程只播一次（Activity 重建不重播）。
- ☑️ **启动计时**：`RuntimeLauncher` 记 `app / prepare / dsh` 三段（对齐 dsh-Go `StartupTimeline`）。
  本机实测冷启动 `app ~540ms · prepare ~45ms · dsh 7.3–8.6s`，慢在 node + dsh 自身。
- ☑️ **NODE_COMPILE_CACHE**：guest env 加 `/root/.cache/node-compile-cache`（Node 24 的 V8 编译缓存，
  实测约 3%，无副作用）。
- ☑️ **接管还活着的引擎**：`RuntimeLauncher.adoptAlive` 从 `engine.log` 取回 token、确认进程与端口，
  且 APK 更新时间 / 运行时版本没变才接管；`EngineViewModel.start` 用 `login()` 验一次再决定。
- ☑️ **首页骨架**：引擎启动中（或首屏无缓存）铺 6 行骨架（呼吸透明度）替代「还没有内容」。
  同时修了 `manager.state` 的 StateFlow 初值把 `Starting` 盖回去、首页误判「引擎没在启动」的问题。
- ☑️ **对话气泡里的附件**：用户发过的图片显示缩略图（最多 3 张 + `+N`），文件显示文件卡；
  附件在选定时就落到 `cacheDir/chat-attachments`，`ChatEntry.attachmentItems` 带路径。
  历史回读只有文件名时仍走原来的 `📎 名字` 回退。
- ☑️ **对话界面 4 个 bug**（2026-11-09）：流式气泡间距抖动（抽 `chatItemSpacing` 纯函数共用）、
  骨架屏避让名字胶囊（`topPadding`）、用户气泡改实心 `bubbleUser` + `onBubbleUser`、
  失败/通知静默（事件行分错误级 `ChatEventTone` + `failureLabel`、中转结果后重拉会话、
  一轮无产出时查 diagnostics 补失败行、`notice` 接 `done/question/view/group`）。
  详见 [bugfix-chat-2026-11-09.md](bugfix-chat-2026-11-09.md)。
- ☑️ **对话界面与网页版对齐（专项，2026-11-09）**：目标「网页版有的，原生必须有」，
  做完才去掉网页版入口。清单见 [web-parity-chat.md](web-parity-chat.md)。
  本轮已补：`divider`（时间分隔）、`view`（只读视图卡）、`answer(questions)`（问答回执卡）、
  `user.images` 计数、`bot.whole`（长文附件 + `file-text` 显示全部）、
  `answer(views)` 回放（`AnsweredViewCard` 按当时填写只读回放）、
  中转类事件行文案核对（群聊工具从结果解析群名，与网页版同一正则；`ToolEventLineTest` 覆盖）。
  清单已收口：群聊时间/耗时、BTW 旁白卡、reactions 三条按 2026-11-09 决定**有意不做**
  （理由见清单末尾），余下全部对齐 → **网页版入口可以去掉了**。

### 名字胶囊面板与二级弹层统一（2026-11-11，第二十二轮）

- ☑️ Bot 名字胶囊面板去掉「复制整段对话」（`chat_copy_transcript`），只保留「设置详情」入口；
  群面板同步去掉，`onCopyTranscript` 参数与 `copyTranscript()` 一起清除。
- ☑️ 面板里的权限选择改成**设置页同一套**：文案优先 `SettingsCopy.permissionLabel/Hint`（服务端英文名只作兜底），
  选中控件换成 `RadioButton`，危险档用 `warning` 副标题色。
- ☑️ 二级弹层全部改底部面板（`SettingsSheet`）：`TextEditDialog`（Bot 做什么 / 名称 / 角色等）、
  `MemoryPanelDialog`（含改一条 `MemoryEditSheet`）、`FolderPickerDialog`（文件/工作目录）、
  `SchedulesSheet` + `NewScheduleSheet`（定时任务）、群设置页的 `ListPickDialog`。
- ☑️ 群名字胶囊面板的「记忆」直接打开记忆面板（不再跳完整设置页）。
- ☑️ 「这个 Bot 做什么」有内容时只显示一次（标题单行省略），空的时候才显示提示，
  修掉标题与副标题各重复一遍内容的问题。
- ☑️ **记忆面板按 DS Bot 重做**（2026-11-11，第二十三轮）：
  - 两个页面：默认「记忆摘要」（`#` 标题 / 段落渲染、生成中骨架、过期淡到 55%、失败可重试），
    `···` 菜单进「全部条目」+「重新生成摘要」；条目页左上角返回。
  - 全部条目不分 chip，改成 `own` / `team`（群 → `group`）两段堆叠，标题右侧显示
    `已用 N / 上限字符`（新解析 `memory-view.limits.main`）；条目按 `scope:topic` 分组，
    右侧只有垃圾桶，点一下变「移除」再点才真删。
  - 空态改成居中圆底记忆图标 + 标题 + 说明。
  - **两种打开方式**：名字胶囊 → 底部面板（固定高度、只预览 + 能删，没有输入框）；
    Bot 设置 → 整屏（`Dialog`，多一个 `memory-ask` 的「问我或直接更新」胶囊输入）。
  - 打开面板发 `memory-view {summarize:true}`，`summarizing` 期间每 1.5s 轮询一次。
  - 直填新增/编辑（`memory-add` / `memory-edit`）在面板里去掉了，之后在 Bot 设置里单独做。
- ☑️ **修 `null`**：`org.json` 的 `optString` 对 JSON `null` 会返回字符串 `"null"`
  （`String.valueOf(JSONObject.NULL)`），记忆条目里的 `topic` / `by` / `added` / `source`
  和 `memory-view.error` 因此在界面上写满 "null"，删记忆时还会把 `"null"` 当 topic 发上去。
  新增 `JSONObject.textOrNull()`（`data/remote/EngineSettingsModels.kt`）并按它解析。
- ☑️ **设置页二级页改成整屏**（2026-11-11，第二十四轮）：新增 `ui/chat/BotSettingsPages.kt`
  （`BotMemoryPage` / `BotSchedulesPage` / `BotFolderPage` / `BotNoticePage`），
  一律走我们的 `SettingsPage` 顶栏 + 导航里现成的 iOS push/pop 动效
  （`DshmBotApp.kt` 新增 `bot-page:<id>:<page>` 这一层）。设置页里点「记忆 / 定时任务 /
  工作文件夹」和群公告都推整屏页，不再弹底部面板；**名称 / 角色仍然是底部面板**
  （单行输入不值得做成整页）。名字胶囊弹出的面板维持底部面板不变。

### 主 Bot 全链路补齐（2026-11-11，第二十五轮）

- ☑️ 数据链路：`state.mainBotIds` 解析到 `BotSummary.isMain` → `ChatRowUi.isMain`
  （DS Bot 允许**同时多个**主 Bot，`mainBotIds` 是数组）。
- ☑️ 主界面长按面板：普通 Bot 显示「设为主 Bot」；主 Bot 且团队里不止一个时显示
  「取消主 Bot」；主 Bot 不再显示「删除」（服务端也会拒绝删主 Bot）。
  新增 `HomeViewModel.setMain`（`set-main {id, main}`）与 `DshmBotApp.homeSetMain`，四个
  `HomeScreen` 调用点全部接上。
- ☑️ 设置页「主 Bot」一档改成三态：唯一主 Bot → 只读提示「要换人去另一个 Bot 设置里设它为主」
  （对齐 DS Bot `onlyMain` 的「Change」）；多主 Bot 之一 → 可「取消主 Bot」；
  普通 Bot → 「设为主 Bot」（设为主时排到第一位 `primary:true`）。
  `BotDetailsViewModel.setMain` 支持 `main` 开关；主 Bot 的「删除」栏整段隐藏。
- ⬜ 差异（有意保留）：DS Bot 用 Switch 行、行上还有「Main Bot」标签和头像主色环；
  我们用行式按钮 + 文案提示，主界面行暂不加主 Bot 标记。

### 流式与消息菜单（2026-11-12，第二十六轮）

- ☑️ 活动状态文案加 DS Bot `bt-shimmer` 的扫光笔刷（2.2s 一轮、整轮连续，无空档）。
- ☑️ 流式渲染对齐 dsh-Go：流式文本按 50ms 合并推送（不再每 token 全页重组）；
  流式气泡跳过行内 markdown 扫描；`inlineMarkdown` 加 LRU 缓存。
  实测 7.4KB markdown 的块解析只要 0.30ms/次，瓶颈是每帧重建行内样式。
- ☑️ 长按两种气泡统一走 `MessageActionSheet`（预览 + 复制/选择文字/分享，用户气泡多
  「编辑后重发」）；`SelectTextSheet` 按 dsh-Go `showSelectTextSheet` 做成 85% 底部面板
  （回复按 markdown 画、用户消息纯文本，标题右侧「复制全部」）。
- ☑️ **修「选择文字串到别的气泡」**：全局选区手势原来用 `position.shard.messageId`
  反查消息；引擎 `displayItems` 很多 item 不带 `id`，`scope:seq` / `history:index` 在
  刷新、缓存、多段分页后会撞车，于是反查到别的气泡。新增 `BubbleBoundsRegistry`：
  每条气泡登记自己的窗口矩形和 `MessageMenuTarget`，长按按手指坐标（重叠时取面积最小）
  直接拿到被按的那条内容，不再经过 messageId。
- ☑️ **修「有表格的消息滑动卡顿」**（2026-11-12）：
  - `BubbleBoundsRegistry` 改用普通 `LinkedHashMap` 存 `LayoutCoordinates`，坐标在长按
    那一刻现算。之前用 `SnapshotStateMap`，滚动时 `onGloballyPositioned` 每帧对所有
    可见气泡写快照状态，表格越长可见气泡越多、越明显。
  - 表格去掉 `IntrinsicSize.Min` + `fillMaxHeight()` 的竖向分隔线组合（每行都要做一次
    固有测量），改成在行背景 `drawBehind` 上按列宽直接画竖线，外框/横线/列间线不变。
- ☑️ **表格仍卡的根因：聊天列表里的旧文字选区系统整套去掉**（2026-11-12）：
  每个表格单元格都通过 `ShardText` 注册一个选区分片（`mutableStateMap` + `onTextLayout`
  写状态），滚动时表格单元格越多越重。长按既然已经统一走 `MessageActionSheet` /
  `SelectTextSheet`（面板内用系统 `SelectionContainer` 选字），
  `ChatMessages` 里就把 `LocalMinisSelectionController` 置空，并撤掉
  `MinisSelectionToolbarHost`（复制 / 加入输入框 / 复制全文）、把手、拖拽跟踪、
  `minisTextKitSelectionGesture`；长按改成列表上一个轻量 `detectTapGestures`，
  按 `BubbleBoundsRegistry` 的坐标认人。`selectionRootCoords` 也改成普通数组引用，
  不再 `mutableStateOf` 每帧写快照。

### ⬜ 待办：含表格的对话滑动仍有一顿一顿（2026-11-12，**未修，用户要求先记录**）

- **现象**：对话里有 markdown 表格时上下滑仍会一顿一顿；纯文字对话没有。
  2026-11-12 用户反馈，明确要求「先记录，现在先不改」。
- **已排除**（前两轮已做，仍有残留卡顿）：`BubbleBoundsRegistry` 的快照写入、
  表格的 `IntrinsicSize.Min` + `fillMaxHeight()` 固有测量、聊天列表里 MinisTextKit 的
  toolbar / 把手 / 拖拽跟踪 / 手势整套；也不是引擎 / SSE / 模型速度问题。
- **剩余根因（只读排查结论）**：`ShardText`（`ui/chat/MarkdownText.kt`）在
  `LocalMinisSelectionController == null` 时**仍然无条件**给每个文本片段挂
  `mutableStateOf<TextLayoutResult?>` + `onTextLayout`、`onGloballyPositioned`、
  `drawBehind`，以及 `remember(id, layoutResult, plainText, ...)` 构建 `TextShard`。
  `RegisterSelectionShard` 此时会直接 return，这些开销却一个没省。
  `TableCell` 走的正是 `ShardText`，表格有 `行数 × 列数` 个单元格，
  滚动/复用时会成片写状态 —— 所以只有表格明显，普通气泡只有几个片段感觉不到。
- **次要叠加项**（不是主因）：底部 `Modifier.blur(40.dp)` 渐变遮罩会让高对比表格线
  滑过时更显「闪」，但不产生重组；`MarkdownTable` 的 `BoxWithConstraints` +
  每行 `drawBehind` 竖线 + `horizontalScroll(rememberScrollState())` 仍是 LazyColumn
  里最重的单个 item。
- **建议修法（等用户发话再动手）**：`ShardText` 按 `controller == null` 分两条路 ——
  聊天列表走极简 `Text`（无 `onTextLayout` / `onGloballyPositioned` / `drawBehind` /
  shard 构建）；`SelectTextSheet` 用系统 `SelectionContainer` 选字，本就不需要 shard。
  改动集中在 `MarkdownText.kt`，不动交互与样式。
- **验收**：含大表格的对话快速上下滑不掉帧；长按 →「选择文字」面板仍能拖选复制；
  表格外框 / 横线 / 列间线视觉不变。

### 流式渲染推倒重来：完全对齐 DeepSearch（2026-11-12，第二十八轮）

用户要求按 `D:\program\DeepSearch` 的流式架构重做，不好可回滚（已 git）。
本轮只替换流式回复渲染链，**对话输入框 `ChatComposer` 一行未动**。

- ☑️ **侧通道**：新增 `BotChatViewModel.streamingText: StateFlow<String>`；
  高频 token 不再写 `_uiState`，只有 `ChatMessages` 的流式气泡订阅它，
  顶栏 / 输入框 / 历史气泡不再跟着重组（DeepSearch `_streamingById`）。
- ☑️ **双路 flush**：按总长度自适应间隔（30/50/80/120/160/200ms）+ 换行快车道
  （短文档新满 50 字且带 `\n` 立即推）+ trailing job 保证最后一段落地；
  状态跨 token 保留，不随重组重置（DeepSearch `updateAssistantMessage`）。
- ☑️ **按块冻结 + 后台解析**：`splitStreamingFragments` 按空行 / 代码围栏切块，
  `MarkdownParseCache` 进程级 LRU 缓存块解析；live tail 在 `Dispatchers.Default`
  上解析后回主线程（DeepSearch `MarkdownBlockBody`）。
- ☑️ **增量行内**：`inlineMarkdownIncremental` 对 ≥1500 字的 live 段落找安全换行边界，
  闭合前缀走缓存、只重扫未闭合后缀（DeepSearch `inlineIncremental`）。
  **流式期不再跳过样式**，`[文字](url)` / 粗体 / 行内代码实时渲染。
- ☑️ **裸链接**：`buildInlineMarkdown` 新增 `http(s)://` 裸 URL 分支，
  包成 `LinkAnnotation.Url` + 主色下划线；修掉截图里
  `https://shishifenxi.com/...` 被当普通文本折行的问题。
- ☑️ **表格稳定**：`TableHScrollStates` 按 `shardId + 表头` 复用 `ScrollState`，
  流式重解析 / live→frozen 切换时横向偏移不回弹（DeepSearch `TableHScrollStates`）。
- ☑️ **表格滚动开销**：`ShardText` 在 `LocalMinisSelectionController == null`
  时走极简 `Text`，不再挂 `onTextLayout` / `onGloballyPositioned` / `drawBehind` /
  shard 构建；这是上一轮记录的「表格还卡」根因，一并修掉。
- ☑️ 单测 `StreamingMarkdownTest`（分块 / 节流单调性 / 裸链接可点）。

- ☑️ **消息内分片 + 逐词淡入 + 慢解析埋点**（2026-11-12）：
  - 一条消息按空行 / 代码围栏切成片段，每片是独立 `MarkdownFragment`
    各自 `produceState` + 进程级 LRU；前缀命中缓存后 Compose skip，只有 live tail 重画。
  - `StreamingFade.kt`：新追加的词 α 0→1（350ms / 90ms 错开 / 300ms 窗口），
    超过 160 词整批不透明；frame 循环只在有活跃区间时跑，空闲挂起。
  - `StreamRenderLog`：单次解析 > 80ms 才 `Log.d("dshm-stream", …)`
    （layer / chars / ms / thread），平时静默。
- ⬜ 仍暂未搬：live 超长片段降级纯文本（DeepSearch 阈值 40K 字，我们当前体量用不到）、
  `MarkdownDocument` 那种 LazyColumn 全文档预览（要有独立 .md 预览页才做）。

### 表单输入框统一（2026-11-12，第二十七轮）

- ☑️ 以新建 Bot 的「名字 / 角色」那套 M3 `OutlinedTextField` 作为**全项目表单输入框基线**：
  `dshmFieldShape()`（`radius.md`）+ `dshmFieldColors()`（Dshm 边框 / 底色 / 游标 /
  label / placeholder / 错误色）抽到 `ui/settings/SettingsComponents.kt`。
- ☑️ `SettingsInput` 内部由手搓 `BasicTextField` 换成 `OutlinedTextField`
  （浮动 label + M3 聚焦态），保留原签名，14 个调用点（设置弹层、账号、密钥、
  浏览器设置、定时任务、Bot 设置二级页、模型供应商等）全部跟着统一。
  `ProviderField` 传 `label`，字段名作浮动 label、提示仍作 placeholder。
- ☑️ 散落的裸 `OutlinedTextField` 全部接同一 shape/colors：新建 Bot 三个字段、
  Bot 说明内联编辑、新建群名称、模型/推理强度 combobox、定时任务目标 combobox、
  密钥「谁能用」combobox。
- ☑️ **明确不动**：对话输入框 `ChatComposer`（附件缩略图 / 语音 / 发送键 / blur 渐变）
  及复用其外壳的记忆「问我或直接更新」输入框，是专门做的复合控件；
  SearchScreen / BotBrowserScreen 地址栏 / PendingDock 这类场景化输入也保持原样。
- ☑️ **修「Bot 设置里的定时任务还在手输时间」**（2026-11-12）：
  `SettingsScheduleTimePicker` 从 `ScheduledTasksScreen.kt` 抽成 `internal` 的
  `SchedulePickerField` / `ScheduleTimePickerField`（24h 时间轮，只读点选字段也换成
  刚统一的 M3 `OutlinedTextField` 描边样式）。Bot 设置 → 定时任务整屏页
  （`BotSettingsPages.kt`）和名字胶囊 → 定时任务底部面板（`BotMemoryFolderDialogs.kt`）
  原来都是 `SettingsInput` 手打 `09:00`，现在三处共用同一个选择器，不再出现手输时间。
- ☑️ **模型 → 添加渠道表单微调**（2026-11-12）：
  - 「获取模型」行正下方的 `SettingsRowDivider` 去掉（后面已选模型 / 手动添加本来
    各自带分割线，重复一条显得断开）。
  - 「保存」按钮从 `SettingsPage(bottomBar = ...)` 固定浮底改成表单滚动内容里的一张
    `SettingsSection` 白卡，跟其它卡片一起滚；文案（保存中 / 等待引擎连接 / 保存）
    和禁用逻辑不变。

### 设置深页返回修复（2026-11-13）

现象：设置里的内层页（渠道详情/编辑、插件清单、namespace 表单、应用名单）按**系统返回**
会直接弹掉一级设置栈、回到上一级设置页，而不是回到该页的上一层。

根因：全 App 只有两个 `BackHandler` —— `DshmBotApp.kt` 顶层（弹设置栈 / 关设置页）
和 `BotBrowserScreen.kt`（内层页，正确样板）。这几个页面自己带内层页（`return` 早退或
`AnimatedContent` 切换），但只接了顶栏返回按钮，没注册内层 `BackHandler`，系统返回被顶层吃掉。

- ☑️ 4 个内层页补内层 `BackHandler`（栈空时不拦截，交回顶层）：`ModelProvidersScreen`、
  `EngineSettingsScreen`（插件清单 / 子代理 / namespace）、`PluginsScreen`（插件表单）、
  `PhoneControlSettingsScreen`（应用名单）；
- ☑️ `ModelProvidersScreen` 的 `page: ProviderTarget?` 改成**真栈**（新 `ui/settings/ProviderNav.kt`，
  `ProviderTarget` 移过去并改 `internal`）：返回永远弹一层，所以
  **详情 → 编辑 → 返回回到详情**（原来直接回列表）；列表「添加渠道」进的表单返回则回列表；
  保存/删除落点保持原样（表单保存成功回列表、详情内改模型成功回详情）。
  `ProviderNavTest`（7 例）锁住这几条栈序列。
- ☑️ 顺带：定时任务页的「新建任务」内联表单接返回（先收表单再退页）。
- 约定（写进 design-system 已知坑）：**设置内层页必须注册内层 `BackHandler`**，样板见
  `BotBrowserScreen.kt`；否则系统返回会跳级。
- 验证：75 测试类 / 435 用例全绿，lint 零 warning，`assembleDebug` 与插桩测试编译通过；
  真机手动过一遍上述路径（顶栏返回与系统返回行为要一致）。

### 密钥列表首字母图标（2026-11-13）

- ☑️ 「团队 → 密钥」列表行左侧补首字母方块，和能力 / 连接器列表同一套
  （`TeamSettingsScreens.kt` 的 `InitialMark`，原来是 `CapabilityMark`，改名成中性名字）；
- ☑️ 同卡片底部「添加密钥」行补同尺寸加号块，否则标题会和上面几行错位；
  顺手把模型渠道页里私有的 `PlusTile` 提成共享组件 `SettingsAddTile()`
  （`SettingsComponents.kt`），两处共用、不再各写一份。

### 修「流式期间用户气泡错位」（2026-11-13）

现象：大模型流式输出时，用户自己发的气泡会跑到错误位置（本轮的工具/事件行插到了它**上面**）；
重新进对话或重新加载历史后又恢复。

根因（`BotChatViewModel`）：乐观用户气泡的占位 seq 写成 `maxSeenSeq + ++optimisticSeq`，
`optimisticSeq` 每次发送都累加且从不清零。`mergeTimeline` 按「插到第一条 seq 更大的条目之前」
定位，占位 seq 一旦高过**本轮**的真实 seq，本轮事件就被插到用户气泡前面 —— 发得越多越明显
（新进对话/新 ViewModel 计数归零，所以看起来「过一会又好了」）。

- ☑️ 占位 seq 改成 `maxSeenSeq + 1`（只比「已加载历史」大一档），删掉累加计数；
- ☑️ 服务端回显虽然被 `seen` 去重，但它带着真实 seq：`adoptLocalSeq()` 把真 seq 认领回本地气泡，
  之后本轮事件按真 seq 插在气泡后面，位置自愈；
- ☑️ `BotChatTimelinePositionTest`（3 例）先复现（本轮事件被插到气泡上方、占位 seq 累加）
  再验证修复：本轮事件落在最新用户气泡之后、占位 seq 不累加、回显认领真 seq。
- 验证：76 测试类 / 438 用例全绿，lint 零 warning，assembleDebug 通过，真机确认。

### 修「用户气泡错位」的残余成因（2026-11-14，高强度复发 → 已修）

76e25fc 之后高强度使用仍复发，逐链路排查出三个残余成因（本轮全部已修）：

1. **feed 条目无条件追加到列表末尾**（主因）：排队/steer 的消息落日志晚
   （DS Bot 把 prompt 放 `agent.inbox.nextTurn/nextStep`，`user/message` 事件在**被消费时**
   才写日志拿 seq），占位气泡挂在末尾的窗口里，先到的收尾文字/回复被 `entries + entry`
   甩到它后面；`mergeTimeline` 只重插事件行、从不动已有条目的相对顺序，错位冻到下次重载。
   修法：feed `message` 落地改走 `mergeTimelineEntry`（有 seq 插到「第一条 seq 更大的条目」
   之前，无 seq 追加末尾；对齐官方 runtime 的 insertAssistantSettlement）。
2. **连发占位碰撞**：HTTP 一回 `sending` 就放行下一条，第一条的回显未到时两条拿到
   同一个占位 seq（= `maxSeenSeq+1`）。修法：新增 `maxLocalSeq` 记本地已占位最大值，
   占位取 `max(maxSeenSeq, maxLocalSeq) + 1`，回显认领真 seq 时同步抬升。
3. **发送序 ≠ 日志序（steer 与排队混发）**：steer 落日志在下一步（秒级）、排队在下一轮
   （可分钟级），认领只改 seq 不改表序，两者的相对顺序错到底。修法：认领后按 seq
   **稳定重排**——无 seq 条目原地不动（保住 `mergeTimeline`「不动无 seq 段」的约束），
   有 seq 的升序归位、同 seq 保持原序；并删掉 `seq <= current.seq` 的拒绝分支，
   **向下也认**（占位偏高的场景自愈）。

配套：**轮末自愈**——`watchForRunEnd` 收尾时若 `unclaimedRequests` 还有没等到回显的
本地气泡就 `scheduleReload()`，自愈窗口从「下次任意重载」缩到「本轮结束」。
`BotChatTimelinePositionTest` 改写为 6 例：S1 排队消息+收尾文字归位、S2 连发防碰撞、
S3 混发按日志序归位，加原有 3 例（本轮事件在气泡后、占位不漂移、回显认领）。

### 产物卡口径定案与落地（2026-11-14，已修；推翻同日早先的「策略 A」）

产物卡「要等轮末 / 中途才出现」的调查历史见下两节；两路基线调查（官方网页 `client.js`、
官方 npm 包）+ 用户拍板后，本轮落地。**最终口径 = 跟官方走：轮闭合（`turn/end`）才出卡、
位置固定在轮末 `endSeq`、出卡后不再移动。**

- **撤销 76e25fc 的策略 A（present 一到就出卡 + 事后前移）**：官方 `useFileMentions`
  只在 `turn.status === "closed"` 且节点是轮末 finalNode 时画（`client.js` `useFileMentions`），
  轮内交付只有 "Delivering/Delivered" 工具行；提前出卡是我们自创的，还带一次位置跳动。
  `deriveArtifacts` 的 eligible 改回仅「已完成的轮」，卡位置固定 `seq = trace.endSeq`。
- **三路兜底砍掉 OUTPUT（扫盘）**：WRITTEN / LINKED 保留（对齐 dsh-Go 的 1-2 级兜底）；
  OUTPUT 假阳性大（时间窗内任何被碰过的文件都成卡），官方也没有这一路
  （`dsh-client-ui-deliverables` 只认 present 声明 + 回复内链接）。
  `scheduleOutputsScan` / `scanWorkspaceOutputs` / `OUTPUTS_CACHE_MS` / `WORKSPACE_SCAN_*` /
  `DEFAULT_WORKSPACE` 整条链路删除。
- **present 轮内工具行**：`present` 进 `FOLLOW_TOOLS`，call 时挂「正在交付 N 个文件…」
  （官方 Delivering），result 到了换成「已交付 N 个文件」（官方 Delivered）；
  快照窗口边缘的悬空调用会把「正在交付」行收掉。
- **DELIVERED 聚合不变**：同一轮多次 present 按 path 去重、首现定序、描述取最新。
- 「模型没 present 也没写链接」的边界从今天起的表现是**不出卡**（与官方一致）；
  治本靠下节的提示词方案（已上线，图片场景实测有效）。
- 测试：`BotChatArtifactLiveTest` 按新口径改写 6 例（轮闭合才出卡+位置=endSeq、
  present 不提前出卡、多次 present 聚合、present 工具行两态、follow 重订、永久失败不重试），
  `ArtifactDerivationTest` 删 OUTPUT 用例、反转「present 即出卡」用例。

### ⬜→☑️ 定位史：交付产物卡要等整轮结束才出现（2026-11-13 定位，当日按 A 修过一轮，2026-11-14 定案推翻）

**现象**（2026-11-13 用户两次校订）：Bot 交付文件后，私聊里那张产物卡**不是马上出现**；
**等一会儿会自己出来**，重新进对话也能立刻看到。（用户原话：这个图片显示产物又不及时显示。）

**根因（已定位，是 9381887 引入的回归）**：产物卡从「声明即出」变成了「等整轮结束」。

- **旧实现**（`9381887^`，`BotChatViewModel.kt:687`）：
  ```kotlin
  val seq = turnEndSeq[turn] ?: presentedSeq[turn] ?: writtenSeq[turn] ?: return@mapNotNull null
  ```
  位置优先 `turn/end`，**没有就退回 `presentedSeq`（`present` 声明那一刻）** ——
  所以 `present` 一到就出卡，不等整轮跑完。
- **新实现**（`9381887` 起，`ArtifactDerivation.kt` + `artifactEntries()`）：
  必须有一轮已经 `turn/end` 才推导，`deliverables/presented` 到了也不画。

**真机证据**（`adb shell run-as com.dshm.bot cat shared_prefs/bot-chat-events:<sessionId>.xml`，
一份 1.5MB 的真实事件记录，604 条）——turn 256 的事件序列：

| seq | 事件 |
|---|---|
| 6042 | turn/start |
| 6048 | tool/call |
| **6049** | **deliverables/presented**（`/root/.dsh/bot/workspace/agnes_cat.png`） |
| 6050 | tool/result |
| 6053 | assistant/message（收尾文字） |
| **6055** | **turn/end** ← 卡到这里才出现 |

事件完整送达（`turn/end` 也到了），所以不是链路/订阅问题；是**推导门槛**卡住了。
模型 `present` 之后如果还要接着干活/等子代理，这段间隔就越拉越长 → 用户感觉「要等一会儿」；
重新进对话时 `eventsCache` 里已经有 `turn/end`，所以**立刻**就有 → 这也解释了「重进就马上有」。

**当时选了策略 A 并于 76e25fc 落地**（已结束或已声明交付都出卡；轮内贴 present、
end 后前移）。两路基线确认官方从不这么做（见上节定案），2026-11-14 推翻、回退到
「轮闭合才出卡、位置固定轮末」。当年顺带确认的两笔遗留也已处理/在案：

- 「`ensureFollow` 的 `onEnd` 之后没人重订、follow 可能永久死」→ **2026-11-14 已修**
  （指数退避重订，见气泡残余成因一节的配套说明与 `BotChatArtifactLiveTest` 两例）；
- `ChatEventsCache.MAX_CHARS = 1MB` 真机已接近上限 → 仍在案（长会话治理，P2 单独做）。


### 产物卡延迟：二次定位（2026-11-13，真机实锤，已修）

第一次定位（「卡要等 `turn/end`」）只解释了文档那一半。用户用修复后的包再测：**图片卡还是慢，文档卡正常**。
从设备拉 `bot-chat-events` 缓存逐轮对比，真正原因是**图片那轮压根没有 `present` 声明**：

- **turn 291（文档，正常）**：模型调了 `present` → `deliverables/presented` → `DELIVERED` → 卡立刻出；
- **turn 290（图片，慢）**：
  1. `bash` 用 node 脚本调 agnes 接口把图下到工作区（`meta={"cwd":...}`，**无 `meta.path`**）；
  2. `read_image` 只是**读**图验证（`meta.path=hotpot.png`，但它是读不是写）；
  3. 最终回复里写的是**行内代码** `` `/root/.dsh/bot/workspace/hotpot.png` ``，**不是 markdown 链接**；
  4. **没有 `present`**。

  于是三个来源全落空：`WRITTEN` 只认 `write` / `str_replace_editor`；`LINKED` 只解析 `](...)`；
   只剩 `OUTPUT` 兜底 —— 要 **等轮末 + 5 秒 TTL 的异步扫盘** 才出卡 → 用户感觉「要等一会儿」。

**先试过的客户端兜底（已回退）**：`linkedPaths()` 曾增加「行内代码里的绝对路径也算产物」
（`INLINE_PATH`）。它能出卡，但会**掩盖「模型到底有没有调 `present`」**，用户要求去掉兜底做干净 A/B，
所以该改动和两条对应测试已回退（现在只认 markdown 链接）。

**最终修法（已真机验证）**：走**提示词**——`GuestSetup` 的 AGENTS.md 增加「交付文件」一节，
明确「文档/表格/演示/**图片/视频/音频**都算交付物，交给用户时调 `present`」。
用户实测：**图片产物卡现在实时出现**（= 模型在这一轮调了 `present`）。详见下一节。

**边界（2026-11-14 更新）**：如果模型在回复里完全没提那个文件路径、也没 `present`，现在**不出卡**
（`OUTPUT` 扫盘兜底已随口径定案整条删除，与官方 `dsh-client-ui-deliverables` 一致）；
治本靠上面的提示词方案（已上线）。WRITTEN / LINKED 两级事件推导兜底保留（对齐 dsh-Go）。

### 产物卡全灭 + retry 行堆山（2026-11-14，真机取证 + 已修）

用户高强度做文档时发现：**产物卡全都不出现了**，同时「已要求重试写成文本的工具调用」一排排堆很高。

**取证（adb run-as 读 bot-chat-events 缓存，方法见上）**：
- 最近 30 轮 `present` 声明 **0 条**、`workspace/changes` 0 条，最近窗口的工具调用全是
  `bash`（18 次）——文档全是模型用 bash 写的，没有一次 `present`；
- 设备上的 `files/runtime/rootfs/root/.dsh/AGENTS.md` **没有交付规则**：交付规则 11-13 加进
  `GuestSetup` 后**只在首次 provision 时写入**，且旧保护逻辑「文件不以我们标记开头就整段跳过」
  把这台设备的宿主基础文件放行了——规则从未落到 live 文件上（运行时升级冲掉重装同理）。
  模型没有指令 → 不调 `present` → 扫盘兜底一砍，bash 写的文件三个来源全够不着 → 卡全灭。

**修法（两处）**：
1. `GuestSetup` 改**幂等 upsert**（每次引擎启动跑）：无文件/自有标记 → 写入或规则更新重写；
   用户文件已带我们的块 / 已有同名交付小节 → 不动；其余（宿主基础文件、升级冲掉的旧文件）→
   **末尾追加**规则块，原文保留。自愈场景：运行时升级冲掉 AGENTS.md 后下次启动自动补回。
2. `ArtifactDerivation` 的 WRITTEN 对齐官方 `produced` 口径（npm 实锤：
   `mutationPath`/`mutationResultPath`）：`write` / `edit` / `str_replace_editor` 变更类命令
   （create/str_replace/insert）的**成功结果全算，编辑/覆盖也算、不看扩展名**——
   文档工作流的 .md 就靠这一路；此前「只认 create + `isArtifactKindPath` 过滤（Markdown 不算）」
   是 dsh-Go 的取舍，官方没有。LINKED 同步去掉扩展名过滤。

**retry 行堆山**：`retry` 事件行官方也是一条一行（`cells.js:619`），这是有意的偏离——
渲染前把**连续且完全相同**的事件行折叠成一条「 ×N」（`EventLineFold`，relay/带目标/带图标
行不折，代表条目取组内第一条保证 LazyColumn key 稳定）。

## 🎯 大目标：让 DSH / DS Bot 适配我们（2026-11-13 用户定，**未排期**）

> 用户原话（要点）：**「我们要本地化魔改一下 DSH 和 DS Bot。DSH 本身是插件思想，可以随时替换和魔改。
> 我们不仅可以完美继承它的能力，还可以魔改出我们想要的。这比我们不断打补丁适配他们强太多了。
> 所以不是我们魔改他、是打补丁适配他吗？是让他适配我们。」**
>
> 关系反转：**从「追着上游打补丁」→「插件化接管，让上游能力长成我们的形状」**。
>
> **决定（2026-11-13）：确认按这个方向走 —— 基于 DSH 的插件思想魔改它，让它适配我们。**
> **本轮只记录，不改任何代码**；动手时从下面「分阶段」的第 1 步开始。
>
> **再度确认（2026-11-13）：核心是把 DSH 和 DS-Bot 这两个「底层」本身魔改成我们要的样子，之后不再打补丁适配它们。**
> 有新问题就回到这两个底层去改，而不是一遍遍 patch 上游。现有 13 个 `runtime/patches` 属于过去的方式，
> 会被「分阶段」第 1 步逐个消化掉。

### 可行性已确认（2026-11-13 研究 `@deepseek-ai/dsh@0.2.2-rc.1`）

- DSH 的 `dsh` npm 包只是 20 个文件的引导壳；**所有能力都是独立插件包**：
  `dsh-tool-present` / `dsh-tool-bash` / `dsh-tool-ask-user` / `dsh-agent-instructions` /
  `dsh-persona` / `dsh-system-prompt` / `dsh-skill*` …（完整清单见
  [dsh-plugin-architecture.md](dsh-plugin-architecture.md)）。
- profile 是「**有序 patch 层栈**」：`bundles → profile/cordis.patch.yml → $DSH_HOME/cordis.patch.yml → --patch`；
  `dsh plugin` 直接转发 pnpm —— **加/删/替换插件是官方一等操作，不用动 `node_modules`**。
- 提示词有四级扩展点：AGENTS.md / `ctx.systemPrompt.section(...)` / `system-prompt/assemble` waterfall /
  替换官方插件包。**每个包还自带 `locale/*.json`（含 `zh`）**，多语言骨架现成。
- 实测原因之一就在上游文案：官方 `present` 的工具描述写着
  「especially **Office documents**, spreadsheets, and slide decks」——
  这就是模型给 docx 调 `present`、给图片不调的直接原因；自写 `present` 插件即可改掉。

### 现状差距

- 我们目前只有 **13 个 build-time patch**（`runtime/patches`，见 `assets/runtime/manifest.json` 的
  `dshPatches`），全部打在 `node_modules/@deepseek-ai/*/lib/index.js` 上，**每次上游升级都要重打 + 回归**。
- DS Bot 插件（`assets/ds-bot`）同样是「改源码 + merge」，没有走插件化替换。
- 提示词方面只注入了「手机操控」一段 AGENTS.md（`DSH_PHONE_CONTROL` / `DSH_PHONE_OWNER`）。

### 分阶段（待排期）

| 阶段 | 做什么 | 产出 |
|---|---|---|
| **0（已完成）** | 摸清 DSH 插件模型、确认可替换性 | [dsh-plugin-architecture.md](dsh-plugin-architecture.md) |
| **1（分析已完，见 [runtime-patches-audit.md](runtime-patches-audit.md)）** | 逐个评估 11 个实际存在的 runtime patch（manifest 里写 13 个是陈旧账目） | 结论：**升级 0.2.2 可直接删 2 个；`zz-direct-runtime` 疑为 proot 下的死代码可删；2 个升级时复核；2 个可迁自研 bridge；4 个必须继续 patch**。理想结果 11 → 4–6 个 |
| 2 | 自写插件接管模型可见面：`present`（中文描述 + 图片/视频也算交付）、自定义 systemPrompt section、按 Bot 的 persona | 产物卡、行为规则可控 |
| 3 | 提示词本地化：AGENTS.md / section / persona 三层写中文产品文案（参照 dsh-Go 的 AGENTS.md、安全简报、工具目录写法） | 中文优先的 agent 行为 |
| 4 | DS Bot 插件同策略：`prompt.js` / `tools.js` 走扩展或替换，而不是 merge 上游 diff | 升级成本可控 |
| 5 | 工具集裁剪：profile 里移除不用的 `experimental-*` 包，减少工具 schema token | 更少的模型选择困难 |

### 约束（研究结论）

- **「魔改」不等于「没有适配成本」**：成本形态变了 —— 从「上游升级后重打 diff」变成
  「把上游改动 merge 进我们自己的副本 / 适配我们的插件」。所以**改动要尽量压在插件层**
  （profile + 自写插件），必须 fork 的部分越少越好；fork 一旦变大，升级就是不可控的 merge。
- profile 是 per-installation，App 启动要写/更新它，且不能覆盖用户的 `cordis.patch.yml`；
- 替换官方包要跟 `name` / `inject` / 输出契约，用 `engines.dsh` 卡版本；
- `complete` section 全 scope 只能一个；同名 section / tool 重复注册会抛错（替换前要确保官方那个没加载）；
- `locale/*.json` 只是包元数据，**工具描述是硬编码英文**，想中文化只能改插件。

### 立即可做的一小步（成本最低，不是本轮）

在 AGENTS.md 里加一条「交付任何文件（含图片/视频）都调用 `present`」——零升级成本，
能先验证「模型行为可被提示词稳定驱动」，再决定要不要走插件化替换（自写 `present`）。


### 交付规则进 AGENTS.md（2026-11-13，提示词第一档）

背景：图片产物卡走兜底（`present` 不调）的根因是官方 `present` 工具描述偏向
「especially Office documents, spreadsheets, and slide decks」。
按「让 DSH 适配我们」的策略，先走**成本最低的提示词层**（AGENTS.md，零升级成本）：

- `data/runtime/GuestSetup.kt` 的 `AGENTS.md` 增加一节「交付文件（DSHM Bot）」：
  交付任何文件都调 `present`（**文档/表格/演示/图片/视频/音频都算**），
  只声明成品、不要只在回复里贴路径；
- 仍是 `dshm-bot:managed` 标记 + 用户自己改过不覆盖；每次引擎启动重写，升级即生效；
- 新增 `GuestSetupTest`（4 例）：交付规则写入 / 自有旧版被升级 / 用户自有文件不动 / 缺失目录能创建；
- 真机验证：装新包冷启动后，`run-as` 读 `files/runtime/rootfs/root/.dsh/AGENTS.md`，
  确认新章节已落盘。

**真机回归（2026-11-13，通过）**：去掉客户端行内路径兜底后让它生成图片，**产物卡实时出现**
（= 模型在这一轮调了 `present`），说明提示词第一档就够用，不必升级到 `systemPrompt.section`
或替换 `tool-present` 描述。若以后在别的模型/场景下失效，再按这条阶梯升级。

### DSH 升级 0.2.1-rc.4 → 0.2.2-rc.1：**暂缓**（2026-11-13）

升级本身有好处（上游已吸收 2 个补丁，见 [runtime-patches-audit.md](runtime-patches-audit.md)），
但用户明确「先不更新」。澄清：**完整 rootfs 构建本来就走 GitHub Actions 的
`runtime.yml`（ubuntu-24.04 runner），不需要本机 Linux**；本机只做 A（补丁重打/pins/lock/版本变更），
CI 做 B（打包）。不是"做不了"，是现在不做。升级清单与 pins 约束见 audit 文档末尾。

### 群聊成员 dock：有意偏离官方（2026-11-13 用户拍板）

现象：某个 Bot 在**自己私聊**里干活时，群聊输入框上方的成员胶囊也会冒出它的状态。

**这是官方行为，不是我们引入的 bug**：
`DS-Bot/ds-bot/src/client/member-status.js` 的 `memberWork` 写的是
`const doing = local ? {...} : work.bots?.[id]`，注释 "Work in this group wins over the Bot's
own chat or another group"，`dockMembers` 只藏「本群正在发言」的人，**不藏 `here=false`**。
插件 `turns.js:291` 的 `workView()` 也确认：`work.bots[botId]` 不带 `roomId` 时就是私聊工作
（带 `roomId` 才是「在别的群干活」；且 `work.bots` 每 Bot 只有一条，私聊优先）。

数据怎么合并（这是回答「单聊和群聊同时干活怎么显示」）：
- `work.rooms[roomId]` 按群独立，`work.bots[botId]` 按 Bot 全局一条；
- 官方 `local ?? work.bots[id]` → **本群的活优先**；本群没活时兜底用全局那条；
- 所以「本群也在干活」时只显示本群那份，不会显示两条；「本群没活、但它在私聊/别的群干活」
  才兜底显示（`here=false`）。

**用户判断：官方这个兜底不合理** —— Bot 在私聊里忙，跟当前这个群无关，不该让群成员 dock
冒状态。所以**我们有意偏离官方**：

- ☑️ `RoomModels.parseMemberStatus()`：`local` 优先；没有 `local` 时，`work.bots[id]`
  **只有带 `roomId`**（在别的群里干活）才保留，**纯私聊工作丢弃**。
  即：保留「在别的群忙」这个官方语义，只砍掉「私聊污染群聊」这一条。
- ☑️ 代码注释写明偏离点与理由；`RoomModelsTest` 3 例锁住口径：
  私聊工作不进群状态 / 在别的群干活仍显示（`roomId` 分支）/ 本群的活照常 `here=true`。
- 顺带核实同链路没有别的串源：群聊 `running` 只认 `room-progress`、活动行群聊只读
  `work.rooms[botId]`、`ourApproval` 群聊按 `roomId==botId` 判定。
- 验证：81 测试类 / 463 用例全绿，lint 零 warning，assembleDebug 通过。

### 个性化页：接了线但 App 不消费 + 修「点一下闪一下」（2026-11-13）

**「个性化里所有东西都没接线」的准确说法**：协议上是接了的 —— `BotPrefsViewModel`
走 `state` 读 `state.prefs`、`set-prefs` 写（引擎 `channel.js:185` 有 `case 'set-prefs'`），
值由引擎持久化、网页端与其它设备共用。但**这些 prefs 是网页端 UI 的偏好**
（DS-Bot `client/index.js` 用它们切主题/强调色/动效）；原生 App 用自己的
`Dshm` 设计令牌 + `ThemeMode`，全仓库只有 `SettingsScreen.kt:92` 读 `prefs.theme`
来显示「个性化」行右侧的副标题，其余（theme / accent / motion / thinking / width / layout）
**都没有作用到 App 界面或聊天渲染**。所以用户体感「点了没效果」是对的：
写进引擎了、别的网页端会变、本机纹丝不动。

> 产品口径这轮已定：**直接接线**（见下一节「个性化真接线」），不再标「本机不生效」。
> 本轮**只修闪烁**，不动接线那层。

**闪烁根因**：`BotPrefsViewModel.set()` 把 `saving=true` 写进 uiState →
`PersonalizationScreen` 的 `enabled = !saving` 让整页所有行先禁用再启用，
还额外插一行「保存中」→ 整页重组，Switch 和文字肉眼闪一下。

- ☑️ 修法：改成**乐观更新** —— `applyPatch()` 只改 patch 里那一个字段，
  不发 `saving`；成功用 `set-prefs` 回执覆写一次（引擎是唯一真值），失败回滚并给错误。
  删掉 `PrefsUiState.saving` 字段和「保存中」行。
- ☑️ 顺手修回执解析：`set-prefs` 回的是 `{"ok":true,"value":{...prefs...}}`，
  旧代码读 `optJSONObject("prefs")` 读不到、靠 fallback 整个对象蒙对；
  新增 `prefsFromResponse()` 同时认 `value` / `prefs` / 裸对象三种形状。
- ☑️ `BotPrefsTest` +3 例（共 6 例）：乐观更新只改一项 / null 重置为默认 /
  回执三种形状。
- 验证：81 测试类 / 467 用例全绿，lint 零 warning，assembleDebug 通过，真机确认不闪。

### 个性化真接线：prefs → 本机界面（2026-11-13）

上一节留的产品口径这轮定了：**不标「本机不生效」，直接接线**。用户原话是
「本机界面纹丝不动。那跟没接线有什么区别呀」—— 团队偏好只写到引擎里、本机不消费，
和没接线没有区别。

**映射表**（网页端语义 → 本机效果，色值全部取网页端 `client.js` 的 `themes.js`）：

| prefs | 网页端 | 本机落地 |
|---|---|---|
| `theme` | 4 套配色 CSS 变量 | `AppPalette` + `DshmColors.withPalette()`：背景/页面底色/卡片/气泡/正文/用户气泡/强调色整套换掉 |
| `accent` | 覆盖主题 accent + `accentInk` 选墨色 | 全局强调色（按钮/链接/用户气泡），墨色按亮度自动选深墨或白 |
| `motion` | `quiet` 关动画 / `lively` 加强 | `MotionProfile`：转场时长 + 6 处循环动画（思考脉冲、活动扫光、浏览器忙碌、引擎环、首屏骨架、对话骨架） |
| `thinking` | 回复上方画 `ThinkingRow` | 对话里画「思考过程」行 |

**故意不接的两个**：`layout`（气泡/扁平）和 `width` / `flatWidth`（气泡宽度）——
用户明确说过不要这两项（「不要气泡和扁平还有气泡宽度」）。本机有自己的气泡样式和
排版规则，属于设计系统的事，不跟网页端的布局偏好走。网页端团队改了这两个，
本机也不跟着变。

**实现要点**

- ☑️ `domain/model/BotPrefs.kt`（模型搬家）+ `domain/model/AppPalette.kt`（配色 id）；
  `data/settings/BotPrefsStore.kt` 是**进程内唯一真值**（`StateFlow<BotPrefs>`）。
- ☑️ `core/design/DshmPalettes.kt`：4 套配色色值 + `withPalette()` + `inkOn()`（移植
  网页端 `accentInk`）+ `parseAccent()`。`deepseek` 用**本机原生令牌**那套
  （团队没动过主题时界面和现在一模一样），另外三套是网页端原值。
- ☑️ `core/design/DshmMotionProfile.kt`：`MotionProfile` + `LocalMotionProfile` +
  `rememberLoopValue()`（`quiet` 时循环动画停在起始值）。
- ☑️ `core/design/DshmTheme.kt`：读 store → 配色 / 强调色 / 动效档位注入令牌。
  明暗仍由本机「设置 → 外观」决定（设备属性），其余跟团队走。
- ☑️ `ui/chat/BotChatPlaceholderScreen.kt`：`ChatMessages` 读 store 里的 `thinking`，
  新增 `ThinkingRow`；气泡的底色/圆角/内边距和宽度**一律不动**（本机自己的样式）。
- ☑️ `ui/chat/BotChatViewModel.kt`：`ChatEntry` 加 `reasoning`，`parseItem` 读 `reasoning`。
- ☑️ **插件改动** `app/src/main/assets/ds-bot/src/host/parts.js`：`assistant/message`
  的 reasoning 块以前被丢掉（只留 `type === 'text'`），现在挂在第一个气泡的
  `reasoning` 字段上（网页端忽略未知字段，不受影响）。插件随 APK 更新自动重装
  （`DsBotPluginInstaller` 用 `lastUpdateTime` 做指纹）。
- ☑️ `ui/settings/PersonalizationScreen.kt`：文案改成「保存在引擎里……本机也照着走」。
- ☑️ **强调色圆点**：照网页端 `.bt-swatch` 改成**固定尺寸 + 固定 2dp 边框**，
  选中只换边框颜色（以前「选中 2dp / 未选中 1dp」，选中的那个可见色块少一圈，
  看着比别的圆小一号；切主题时环色再变一次，大小观感跟着抖）。「跟随主题」
  那个改成网页端同款的斜切。
- ☑️ `ui/home/HomeViewModel.kt`：引擎起来时拉一次 `state` → 写 store；引擎断开时 `clear()`。

**原生样式保留情况**（用户追问「我自己那套样式颜色留了没有」）

- ☑️ **`deepseek` 配色 = 本机原生令牌，一个色不差**：`DshmColors.Light` /
  `DshmColors.Dark` 的原值整套搬进配色表（背景、页面底色、卡片、弱化底、气泡、
  正文、次级/三级文字、分隔线、用户气泡、强调色、墨色、悬浮圆钮底色）。
  `DshmPalettesTest.deepseekPaletteIsExactlyTheNativeTokens` 锁死这一点：
  `DshmColors.Light.withPalette(DEEPSEEK) == DshmColors.Light`（明暗各一条）。
- ☑️ **图标色和三档阴影不跟着配色变**：它们是「遮蔽」不是「墨色」，跟着文字色
  派生的话深色主题下会变成发亮的雾（原来都是黑的）。`shadowsAndIconTintStayNative` 锁死。
- ☑️ **明暗仍归本机**：「设置 → 外观」的跟随系统 / 浅色 / 深色不受团队偏好影响，
  团队偏好只管配色方案、强调色、布局、宽度、动效、思考过程。

**这一轮没做的**

- 群聊里没有思考行：群聊的 item 来自 `group_relay` 的结果（`mobile.js` 的 `roomItems`），
  里面没有 reasoning；要做到得改群聊那一层的产出，本轮先没动。
- 气泡样式和内容宽度不跟团队走（见上面的「故意不接的两个」）。
- 强调色在深色主题下沿用同一个色值：网页端对已知色板有一张深色变体表，本机不引那张表，
  免得半吊子的近似。
- `lively` 在本机体现为「转场稍慢」，网页端那套空闲小动作（Bot 眨眼/扭动）本机没有对应物。

**验证**：84 测试类 / 483 用例全绿（新增 16：`DshmPalettesTest` 7、`ChatAppearanceTest` 5、
`BotPrefsStoreTest` 4），lint 零 warning，assembleDebug 通过。

### 个性化：统一令牌写法 + 思考过程真接上 + 主题改名（2026-11-13）

上一节接线之后真机跑出来三个问题，这一轮收掉。

**1）强调色圆环切主题会变大小 —— 根因是同一个令牌两套写法**

真机像素证据（个性化页那行，y=1056）：

```
色块2 (#4D6BFE 蓝): x=254..264 (236,238,241) ← 白环 | x=264..310 (77,107,254) ← 颜色 | x=310..320 (236,238,241) ← 白环
色块1 (自, 选中):  x=128..138 (11,11,15)      ← 黑环（textPrimary）
```

`(236,238,241) = #ECEEF1` —— **deepseek 那一套的 `divider`，不透明的近白色**，压在
颜色上面，每个色块外面套一圈白边。mono / paper / moonlight 的 `divider` 是
`text.copy(alpha = .10)`（网页端 ink/line 的派生规则），压到颜色上是一圈**暗边**，
白边没了 → 切主题时圆环的观感大小就跳。

**同一个令牌两套写法**：deepseek 的次级/三级文字、分隔线是我按原生令牌写死的
**不透明 hex**；另外三套是按网页端规则用 **alpha 派生**的。

- ☑️ 统一成 **deepseek 的写法**：所有配色的墨色令牌（次级文字 / 三级文字 / 分隔线）
  都是**不透明实色**（`inkOver()` 把 ink 按比例烤到底色上）。
  强调色圆块在四套主题下画法逐像素一致。
- ☑️ 回归锁 `everyPaletteBakesItsInkAsOpaque`：所有配色 × 明暗的三个墨色令牌
  `alpha == 1`。
- ☑️ 你原生令牌仍然一个色不差（`deepseekPaletteIsExactlyTheNativeTokens` 继续锁着）。

**2）思考过程：之前那条插件补丁是错的**

真机事件里的真实形状：

```json
{"type":"assistant/message","data":{"turn":39,"step":18,"message":{"content":[
   {"type":"reasoning","text":"…"},{"type":"tool-call",…}]}}}      ← 只有思考 + 工具，没有 text 块
{"type":"assistant/message","data":{"turn":39,"step":20,"message":{"content":[
   {"type":"reasoning","text":"…"},{"type":"text","text":"搜不了（接口余额用尽）…"}]}}}
```

旧补丁只在「这条消息有 text 块」时挂 reasoning → **只思考不说话的步骤，思考全被丢掉**；
一轮里分几步想，也只留最后一步。

- ☑️ 插件 `parts.js` 改成**按轮累计**：一轮里的思考先攒着，挂在该轮的文本气泡上
  （网页端就是「思考行在回答上方」）；新的一轮开始就清空。用真机那 489 条事件
  跑过一遍：29 个气泡里 20 个带上了思考。
- ☑️ 原生 `ThinkingRow` 改成**可折叠**（网页端是 `<details>`，默认收起），
  标题行可点，展开才看正文 —— 一段思考能有几百字，摊开会把对话淹了。
- ☑️ **引擎自动重启**：APK 换过之后引擎里跑的还是旧插件代码（引擎是长驻进程，
  APK 更新不会重启它），磁盘上那份也要等下次启动才被覆盖 → 指纹对不上就自动
  重启一次引擎（`DsBotPluginInstaller.fingerprint` + `EngineViewModel.restartForNewPlugin`）。
  否则改了插件却「一点反应都没有」。

**3）主题改名**

- ☑️ `DeepSeek` → **默认**（`Black & white` / `黑白` 是 mono）。
  主题 id 不变（引擎那边是 `deepseek` / `mono`），只改显示名。

**验证**：81 测试类 / 481 用例全绿，lint 零 warning，assembleDebug 通过。

### 收尾三件：撤思考过程 + 运行失败可见 + 强调色统一主色（2026-11-13）

**1）「显示思考过程」整个撤掉**（用户：不要了现在）

- ☑️ 个性化页的开关删掉；原生 `ThinkingRow` / `ChatEntry.reasoning` / 解析删掉；
  插件 `parts.js` 回退成上游原样（不再带 reasoning 字段）；相关字符串删掉。
- ☑️ `BotPrefs.thinking` 字段**保留**（引擎真值、网页端还在用，我们照常读写透传），
  只是本机不再消费它。
- ☑️ 引擎自动重启机制保留（插件指纹变化 → 重启一次引擎，这轮回退也会触发一次）。

**2）「请求模型失败 / 运行失败」静默 —— 根因 + 修复**

读引擎源码（设备 rootfs 里的 `dsh-agent-loop` / `dsh-llm-retry`）确认：

- 重试中：每次调度重试记 `llm/retry {retry, maxRetries, failure}`（我们**已有**渲染
  「模型请求失败，正在重试(n/max)：原因」）；
- **最终失败**：`dsh-agent-loop` 的 catch 分支记
  `turn/end { reason: { kind: "error", error: { code, message } } }`，而我们对
  `turn/end` **只记 seq 什么都不画** —— 这就是静默。而且很多失败（额度用尽等）
  错误码不可重试，从头到尾**一条 `llm/retry` 都没有**，只有这条 turn/end。
- ☑️ 修复：`turnEndError()` 解析 `reason.kind == "error"`（completed / aborted 不算），
  画一条红色事件行 `eventKind = "failed"`（复用群聊失败那轮的全套基建：
  `failureLabel` 把 AUTH/QUOTA/SERVER/TIMEOUT… 翻成人话）→ 界面显示
  「**小助手 没能回答：余额不足**」。
- ☑️ 顺带修两个隐患：`mergeSessionEvents` 的 base 过滤只剥 `tool:/present:/shot:`
  前缀，`retry:` 行每次合并都会再插一份（越叠越多）—— 加剥 `retry:` / `turnfail:`；
  这两类事件行的 `seen.add()` 守卫去掉（`seen` 是聊天条目的去重集，follow 重连
  快照重放时它会把事件行挡掉导致消失；去重靠 `toolEvents` 的 seq 键就够了）。

**3）强调色 = 全局主色（用户：强调色怎么没改用户气泡 / 主题蓝统一成色板蓝 / 删「自」）**

- ☑️ **用户气泡跟强调色走**：`withPalette` 里 `bubbleUser = accent`、
  `onBubbleUser = inkOn(accent)` —— 按钮 / 链接 / 用户气泡是同一个主色，
  团队换强调色气泡立刻跟着换。配色表的 `user` / `userText` 字段删掉。
- ☑️ **默认主题的蓝统一成第一个色板的蓝**（DS Bot 品牌蓝 `#4D6BFE`）：
  `DshmColors.Light` 的 accent / bubbleUser 都换成 `#4D6BFE`（原来是两 глубок разных
  蓝 `#1F4FD8` / `#2F6BFF`）；深色主题气泡跟 accent（`#6A84FF`）。
  「默认 = 第一个蓝」，`defaultThemeAccentIsTheFirstSwatchBlue` 锁住。
- ☑️ **删掉「自」（跟随主题）色板**：强调色一行只剩 7 个颜色圆点；
  accent 没设值时**第一个圆点显示选中**（默认主题下它就是实际生效的主色）；
  点任意圆点都写显式 hex。`deepseekPaletteIsExactlyTheNativeTokens` 继续锁
  「deepseek 配色 = 原生令牌」。

**验证**：82 测试类 / 486 用例全绿，lint 零 warning，assembleDebug 通过。
