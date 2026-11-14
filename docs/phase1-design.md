# Phase 1 设计：宿主侧统一时间线投影

> 状态：设计稿（2026-11-14，纸面工作，未动任何代码）。
> 上游宪章：`docs/timeline-parity.md`（投影语义唯一权威清单，本文件引用的 ①-⑫ 规则号均指它）。
> 消费面依据：`BotChatViewModel.kt`、`DshMux/DshMuxHub/DshMuxFrame`、`BotChatDependencies`、
> `DshmBotApp` 装配段（`chatAlertViewModel` 单实例 + `setEndpoint`）、`ChatAlertViewModel`、
> `PhoneControlSession`（`service/PhoneOverlay.kt:348`）、`ChatHistoryCache/ChatEventsCache`、
> `DsBotCapabilities`（hello 协商）、DS Bot v0.2.5 `src/host/{channel,feed,mobile}.js`、
> `runtime/bridge/dsh-go-bridge/lib/index.js`（路由 + Connection 鉴权栅栏先例）。

## 0. 目标一句话

把「会话事件 → 时间线行」的全部语义收进 DS Bot 宿主插件（复用/包裹官方 displayItems 与
deliverablesDefinition 语义），Android 客户端退化为「纯渲染 + 本地乐观 echo」，
消灭客户端五路自建投影。

---

## 1. 投影器放哪层

**决策：DS Bot 宿主插件新模块 `src/host/projection.js`（随 `app/src/main/assets/ds-bot`
一起随引擎分发），基于官方 `displayItems` 扩展包裹，不新起 bridge 模块。**

- 为什么在插件：投影需要的全部闭包状态都在插件手里——`state.exchangeTurns`（隐藏交换轮，
  displayItems 行归属的前提）、`chatOwners`（feed.js 的 bot 寻址）、`partsOf`（earlier 分页）、
  `roomProgress/roomInbox`（群聊）、`agent.inbox`（排队/插话两队列，`dsh-agent/lib/index.js:11`）。
  这些状态 bridge（`runtime/bridge/dsh-go-bridge`，定位是原生插件协议层 + 鉴权栅栏先例）拿不到；
  硬跨层拉取等于再造第二事实源，正是宪章要消灭的东西。
- 为什么扩展而非新写：`parts.js:1212` 的 `displayItems` 已经把 user/bot/answer/view/event/divider
  六类行 + seq/turn 盖章做对了（宪章 ①），官方网页端自己就用它。投影器 = `displayItems`
  的**包裹层**，在它的输出上补三类它故意不出的行（官方网页由浏览器端补）：
  1. **工具行**：`tool/call`+`tool/result`（DRAWN 集合语义，含 present 的
     「正在交付/已交付」两态）——网页端由 `ToolCell`/`renderCall` 在浏览器里做；
  2. **产物卡**：按官方 `deliverablesDefinition` 累加器语义（`deliv:1160-1253`）在插件侧
     累加 presented/produced，`presentedForClosing`（`file.seq < closingSeq`）出卡，
     位置固定 `turn/end`（宪章 ⑦）；
  3. **retry / turnfail 行**：`llm/retry` 与 `turn/end reason.kind=error`（宪章 ⑨）。
- **版本协商**（沿用 `DsBotCapabilities` 先例，`mobile.js:203` hello / `CAPABILITIES` 数组）：
  - `CAPABILITIES` 增加 `'timeline'`（纯增量，不动 `api` 版本号——旧客户端看到未知能力自动忽略，
    `mobile.js:16` 的注释就是这个约定）；
  - 旧插件（无 `timeline` 能力）→ Android `DsBotCapabilities.supports("timeline") == false`
    → **整条回退旧管线**（load + feed message + session/follow，即现在跑着的这条路）；
  - 新插件 + 旧 App → App 不认识 `timeline` 能力，行为不变；
  - 服务端实现不删旧端点：`chat`/`room-chat`/`feed` 原样保留（官方网页端自己也依赖）。

## 2. 行 schema

```kotlin
// 单行（宿主 JSON 或 Kotlin data class 双向对齐）
Row {
  key: String,        // 数据键，稳定、可去重（见下）
  kind: String,       // 见下表
  seq: Long?,         // 会话日志 seq；echo/inbox 行为 null
  turn: Int?,         // 归属轮（官方 ConversationLocationIndex 边界游标语义）
  timeMs: Long?,      // 事件 time（行排序不靠它，只用于显示/分隔线）
  payload: JSONObject // 按 kind 定形状（下表）；未知字段客户端必须忽略（向前兼容）
}
```

