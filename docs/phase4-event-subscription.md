# 四期：事件订阅（对齐 dsh-Go `transcript.dart`）

> 2026-11-15 立项。用户拍板：「事件订阅（四期大工程）」。
> 依据：`docs/dsh-study.md`、本仓 `dsh-Go/packages/dsh_client/lib/src/transcript.dart`、
> `ds-bot/src/host/projection.js`。目标：**durable 事件日志是唯一事实源，显示永远由它派生**，
> 让「用着用着乱、冷启动才好」在结构上不可能发生。

## 0. 为什么必须走这条

现状：客户端消费的是宿主 `projection.js` **投影后的行**。行的 `seq` 是显示序、**不连续**
（divider 无 seq、群聊 relay 行 seq=null、part 之间独立），所以用不了 dsh-Go 的铁律
`seq == lastSeq + 1`，只能靠 `from/bufferFrom` 软信号 + 各种兜底 → 反复出稳定性问题。

dsh-Go 的模型（`transcript.dart`）：
- `SplayTreeMap<seq, event>` 唯一事实源，`cursor` 单调；
- 同 seq 幂等覆盖；`seq <= lastSeq` 丢弃；`seq > lastSeq + 1` 是缺帧；
- 缺帧 → `_fill`（翻页补洞）→ 补不齐 `_Resync` → 整段重同步；
- `items` 永远从日志全量/等价折叠派生，增量只是缓存；
- 非严格追加 / 快照 / 更早页 ⇒ 作废缓存全量重折；
- 没有消息删除，日志 append-only。

## 1. 分期

| 期 | 内容 | 验收 |
|---|---|---|
| **4a（已完成）** | 宿主转发原始 durable 事件（带连续 seq）；客户端维护每会话事件日志 + 连续性判据 + 补洞；**补全折叠载荷 + 自校验测试** | 重复/乱序/缺帧可判定，缺帧能补洞（Kotlin 646→647、宿主 60→61 含载荷覆盖守卫） |
| **4b（本阶段）** | 客户端折叠**私有 user/bot 气泡子集** + **oracle 差分**（宿主 displayItems 生成期望值） | `foldPrivateChat` 对 10 个场景与宿主逐行相等（`PrivateChatFoldTest`，655 含此测试）；**未接线渲染** |
| **4b-1（已完成）** | 客户端折叠扩到**全部私聊行类型**（user/bot/tool/retry/turnfail/changes/artifact），产出客户端已消费的 `TimelineRow` | `TimelineFoldTest` 对 11 个场景与宿主**逐行相等**（key/kind/seq/turn/timeMs/payload），期望值来自宿主真实现生成的 fixture（667 含此测试） |
| 4b-2 | 折叠补上「produced 口径」的产物卡（工具写文件路径扫描） | oracle 差分覆盖（15 场景全绿） |
| **4c** | **只切私聊**（客户端自折叠）；群聊保持服务端条目模型；退役旧增量路径 | 高强度使用无「丢失/错位」，仅靠事件日志全量重算自愈 |

### 2.6 群聊**不**走 seq 日志（照搬 dsh-Go，2026-11-15 定）

调研结论（`dsh_client/lib/src/dsbot_api.dart`）：dsh-Go 的群聊模型是「**服务端条目 + 群内稳定 id + 不透明游标 + 最新页重读**」，
**不**把群聊建模成 seq 日志。我们若照搬，结论一致，且有两个硬约束支持：

1. **群聊产物卡的数据不在群会话的事件里**——它来自各成员**自己的会话**的 present 声明
   （宿主 `roomArtifacts(roomId, 0, {scan:false})`）。客户端只订阅群会话的事件，**拿不到**这张卡。
2. 群聊条目 id 是 `<seq>` 或 `<callId>:<index>`（`mobile.js` `replyItem`），**位置序**，与 seq 无关——
   硬造 seq 空间会与成员隐藏会话、多段对不上。

所以 **4c 只切私聊**；群聊继续用服务端条目（`room-chat` / `timeline` 的 room 分支），
客户端的 [SessionEventLog] 仍为群会话保留（完整性判据要用），但不拿它折叠显示。
> 这一条写在这里，是为了以后别有人想「统一成一套」而踩坑：群聊与私聊的模型不同，是上游设计，不是我们的偷懒。

## 2. 4a 设计

### 2.1 宿主：转发原始事件

在 `projection.js` 的 `session/event` 监听器里（`record()` 之后）发一条 feed 事件：

```
event: session
data: { sessionId, seq, type, time, data? }
```

