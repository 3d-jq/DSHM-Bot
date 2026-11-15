# DSH 研究（DSH Study）

> 目的：把 **DSH（DeepSeek Harness，我们引擎的底层）** 完整摸清，作为 **DSHM Bot（Android 客户端）对齐的权威依据**。
>
> 版本：**`@deepseek-ai/dsh* = 0.2.1-rc.4`**（与 rootfs 一致，见 `app/src/main/assets/runtime/manifest.json` 的 `dshVersion` / `runtimeVersion: r15-dsh0.2.1-rc.4-...`）。
>
> 只读研究：**没有改任何代码**。源码不在仓库，从 **npmmirror** 拉取（`https://registry.npmmirror.com/@deepseek-ai/<pkg>/-/<pkg>-0.2.1-rc.4.tgz`），解在仓库外的隔离临时目录（`.zcode/tmp/dsh-research/`，已在 `.gitignore` 内），**未执行包内任何脚本**。
>
> 引用写法：`<包>:<包内相对路径>:<行号>`。行号来自 npm 发布的 `lib/*.js` / `lib/types/*.d.ts`。
> 主线证据：`dsh-session`（会话/事件）、`dsh-agent-loop`+`dsh-agent`（循环/收件箱）、`dsh-api-session-controller`（会话 RPC/follow/page）、`dsh-api-gateway`+`dsh-client-connection`（传输/鉴权）、`dsh-workspace-changes`+`dsh-client-ui-deliverables`（改动）、`dsh-client-ui-slots`+`dsh-client-ui-chat`+`dsh-client-ui-conversation`（槽位）、`dsh-tools`+`dsh-tool-present`（工具）、`dsh-web-app`+`dsh-base`（组合/能力边界）。

---

## 0. 一句话总览

**DSH = 一个 Cordis 插件组合**：`@deepseek-ai/dsh` 只是引导壳，能力全在 `@deepseek-ai/dsh-*` 独立包里。会话是**追加式事件日志**（`seq` 连续），客户端 UI 是日志的**纯投影**，浏览器只通过 **`/api` 上的“HTTP 精确路由 + 通用 RPC 信封 + `/api/remote.mux` WebSocket”** 这一套传输抵达宿主。**DS Bot 就是挂在 DSH 上的一层宿主插件 + 一层客户端槽位插件**，它并不改 DSH 内核。

本批拉取的包（示例）与仓库内既有资料：`docs/dsh-plugin-architecture.md`（组合模型）、`docs/timeline-parity.md`（时间线投影宪章）、`docs/runtime-patches-audit.md`（补丁评估）。三者是本文的上下文，本文是**面向对齐的 DSH 本体权威清单**。

---

## 1. 事件与会话模型

### 1.1 会话就是“追加式事件日志”

- 事件信封 `SessionEvent`：`{ type, seq, time, data, ignorable?, surfaceOp?, sourceEventSeqs? }`
  —— `dsh-session:lib/types/types.d.ts:490-513`。`seq` 是会话内单调序列号；`time` 是 Unix epoch 毫秒。
- **`seq` 分配规则**：下一个事件的 `seq` **永远等于当日志长度**（`seq = log.length` 连续性契约），从 0 开始、连续无洞 —— `dsh-session:lib/types/index.d.ts:238-239`。
- 磁盘格式版本 `SESSION_FORMAT_VERSION = 4` —— `dsh-session:lib/types/types.d.ts:55`。`SessionHeader` 里带 `id/createdAt/cwd/parentSession/isSeeded/origin/delegationDepth/agentPreset`（`types.d.ts:59-96`）。
- 只有“消息型”事件（`surface`）能带 `surfaceOp`/`sourceEventSeqs`；`SurfaceEventType = system/message | developer/message | user/message | assistant/message | tool/result`（`types.d.ts:443`）。其余是 `log-only`（边界、审计、UI 状态）。
- 未知事件类型：读取方遇到不认识的 `type` 且**没有** `ignorable: true` 时**必须拒绝重建会话**（防止静默丢事件）—— `known-event-types.js:7-20`、`types.d.ts:498-508`。

### 1.2 完整事件词汇表（62 个 `type`）

**唯一权威来源**：`dsh-session:lib/types/known-event-types.js:21-84` 的 `KNOWN_SESSION_EVENT_TYPES`（生成物）。每个 `type` 由**产出它的包**通过声明合并（`interface SessionEventMap`）注册。

| 分类 | 事件 `type` | 声明的包 |
|---|---|---|
| 边界（核心） | `turn/start` `turn/end` `step/start` `step/end` | `dsh-session`（`types.d.ts:263-287`） |
| 消息（核心） | `system/message` `developer/message` `user/message` `assistant/message` `assistant/attempt` `tool/call` `tool/result` | `dsh-session`（`types.d.ts:295-389`） |
| 请求头（核心） | `request/header` `request/context` `session/end-seed` | `dsh-session`（`types.d.ts:394-433`） |
| 收件箱 | `agent/inbox/spliced` | `dsh-agent`（`types.d.ts:79-93`） |
| 审批 | `approval/asked` `approval/decided` `approval/policy` | `dsh-user-approval`（`types.d.ts:28-56`、`index.d.ts:34`） |
| 重试 | `llm/retry` `llm/retry-started` | `dsh-llm-retry`（`types.d.ts:5-12`） |
| 交付 | `deliverables/presented` | `dsh-tool-present`（`types.d.ts:11-18`） |
| 改动 | `workspace/changes` | `dsh-workspace-changes`（`types.d.ts:104-111`） |
| 模型选择 | `model/selection` | `dsh-api-session-controller`（`types.d.ts:31`） |
| 待办 | `todo/write` | `dsh-tool-todo`（`types.d.ts:27-33`） |
| 计划模式 | `plan/mode` | `dsh-plan-mode`（`index.d.ts:37-46`） |
| 目标 | `goal/change` | `dsh-goal`（`domain.d.ts:47`） |
| 任务 | `job/registered` `job/settled` | `dsh-jobs`（`types.d.ts:48-58`） |
| 子代理 | `subagent/catalog` `subagent/descriptor` `subagent/model-selection-policy` | `dsh-subagent`（`catalog.d.ts:36`、`descriptor.d.ts:27`）、`dsh-tool-subagent` |
| 预设 | `agent-preset/selected` | `dsh-agent-preset-registry`（`session.d.ts:18`） |
| 标题 | `session/title` `session/title-llm-request` | `dsh-session-title`（`index.d.ts:37`）、`dsh-session-title-llm` |
| 斜杠命令 | `command/run` `command/done` | `dsh-commands`（`types.d.ts`） |
| 反馈 | `feedback/message-put` `feedback/message-delete` `feedback/record` | `dsh-message-feedback`（前两个；`feedback/record` 本批未定位） |
| 压缩 | `compaction/start` `compaction/end` `compaction/summary` `compaction/prune` | `dsh-compaction*`（本批未拉） |
| 图片卸载 | `image/offload` | `dsh-compaction-image-offload` |
| 工作目录 | `working-directory/change` | `dsh-working-directory` |
| 沙箱/权限 | `sandbox/mode` / `permission/preset` | `dsh-sandbox-policy` / `dsh-permission-presets` |
| 定时 | `schedule/change` | `dsh-schedule`（`types.d.ts`） |
| 团队（实验） | `team/member` `team/task` `team/message/queued` `team/message/delivered` | `dsh-experimental-agent-team`（`types.d.ts:185-208`） |
| 工作流 | `tool-workflow/run-start` `run-end` `agent-start` `agent-end` | `dsh-tool-workflow`/`dsh-workflow` |
| PTC 子派发 | `tool/ptc-dispatch` `tool/ptc-dispatch-start` | `dsh-tools`（`types.d.ts:31-75`） |
| 联网 | `web/deepseek-search-llm-request` | `dsh-web-search-deepseek` |
| 遥测 | `session-log-deepseek/delivery-accepted` | `dsh-session-log-deepseek` |
| Hook | `hook/invoked` `hook/result` | 本批未定位（无 `dsh-hook` 包） |