| kind | 载荷要点 | key 规则（官方惯例） | 对应官方 |
|---|---|---|---|
| `user` | `text`、`blocks[]`（**附件块在前文本在后**，宪章 ⑤ 收口）、`images`、`requestId?`（=`source.rpcId`，回显认领用） | `user:<seq>` | `parts.js displayItems` kind:user |
| `bot` | `text`、`author/botId`（群聊）、`durationMs`、`whole?`（长文附件） | `bot:<seq>`；同 Bot 连续多条保留多行（合并是**渲染层** `stackReplies` 的事，`client/cells.js:313`） | displayItems kind:bot |
| `tool` | `tool`、`state`（`running/ok/error`）、`label`、`target?`、`callId` | `tool:<resultSeq>`；present 调用态 `tool:<callSeq>`（随 result 到达**换键**为 result seq，客户端按 turn+callId 关联撤下行） | `deliv:508` PresentRow + `ToolCell.renderCall` |
| `artifact` | `files[]`（path/description）、`delivered`（present 声明 or produced 推导）、`turn` | `present:<turn>`（位置 = 该轮 turn/end 的 seq，出卡后不移动） | `deliv:1626` DeliverablesTail |
| `event` | `eventKind`（request/reply/report/stopped/done/failed/retry/missed/…）、`code?`、`botId/name` | `event:<seq>`（retry=`retry:<seq>`、turnfail=`turnfail:<seq>` 保持现名，测试已锁） | displayItems kind:event + turns.js |
| `divider` | `timeMs`（part 起点） | `divider:<partIndex>` | displayItems kind:divider |
| `view` / `answered` | 现 `ChatViewCard` / `ChatAnsweredQuestion/View` 原样 | `view:<seq>` / `answer:<seq>` | displayItems kind:view/answer |
| `inbox` | `text`、`slot`（`next-turn`/`next-step`）、`requestId?`、`itemId` | `inbox:<itemId>`；seq=null，**只排在已知 seq 尾部**（宪章 ① 的「无 seq 不参排序」约束） | `agent:11-160` Inbox 投影（插件可直读 `agent.inbox.nextTurn/nextStep`） |
| `turntail` | 该轮收尾元信息（失败原因、分隔线锚点）；可选，1c 再发 | `turntail:<turn>` | `client/cells.js:706` TurnTailCell |
| `streaming` | **不发**（见 §开放问题 3）：流式 delta 维持客户端侧通道，行流只发 settled 行 | — | `client/cells.js:236` |

**key 稳定性三条规则**：
1. **乐观替换**：客户端 echo 行数据键 `p:<requestId>`（§4）；服务端 `user` 行带 `requestId`
   字段，客户端认领后**渲染键保留 `p:<requestId>`**、数据键切到 `user:<seq>`——同 dsh-Go
   的「行 key 不变、气泡从发送中渐变到位」（`dsh-Go chat_rows.dart:27`），LazyColumn 不换元素；
2. **earlier 插入**：key 即身份，翻页只是把更小 seq 的行插进前面，不重发不改键
   （服务端按 `beforeSeq` 分页，行内容幂等）；
3. **分组折叠**：`EventLineFold`（宪章 ⑨ 有意偏离）的组键 = 组内第一条的 key，
   折叠是纯渲染层，不影响数据键。

**无 seq 行的排序**：投影端保证「有 seq 段严格升序」；echo/inbox 行无 seq，客户端只把它们
**追加在已排序段的尾部**（乐观位置），认领后由服务端行替换——客户端**不做全表排序**
（`resortBySeq` 在 1b 后只作为 echo 认领时的收口，见 `BotChatViewModel.kt:915` 的现状）。

## 3. 通道协议

**决策：快照走 `/api/bot` 新端点 `timeline`（unary），增量走现有 feed SSE 的新事件类型
`timeline`。不新开 mux 流。**