- **每个有 seq 的 durable 事件都发**（保证 seq 连续可判定）。
- 只对「时间线折叠需要」的类型携带 `data`（`TIMELINE_EVENT_TYPES`）；其它类型只发
  `{sessionId, seq, type}` —— **仍然占位，保证连续性**，又不把巨大工具输出推给手机。
- 发送走 `rt.publishFeed`（受 `watched()` 闸），不新增并行通道。

需要携带 data 的类型（4b 折叠用）：
`user/message`、`assistant/message`、`assistant/attempt`、`tool/call`、`tool/result`、
`turn/start`、`turn/end`、`deliverables/presented`、`workspace/changes`、`llm/retry`、
`session/fork`（parts 边界）。

### 2.2 宿主：补洞查询

新增 `/api/bot` 方法 `events`（只读，加入 `READS`）：

```
请求：{ sessionId, from, through?, max? }
应答：{ sessionId, events: [{seq,type,time,data?}], oldestSeq, newestSeq, hasMore }
```

实现：先从投影的环形缓冲取（800 条，快路径）；不够再走
`ctx.sessionController.page({address:{kind:'session',sessionId}, throughSeq, beforeSeq, maxMessages})`
向前翻页（与 dsh-Go `_fill` 同构）。

### 2.3 客户端：事件日志

`data/remote/SessionEvents.kt`：`SessionEvent` + `SessionEventLog`（`TreeMap<Long, SessionEvent>` + **连续游标**）。
判据与 dsh-Go 完全一致：
- `seq <= cursor` → 重复，丢弃；
- `seq == cursor + 1` → 追加；
- `seq > cursor + 1` → **缺口** → 发 `events{from=cursor+1, through=seq-1}` 补洞；
  补洞结果必须首尾正好接上 = 收洞成功，否则视为不可修补 → 整段重读。

### 2.4 客户端：私聊折叠（4b）

`ui/chat/TimelineFold.kt` 的 `TimelineFold.fold(events, exchangeTurns, cwd, botName, resolveBot)`
（输入是 `SessionEventLog.eventsIn` 的结果）——**产出客户端已消费的 `TimelineRow`**，
刻意不做新类型：**下游（映射 → ChatEntry → 渲染）一行都不用改**，4c 只换「行的来源」。

覆盖的行类型与宿主逐字对齐：
- `user` 行（仅 `source.kind === "user"`；文本 = text 块 `\n` 连接后 trim；`images`；`blocks` = 带 attachment 的 image/file 块名；`requestId` = `source.rpcId`）；
- `bot` 行（`assistant/message` 按文本块各出一行，空白块不出行）；
- `tool` 行（9 个社交工具；`present` 不成行——交付卡是它唯一的表面）；
- `event` 行（`llm/retry` → `retry:<seq>`；`turn/end` 且 `reason.kind === "error"` → `turnfail:<seq>`，带 `name`）；
- `changes` 行（`workspace/changes` → `changes:<seq>`，载荷**空**，摘要客户端另外拉）；
- `artifact` 行（`deliverables/presented` → `present:<turn>`，贴在**该轮 turn/end** 的 seq 上；闭合那一句之后的声明不上卡）。

**oracle 差分**：`test/gen-display-items.mjs` 直接跑宿主的 `displayItems` + `composeTimelineRows`
生成 `test/fixtures/timeline-fold-oracle.json`（同一份拷进 JVM 测试资源）；
`TimelineFoldTest` 逐场景逐行比对两个独立实现。差分已经抓到过三处真实不一致
（changes 载荷多带 turn、retry/turnfail 漏带 name）——这正是它的价值。

### 2.5 与现有时间线的共存（4a/b 不动渲染）

4a/b 只增加「事件完整性 + 折叠正确性」两层，渲染仍走现有 `timeline` 行管线；等 4b oracle 差分覆盖到卡片/工具行后，4c 再切换显示并退役旧路径。

## 3. 验收

- 宿主 JS：转发帧 seq 连续、占位事件不丢、`events` 补洞首尾正确、缓冲轮转后走翻页；**载荷覆盖守卫**（扫描折叠源码读到的每个事件类型都必须在 `TIMELINE_EVENT_TYPES` 里）。
- Kotlin：`SessionEventLog` 重复/乱序/缺帧/补洞合并/多会话隔离 + `foldPrivateChat` 与宿主逐行差分。
- 真机：断网/切后台/长会话后，事件日志 `cursor` 连续；缺帧时能自动补回。