> 注：`hook/*`、`feedback/record`、`permission/preset`、`sandbox/mode`、`working-directory/change`、`image/offload` 这些的**具体 data 形状**来自本批未拉的包；词汇名与归属以上表为准，形状待补（见 §9）。

### 1.3 turn / step 边界语义

- `turn/start {turn}`：开启第 `turn` 个回合——**在循环认领队列输入/跑 pre-step 之前**；拒绝/空输入/取消/失败可**不开 step 就结束**（`types.d.ts:263-265`）。
- `step/start {turn, step}` / `step/end {turn, step}`：一个 step = **一次模型调用 + 它请求的工具执行**（`types.d.ts:279-287`）。
- `turn/end {turn, reason}`：`reason` 是 `TurnEndReason`：`completed | aborted{reason} | blocked | error{error:LlmFailure} | max-tokens | interrupted | forked`（`types.d.ts:163-211`）。`interrupted` 由崩溃恢复/冷读合成，`forked` 由 fork 种子构造写入，循环本身不发这两个。
- 回合边界**不 await 落盘**：per-request 持久化检查点归 `dsh-session-checkpoint-policy`；读者要读最新存储得自行 flush（`types.d.ts:266-273`）。

### 1.4 关键事件的 data 形状（本批已核实）

- **`assistant/message`**：`{ turn, step, message: AssistantMessage, stream: AssistantStreamRecord[], usage?: TokenUsage, interrupted?: true }` —— `types.d.ts:331-339`。
  - `stream` 是**紧凑的定时模型流**（不合并 delta 边界）：记录形如 `{type:'text-chunks'|'reasoning-chunks', time0, index, dt[], texts[]}`、`{type:'tool-call-chunks', ..., id, name?, args[]}`、`{type:'chunk', time, chunk}` —— `dsh-llm:lib/types/assistant-stream.d.ts:20-44`。
- **`tool/call`**：`{ turn, step, callId, name, arguments }`，`arguments` 是**模型原样产出的 JSON 字符串（未解析）** —— `types.d.ts:355-361`。
- **`tool/result`**：`{ turn, step, message: ToolResultMessage, error?: {name, code, reason?}, meta?: JsonValue }`。—— `types.d.ts:375-389`。`meta` 是**工具私有的展示负载**（对内核不透明、必须 JSON 可序列化、会被写入日志以便重放还原同一张卡片；例如 `dsh-tool-fs` 在这里放结果期上下文 diff）。
- **`deliverables/presented`**：`{ turn, callId, files: PresentedFile[] }`，`PresentedFile = { path, description? }` —— `dsh-tool-present:lib/types/types.d.ts:5-18`。由 `present` 工具成功结果写入（含嵌套调用）。
- **`workspace/changes`**：**只有 `{ turn }`** —— `dsh-workspace-changes:lib/types/types.d.ts:104-111`。摘要本体**不在事件里**，存在宿主内存，按 `(sessionId, seq)` 取（见 §4）。
- **`agent/inbox/spliced`**：`{ target: 'next-turn'|'next-step', start, removedCount?, inserted: UserMessage[], outcome?: 'canceled' }` —— `dsh-agent:lib/types/types.d.ts:79-93`。
- **`llm/retry`**：`{ retryId, turn, step, provider, mode:'normal'|'always', policyKey, retry, delayMs, failure: LlmFailure, maxRetries? }`；**`llm/retry-started`**：`{ retryId, turn, step, retry }` —— `dsh-llm-retry:lib/types/types.d.ts:14-40`。
- 文本/图片/文件块：`ContentBlockMap = { text, reasoning, image, file, tool-call, tool-addition, tool-removal }` —— `dsh-llm:lib/types/types.d.ts:114-126`。附件引用型 `image`/`file` 走 `dsh-attachment`。`TokenUsage`（inputTokens 为**非缓存**输入，缓存单列）—— `types.d.ts:160-174`。

---

## 2. 会话流协议

传输底座见 §3；本节是**会话读写的精确形状**，权威源 `dsh-api-session-controller:lib/types/types.d.ts` 与 `.../history.js`。

### 2.1 `session/follow`（实时流）

- 请求 `SessionFollowRequest`：`{ address: SessionAddress, maxMessages?, turnWindow?, assistantStream? }`
  —— `types.d.ts:482-486`。`SessionAddress = {kind:'session', sessionId} | {kind:'subagent', parentSessionId, childSessionId, mode}`（`types.d.ts:403-412`）。
- **帧 `SessionFollowFrame`**（`types.d.ts:539-550`）：
  1. 先一帧 **`snapshot`**：`{ header: SessionWireHeader, cursor: number, records: SessionHistoryRecord[], hasMore, projections, assistantStream? }` —— 快照是**从 cursor 往前的窗口**（`paginate(...)`，默认 `maxMessages`）。`cursor` 是取快照时的**日志切口 seq**。
  2. 之后是增量帧：`{ type:'event', event: SessionWireEvent }`，以及（当 `assistantStream:true`）`{ type:'assistant-stream', frame }`。