- 为什么不新开 mux 流：mux 的 `open` 帧只能开**引擎 Remote 流**（`session/follow` 是
  api-session-controller 的），插件注册不了新的 mux endpoint——`DshMuxFrame.open` 由网关
  转发（「forwarded Remote event stream requires an empty args object」这条报错就是证据，
  `DshMuxFrame.kt:52`）；而 `/api/bot`（`channel.js:549` 的 `ctx.connection.fetch.register`）
  和 `/api/bot/feed`（`feed.js:85`）是**插件自有通道**，Android 已有完整解析器
  （`DshClient.bot/feed`、`BotFeedEvent`）。鉴权沿用该路由既有的 Connection 栅栏
  （`channel.js` 走共享 `/api` 通道；bridge 的 `rejectionOf`/fallback guard
  （`bridge/lib/index.js:153-222`）是「插件自注册路由也要过 Connection 鉴权」的先例，
  timeline 端点不需要额外动作）。
- **快照帧**（`timeline` 端点，进 `channel.js` 的 `READS` 集合 + `mobile.js` capability）：
  ```json
  // 请求 {botId|roomId, since?: seq, cursor?: {beforeSeq, part}, max?: 60}
  // 响应
  { "sessionId|roomId": "...", "seq": 1234,            // 服务端当前最大 seq
    "rows": [Row...],                                   // 升序；since 重放时只含 seq > since 的行
    "cursor": {"beforeSeq": ...} | null,                // 还有更早的（对齐 EARLIER_PAGE=60 的页大小）
    "running": false, "speaking": null }                // 群聊才带
  ```
- **增量帧**（feed SSE，`event: timeline`，复用 `feed.js:51-77` 的 publish 位置）：
  ```json
  { "botId|roomId": "...", "seq": 1237, "rows": [Row...] }   // 只发新行；行缓冲追不上时发 {"reset":true} 让客户端重拉快照
  ```
  客户端解析：`BotFeedEvent(event="timeline", data=...)` 新分支（`DshClient.kt:20`）。
- **断线重连 = seq 游标重放**：SSE 断开（或 mux 重连generation 变更）后，客户端调
  `timeline {since: lastSeq}`；宿主侧每会话维护**内存行缓冲**（环形，上限 ~600 行，
  超出即声明 `reset`）——不落盘（§开放问题 1），缓冲外靠 `sessionController.page`
  （`parts.js` 的 `partPage` 先例）从事件日志重放重建。
- **与 session/follow 的关系**：过渡期**并存**——follow 继续驱动旧管线（也是 shadow 对比的
  一方），1b 切主后 follow 在单聊降级为「产物事件缓存」（宪章 ⑦ 的事件记录仍从它来，
  直到 1c 把产物累加也搬进插件），1c 评估退役。

## 4. 乐观气泡（客户端只剩这一份本地状态）

- **echo 行**：数据键 `p:<requestId>`，本地生成、点击即上屏（现状
  `BotChatViewModel.kt:1151-1199` 的行为保留）；payload 含本地附件字节路径
  （`ChatAttachmentItem`，乐观期本地预览 = 官方「本地字节直到回显」，
  `conv:4717-4740`）。
- **三态表达**（对齐官方 inbox 对账，`dsh-agent/lib/index.js:11` + dsh-Go `PendingPrompt`）：
  - **发送中**：只有本地 `p:` 行；
  - **排队**：服务端投影发出 `inbox` 行（`slot: next-turn`），客户端把同 `requestId` 的
    `p:` 行替换为 inbox 行（显示「排队中」，可撤回/改插话——`session/updateQueue`，
    契约已锁 `DshSessionRepositoryContractTest`）；
  - **插话**：inbox 行 `slot: next-step`（显示「插话中」）；被 claim 后变成 `user` 行
    （seq 落在轮内，宪章 ③）。
  - 认领：`user` 行带 `requestId` → 删 `p:`/`inbox` 行 → 渲染键过渡（§2 规则 1）。
  - `unclaimedRequests` 自愈重拉（`watchForRunEnd` 里那次 `scheduleReload`）在新管线里
    变成「本轮结束行到达后仍无 echo → 重拉快照」，逻辑保留。
- **附件收口**（宪章 ⑤ 的 ❌）：①宿主投影 user 行 `blocks[]` 顺序 = 官方（附件块在前）；
  ②发送侧 `DshSessionRepository.prompt` 的 content 顺序同步改为**附件在前文本在后**
  （现在 `text→file`，官方 `conv:4724-4731` 是 `attachments→text`）——改码时同步改
  `promptBuildsContentAndReturnsStableRequestId` 的断言。

