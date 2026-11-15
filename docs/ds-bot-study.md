# DS Bot 插件全貌研究（做「DS Bot 安卓版」的权威依据）

> 生成：2026-11-15。**只读调查**，未改任何代码。
>
> 材料以 **`app/src/main/assets/ds-bot/`（v0.2.5，实际随我们引擎跑的插件）** 为准；
> `DS-Bot/ds-bot/`（v0.2.4，带 `src/client` 未打包源码，比 `client.js` 打包产物易读）作对照；
> `DS-Bot-mobile-api/ds-bot/`（v0.2.1）只在移动端专用端点处参考。
>
> 边界：**DSH 本体（`@deepseek-ai/*`）由另一路调查**。本文只触及 DS Bot 插件与 DSH 的接口面
> （插件监听/拦截了哪些 DSH 事件、DSH 侧事件在客户端长什么样），不展开 DSH 内部实现。
>
> 出处缩写：`host/` = `app/src/main/assets/ds-bot/src/host/`；`client.js` = `app/src/main/assets/ds-bot/client.js`；
> `我们` = `app/src/main/java/com/dshm/bot/`。

---

## 1. 宿主插件全貌

### 1.1 入口与运行时

- 插件名 `ds-bot`，注入 DSH 服务：`['llm','tools','systemPrompt','sessionController','sessions','sessionTitle','workspaceRegistry','connection']`（`index.js:43-44`）。
- `apply(ctx, config)` → `createRuntime(ctx, config)`（`index.js:52-72`）：
  - `dshHome` = `folders.dshHomeOf(process.env.DSH_HOME)`，插件私有目录 `home = config.home ?? <dshHome>/bot`，状态文件 `<home>/state.json`（`index.js:57-64`）。
  - `defaults`：首个 Main Bot 名 `Chief` / 角色 `Chief of Staff`（`index.js:65-68`）。
  - 依次 `module.install(rt)`（`index.js:70`）。每个模块把自己的公开函数挂到 `rt` 上，**运行时**通过 `rt.*` 互调，安装期不互调（`index.js:46-49`）；监听器与工具按注册顺序排列，**usage 最后**（它要在所有改写请求的监听器之后再计量）。

### 1.2 模块清单与职责

MODULES 顺序（`index.js:50`）：

| # | 模块 | 文件 | 职责（来自文件头注释/实现） |
|---|---|---|---|
| 1 | diagnostics | diagnostics.js | 本机出错记录，内存 + `<home>/logs/diagnostics.jsonl`；文本清洗（去密钥/凭据/home 路径）后供 issue 报告 |
| 2 | store | store.js | state.json 存档、迁移、单写者锁（home-lock.js）、Session→Bot/群 索引 |
| 3 | models | models.js | 每个 Bot 一个模型，建时定、只在其设置或 Main Bot 处改 |
| 4 | providers | providers.js | 模型 provider 与 API key（无 DSH Models 页的窗口用） |
| 5 | permissions | permissions.js | Bot 级权限预设，落到它每个 Session；群可覆盖 |
| 6 | turns | turns.js | 每个 Session 现在在干什么（吉祥物）、被别的 Bot 触发的 turn、群轮 waiter/inbox、turn 结束的回复路由 |
| 7 | handoffs | handoffs.js | Bot 互相委托的"工作"（work）模型；等谁、为谁做；结果自动上行 |
| 8 | delivery | delivery.js | 往 Bot Session 投消息的统一入口（relay/steer、长文转文件） |
| 9 | roster | roster.js | 建/改/删 Bot 与群聊、Main Bot 角色、新团队首个 Main Bot |
| 10 | folders | folders.js | 工作目录：群目录 > Bot 目录 > 团队工作区；目录改变→切新 part；folders/folder-pick/folder-list |
| 11 | agents | agents.js | 团队的 DSH Agent（普通 dsh 会话，非 Bot），列在侧栏但不是团队成员 |
| 12 | prompt | prompt.js | 一段 system prompt 文本，只依赖本 Session 的 Bot 记录（前缀稳定） |
| 13 | timeContext | time-context.js | 注入当前时间上下文（`agent/pre-step`） |
| 14 | tools | tools.js | 团队工具注册：名单、Bot 间消息、群设置与发言、ask_user/show_view 卡片 |
| 15 | chatIndex | chat-index.js | 读 Bot 自己的聊天：历史分页 + SQLite FTS5 索引（read_own_chat 的 search/latest/around） |
| 16 | groups | groups.js | 群聊：房日志、成员群 Session、轮次与 @提及、轮报告、群工具（group_relay/read_group_chat/read_own_chat） |
| 17 | approvals | approvals.js | **DS Bot 是它拥有的每个 Session 的审批应答者**；answer 走 `approval-answer`，不经 DSH 卡片 |
| 18 | resume | resume.js | DSH 关闭/重启时丢掉的 Bot 工作，记在 `inflight.json`，下次启动捡起一次 |
| 19 | memory | memory.js | 记忆：Markdown（Agent Memory Repo 格式），`<home>/memory/bots/<botId>/`、群记忆；记忆评审 |
| 20 | parts | parts.js | part 机制（长对话原地压缩 or 切新 Session）、handoff note/package、历史分页、**`displayItems` 投影** |
| 21 | images | images.js | 纯文本模型的图片：请求到不可读图的模型前先转文字（vision），按附件缓存 |
| 22 | secrets | secrets.js | 密钥卡：值只在 secrets.json(600) 与进程内；shell 里 `$DSH_SECRET_<NAME>`；请求/结果/模型请求全程改写 |
| 23 | connectors | connectors.js | 外部服务（GitHub 优先）：经 DSH 的 MCP 客户端挂 `mcp__github__<tool>`；token 是 GITHUB_TOKEN secret |
| 24 | capabilities | capabilities.js | 能力库（MCP/插件等）与 Bot 挂载；挂载时 reconcile Agent |
| 25 | schedules | schedules.js | 定时任务：列出/建/删；part 切换时把任务搬到新 Session；群里以用户消息形式到达 |
| 26 | browser | browser.js | Bot 浏览器（Desktop）：Electron webview 由窗口持有，`browser-wait`/`browser-result` 长轮询打通 read_browser |
| 27 | phone | phone.js | 手机控制：用户选定的 Bot 经无障碍服务操作 Android；`phone-wait`/`phone-result` 长轮询 |
| 28 | usage | usage.js | 用量账本 `usage.sqlite`；每模型调用 token 计量 |
| 29 | update | update.js | 插件自身更新（npm/GitHub tag），经 DSH 插件管理器重装 |
| 30 | dshUpdate | dsh-update.js | 与已核验 DSH 版本对齐的 DSH 更新 |
| 31 | thinking | thinking.js | 一个 Turn 的精确思考时长（从 timed stream 的 reasoning chunk 推） |
| 32 | mobile | mobile.js | 自绘团队界面的客户端 API（**手机优先**）：版本握手、群聊 items、notices 补读、头像按引用、记忆直改、单 Bot 用量 |
| 33 | feed | feed.js | SSE 实时流 `api/bot/feed`：revision/live/message/stream/notice/room/timeline |
| 34 | channel | channel.js | `POST /api/bot` 路由（**88 个端点**）+ `state`/`live` 视图；此处 install btw/side/projection |
| 35 | remote | remote.js | 同一套 API 的 DSH Remote 方法 `dsBot/call` + `dsBot/feed`（给只能转发 Remote 方法的载体/中继） |

> `btw.js`（aside 旧功能遗留）与 `side.js`（侧聊）**不在 MODULES 里**：由 `channel.install()`
> 在开头调用 `installBtw(rt)` / `installSide(rt)` / `installTimeline(rt)`（`channel.js:26-28`）。
> `installTimeline` = `projection.js` 的 install（统一时间线投影），依赖 `rt.displayItems`（来自 parts，先装）。