- **seq 连续校验（关键）**：增量从 `cursor+1` 起，**逐条要求 `event.seq === 期望值`**；小于则跳过（已包含在快照），**不连续直接抛 `gateway/internal: session event stream skipped seq N`** —— `history.js:244-249`。即**单条流内 seq 无洞**。
- **断线重连怎么补**：`follow` 请求**不带 cursor**——重连=**重新开一条 follow，拿一帧新 `snapshot`**，快照**覆盖**客户端已有状态；`snapshot.cursor` 告知续点。没有“靠 seq 补齐”的独立通道。冷启动/续读历史用 `session/page`。

### 2.2 `session/page`（历史分页，向后翻）

- 请求 `SessionPageRequest`：`{ address, throughSeq: number, beforeSeq?, maxMessages?, turnWindow? }` —— `types.d.ts:467-480`。语义：
  - `throughSeq`：**inclusive 日志切口**（必须来自对应 follow 的 snapshot；`-1` 表示尚无事件）。校验：不得越过当前游标、且日志必须在 `throughSeq` 有连续前缀 —— `history.js:101-118`。
  - `beforeSeq?`：本页的最右下界（**不含**）。
  - `turnWindow?: { minMessages, minTurns }`：**按消息对齐**——跨到“至少 minMessages 条 append 原生的 user/assistant 消息、至少 minTurns 个 turn 起点（含 `beforeSeq` 处那个不完整 turn）”才停，除非 `maxMessages`/历史耗尽先赢（`types.d.ts:473-479`、`paginate(... history.js:395-425`）。
- 响应 `SessionPage`：`{ records: SessionHistoryRecord[], hasMore: boolean }` —— `types.d.ts:534-537`。

### 2.3 `session/control`（会话列表实时变更）

- 流帧 `SessionControlFrame`（`types.d.ts:562-568`）：每代**先一帧 `baseline`**（`{ projections: Record<SessionId, SessionProjectionBaseline> }`），随后是 `{ type:'projection', sessionId, key, value, seq }`（一条已完成投影及其持久化水位）。用于会话列表列/状态的实时更新。
- 客户端可见的转发事件（`api-session-controller`）：`api-session/activity | added | error | removed | status` —— `dsh-api-session-controller:lib/types/remote-events.d.ts:2`。

### 2.4 `agent/inbox` 的 claim / splice 语义

- 每个 Agent 有**两条有序待处理队列**：`next-turn`（逐回合）与 `next-step`（下一步边界）—— `dsh-agent:lib/types/types.d.ts:31`、`runtime-types.d.ts:44-84`。
- 操作：`append/prepend/replace/remove/splice/clear`；`splice(target,start,deleteCount,inserted)` 应用标准 splice 语义并**持久化记录归一化结果**（`runtime-types.d.ts:48-84`）。`clear()` 先清 `next-step` 再清 `next-turn`。
- **“claim”（认领）**：循环在 `turn/start` 之后把队列输入认领进本回合（`types.d.ts:263-265` 的注释；`session/prompt` 只是把消息放进收件箱，回执 `{accepted:true}`，见 §3）。
- 每次变更写一条 `agent/inbox/spliced`；`session-projection` 在 `Session.append()` 返回前先把已提交事件折叠进投影，**收件箱的实时通知在那次 commit 之后跟随**（`dsh-agent:types.d.ts:79-83`）。取消且 `keepInbox:true` 时不发 `canceled` splice（`runtime-types.d.ts:38-42`）。

### 2.5 客户端 `$events` 转发流（审批/提问）

- 网关内建逻辑流：`$events`（下行 frame：`ready | emit | waterfall | cancel`）与 `$events/result`（上行回执）—— `dsh-api-gateway:lib/types/stream-protocol.d.ts:6-8,45-70`。
- `ready` 帧带 `clientId` + `host.home`；`waterfall` 帧带 `event/eventId/agentId/request`，客户端必须回 `{clientId, eventId, outcome:{kind:'result'|'next'|'rejected'}}`（`stream-protocol.d.ts:32-110`）。
- 需要用户回应的两类 waterfall：**`approval/request`**（`dsh-user-approval`）与 **`user-questions/request`**（`dsh-user-questions`，`types.d.ts:145`）。

---

## 3. HTTP API 全清单

### 3.1 三条传输通道（都在 `127.0.0.1:{PORT}` 上）

1. **通用 RPC 信封（`/api` 共享通道）**：`POST /api/<method>`
   - 请求：`{type:'client-request', rpcId, method, payload}`；响应：`{type:'server-response', rpcId, result:{ok,value} | {ok:false,error}}`（HTTP 仍 200）—— `dsh-client-connection:lib/types/rpc.d.ts:63-77`。
   - 前缀常量 `API_PATH = "/api"`（`api-path.d.ts:6`）。**注意**：客户端里 `payload` 的 `args` 包装由调用方决定（DSH 自身 `payload:{args}`；本仓库 `DshClient.rpc` 亦如此）。
2. **精确 Fetch 路由（GET/HEAD/POST，非 JSON-RPC 专用）**：`ConnectionFetchRoute { path, methods, requestBody:'buffered'|'streaming', fetch(Request)->Response }`，经 `ctx.connection.fetch.register(...)` 注册，或经 `ctx.connection.rpc.intercept('/api', matches, handler)` 在共享通道上拦截（`rpc.d.ts:120-176`）。
3. **WebSocket mux**：`REMOTE_STREAM_MUX_PATH = "/api/remote.mux"`（`dsh-api-gateway:stream-protocol.d.ts:4`）。承载所有逻辑流（含 `session/follow`、`session/control`、`workspace/follow`、`job/follow`、以及内建 `$events`）。帧：`open/item/end/cancel` 上行，`item/error/end` 下行（`stream-protocol.d.ts:118-160`）。

### 3.2 鉴权（cookie/token）