## 5. 原生特有功能怎么挂

| 功能 | 现状通道 | Phase 1 决策 |
|---|---|---|
| 审批/提问/密钥卡 | mux `$events` waterfall + channel `state.questions/views/secretRequests`（`ApprovalMerge.kt`、`ChatPending.kt`） | **保留原通道，不并入行流**。理由：卡片是宿主级交互状态（host 寻址 + eventId 应答回执），官方网页端同样把它们画在 dock/slot（`conversation.input.dock`）而不是 chat node；行流是 session 寻址的纯投影，混入交互状态会重新引入第二事实源 |
| 通知 | `ChatAlertViewModel` 直听 `$events`（`api-session/status` true→false + waterfall + diagnostics） | **不动**。它不消费行流，依赖的全是宿主级事件；timeline 管线对它透明 |
| 手机操控 | `PhoneControlSession`（`PhoneOverlay.kt:348`）状态机 + `send(steer=true)` 走 channel `send`（`mode:'steer'`） | 状态机不动；1b 起 `watchForRunEnd` 的轮询收尾**保留兜底**，但「回合结束」主信号可来自行流（`turntail` 行到达 / feed `running=false`），`finishWithResult` 触发条件不变 |
| 语音输入 | `SpeechRepository`（独立） | 不动 |
| 浏览器缩略图 | 客户端本地 `browserShots` + `lastTurnEndSeq` 贴位 | 1b 改贴 `turntail:<turn>` 行的 seq；机制不变 |

## 6. 迁移与回滚

- **能力开关**：`DsBotCapabilities.supports("timeline")`（服务端 hello 声明）是主开关；
  客户端再叠一个**设置项 kill-switch**（`DshGoPreferences.timelineParity`，默认跟随能力）。
  任一关闭 → 旧管线。回滚数据无损：旧管线的历史缓存（`ChatHistoryCache`）在双跑期间照常维护。
- **shadow 双跑**：debug 设置 `timelineShadow`。开启时单聊 VM 同时跑两路：旧管线
  （load+feed+follow，出旧 `entries`）与新管线（timeline 快照 + feed timeline 增量，出行序列），
  对比**行 key 序列 / seq / kind**，差异经 `DshMuxLog` 输出（每会话限频 1 条/5s，附首个分歧点
  两侧的行摘要）。验收即「shadow 差异 = 0」。宿主侧不加开关（投影端点天然无副作用，
  不影响官方网页端）。
- **灰度顺序**：**私聊先行，群聊后**（1c）。理由：单聊投影 = displayItems 直系（语义最稳），
  群聊要并 roomItems/room-progress/群产物三路，风险面大；且单聊已有完整测试矩阵
  （宪章 §3 的 18 类）可复用。
- **一键回旧管线**：kill-switch 关闭即回，无需重装/清数据；宿主 `timeline` 端点对新老
  客户端均无侵入。

## 7. 测试策略

1. **宿主 JS 侧**（先例：`ds-bot/test/*.test.js` + `test/harness-resolve.mjs` + `scripts/lint.mjs`）：
   - `projection.test.js`：事件日志 fixture → 投影行序列断言（行序、key、kind、
     artifact 口径、inbox 行、折叠前的原始行）。fixture 直接用**真实会话日志形状**
     （tool/call+result、turn/start/end、deliverables/presented、llm/retry、
     agent/inbox/spliced）；
   - channel 集成：`timeline` 端点经 `handle(endpoint, payload)` 直调断言（READS 门、
     只读主机放行）。
2. **Kotlin 契约测试**：
   - `FakeDshServer` 契约：`timeline` 端点 envelope/行形状/游标回传（对齐
     `DshSessionRepositoryContractTest` 风格）；
   - VM 测试：`FakeMuxLink` + feed `timeline` 事件驱动的行流 → 行序列断言（现有
     `BotChatArtifactLiveTest` harness 复用）；shadow 对比器单测（构造分歧，断言日志）。
3. **官方对拍**：
   - **dsh-Go fixtures**（`dsh-Go/packages/dsh_client/test/fixtures/`，现成会话日志）回放进
     projection.js，断言行序/出卡时机与 dsh-Go `deriveRows` 语义一致（turn/end 对位、
     steer 分片、重复 present 聚合）——这是最便宜的官方对拍面；
   - npm 官方包（conv/deliv）只有编译产物没有 runtime fixture，对拍基准以宪章 §2 的
     file:line 引用为准（0.2.1-rc.4 本地副本路径见宪章 §5）。