### 1.3 模块间关键依赖（运行时经 `rt`）

- `channel` 需要：`store`（state/load/save/readOnly）、`roster`（createBot…）、`groups`（createRoom/greetRoom/continueRoom/roomArtifacts）、`folders`（folderEndpoint/team-workspace）、`agents`、`secrets`、`capabilities`、`schedules`、`memory`、`browser`/`phone`（长轮询 channel）、`feed`（publishFeed）、`parts`（displayItems/earlierPage/chatPage）、`projection`（timelineEndpoint）。
- `delivery.deliver()` 是唯一投递口，被 `turns`/`groups`/`handoffs`/`roster` 反复调用；它反过来调 `resolveAgent`（并顺带 `hideBotUnusedTools`）。
- `turns` 是"现在在忙什么"的唯一来源：`activityView/workView/roomWorkView/turnsView` 被 `channel.live()` 直接消费。
- `groups` 调 `delivery.deliver` + `turns.roomWaiters/roomProgress/roomInbox`（群轮的等待与排队）。
- `feed` 消费 `turns.chatOwners`（Bot 的 own-chat Session→botId）、`parts.displayItems`、`projection.timelineDelta`、`mobile.noticeCursor`。
- `mobile` 写 `rt.publishFeed('notice', …)`、`rt.roomLineAdded`、`rt.cardShown`、`rt.approvalWaiting`（分别由 groups、turns/tools、approvals 调用）。

---

## 2. 全部端点清单（`/api/bot`）

- 路由：`channel.js:141` 的 `handle(endpoint, payload, signal)`；`switch` 在 `channel.js:147-571`；
  末尾 `default → bot/not-found`（`channel.js:569-570`）。
- 同一 `handle` 也服务 DSH Remote 方法 `dsBot/call`（`channel.js:581`、`remote.js`）。
- **只读主机（另一个 dsh 持锁）** 只能走 `READS` 集合（`channel.js:100`）；不在集合里的一律
  `bot/read-only`（`channel.js:146`）。下表 `只读` 列 = 在 READS 里。
- 返回统一外壳：`{ok:true, value}` 或 `{ok:false, error:{code,message,details}}`（`channel.js:35-36`）；
  错误码 `bot/invalid` / `bot/not-found` / `bot/refused` / `bot/internal` / `bot/read-only` / `bot/stale` / `bot/browser-off` / `bot/phone-off` / `bot/unsupported`（`channel.js:15-23`）。

### 2.1 状态与目录（读）

| 端点 | 做什么 | 入参要点 | 返回 | 只读 | mutating |
|---|---|---|---|---|---|
| `state` | 团队视图 + live 区；`since` 命中则 `{unchanged:true}` | `since`(revision)、`models`(modelsKey)、`locale`、`avatars:"ref"` | `{revision, bots[], rooms[], agents[], questions, views, exchangeTurns, secrets, capabilities, secretRequests, colors, models, prefs, browser, memory, readOnly, …} + live{activity, work, memoryNews, memoryReviews, connectors, capabilityStatuses}` | ✅ | 否（首拉会 bootstrap/catalog/loadSecrets） |
| `models` | 强制刷模型目录 | — | `{modelsKey}` | ❌ | 否 |
| `efforts` | 某 model ref 的思考档位 | `ref` | `{efforts:[…]}` 或 `{efforts:[]}` | ✅ | 否 |
| `providers` | provider + key 视图 | — | providersView | ✅ | 否 |
| `provider-models` | 探某 provider 可用模型 | body | `discoverModels(body)` | ✅ | 否 |
| `usage` | 用量页数据 | `timeZone` | `usageView`（owners/models/rows/hours/scanning） | ✅ | 否 |
| `usage-summary` | 单 Bot 用量汇总 | `botId`,`timeZone` | `{botId, day, week, month, models[], scanning}` | ✅ | 否 |
| `run-stats` | DSH 自己的整段会话统计（状态栏那颗） | `botId` | `projections.snapshot(...).values` 或 null | ✅ | 否 |
| `thinking` | 某 Turn 精确思考时长 | `sessionId`,`turn` | `{ms}` / `{}` / null | ✅ | 否 |
| `diagnostics` / `diagnostics-log` | 本机诊断报告 / 原始日志 | body / — | report / logText | ✅ | 否 |
| `update-check` | 插件与 DSH 更新检查 | `fresh` | `{…updateCheck, dsh:{…}}` | ✅ | 否 |
| `repo-stars` | GitHub star 数 | — | stars | ✅ | 否 |

### 2.2 会话内容（读）

| 端点 | 做什么 | 入参要点 | 返回 | 只读 | mutating |
|---|---|---|---|---|---|
| `chat` | Bot 自己聊天的分页（**当前 part 优先，回退更早 parts**） | `botId`,`cursor` | `{sessionId, items[], cursor}` | ✅ | 否 |
| `earlier` | 某 part 更早的历史页 | `sessionId`,`cursor` | `{items[], cursor, btw[], startedAt}` | ✅ | 否 |
| `timeline` | **统一时间线投影**（官方 displayItems 行 + tool/deliverable/failure/inbox 行）；群/单聊同构 | `botId` 或 `roomId`、`since`、`cursor{part,beforeSeq}`、`max`(≤400) | `{sessionId|roomId, seq, rows[], cursor, running?, speaking?, replayed?, partial?}` | ✅ | 否 |
| `room-chat` | 群聊 items（`roomItems`：user/post/relay replies 展开） | `roomId`,`cursor{beforeSeq}` | `{roomId, items[], cursor, running, speaking}` | ✅ | 否 |
| `room-progress` | 群轮进行中的回复 | `roomId` | `[{callId, replies, speaking}]` | ✅ | 否 |
| `room-artifacts` | 一轮交付（成员 present 声明 + 未 present 的产物文件） | `roomId`,`since` | roomArtifacts | ❌* | 否 |
| `exchange` | 两个 Bot 的往来消息 | `a`,`b` | state.exchanges 过滤 | ✅ | 否 |
| `exchange-text` | 某条往来的全文（长文在收件 Session/文件里） | `id` | `{text, whole}` | ✅ | 否 |
| `file-text` | 长文附件全文（group "Show all"） | `file.attachmentId` | `{text, whole}` | ✅ | 否 |
| `folders` | 工作目录候选 + 团队目录 + picker 类型 | — | `{team, picker, folders[]}` | ❌ | 否 |
| `folder-list` | 列目录 | `path` | `{path, home, crumbs, entries, truncated}` | ❌ | 否 |
| `schedules` | 定时任务清单 | — | schedulesView（含 `available`） | ✅ | 否 |
| `memory-view` | 记忆面板视图 | target/scope/topic | memoryView | ✅ | 否 |
| `memory-ask` | 问记忆（可能触发评审/写账） | target/scope/topic/query | memoryAsk 结果 | ❌ | 否 |
| `hello` | 移动端版本握手 | `api:[…]` | `{api, apiMin, apiMax, plugin, capabilities[], boot, seq, revision, readOnly, memory, feed, rpc}` | ✅ | 否 |
| `notices` | 断线补读通知 | `boot`,`since` | `{boot, seq, reset, notices[]}` | ✅ | 否 |
| `avatar` | 头像按引用取图 | `botId` | `{botId, ref, image}` | ✅ | 否 |
| `browser-wait` | Desktop 窗口的 read_browser 长轮询 | `clientId`,`open` | 待读请求 / 空 | ✅ | 否 |
| `browser-result` | 窗口回填一次读/动作结果 | body | channel.result | ✅ | 否 |
| `phone-wait` | 手机端 phone 控制长轮询 | `clientId` | 待办动作 | ✅ | 否 |
| `phone-result` | 手机端回填动作结果 | body | channel.result | ✅ | 否 |

### 2.3 写入 / 操作（mutating）