- 启动打印一行 `dsh web: http://127.0.0.1:PORT/?token=<43 base64url>` —— `app/src/main/assets/runtime/manifest.json:194-199`。
- `GET /?token=...`：**303 重定向** 到 `./` 并 `Set-Cookie: dsh-auth-<id>=v1.<...>; Path=/; HttpOnly; SameSite=Strict`（manifest:200-203）。cookie 用**持久签名密钥**签（`BrowserAuth.create` / `isAuthenticated`，`dsh-client-connection:lib/types/browser-auth.d.ts:8-60`）。
- API 客户端存在独立换票路由 `/connect`（`CONNECT_PATH`，`connect-protocol.d.ts:5`），换出**独立 API operator Peer**（`interactions` 可为 `none`）。
- 无 cookie 的 `/` 返回 **401**；有 cookie 返回 200（manifest:204-207）。非 loopback 权威需 `trustedHosts` 显式声明（`rpc.d.ts` 的 `allowsRemoteAuthorities`）。
- **每次 `POST /api/<method>` / 精确路由都要带 cookie**；401/403 时重登再试一次（本仓库 `DshClient.withAuthRetry`）。

### 3.3 精确 Fetch 路由（全量，本批可核实）

| 路径 | 方法 | 归谁 | 用途 |
|---|---|---|---|
| `/api/session/uploadFileBinary` | POST | `dsh-client-file-upload`（`protocol.d.ts:2`） | 原始字节上传，`?sessionId=&name=`，body `application/octet-stream`，返回 receipt |
| `/api/file` | GET | `dsh-api-workspace-files`/宿主 | `?path=<绝对路径>` 读文件字节（有体积上限） |
| `/api/changes.summary` | GET | `dsh-client-ui-deliverables`（`changes.d.ts:5`） | `?sessionId=&seq=` 取某 turn 改动摘要 |
| `/api/changes.diff` | GET | 同上（`changes.d.ts:7`） | `?sessionId=&seq=&index=` 取单文件对比 |
| `/api/changes.open` | POST | 同上（`changes.d.ts:9`） | 在宿主桌面打开某文件 |
| `/api/present.open` | POST | `dsh-client-ui-deliverables`（`presented.d.ts:6`） | 打开 `present` 声明的文件（`open`/`reveal`） |
| `/api/present.host` | GET | 同上（`presented.d.ts:8`） | 宿主桌面能力/文件管理器元数据 |
| `/api/remote.mux` | WS | `dsh-api-gateway`（`stream-protocol.d.ts:4`） | 所有逻辑流 |
| `/connect` | GET | `dsh-client-connection`（`connect-protocol.d.ts:5`） | API 换票 |
| `/` + `/api/...` | 任意 | `dsh-client-connection` | 鉴权/索引/共享通道兜底 |

> 说明：`/api/bot`、`/api/bot/feed` **不是 DSH 核心**，是 DS Bot 插件注册的（见 §6.4）。

### 3.4 通用 RPC 方法清单（按 namespace）

**会话**（`dsh-api-session-controller`，`typert.remote-client.d.ts:41-61`）：
`session/list` `session/create` `session/rename` `session/fork` `session/search` `session/prompt` `session/cancel` `session/updateQueue` `session/modelCatalog` `session/selectModel` `session/projections` `session/page` `session/follow`（stream） `session/control`（stream） `session/attachment` `session/canOpenWorkspacePath` `session/openWorkspacePath` `session/workspacePathApplications` `session/initializeDefaultModel`；`skills/list`；`fileReferences/list`（agent 作用域）。

**工作区**（`dsh-api-workspace-controller`）：`workspace/create` `workspace/delete` `workspace/rename` `workspace/follow`（stream） `workspace/initializeDefault` `workspace/insertBefore` `workspace/insertSessionBefore` `workspace/pinSession` `workspace/unpinSession` `workspace/archiveSession` `workspace/unarchiveSession`；`directoryPicker/list` `directoryPicker/createDirectory` `directoryPicker/pick`。

**工作区文件**（`dsh-api-workspace-files`）：`workspaceFiles/list` `workspaceFiles/stat` `workspaceFiles/read` `workspaceFiles/readBytes` `workspaceFiles/changes`。

**账号**（`dsh-api-account-controller`）：`account/getState` `getProfile` `getBalance` `startSignIn` `cancelSignIn` `signOut` `watch`（stream） `watchExpiry`（stream） `hasRunningAccountTasks` `getUnnotifiedBonuses` `ackBonusNotified`。

**设置/凭据**（`dsh-api-settings-controller`）：`settings/describe` `settings/replace` `settings/update` `settings/mutate` `settings/openSettingsDocument`；`credentials/describe` `credentials/set` `credentials/unset`。

**终端**（`dsh-api-terminal-controller`）：`terminal/create` `terminal/list` `terminal/attach`（stream） `terminal/close` `terminal/rename` `terminal/retain` `terminal/shells` `terminal/environment`。

**任务**（`dsh-api-job-controller`）：`job/list` `job/follow`（stream） `job/kill`。

**远端访问**（`dsh-api-remote-access`）：`remoteAccess/follow`（stream）。

**其它 DSH 内建（来自 base/web 组合）**：`userQuestions/answer`、`commands/execute`、`llm/listProviders`、`permissionPresets/catalog` 等由各自插件提供（DS-Bot 之外）。

### 3.5 `POST /api/bot` 之外——DS Bot 自己的 RPC

`dsBot/call {endpoint,payload}` 与 `dsBot/feed {}`（stream）—— `app/src/main/assets/ds-bot/src/host/remote.js:1-15`。供只转发 Remote（不转发 Fetch 路由）的中继使用（如移动 API）。

---

## 4. workspace/changes 机制

- **谁发**：`dsh-workspace-changes` 插件。它观察**每个有 cwd 的 Session 的顶层 turn**，抓文件工具改动，发 `workspace/changes` 并在内存里保留摘要 —— `dsh-workspace-changes:lib/types/index.d.ts:44-52`、`recorder.d.ts:9-38`（`TurnRecorder`）。
- **事件里有什么**：**只有 `{turn}`**（`types.d.ts:104-111`）。摘要/对比**留在宿主**，`workspaceChanges.summary(sessionId, seq)` / `.diff(sessionId, seq, index, signal)` 按事件 `seq` 取（`types.d.ts:88-102`）；Session 被 dispose 后即失效。
- **`/api/changes.summary` 返回的确切形状**（`ChangesSummary = Pick<WorkspaceChangesSummary,'turn'|'files'|'total'|'added'|'deleted'>`，`changes.d.ts:33`）：
  - `WorkspaceChangesSummary`：`{ turn, cwd, files: WorkspaceChangedFile[], total, added, deleted, snapshot?: {before, after} }`（Host 只把 `turn/files/total/added/deleted` 交给路由）—— `dsh-workspace-changes:lib/types/types.d.ts:23-40`。
  - `WorkspaceChangedFile`：`{ path, display, added, deleted, binary?, oversized? }` —— `types.d.ts:4-21`。**binary/oversized 时 `added`/`deleted` 为 0**（binary=git 报二进制或捕获侧含 NUL；oversized=某侧超过 `maxFileBytes`）。
  - `/api/changes.diff` 返回 `WorkspaceFileDiff`：`{kind:'text', path, display, before, after, hunks: WorkspaceDiffHunk[], coarse}` 或 `{kind:'binary'|'oversized', path, display}` —— `types.d.ts:56-79`。hunk：`{oldStart,oldLines,newStart,newLines,lines[]}`，每行带 `+`/`-`/空格 前缀（`types.d.ts:42-54`）。