## 8. 分期

| 期 | 内容 | 验收标准 | 规模（相对九项修复轮） |
|---|---|---|---|
| **1a 宿主投影 + shadow（私聊）** | 插件 `projection.js`（displayItems 包裹 + 工具/产物/retry 行 + inbox 行）；channel `timeline` 端点 + capability；Kotlin 侧行解析器（Row → 内部模型）+ shadow 对比器；JS/Kotlin 测试 | shadow 模式下固定 fixture 与真实会话的 key/seq/kind 差异 = 0；官方网页端行为无回归（插件改动不影响 client.js 路径） | ≈ **1.5×** |
| **1b 私聊切主** | feed `timeline` 增量事件 + seq 游标重连重放；echo/inbox 三态 UI；附件块顺序收口（宿主+发送侧+测试断言）；kill-switch 设置项；单聊旧管线转兜底 | 三场景（连发/steer/附件）真机表现与宪章 ②③④⑤ 一致；断网重连不丢行不重复；kill-switch 一键回旧管线可用 | ≈ **1×** |
| **1c 群聊 + 收尾** | room 投影（roomItems 语义并入、room-progress/群产物卡）；inbox dock UI（排队改插话/撤回）；follow 降级诊断并评估退役；旧管线代码删除 | 宪章 ⑩ 全绿 + 群聊三场景；`bot-chat-events:*` 缓存可下线；测试矩阵全绿 | ≈ **1.5–2×** |

---

## 关键设计决策清单

1. **投影器进 DS Bot 插件而不是 bridge**——displayItems/deliverables/inbox 的闭包状态全在插件，bridge 拿不到，硬拉就是第二事实源。
2. **包裹官方 displayItems 而非新写**——user/bot/answer/view/event/divider 六类行的官方实现直接复用，插件只补网页端在浏览器里做的三类（工具行/产物卡/retry）。
3. **能力协商走 hello `capabilities` 加 `'timeline'`，不动 api 版本**——旧插件/旧 App 双向兼容，回退 = 能力缺席。
4. **快照走 `/api/bot` `timeline`、增量走 feed SSE 新事件，不开新 mux 流**——mux open 帧只转发引擎 Remote 流，插件注册不了；两条插件自有通道 Android 已有解析器。
5. **数据键遵循官方惯例（`user:<seq>`/`present:<turn>`/`inbox:<itemId>`…），渲染键单独一层**——echo 行 `p:<requestId>` 在认领后保留渲染键，气泡原地渐变不换元素（dsh-Go 同款）。
6. **无 seq 行（echo/inbox）只追加尾部、不做全表排序**——延续宪章 ① 的「不动无 seq 段」约束，认领交给服务端行。
7. **产物累加器按官方 deliverablesDefinition 语义在插件侧重写**——`file.seq < closingSeq` 与「轮闭合才出卡」在宿主一次算好，客户端不再有 deriveArtifacts 的第二份口径。
8. **审批/提问/密钥卡、通知、手机操控、语音全部维持现有通道**——它们是宿主级交互状态，不是会话投影；混入行流会重新制造双源。
9. **seq 游标重放 + 内存行缓冲（不落盘）**——缓冲外用 sessionController.page 重放，避免再养一份 ChatEventsCache 类的持久事件副本。
10. **shadow 双跑放客户端（debug 开关）**——旧管线与 timeline 并行出结果、DshMuxLog 限频对比，宿主零开关零侵入。

## 留给用户拍板的开放问题

1. **行缓冲持久化**：宿主纯内存 + 按需重放（本设计建议，省一份持久副本）vs 落盘（冷启动更快但多一处状态）？
2. **流式行**：行流只发 settled 行、流式 delta 维持现有侧通道（本设计建议，官方亦无独立 streaming 行）vs 行流带文本块增量（一步到位但帧频高）？
3. **`timeline` 增量是否也喂官方网页端**（进 client.js）：本设计建议只给 Android（官方 web 有自己的 conversation runtime，喂它反而引入双源）？
4. **1b 切主的灰度面**：按「能力 + 设置开关」全局切（本设计建议）vs 按会话逐个切？