| 端点 | 做什么 | 入参要点 | 返回 | 只读 | mutating |
|---|---|---|---|---|---|
| `send` | 给某 Session 发用户消息（queue 或 steer） | `sessionId`,`text`,`requestId?`,`mode:'steer'?` | `true` | ❌ | ✅ |
| `stop` | 停某 Session/群/Bot 当前 turn | `sessionId` | `stopped`(bool) | ✅ | ✅ |
| `stop-member-work` | 群轮里停某成员 | `roomId`,`botId` | stopMemberWork 结果 | ❌ | ✅ |
| `answer-question` | 回执 `ask_user` 卡 | `sessionId`,`questionId`,`answer` | `true` | ❌ | ✅ |
| `view-reply` | 回执 `show_view` 卡 | `sessionId`,`viewId`,`action`,`values` | `true` | ❌ | ✅ |
| `dismiss-question` / `dismiss-view` | 丢弃待答卡 | `sessionId` | `true` | ❌ | ✅ |
| `approval-answer` | 允许/拒绝一个待审批 | `id`,`choice: allow\|reject` | `true` | ✅ | ✅ |
| `room-continue` | 群轮暂停后继续 | `roomId` | view() | ❌ | ✅ |
| `room-queue` | 撤回群排队消息（仅 `remove`；无 steer） | `roomId`,`id`,`action:'remove'` | `{removed}` | ❌ | ✅ |
| `create-bot` / `update-bot` / `delete-bot` / `duplicate-bot` | Bot 生命周期 | id + patch；create 带 name/role/color/instructions/brief/avatar/model/effort/workDir | bot / view() | ❌ | ✅ |
| `set-main` | 设/撤 Main Bot | `id`,`main`,`primary`,`replace` | view() | ❌ | ✅ |
| `set-flags` | 置顶/隐藏 | `id`,`pinned`,`hidden` | setFlags | ❌ | ✅ |
| `create-room` / `update-room` / `delete-room` | 群聊生命周期 | members/name/notice/mode/admin/add/remove/workDir/permission/relayLimit | room / `{room,changes}` / view() | ❌ | ✅ |
| `set-prefs` | 主题/强调色/动效/布局/宽度/思考行 | `theme`,`accent`,`motion`,`layout`,`width`,`flatWidth`,`thinking` | `state.prefs` | ❌ | ✅ |
| `team-workspace` | 设团队工作区路径 | `path` | `{team}` | ❌ | ✅ |
| `add-agent` / `agent-session` / `agent-adopt` / `agent-archive` / `agent-rename` / `remove-agent` | DSH Agent 与它的 Session | agentId/sessionId/title/workspaceId | agent / `{sessionId}` / true / view() | ❌ | ✅ |
| `provider-key` / `provider-add` / `provider-remove` | provider 与 key 增删 | provider/value/body | providersView / addProvider / removeProvider | ❌ | ✅ |
| `secret-set` / `secret-allow` / `secret-cancel` / `secret-update` / `secret-delete` | 密钥卡与访问范围 | name/value/scope/purpose/requestId | secretView / true | ❌ | ✅ |
| `connector-connect` / `connector-disconnect` / `connector-retry` | 连接器（GitHub） | id/token | connectorsView | ❌ | ✅ |
| `capability-save` / `capability-delete` / `capability-approve` / `bot-capability` | 能力库与挂载 | body/name/botId/attached | item / `{bot,attached}` | ❌ | ✅ |
| `schedule-create` / `schedule-delete` | 定时任务 | target/title/prompt/when；sessionId/id | schedulesView | ❌ | ✅ |
| `memory-forget` / `memory-edit` / `memory-add` | 记忆删除/编辑/新增（mobile 侧 edit/add 同源） | target/scope/topic/text/next | memoryView | ❌ | ✅ |
| `side-new` / `side-ask` / `side-cancel` / `side-close` | 侧聊（Bot 面板旁路问答） | sessionId/chatId/entryId/text | sideView 片段 | ❌ | ✅ |
| `update-install` / `dsh-update-install` | 触发插件/DSH 更新安装 | — | updateStart() | ✅ | ✅ |

> 计数：`channel.js` 的 `case` 去重后共 **88 个端点名**。其中 `hello/notices/avatar/room-chat/usage-summary/memory-edit/memory-add` 转 `mobile.mobileEndpoint`（`channel.js:399-407`、`mobile.js:301-314`），`timeline` 亦由 `mobileEndpoint` 兜底转发。
> 只读 `❌*` = 功能本身是读，但**不在 `READS` 集合里**，所以在只读主机上会被 `bot/read-only` 拒（如 `room-artifacts`、`folders`、`folder-list`、`memory-ask` 都是这种）。
> `browser-takeover/browser-stop/browser-give-back/browser-import` 在 v0.2.4 的 `src/client/index.js` 里出现，但 v0.2.5 `channel.js` **没有**对应 case → 归属 Desktop 应用的 bridge，不在插件路由内（见 §8）。

---

## 3. 事件与状态

### 3.1 `state` 响应结构

**`view()`（`channel.js:40-84`，随 revision 变化）**：

- `revision`：`state.revision`（每次 `save()` +1，`store.js:301`）。
- `workspaceId`：`state.workspaceId ?? null`。
- `mainBotId`：`state.mainBotIds[0] ?? null`；`mainBotIds`：全部 Main Bot。
- `bots[]`：每个 Bot 去掉 `segments/kickoff`，补 `sessionId`（当前 own-chat part）、`parts`（历史 part 的 sessionId 列表）、`waiting:true`（还在等用户选模型）。其余字段即 `state.bots[id]`（name/role/color/avatar/instructions/model/effort/workDir/capabilities/pinned/hidden/createdAt 等）。
- `rooms[]`：去掉 `log/seen/lateRounds/relays/sessions/segments`，补 `parts`（成员群 Session + 历史分段的 sessionId）、`relayLimitDefault`、`preview`（最后一条 log：用户原文或 `名字：文本`）。其余即 `state.rooms[id]`（name/members/admin/mode/notice/workDir/permission/relayLimit/named/paused…）。
- `exchangeTurns`：`state.exchangeTurns`（Session→被隐藏的 turn 号数组）。
- `questions`：`state.questions`（`{botId: {id,callId?,question,detail?,options[],multiSelect?,allowCustom?,askedAt}}`，`tools.js:606-615`）。
- `views`：`state.views ?? {}`（`{botId: {id,callId?,title,view,askedAt}}`，`tools.js:654-660`）。
- `btwCards`：`rt.btwView()`（aside 卡，按 part）。
- `sideChats`：`rt.sideView()`（每个 Bot 的侧聊，创建序）。
- `agents[]`：`state.agents`（团队 DSH Agent）。
- `secrets`：`rt.secretsView()`（**只有 key 名与可用范围，值不出宿主**）。
- `capabilities`：`rt.capabilityLibraryView()`。
- `secretRequests`：`state.secretRequests ?? {}`（待填密钥卡）。
- `colors`：`[...COLORS, ...EXTRA_COLORS]`（`constants.js:5-7`）。
- `models` / `defaultModel` / `modelsKey`：模型目录（来自 DSH，不在 state.json）。
- `permissions`：`rt.permissionCatalog?.()`。
- `prefs`：`state.prefs`（theme/accent/motion/layout/width/flatWidth/thinking）。
- `browser`：`rt.browserEnabled`（Desktop 才 true）。
- `memory`：`rt.memoryEnabled !== false`。
- `readOnly`：**非空字符串 = 只读原因**（另一个 dsh 持锁，`store.js:59-64`）。

**`live()`（`channel.js:90-97`，不随 revision 变，每次都回）**：