- **本地 git 影子仓库**：`TurnRecorder` 用 `git rev-parse --show-toplevel --absolute-git-dir --git-path objects` 定位仓库（`lib/index.js:233-252`），在**私有临时目录**里拷贝仓库 index（`GIT_INDEX_FILE`）、`git add` 非忽略文件、`git write-tree` 得到树对象；仓库自身的 object store 只**只读挂为 alternate**，**不改**工作树/index/refs（`index.js:263-304`）。turn-start / turn-end 各拍一次快照 → `snapshot.before/after` 树 id；摘要用 `git diff-tree -r -M -z --numstat`（`index.js:8-13`），单文件对比在 blob 之间做。
- **配置上限**：`timeoutMs` `outputMaxBytes` `maxFiles` `maxFileBytes` `diffTimeoutMs`（`index.d.ts:11-27`）。非 git 目录/无 git → 不拍快照，仍列出文件工具改过的文件（`recorder.d.ts:30-38`）。
- **对比超时**：行对比超 `diffTimeoutMs` 时 `coarse:true`，全部行按替换展示（`types.d.ts:68-70`）。

---

## 5. UI 契约

DSH 网页端是**从会话日志投影**出来的 React 应用，扩展点是**槽位（Slot）系统**。权威包：`dsh-client-ui-slots`（机制）、`dsh-client-ui-conversation`（对话容器/位置索引）、`dsh-client-ui-chat`（聊天槽位）、`dsh-client-ui-deliverables`（turn 尾部“交付/改动”）。

> ⚠️ 本批从 registry 拉到 `dsh-client-ui-slots` / `dsh-client-ui-primitives` / `dsh-client-store`（同 `0.2.1-rc.4`），但**它们不在 rootfs 的 lock（`runtime/locks/dsh-0.2.1-rc.4/package-lock.json`）里**——说明客户端 UI 在 rootfs 里是**预构建产物**（`dsh-web-frontend` bundle），不作为运行时 npm 依赖安装。因此槽位**契约以源码为准**，部署形态以 bundle 为准（见 §9）。

### 5.1 槽位系统（`dsh-client-ui-slots`）

- 槽位契约表 `SlotMap` 靠**声明合并**扩展，一条 `register` 同时贡献：组件 +（可选）子槽声明 + store 座位 + 注入面 —— `dsh-client-ui-slots:lib/types/index.d.ts:1-20`。
- **基数 `SlotKind`**：`single`（单占位，后注册替换先注册）| `list`（有序列表，`id` 相同则替换）| `keyed`（按 key 分发，同 key 替换）| `chain`（选择器路由链）—— `index.d.ts:82-83`。**作用域 `SlotScope`**：`root | session-maybe | session`（`index.d.ts:84-85`）。默认渲染在 `div`，`inline:true` 用 span 锚点（`index.d.ts:99`）。
- **chain 路由**：注册携带 `priority`（默认 0，**越小越先**；平手按装配顺序）；渲染时按顺序跑选择器，**第一个非 null 者当选**并把结果作为组件的 `matched` prop；全 null 落到 owner 的 `fallback`（`index.d.ts:250-259`）。`overlay:true` 时 fallback 常驻（`display:none`）以保留草稿/DOM 状态（`index.d.ts:241-248`）——唯一消费方是 `conversation.composer`。
- **`inject`**：槽位级“注入面”（共享能力），由**父注册的子声明**提供，所有子项共享、子项不可替换（`index.d.ts:114-119`、`156-164`）。`renderSlot`/`renderSlotChain`/`renderFactories` 通过 props 下传（`index.d.ts:309-332`）。
- 标准能力座位：`GlobalStandardProps`（所有槽位）、`SessionStandardProps`/`SessionMaybeStandardProps`（session 作用域），由 `ui-session` 等填充（`index.d.ts:189-221`）。locale 座位 `t` 由 `locale:'<ns>'` 声明（`index.d.ts:74-81`）。

> “shadow”在本批 `dsh-client-ui-slots` 的 .d.ts 里**没有**这个成员名；`single`/`list` 的“同 key 替换/复用”就是覆盖（override）语义。DS-Bot/客户端的 `client.js` 里出现的 `shadow` 是**编译产物/其它上下文**（见 §6.4）。

### 5.2 对话容器与位置索引（`dsh-client-ui-conversation`）

- `conversation` 负责**把日志按 seq 摄入**，用 `turn/start|end`、`step/start|end` 划定**turn/step 归属（location index）**，并把“turn 级累加器”喂给尾部槽位（对照 `docs/timeline-parity.md` 的权威表述）。
- 槽位（节选，`dsh-client-ui-chat:lib/types/client/contract/slots.d.ts`）：
  - `conversation.view`（single）：整个对话视图。
  - `conversation.chat.flow`（single）：有序聊天行/本地回声 + 视口可见性钩子（`slots.d.ts:320-326`）。
  - **`conversation.chat.node`（keyed, session）**：**最终聊天行渲染器，按 `ChatNodeKind` 分发**；复用某 key 即**替换该 kind 的渲染器**；某 kind 无占位则**不渲染该行**（`slots.d.ts:327-343`）。`keyProps[Kind] = { node: ChatNode<Kind> }`。
  - `conversation.chat.commandview`（keyed）：按命令名分发命令行（`slots.d.ts:365-369`）。
  - `conversation.chat.message.images` / `conversation.chat.user.images`（single）：消息图片组 / 用户图片（含 pending、本地回声）（`slots.d.ts:349-359`）。
  - `conversation.chat.reasoning.body`（single，官方 priority 100，值更小者替换）（`slots.d.ts:370-379`）。
  - **`conversation.chat.turnTail`（list, session）**：**一个已完成 turn 的动作行之前的有序贡献**，每项收到 `{turn, closingSeq, 文件 opener}`；新 `id` 追加、无内容返回 null（`slots.d.ts:380-389`）。**这就是“tail”**。
  - `conversation.chat.assistant-actions`（list）：一条已定型 assistant 消息的有序动作（`slots.d.ts:390-399`）。
  - **`conversation.composer`（chain, root）**：输入区接管链（`ChainRenderOpts.overlay` 的唯一消费者）。
  - 工厂槽（Factory，带 props/children）：`conversation.chat.user.content`（子槽 `conversation.chat.user.images`）、`conversation.chat.reasoning.content`（`slots.d.ts:298-317`）。
- `conversation.chat.node` 的 key 域 `ChatNodeKind` 由对话侧定义；DS-Bot 用它在聊天流里插入自定义节点（如群聊卡片，见 §6.4）。

### 5.3 交付与 tail 组装（`dsh-client-ui-deliverables`）

- 该包在 **`conversation.chat.turnTail`** 注册尾部组件 `DeliverablesTail`（`client/Deliverables.d.ts:42`），由 `Deliverables` 组装（`Deliverables.d.ts:50`）。
- **tail 的数据是怎么来的**（`client/turn-deliverables.d.ts`）：
  - `ConversationTurnDataMap.deliverables` → `DeliverablesTurnData { produced[], presented?, changes? }`（`turn-deliverables.d.ts:20-25`）。
  - `presented` = 从 `deliverables/presented` 事件（`present` 工具）取，**首个出现的路径顺序**去重（`presentedForClosing`）。
  - `changes` = 该 turn **最新的 `workspace/changes` 通告**，只带 `{ seq }`；摘要/对比再按 `(sessionId, seq)` 走 `/api/changes.summary` `/api/changes.diff`（`turn-deliverables.d.ts:26-30`、`changes-summary.d.ts`）。
  - `produced` = 成功 `write`/`edit`/变异的 `str_replace_editor` 调用记录的目标路径（**模型没说也会列**）。
- 子槽（`client/file-actions.d.ts`）：`deliverables.file.actions`（list）与 `deliverables.review.file.actions`（list）——打开/在文件管理器显示某文件（`owner.onAction`、`actionUrl`）。
- **资源地址**：review 标签 `dsh-resource://changes-review/session/...`（`changes.d.ts:20`、`changesReviewAddress`）。

### 5.4 primitives

`dsh-client-ui-primitives` 提供通用展示件（如 `MarkdownFileMentions` / `MarkdownLabels`，被 chat/deliverables 引用）——`dsh-client-ui-chat:slots.d.ts` 与 `turn-deliverables.d.ts` 的 import 可见。

---

## 6. 工具与插件机制

### 6.1 Cordis 插件 = 组合的最小单元

- 每个 `@deepseek-ai/dsh-*` 就是**一个 Cordis 插件**：`apply(ctx, config)` + `name` + `inject`（声明依赖的服务）—— 例：`dsh-tool-present:index.d.ts:2-18`、`dsh-workspace-changes:index.d.ts:6-9`。`dsh-base` 的 `cordis.patch.yml` 是**默认组合**（一个 `insert` 行表，行按 `id` 定位，后层覆盖前层）。
- **profile = 有序 patch 层栈**：`dsh-base`（核心）→ 模式 bundle（`dsh-web-app` 等）→ 用户/覆盖 patch。默认 `DEFAULT_PROFILE_BUNDLES = ["@deepseek-ai/dsh-base"]`；可选 `OPTIONAL_BUNDLES`（experimental-*）；`ON_DEMAND_BUNDLES = ["dsh-subagent-claude-code","dsh-subagent-codex"]` —— `dsh-app-boot:lib/index.js:568-591,2407`。
- **双面包（dual-face）**：包在 `package.json` 里声明 `dsh.client.platform = "web"` 且导出 `./client`（宿主侧 `dsh-client-modules` 扫 Loader entries 里声明 `dsh.client` 的包，合成前端模块图并注入 index）—— `dsh-client-modules:index.d.ts:2-30`；`dsh.bundle.patch` 指宿主 patch（如 DS-Bot 的 `./cordis.patch.yml`，`assets/ds-bot/package.json:56-63`）。

### 6.2 工具注册（`dsh-tools`）

- `ctx.tools.register(definition): () => void`（返回卸载器）—— `dsh-tools:index.d.ts:634-637`。`defineTool(options)` 构造 `ToolDefinition`（schema + 执行 + 可选 `presentCall`/`presentResult`）—— `schema.d.ts:177-248`。
- **展示契约**：`presentCall(args) -> ToolCallView`（pending 卡片）与 `presentResult(args, result) -> ToolResultView`（完成卡片）；返回 `undefined` 回退到通用展示（`index.d.ts:174-190`）。工具私有展示负载写进 `tool/result.meta`（`types.d.ts` + §1.4）。视图类型（`card` 判别）：`Generic/Terminal/Diff/Search/Read/Web/WebSearch/WebFetch...`（`presentation.d.ts`）。
- **执行流水线（waterfall 事件，可按 agent 作用域过滤）**：`tools/pre-execute`（allow/deny/cancel/ask）、`tools/execute`（超时/重试/指标）、`tools/post-execute`、`tools/ptc-dispatch-log`、`tools/result`、`tools/change` —— `index.d.ts:44-102`。

### 6.3 插件注册“路由/事件/槽位”的 API

- **HTTP**：`ctx.connection.fetch.register(route)`（精确路由）与 `ctx.connection.rpc.handle(channel, ...)` / `ctx.connection.rpc.intercept('/api', matches, handler)`（通道/拦截）—— `dsh-client-connection:rpc.d.ts:120-176`。DS-Bot 的 `/api/bot/feed` 就是这样注册的（`assets/ds-bot/src/host/feed.js:139-156`）。
- **Remote 方法**：`@Remote` 标记 + `TypertRemoteService`（无 Typert 生成器的纯 JS 插件走 Gateway 的 source 模式）—— `assets/ds-bot/src/host/remote.js:1-20`。
- **会话事件**：向 `Session.append(type, data, opts)` 追加；`seq` 由日志分配（§1.1）。外部插件事件用 `appendPluginRecord(session, 'plugin:...', data)`，类型名 `plugin:<segments>`（`dsh-session:index.d.ts:117`、`types.d.ts:514-544`）—— **DS-Bot 明确不用新事件类型**（其 roster/rooms/exchange 存 `DSH_HOME` 下一个 JSON）。
- **槽位**：客户端包声明合并 `SlotMap` 并 `register`（§5.1）。
- **Cordis 事件**：`ctx.on(event, listener)`，`@mode emit|waterfall`（如 `session/event`、`tools/pre-execute`、`user-questions/request`）。