- `activity`：`rt.activityView()` → `{botId: 'thinking'|'searching'|'working'|'sending'|'orbit'|'waiting'|'alert'}`（`turns.js:271-289`）。
- `work`：
  - `bots`：`rt.workView()` → `{botId: {since, tool?, said?, note?, target?, roomId?, switching?, folder?}}`（`turns.js:311-327`）。
  - `rooms`：`rt.roomWorkView()` → `{roomId: [{botId, since, tool?…, speaking?}]}`（`turns.js:336-351`）。
  - `approvals`：`rt.approvalsView()` → `[{id, sessionId, botId, roomId?, tool, reason, detail{text?,code?}, at, answerable}]`（`approvals.js:50`）。
  - `waiting`：`rt.waitingView` → `{botId: {bots[], rooms[], for[]}}`（谁在等谁的活，`handoffs.js:120-133`）。
  - `turns`：`rt.turnsView` → `{sessionId: since}`（每个在跑的 turn 起始时间，`turns.js:330-332`）。
- `memoryNews` / `memoryReviews`：记忆变更新闻 / 评审状态（`memory.js`）。
- `connectors`：`rt.connectorsView()`。
- `capabilityStatuses`：`rt.capabilityStatusesView()`。

### 3.2 持久化 `state.json` 顶层（`store.js:22`）

`{ version:1, mainBotIds[], bots{}, rooms{}, agents{}, capabilities:{items{}}, exchanges[], exchangeTurns{}, works{}, questions{}, views{}, prefs{}, switching{}, revision }`
——运行时还加 `workspaceId`、`secretRequests`、`whaleStored`（见 `store.js:106-226` 的 adopt/迁移）。

### 3.3 实时 `feed`（SSE `api/bot/feed`）

`feed.js` 头注释 `feed.js:1-13` 定义事件类型；注册在 `feed.js:139-156`：

| 事件 | 触发 | data 形状 | 语义 |
|---|---|---|---|
| `hello` | 连接即发（`feed.js:149`） | `{revision, api?, boot?, seq?}` | 团队 revision + （移动端）版本与通知游标 |
| `revision` | `stateSaved()`（`store.js:322` → `feed.js:57-59`） | `{revision}` | 团队存档了，重拉 `state` |
| `live` | `agent/status` 里本团队的 Session（`feed.js:72-74`），150ms 合并 | `{}` | 工作态变了但无新 revision，重拉 `state` |
| `message` | 自己的 own-chat 的 `session/event` → `displayItems(session,[event])`（`feed.js:92`） | `{botId, sessionId, item}` | 一条聊天条目（与 `chat` 条目同形） |
| `stream` | `agent/assistant-stream`（`feed.js:116-131`） | `{botId, sessionId, attemptId, phase:'start'\|'end'}` 或 `{…, text}` | Bot 正在写的回复（start/文本/end）；被隐藏的交换 turn 不推 |
| `notice` | `mobile.addNotice`（`mobile.js:136-144`），kind ∈ message/group/done/question/view/approval | `{seq, time, kind, botId?, name?, sessionId?, roomId?, title?, text}` | 离屏客户端该知道的事（手机后台/断线回来补读） |
| `room` | `rt.roomLineAdded` / 群 Session 的 `tool/call|tool/result|user/message`（`mobile.js:153-197`），150ms 合并 | `{roomId}` | 群聊变了，重拉 `room-chat` |
| `timeline` | own-chat / 群 Session 的 `session/event` → `projection.timelineDelta`（`feed.js:83-110`） | `{botId\|roomId, seq, from, bufferFrom, reset?, rows[], running?, speaking?}` | 统一投影的增量行；`from/bufferFrom` 决定能否桥接游标还是重拉快照 |

**哪些是「DS Bot 自己加的」（相对 DSH）**：`api/bot/feed` 这一整条流**都是 DS Bot 加的**（DSH 本体另有一套会话事件流/`$events`，见 §4）。DS Bot 的 7 种 feed 事件没有一种来自 DSH 现成事件；`message`/`stream`/`room`/`timeline` 都是插件对 `ctx.on('session/event'|'agent/assistant-stream')` 的再投影。

### 3.4 投影行模型（`timeline` 端点 / `timeline` feed）

行 = `{key, kind, seq, turn, timeMs, payload}`（`projection.js:170`）。**kind 全集**：

- 官方 `displayItems` 包装（`projection.js:193-241`）：`user`（→ 行 kind `user`）、`bot`、`answer`（→ 行 kind `answered`）、`view`、`event`、`divider`。
- 官方网页端在浏览器里才画的：`tool`（`toolRowsOf`，`projection.js:247-327`）、`artifact`（`artifactRowsOf` 产物卡，`334-410`；群聊 `roomArtifactRow`，`581-600`）、`event`（`failureRowsOf`：`llm/retry` / turn 失败，`414-447`）、`inbox`（`inboxRowsOf` / `roomInboxRowsOf`，`451-469` / `607-625`）。

行 key 惯例：`user:<seq>` / `bot:<seq>` / `tool:<seq>` / `present:<turn>`（群 `present:<roomId>:<turn>`）/ `retry:<seq>` / `turnfail:<seq>` / `event:<seq>` / `answer:<seq>` / `view:<callId>` / `inbox:<itemId>`。带 seq 的行严格升序；无 seq 的（inbox）追加在尾部。

**`tool` 行只对固定工具集生成**（`TOOL_ROWS`，`projection.js:41`）：`message_bot / stop_bot / create_bot / update_bot / create_group / update_group / delete_group / post_to_group / read_group_chat`。
⚠️ **`present` 不在里面**（用户 2026-11-14 拍板）：交付只由产物卡显示，`present` 不产生任何 tool 行（`projection.js:36-41`，与 `docs/timeline-parity.md` 规则 ⑧ 一致）。`remember/forget` 也不成行（活动行显示）。

**群聊 `composeRoomRows` 的额外有意偏离**（`projection.js:509-537`）：`working`（"X 开始做了"）与 `approval`（"X 等你批准"）**不画成事件行**（前者每条开工都来一遍无信息量；后者与审批面板/成员 dock 重复）；`done/stopped/failed/missed/lookup/resumed/cut` 保留。

---

## 4. 宿主事件（`ctx.emit` / `$events` 通道）与插件侧事件

> ⚠️ 本节里 `approval/request`、`user-questions/request`、`api-session/*` 都是 **DSH 主机级事件**（不是 DS Bot 插件 emit 的）。DS Bot 的接入点如下。

### 4.1 DS Bot **拦截/消费**的 DSH 事件

| 事件 | 插件位置 | 形状与语义 |
|---|---|---|
| `approval/request` | `approvals.js:15-48` | DS Bot 用 `{prepend:true}` **抢在 DSH 默认应答者之前**成为它拥有的 Session 的审批应答者。它把请求转成内存条目 `{id(uuid), sessionId, botId, roomId?, tool, reason(≤500), detail:{text?,code?}(callDetail), at}` 存入 `pending`，并 `noteApproval`/`approvalWaiting`（后者发 notice）。返回一个 Promise，直到客户端经 `approval-answer` 调 `entry.answer(outcome)`；`outcome ∈ allowed-once / rejected / cancelled`（`approvals.js:8`）。`request.signal.abort` → `cancelled`。群 Session 额外 `yieldRoomTurn`（群聊不干等）。**没有 owner（非团队 Session）才 `next()` 交回 DSH。** |
| `user-questions/request` | **不由插件 emit**。插件用 `ask_user`（`tools.js:565-621`）替代它：卡片写进 `state.questions`，回答走 `answer-question`；并**把 DSH 的 `ask_user_question` 从 Bot Session 的工具表里摘掉**（`tools.js:670-699`，`BLOCKING_QUESTION_TOOL`）。所以 Bot 会话的提问卡不走 DSH waterfall。 |
| `session/event` | `turns.js:382`、`mobile.js:183`、`feed.js:76`、`projection.js:658`、`resume.js:131`、`tools.js:725` | 会话日志事件驱动一切：turn 追踪、通知、feed、投影缓冲。 |
| `agent/status` | `feed.js:72`、`parts.js:1198`、`memory.js:1546`、`images.js:193` | agent 运行态变化（→ `live` feed / 记忆评审 / 图片）。 |
| `agent/created` | `agents.js:70`、`parts.js:308`、`tools.js:694`、`phone.js:163`、`capabilities.js:489` | 新 Agent 就位即限制工具、挂能力、接 compaction。 |
| `agent/assistant-stream` | `feed.js:116` | 流式回复帧（→ `stream` feed）。 |
| `agent/pre-step` | `parts.js:1026`、`time-context.js:18` | 每步前：上下文/时间注入、切 part 判定。 |
| `agent/request-error` | `parts.js:1124,1142` | 请求失败：溢出/费用等处理。 |
| `agent/inbox/discarded` | `resume.js:121` | 关闭时被丢弃的 inbox 消息（resume 用）。 |
| `tools/execute` | `tools.js:338`、`secrets.js:489` | 工具执行拦截（working_directory、密钥改写）。 |
| `llm/stream` | `groups.js:1078`、`images.js:179`、`parts.js:1208`、`secrets.js:510`、`usage.js:579` | 模型流拦截（图片转文字、密钥脱敏、用量计量、群轮步骤）。 |
| `system-prompt/assemble` | `parts.js:1092` | 组装 system prompt。 |