### 6.4 DS Bot 作为插件覆盖/扩展了什么

DS-Bot 资产在 `app/src/main/assets/ds-bot/`（v0.2.5）。**宿主半** `index.js`：`inject = ['llm','tools','systemPrompt','sessionController','sessions','sessionTitle','workspaceRegistry','connection']`，按固定顺序 `install` 40+ 模块（`index.js:16-64`）。

- **宿主路由/服务（它新增的，不是 DSH 核心）**：
  - `POST /api/bot`（`ROUTE='/api/bot'`，`src/host/constants.js:22`）：一个 endpoint 分发器，覆盖 **state / models / chat / timeline / room-* / create-bot / update-bot / folders / agents / secrets / connectors / capabilities / schedules / …**（`src/host/channel.js` 的 `case '...'`）。
  - `GET /api/bot/feed`：SSE（事件：`revision | live | message | stream | notice | room | timeline`，首帧 `hello`）—— `src/host/feed.js:1-16,139-156`。
  - Remote：`dsBot/call`、`dsBot/feed`（`src/host/remote.js`）。
  - `cordis.patch.yml`：挂 `bot` 与 `ds-bot/web-search` 两行（`assets/ds-bot/cordis.patch.yml`）。
- **客户端槽位（`client.js` 注册/覆盖的 key，本批核实）**：
  - `conversation.chat.node`（插入自定义节点，如 `chat-room`）、`conversation.chat.turnTail`。
  - 输入区：`conversation.composer`（chain 接管）、`conversation.input.dock`、`conversation.composer.dock`、`conversation.input.attachments`、`conversation.input.left/right/plan/permission/model`、`conversation.session.header`。
  - 侧栏/设置/外壳：`sidebar.footer.action`、`sidebarRight`、`sidebarRightTabs`、`sidebar.workspaces`、`sidebar.settings`、`sidebar.brand.name/mark`、`settings.trigger`、`settings.open`、`settings.models.sign-in`、`settings.account`、`settings.launcher`、`shell.overlay`。

> 结论：**DS-Bot 完全走 DSH 的公开扩展点**（槽位 + `connection.fetch/rpc` + Remote + 工具），没有改内核，也没加会话事件类型。它就是“**宿主插件 + 客户端槽位插件**”的样板 —— 说明我们同样可以「插件化接管」。

### 6.5 `dsh-tool-present` 与交付事件

- `dsh-tool-present`（`inject` 了会话/交付相关服务）注册 `present` 工具，成功结果写 `deliverables/presented`，并带**持久文件引用**（`index.d.ts`）。它**按 agent 预设挂载**（standard/ptc 预设里才有，`dsh-web-app:presets/*.patch.yml`），不是全局。
- 因此 `deliverables/presented` 只在使用含 `present` 的预设时才出现；`workspace/changes` 则由 web 组合里的 `workspace-changes` 行提供（`dsh-web-app:cordis.patch.yml:352-358`）。

---

## 7. 能力边界

### 7.1 DSH 自带（**不依赖 DS-Bot 就有**）

以 `dsh-base` 的 `cordis.patch.yml`（默认组合）+ `dsh-web-app` 的 web 组合为准：

- **内核**：`dsh-session`（事件日志）、`dsh-agent`/`dsh-agent-loop`（回合/步骤循环、收件箱）、`dsh-llm` + `dsh-llm-deepseek-api-key` + `dsh-llm-pi-ai` + `dsh-llm-retry`、`dsh-tools`、`dsh-system-prompt`、`dsh-sandbox*`、`dsh-permission-presets`、`dsh-user-approval`、`dsh-user-questions`、`dsh-credentials*`、`dsh-settings`/`dsh-config-editor`。
- **内置工具**：`tool-fs`、`tool-fs-search`、`tool-bash`/`tool-pwsh`、`tool-web`（`web_search`/`web_fetch`）、`tool-todo`、`tool-skill`、`tool-goal`、`tool-jobs`、`tool-subagent`(+`subagent_control`/`list_agents`)、`tool-workflow`、`tool-cordis`、`tool-call-timeout-policy`、`tool-working-directory`、`tool-workspace-dependencies`、`tool-session-query`、`tool-str-replace-editor`、`tool-ask-user`、`tool-ralph`、`tool-schedule`、`tool-present`（按预设）。
- **会话能力**：压缩（`compaction-basic`/`-tool-result-pruner`/`-image-offload`）、子代理（spawn/fork，含 claude-code/codex 按需）、计划模式、目标、标题（LLM）、搜索（FTS5 需开启）、投影/投影缓存、检查点。
- **Web 面**：`host-webserver` + 连接层 + **全套 `client-ui-*` 槽位** + `api-*-controller`（会话/工作区/文件/账号/设置/终端/任务）+ `workspace`/`workspace-changes`/`schedule`/`file-reference`/`session-turn-outline`/`attachment`/`office-to-pdf` 等。**改动的摘要+diff、交付卡片、审批/提问 UI，都是 DSH 自带。**

### 7.2 只有 DS-Bot 才有（DSH 不带）

- **多 Bot 团队 / 群聊 / Bot 互发**（roster、rooms、exchange log、`message_bot`、`@Name` 传递、群记忆）。
- **`/api/bot` + `/api/bot/feed` 通道、`dsBot/call`/`dsBot/feed` Remote**、以及对应的自定义槽位（侧栏品牌、团队视图、Bot 设置页、群聊卡片、输入区接管）。
- **移动 API / timeline 行投影**（`timeline` endpoint、`room-progress`、`usage` 等）、头像、记忆（三级）、secrets 卡片、connectors、capabilities、schedules 的团队化、phone/browser 桥、更新检查。

> **对我们最关键的一条**：DSH 已经自带“**改动摘要/diff + 交付卡 + 审批/提问**”，DS-Bot 只是**在其上加团队化包装**。DSHM Bot 若要“只用 DSH”即可获得的气息（timeline 投影、改动卡片），这些数据源 **DSH 本体就有**，不必依赖 DS-Bot。

---

## 8. 对 DSHM Bot 的意义

对照 `app/src/main/java/com/dshm/bot/data/remote/DshClient.kt` 与 `DshSessionRepository.kt`。

### 8.1 我们的能力底座（已在用）