### 4.2 DSH 侧事件在客户端长什么样（我们的 `DshHostEvent`/`DshHostRequests`）

- `approval/request`（`我们 data/remote/DshHostRequests.kt:11-53`）：`{eventId, agentId(=sessionId), request:{toolName, callId?, reason?, displayReason{en,zh}, …}}`。
  ⚠️ 对**团队 Session**，请求已被 `approvals.js` 抢先应答，客户端应走 DS Bot 的 `state.work.approvals` + `approval-answer`（对齐 `docs/timeline-parity.md` 规则 ⑫），而不是直接回 waterfall。
- `user-questions/request`（`DshHostRequests.kt:102-132`）：`{eventId, agentId, questions:[{id,question,detail?,header?,options[],multiSelect,intent?}], wait:{callId,timed}}`。对团队 Session 不出现（被 `ask_user` 取代）。
- `api-session/status`：`emit` 帧，`[sessionId, running]`，本会话是否在跑的**权威**信号（`我们 ui/chat/BotChatViewModel.kt:590`）。
- `api-session/added`：`emit` 帧，带整份会话摘要（含 `running`），给刚连上补状态（`BotChatViewModel.kt:601`）。

这些是 **DSH mux/`$events` 帧**（`DshMuxHub.kt:15-16`），不是 `api/bot/feed` 的事件。

---

## 5. 网页端渲染全清单（`client.js` v0.2.5）

### 5.1 安装与槽位

`src/client/index.js` 用 `shadow(name, options, component)`（优先级 `BOT_PRIORITY = -100`）覆盖 DSH 官方 cell（`DS-Bot/ds-bot/src/client/index.js:791-796`、`888-933`）：

- 会话节点：`assistant-step→AssistantCell`、`turn-trigger→TriggerCell`、`tool-call→ToolCell`、`turn-process→TurnProcessCell`、`question-reply→AnsweredReply`、`compaction→Nothing`、`context→Nothing`、`user/steering→UserMessageCell`（若有）。
- turn tail：`turnTail→TurnTailCell`（分隔线/新消息线）、`bot-btw→BtwTurnTail`、`bot-learning→LearningTurnTail`。
- 输入区：dock 里 `MemberDock` / `ModelChoiceDock` / `QuestionDock` / `WidgetDock` / `SecretDock` / `HandoffDock` / `LearningDock`；`conversation.composer` 选 `question` 交互时给 `QuestionCard`。
- 侧栏/壳：`TeamSidebar`、`BrandMark/Name`、`FooterAccount`、`YouAvatar`、`AccountLauncher`、`Overlays`、`DeepSeekSignIn`、`ErrorToast`、`BotReturn`。

### 5.2 `displayItems` 产出的行 kind 与渲染组件

宿主 `displayItems`（`host/parts.js:1226-1283`）产出的 kind：`bot` / `user` / `answer` / `view` / `event` / `divider`。
客户端按 `chatRow`（`client.js:7443-7472`）分派：

| kind | 渲染组件 | 说明 |
|---|---|---|
| `divider` | `PartLine`（`client.js:7330`） | 一段会话的分隔线（带日期） |
| `user` | `EarlierUser`（`client.js:7339`） | 用户气泡 + 折叠 + "N images" 计数（**只有计数，无图**） |
| `answer` | `AnsweredReply`→`AnsweredView`/`AnsweredCell`（`client.js:6026/6008/4009`） | 已答过的 view / question 卡回放 |
| `view` | `WidgetCell`（只读，`client.js:5988`/`7453`） | 展示型 view 蓝图回放 |
| `bot` | `Bubbles`（`client.js:6961`） | 多段文本、折叠、`whole` 长文附件 |
| `event` | `GroupEvent`（report，`client.js:7308`）或 `EventLine`（request/reply/stopped，`client.js:7237`） | 可点开对方会话 |
| `btw` | `BtwCardView`（`withBtwRows`） | aside 卡（有创建入口在别处；对话里只回放） |

实时活动行 `ActivityIndicator`（`client.js:8041-8126`）：`bt-typing` + `BotMark`/`RoomAvatar` + `Shimmer` 短语 + `Elapsed`。

### 5.3 `DRAWN` / `CARDS` 白名单

- `DRAWN`（`client.js:7830`）：`message_bot, stop_bot, create_bot, update_bot, group_relay, create_group, update_group, delete_group, post_to_group, ask_user, request_secret, ask_user_question, show_view, read_group_chat, remember, forget` —— **只有这些工具调用会画**。
- `CARDS`（`client.js:7831`）：`ask_user, request_secret, show_view` —— 需要"卡片"语义的（用于 `useShownCards` 计数，避免同一卡既在 dock 又在正文）。
- `ToolCell`（`client.js:7934-7950`）：`callTree` 展平子调用 → 过滤 `DRAWN` → `renderCall`；`drawn.length===0` 返回 null（其余工具**隐藏**，不画）。

### 5.4 `renderCall` 每个 case（`client.js:7854-7932`）

| 工具 | 渲染 | 备注 |
|---|---|---|
| `message_bot` | `EventLine`("Messaged X")；出错时 "Couldn't message X" | 可点开 exchange；`whole=true` |
| `stop_bot` | `EventLine`("Stopped X") / "Couldn't stop X" | 同上 |
| `create_bot` | `EventLine`("Created X")，需 done 且找到 Bot | 点开新 Bot 会话 |
| `update_bot` | `EventLine`("Updated X")，需 done 且找到目标 | 未找到返回 null |
| `group_relay` | `GroupReplies` | 群轮回复展开（见 §5.5） |
| `create_group`/`update_group`/`delete_group`/`post_to_group` | `GroupEvent`(前缀/群名)，从结果正则 `^(Created group\|Updated\|Nothing changed in\|Deleted\|Posted in) "([^"]+)"` 解析群名 | 失败给错误行 |
| `ask_user` | `PendingQuestion`（待答）或隐藏（`bt-q-gone` 占位） | 被 dock 持有则不画 |
| `show_view` | `WidgetCell` | 待答→`PendingView`(inline)；只读→`WidgetCard` |
| `request_secret` | `SecretToolCell` | 见 §5.5 |
| `ask_user_question` | `SettledQuestion`（已答回放） | done 才画 |
| `read_group_chat` | `GroupEvent`("Read ⟨群⟩") | done 且匹配群名 |
| `remember`/`forget` | `MemoryEvent` | done 且非 error |
| 其他 | null（隐藏） | |

### 5.5 重点组件

- **`GroupReplies`**（`client.js:7717-7829`）：未 done 时每 ~1s 轮询 `room-progress`，把 `live.replies` + `speaking` 合进来；done 时直接 `JSON.parse(content).replies`。逐 kind 渲染：`user`（bt-urow+MarkdownText）/ `failed`（错误行，`failureLabel`）/ `lookup` / `missed` / `paused`（带"继续"按钮→`continueRoom`）/ `resumed` / `cut` / `approval` / `working`（可点开详情）/ `stopped` / `done` / 其余（`GroupMessage`）。`pending`（speaking）渲染为 `bt-activity` + `Shimmer`。
- **`EventLine`**（`client.js:7237-7263`）：`prefix` + `BotMark` + 名字，`whole` 且单 Bot 时是可点整行。`opts.onOpen` 打开对方会话。
- **`WidgetCell`**（`client.js:5988-6000`）：有 pending `views[sessionId]`（且 callId 匹配、未被 dock 持有）→ `PendingView`；否则若 `checkShownView` 通过且非交互 → `WidgetCard`（只读）。`chatRow` 的 `view` 行也从蓝图重建同一 `WidgetCell`。
- **`SettledQuestion`**（`client.js:3928-3959`）：question 文本 + 已选 option 列表（含 custom）；空选择显示 "Dismissed"。
- **`SecretToolCell`**（`client.js:6322-6335`）：从 `roster.secretRequests[botId]` 找 callId 匹配的请求；pending→`RequestView`；"already saved"→事件行 "{bot} is using {name}"；只读主机→"could not ask for a key"。
- **`MemoryEvent`**（`client.js:7316-7329`）：结果符合 `^(Saved to|Updated in|Removed from) /` 才画；判断 team/bot 目标，点开记忆面板。
- **`TOOL_PHRASES` / `toolActivity`**（`client.js:7646-7699`）：正则到短语的映射（web_search→"Searching online" 等）；具名工具（ask_user/request_secret→null；list_secrets/group_relay/message_bot/stop_bot/create_bot/update_bot/list_bots/read_group_chat/read_own_chat/remember/forget/recall…）→ 明确 label+state。用于活动行的实时短语。
- **`workPhrase`**（`client.js:7700-7708`）：把 `work.bots[id]`（tool/said/note/target/switching/folder）合成一行"在做什么"。

### 5.6 turn tail（deliverables）与 changes 卡的关系

- 自带插件**不覆盖** DSH 官方 turn tail：`turnTail` 只叠了分隔线（`TurnTailCell`，`client.js:7601`）、`BtwTurnTail`、`LearningTurnTail`（`src/client/index.js:897-903`）。DSH 官方 turn tail 自身承载"这一轮交付的文件卡（deliverables）"，插件靠它显示产物卡。
- 宿主侧产物口径（`projection.js:334-410`，对齐官方 `deliverablesDefinition`/`presentedForClosing`）：**轮闭合才出卡**、位置钉在该轮 `turn/end` 的 seq、有 `present` 声明只用声明（同轮按 path 去重、首现定序、描述取最新），否则按 `produced`（write/edit/str_replace_editor 变更成功结果）；>4 个折叠（`COLLAPSED_ARTIFACTS=4`）。
- 群聊产物卡（`roomArtifactRow`，`projection.js:581-600`）：**只认成员 present 声明、不扫盘**；按时刻分桶到各轮，**每轮一张**（`bucketPresentsByTurn`，`549-573`），key `present:<roomId>:<turn>`。
- 即：**产物卡（`artifact` 行）= deliverables 的渲染面**；`present` 工具行被有意去掉，二者不是两个面（`timeline-parity.md` 规则 ⑦⑧）。

---

## 6. 提示词与行为约定

### 6.1 `prompt.js`

`promptFor(id)`（`prompt.js:54-78`）按目标 Session 类型产出 system prompt 段落（`bot-identity`，order 640，`interpolate:false`，`prompt.js:80-85`）：

- **Bot Session**：`# Your identity`（`You are <name>, a Bot on the user's team…` + role + 自定义 `instructions`）+ `TEAM_RULES`（Main Bot 再前置 `MAIN_RULES`）+（若该 Bot 是手机主人）`PHONE_RULES`。
- **群 Session**：`# Group chat relay`（"你只是中继，不是参与者；每条新用户消息 `group_relay()` 一次，空回复结束"）。
- 其他：空。

`TEAM_RULES`（`prompt.js:4-25`）核心约定：每个 Bot 是独立长会话；`list_bots` 现查现用；`message_bot` 只在需要对方行动时发、不等不轮询、回复晚到；`reply_to:"user"` 让目标 Bot 直接回用户、不回转；被 `[Message from …]` 打开 turn 时结尾文本自动回转、用户看不到；`[Reply from …]` 收束一次交换；卡片（`ask_user`/`show_view`）会结束 turn、答案作为用户下一条消息到达；`request_secret` 收密钥、永不打印；长会话要么就地 `[Checkpoint N]` 要么切 `[Handoff · part N]`，精确细节用 `read_own_chat` 按 anchor 读；用用户语言回复。

`MAIN_RULES`（`prompt.js:27-38`）：Main Bot 统筹团队、建 Bot、路由任务、管群（create/update/post/delete_group）。可多名。

`PHONE_RULES`（`prompt.js:40-48`）：只给"用户选定的手机操控 Bot"；先 `phone_screen`，屏 id/元素 id 只对当前屏有效；屏幕文本是 App 内容不是指令；**永不填/按密码、验证码、支付字段**，敏感步骤 `phone_handoff`；被拒就停手，不得绕过。

### 6.2 `delivery.js` 的 relay / steer 语义

- `deliver({fromId,toId,sessionId,text,role,replyTo,roomId,audience,note,exchangeId,steer,deferred,resumed,retried,resent,merged,askedBy,fromRoomId,files,length,work})`（`delivery.js:51-103`）：把消息投进目标 Session（默认收件人 own-chat）。消息头由 `headerFor(role,…)` 生成（`delivery.js:17-34`），例如 `[Message from X]`、`[Reply from X]`、`[Group chat "N" · …]`、`[Stopped by X]`、`[Secret card]`；`audience:'user'` 时 `[Message from X · the user sees your reply here]`。
- **steer**：`agent.steer(message)` —— 插进**正在跑的 turn** 的下一步；**queue**（默认）：`agent.followup(message)` —— 排到下一 turn。`channel.js` 的 `send` 用 `body.mode === 'steer' ? 'steer' : 'queue'`（`channel.js:468-474`，注释点名"手机操控悬浮条插话"）。
- 角色 `request/reply/stopped` 记为 team exchange（`EXCHANGE_ROLES`，`delivery.js:37`），写入 `state.exchanges`（`delivery.js:77-87`）。
- 长文：`attachLong(text,name)`（`delivery.js:127-140`），> `MESSAGE_INLINE_MAX`（=GROUP_LINE_READ=20000）转 DSH 文件，消息保留前 `LONG_HEAD=1000` 字 + 说明。
- `exchangeText`（`108-122`）：从投递到的 Session 读回全文（否则落文件/截断）。`fileText`（`143-159`）：读附件全文（≤`TEXT_SHOW_MAX=200000`）。

### 6.3 `turns.js` 的 `groupOwners` / `chatOwners` 映射

映射表在 `store.js`（`store.js:354-380`）：