- **登录/鉴权**：`GET /?token=` 换 `dsh-auth-*` cookie；401/403 重登重试 —— `DshClient.kt:72-89,57-65`。底座正确（§3.2）。
- **通用 RPC 信封**：`POST /api/<method>` + `{client-request,rpcId,method,payload:{args}}` → `{server-response,result:{ok,value}}` —— `DshClient.kt:365-414`。与 §3.1 一致。
- **主机事件流**：`/api/remote.mux` WebSocket 承载 `$events`，处理 `ready/emit/waterfall/cancel`，解析 `approval/request`、`user-questions/request` —— `DshClient.kt:100-103`、`DshHostEvent.kt:42-80`。
- **会话 unary**：`session/list|create|rename|fork|search|prompt|cancel|updateQueue|modelCatalog|selectModel|projections`、`workspace/archiveSession|unarchiveSession`、`llm/listProviders`、`credentials/describe`、`commands/execute`、`userQuestions/answer` —— `DshSessionRepository.kt:18-254`。
- **附件/文件**：`POST /api/session/uploadFileBinary`、`GET /api/file` —— `DshClient.kt:175-262`。
- **DS-Bot 通道**：`POST /api/bot`（`schedules/schedule-create/schedule-delete/state`…）与 SSE `GET /api/bot/feed`、`timeline` 快照 —— `DsBotApi.kt:14-69`、`DshClient.kt:165-167,264-327`。

### 8.2 我们还没接、但 DSH 已有的能力（**优先清单**）

| DSH 能力 | 端点/协议 | 现状 |
|---|---|---|
| **历史分页（向后翻）** | `session/page`（`throughSeq/beforeSeq/maxMessages/turnWindow/hasMore`） | 未接（`0` 处引用）。目前靠 DS-Bot `timeline` 投影 |
| **实时会话流（快照+增量）** | `session/follow`（WebSocket 流） | **部分**：`DshMux`/`SessionModels.kt` 有帧模型，但 `DshSessionRepository.kt:11` 明说“待下一批” |
| **会话列表实时状态** | `session/control`（baseline+projection 流） | 未接 |
| **改动摘要 / diff / 打开** | `GET /api/changes.summary`、`/api/changes.diff`、`POST /api/changes.open` | **未接**（改动卡片可完全用 DSH 自带数据） |
| **交付文件打开** | `POST /api/present.open`、`GET /api/present.host` | 未接 |
| **工作区 CRUD/实时** | `workspace/create|delete|rename|follow|pin/unpin`、`directoryPicker/*` | 未接 |
| **工作区文件树/读/字节** | `workspaceFiles/list|stat|read|readBytes|changes` | 未接（我们走 `/api/file`） |
| **单条会话投影** | `session/projections` | 已接（`DshSessionRepository.kt:187-188`） |
| **技能目录** | `skills/list` | 未接 |
| **会话附件读** | `session/attachment` | 未接 |
| **打开工作区路径** | `session/openWorkspacePath`、`session/canOpenWorkspacePath`、`workspacePathApplications` | 未接 |
| **账号** | `account/*`（`getState`/`getBalance`/`startSignIn`/`watch`…） | 未接（仅 `credentials/describe`） |
| **设置/凭据写** | `settings/*`、`credentials/set|unset` | 未接 |
| **终端** | `terminal/*` | 未接 |
| **后台任务** | `job/list|follow|kill` | 未接 |
| **文件引用补全** | `fileReferences/list` | 未接 |
| **子代理** | `subagent/catalog`、`tool-subagent*`、`session/fork`（已接 fork） | 部分 |

### 8.3 结论

- 我们的**底座（cookie + RPC 信封 + mux + 会话 unary）与 DSH 契约一致**，可以放心在这上面继续。
- **最值得尽快接的 DSH 原生能力**：`session/follow`（替代/补齐自建流）、`session/page`（历史）、`changes.summary/diff/open` + `present.open/host`（**改动/交付卡片直接用 DSH 数据**）、`workspaceFiles/*`、`session/control`（会话列表实时）。
- 注意：DSH 的自带能力**不经过 DS-Bot**，所以即使 DS-Bot 不可用/未装那条线，这些端点仍在。DS-Bot 只提供团队/群聊/移动 projections 等**上层**。

---

## 9. 仍不确定 / 需要设备验证

1. **客户端 UI 包的部署形态**：`dsh-client-ui-slots` / `-primitives` / `-store` **不在 rootfs lock** 里（而在 registry 有同版本包）。需在设备 rootfs 里确认前端是预构建 bundle，且槽位契约与源码一致（`adb shell run-as com.dshm.bot ls files/runtime/rootfs/opt/dsh/node_modules/@deepseek-ai/`）。
2. **`/api/file` 的宿主归属与响应头**：本批未拉到注册该路由的包（正文），需设备抓包确认（体积上限、`Content-Type`、错误码 404/413 是否如我们对齐）。
3. **`hook/invoked` `hook/result`、`feedback/record`、`permission/preset`、`sandbox/mode`、`working-directory/change`、`image/offload` 的 data 形状**：词汇名与所属包已列（§1.2），但具体字段来自本批未拉的包，需补拉确认。
4. **`session/follow` 的 `assistantStream` 帧细节 / `revision` 语义**：本批从类型与 `history.js` 推断（`revision` 单调、`snapshot` 覆盖），需在设备上跑一次断线重连实测其“覆盖 vs 续读”表现。
5. **`POST /api/<method>` 的 `payload` 形状**：DSH 内部是 `payload:{args}`；本仓库 `DshClient.rpc` 也发 `{args}`，但服务端是否**强制** `args` 包装/是否接受裸对象，需实测（DS-Bot 的 `dsBot/call` 亦然）。
6. **`/api/present.host` 在 Android/proot 下的 `fileManager` 取值**：类型是 `finder|explorer|directory|null`，Android 上大概率 `null`/`available:false`，需实测。
7. **`dsh-go-bridge` 与 direct engine 对 HTTP 的影响**：manifest 里 `dshGoBridge 0.4.0` 与 `directEngine` 会改路径语义/`route-guard`；是否影响 `/api/*` 的可见性（`route-guard.diff` 在 webserver 上加了 `dshGoRejectRequest`），需在设备上核对。
8. **DS-Bot `/api/bot` 的 HTTP 与 `dsBot/call` Remote 是否等价**：`src/host/mobile.js:221` 提到“同 calls 同 feed 的 Remote 版本”，需核对两端输出是否逐字段一致。