- `chatOwners: Map<sessionId, botId>` —— 每个 Bot 的**当前 own-chat part** Session → botId（`chatOf(botId)=bot.sessionId`）。
- `groupOwners: Map<sessionId, {botId, roomId}>` —— 每个成员在**每个群**的隐藏群 Session → 所属。
- `pastOwners: Map<sessionId, {botId, roomId?}>` —— 归档的旧 part（own-chat 或群的）。
- `selfOf(id)`：own-chat → groupOwners.botId → pastOwners.botId（`store.js:380`）。
- `indexSessions()` 在每次 `save()` 重建（`store.js:361-377`），并调 `rt.indexAgents()`。
- turns 用它们决定：一个 turn 是否隐藏（`exchangeTurns`）、回复去哪（own-chat vs 群，`speaksInGroup` `turns.js:196`）、活动/工作在同一个 Bot 上如何合并（`activityView` `271-289`，`workView` `311-327`，`roomWorkView` `336-351`）。
- 群轮：`roomWaiters`（等回复的 waiter）、`roomProgress`（进行中的回复）、`roomInbox`（下轮要中继的排队项，用户项带稳定 id `roomq:<roomId>:<n>` 可 `room-queue` 撤回）、`stopRoomTurn`（用户停一轮 → 停成员工作）。

### 6.4 群聊语义（`groups.js`）

- 两种发言模式 `ROOM_MODES = ['everyone','admin']`（`constants.js:53`）：`everyone` 每个成员按序都答（admin 先）；`admin` 普通消息先到 admin，由 admin 用 @Name 点名。新群默认 `admin`（`constants.js:48-52`）。
- @提及（`mentionsIn`，`groups.js:86`）选择发言者；成员回复里的 @Name 会叫下一个成员；只有 admin 的 `@all` 能叫全员（`groups.js:541-548`）。
- 轮次上限 `relayLimit`（`defaultRelayLimit(room)`，`groups.js:40`），超限暂停（`paused`，可 `room-continue`）。
- 群工具：`group_relay`（群里每条新消息调一次，不参与正文）、`read_own_chat`（群里看自己 own-chat）、`read_group_chat`（own-chat 里看某群）。
- 群 Session 的 system prompt 是"中继"人设（§6.1），成员正文走 `agent/assistant-stream` → `roomStepText` 实时发群。

### 6.5 AGENTS.md 相关内容

- 仓库 `DS-Bot/AGENTS.md`（72 行）是**给 AI 编码代理**的规则（不是给 Bot 的）：`ds-bot/` 是 DSH 插件、`app/` 是桌面应用；**硬规则**：基于 DSH 不 patch DSH；不写/印/提交密钥；只用原创素材；`client.js` 是 `src/client/` 的构建产物（禁手改）；不碰用户自己的 DSH 数据。
- 与之相关的是 **Bot 的 `instructions` / `brief`**（不是 AGENTS.md 文件）：`create_bot`/`update_bot` 的 `instructions` 进 system prompt 的 identity 段（`prompt.js:63`）；`brief` 只用于**开局 kickoff**（`roster.js:106/136-147`）：首个 Main Bot 无模型前 `waiting`，用户选模型后发 kickoff，让 Bot 先自我介绍再问从哪开始。

---

## 7. 对我们的能力面（能力矩阵）

> 列义：**能力/信息** → **宿主数据来源** → **官方网页端渲染** → **我们现状**。
> 我们现状出处：`app/src/main/java/com/dshm/bot/`（`data/remote/`、`ui/chat/`、`ui/settings/`）。
> 图例：🟢 已对齐 / 🟡 部分 / 🔴 官方有、我们没有 / 🚫 有意不做。

### 7.1 团队与状态

| 能力/信息 | 宿主数据 | 官方渲染 | 我们现状 |
|---|---|---|---|
| 团队名单（Bot/群/Agent） | `state.bots/rooms/agents` | `TeamSidebar` | 🟢 `DsBotParser.parseState` + `HomeScreen`/`ui/home` |
| Main Bot 标记/首个 Main | `state.mainBotId(s)` | 侧栏 tile/徽标 | 🟢 `set-main` 已用 |
| Bot 预览（最新输出） | 本质无（或 `chat` 末条 / `feed message`） | 侧栏副标题 | 🟢 `DsBotRepository` 用 `chat` 补 + `feed message` 实时 patch |
| 团队 prefs（主题/布局/宽度/思考行/动效） | `state.prefs` | 设置面板 | 🟢 `set-prefs`（`PersonalizationScreen`） |
| 只读主机提示 | `state.readOnly` | 顶部提示 | 🟢 `hello.readOnly` / `state.readOnly` |
| 用量页 | `state.live` 无关；`usage` | `UsagePage` | 🟢 `usage` / `usage-summary` |
| 诊断 | `diagnostics*` | About/诊断页 | 🟢 `diagnostics` / `diagnostics-log` |
| 插件/DSH 自更新 | `update-check`/`update-install`/`dsh-update-install` | About 更新行 | 🔴 未接（`update-check`/`install`/`repo-stars` 均未用） |
| DSH Agent（列在侧栏、普通会话） | `state.agents` + `add-agent/agent-*`/`remove-agent` | Agent 行 + 原生会话面 | 🔴 未接（6 个 Agent 端点全未用；`agents` 只在别处读） |

### 7.2 单 Bot 聊天（own chat）

| 能力/信息 | 宿主数据 | 官方渲染 | 我们现状 |
|---|---|---|---|
| 统一时间线（快照+增量） | `timeline` 端点 / `timeline` feed | 五路 cell 覆盖 | 🟢（Phase 1 已落地；`TimelineRows.kt`/`TimelineRowMapping.kt`） |
| user/bot 气泡、多段、折叠 | `displayItems` user/bot | `EarlierUser`/`Bubbles` | 🟢 `ChatTranscript`/`ui/chat` |
| 长文 `whole`（Show all） | bot 行 `whole` + `file-text` | `Bubbles`+`file-text` | 🟢 `WholeAttachmentRow` + `file-text` |
| 已答 question / view 回放 | `answer` 行 | `AnsweredCell`/`AnsweredView`/`SettledQuestion` | 🟢 `ChatEntryKind.ANSWERED` + `ChatPending` |
| 展示型 view 卡 | `view` 行 / `show_view` | `WidgetCell`/`WidgetCard` | 🟢 `PendingDock` 全 widget 类型（metric/table/chart/progress/callout/sources/html/inputs/options/confirm/form/submit） |
| 交互 view 卡（提交） | `state.views` | `PendingView`（dock） | 🟢 `view-reply` |
| ask_user 卡 | `state.questions` | `PendingQuestion`/`QuestionCard` | 🟢 `answer-question` / `dismiss-question` |
| 密钥卡 | `state.secretRequests` | `SecretDock`/`SecretToolCell` | 🟢 `secret-set/allow/cancel/update/delete` |
| 分隔线/新消息线 | `divider` | `PartLine` | 🟢 `PartDividerRow`；新消息线 🚫（用户要求去） |
| earlier 分页 | `earlier`/`timeline cursor` | `EarlierParts` | 🟢 `loadEarlier` + cursor |
| 流式草稿 | `stream` feed | `Bubbles` 原地 | 🟢（侧通道；有意偏离，观感等价） |
| 活动行（思考/工具短语/耗时） | `state.live.activity`/`work.turns` | `ActivityIndicator` | 🟢 `ChatActivity` |
| 模型选择（首聊 waiting） | `bots[].waiting`/`models`/`efforts` | `ModelChoiceDock` | 🟢 |
| run-stats（状态栏统计） | `run-stats` | 官方状态栏 | 🟢 用于 Bot 详情 |
| Bot 间往来对话 | `exchange`/`exchange-text` | 点 `EventLine` 打开 | 🟢 `BotExchangeDialog` |
| BTW aside 卡 | `state.btwCards` | `BtwCardView` | 🚫（2026-11-09 有意不做） |
| reactions | 浏览器本地 | hover 工具 | 🚫（本地存储，无法同步） |
| **侧聊（side chats）** | `state.sideChats` + `side-new/ask/cancel/close` | 侧栏侧聊面板 | 🔴 **完全未接** |

### 7.3 工具事件行

| 能力/信息 | 宿主数据 | 官方渲染 | 我们现状 |
|---|---|---|---|
| message_bot/stop_bot 事件行 | `tool` 行 | `EventLine` | 🟢（`.tool` 行 → EVENT） |
| create_bot/update_bot 事件行 | `tool` 行 | `EventLine` | 🟢 |
| 群操作事件行（含群名正则） | `tool` 行 | `GroupEvent` | 🟢 |
| read_group_chat 事件行 | `tool` 行 | `GroupEvent` | 🟢 |
| remember/forget 记忆事件行 | `tool` 行 | `MemoryEvent` | 🟢 |
| group_relay 回复展开 | `tool` 行 + `room-progress` | `GroupReplies` | 🟢（timeline room 行 + 轮询兜底） |
| present 工具行 | — | 🚫（官方两态行；我们与官方 2026-11-14 一致：不画） | 🟢（已对齐"不画"） |
| 其他工具（shell/read/write…） | — | 隐藏（DRAWN 外） | 🟢（不画） |

### 7.4 群聊

| 能力/信息 | 宿主数据 | 官方渲染 | 我们现状 |
|---|---|---|---|
| 群 items（user/post/relay replies） | `room-chat`/`timeline room 行` | `GroupReplies` | 🟢 |
| 谁在发言 / 轮状态 | `room-progress`/帧 `running,speaking` | `bt-activity` | 🟢 |
| 群产物卡（每轮一张） | `room-artifacts`/timeline `present:<roomId>:<turn>` | turn tail 产物卡 | 🟢（走 timeline；`room-artifacts` 端点未直用） |
| 群轮事件行（done/stopped/failed/missed/lookup/resumed/cut） | room `event` 行 | `GroupReplies` 事件行 | 🟢 |
| working/approval 行 | — | 🚫（官方画；我们与官方 2026-11-14 一致：不画） | 🟢 |
| 暂停/继续 | `rooms[].paused` | "Continue" | 🟢 `room-continue` |
| 停某成员 | `stop-member-work` | 成员 dock | 🟢 |
| 群设置（成员/admin/mode/notice/目录/权限/relayLimit） | `update-room` | `GroupSettings` | 🟢 `RoomSettings*` |
| 撤回排队消息 | `room-queue remove` | dock | 🟢 |

### 7.5 设置与资源

| 能力/信息 | 宿主数据 | 官方渲染 | 我们现状 |
|---|---|---|---|
| Bot 设置（名/角色/色/模型/思考/目录/指令/头像） | `create-bot`/`update-bot`/`set-flags`/`avatar` | `BotSettings` | 🟢 |
| 复制 Bot | `duplicate-bot` | — | 🟢（`BotDetailsViewModel`） |
| 工作目录选择 | `folders`/`folder-list`/`folder-pick`/`team-workspace` | `FolderMenu` | 🟡 用 `folders`/`folder-list`；`folder-pick`（原生选择器）用了；**`team-workspace` 未用** |
| 定时任务 | `schedules`/`schedule-create`/`schedule-delete` | `Schedules` | 🟢 |
| 记忆面板 | `memory-view`/`memory-ask`/`memory-forget` | `MemoryPanel` | 🟢（另有 `memory-edit`/`memory-add` 直改 🟢） |
| 密钥库 | `state.secrets`/`secret-*` | 密钥面板 | 🟢 |
| **能力库（新增/编辑）** | `capability-save` | `CapabilitySettings` | 🟡 有 `capability-approve`/`capability-delete`/`bot-capability`（挂/摘/批/删），**`capability-save`（新建/编辑一项）未用** |
| 连接器（GitHub） | `state.live.connectors`/`connector-*` | `Connectors` 页 | 🟢 |
| provider 与 key 管理 | `providers`/`provider-add`/`provider-key`/`provider-models`/`provider-remove` | provider 页 | 🟡 我们走 **DSH 引擎设置**（`EngineSettingsViewModel` 的 `providers` 命名空间），**DS Bot 的 provider-* 端点未用** |
| 权限预设 | `permissions` + `state` | PermissionPicker | 🟢（`PermissionScreen`） |
| 手机控制（无障碍） | `phone-wait`/`phone-result` + `phone_*` 工具 | Android UI | 🟢（`service/Phone*`） |
| Bot 浏览器 | `browser-wait`/`browser-result` + `read_browser` | webview 面板 | 🟢（`BotBrowser*`） |

### 7.6 「官方有、我们没有」清单（按重要性排序）

1. **侧聊（side chats）**：`side-new/side-ask/side-cancel/side-close` + `state.sideChats`。官方有整套"旁路问答"面板（不写 Session、不派工具），我们**零覆盖**。这是唯一成体系的缺失功能面。
2. **DSH Agent 管理**：`add-agent/agent-session/agent-adopt/agent-archive/agent-rename/remove-agent` + `state.agents`。官方侧栏有独立的"DSH Agent"（普通 dsh 会话，无团队工具），我们未呈现、未接任何 Agent 端点。
3. **能力库写入**：`capability-save`（新建/编辑能力条目）。只能挂/摘/批/删已有项，不能新建。
4. **团队工作区切换**：`team-workspace`。目录选择能用，但改"团队工作区"这一项缺。
5. **provider 管理与密钥写入走 DS Bot 端点**：`provider-add/provider-key/provider-models/provider-remove`。我们改走 DSH 引擎设置（能否在"宿主只有 /api/bot 通道"的场景下工作，取决于 DSH 设置通道是否同样可达——见 §8）。
6. **插件/DSH 自更新 UI**：`update-check/update-install/dsh-update-install/repo-stars`。
7. （轻）`room-artifacts` 端点未直用：我们用 timeline 的 `present:<roomId>:<turn>`，功能等价，仅少一个兜底入口。

**已明确"有意不做"（非缺口）**：BTW aside 卡、reactions、群消息时间/耗时、新消息分隔线、present 工具行、群 working/approval 行。

---

## 8. 仍不确定

1. **`approval/request` 在团队 Session 的双通道冲突**：插件 `approvals.js` 已用 `prepend` 抢先应答；但客户端 mux(`$events`)是否仍收到同一条 waterfall？我们同时有"waterfall 审批"和"`state.work.approvals`+`approval-answer`"两条路，实际哪条对团队 Session 生效需真机取证（属 DSH × 插件交界，另一路调查）。
2. **`browser-takeover/browser-stop/browser-give-back/browser-import`**：出现在 v0.2.4 `src/client/index.js`，但 v0.2.5 `channel.js` 无对应 case。推测由 Desktop 应用自己的 bridge 注册（不在插件路由内），未验证。
3. **`update-check` 的完整返回形状**（`{…updateCheck, dsh}`）未逐字段确认（`update.js`/`dsh-update.js` 未通读）。
4. **`state.prefs` 的全部键**（theme/accent/motion/layout/width/flatWidth/thinking 之外是否还有）未穷举；`set-prefs` 只校验上述键。
5. **`capabilities.capabilityStatusesView` 与 `capabilityLibraryView` 的字段**未逐字段确认（能力页能力条目结构）。
6. **记忆面板 `memory-view`/`memory-ask` 的详细载荷**未逐字段读（`memory.js` 1584 行只读了端点分派与 edit/add）。
7. **`usage` 行数组的列序**：`mobile.js:126` 注释为 `[hour, owner, model, kind, input, cacheRead, cacheWrite, output, calls]`，但未在 `usage.js` 内在逐列核对。
8. **`roomItems` 与 `timeline room 行` 的字段级差异**：官方网页 `room-chat` 走 `roomItems`；我们的 timeline 走 `composeRoomRows(roomItems…)`，二者对 `lookup/missed` 等事件文案是否完全一致未逐一比对。
9. **`side.js`/`btw.js` 的载荷细节**（`sideView`/`btwView` 字段）未读；由于我们未接侧聊，暂不影响，但做侧聊前须补。
10. **v0.2.4 与 v0.2.5 的差异范围**：任务描述称 v0.2.5 新增 `mobile.js` roomItems 与 `groups.js` roomArtifacts；本文以 v0.2.5 为准，未做完整 diff。
