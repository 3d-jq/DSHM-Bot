# 时间线对齐宪章（timeline-parity）

> 生效：2026-11-14（Phase 0）。本文件是**投影语义的唯一权威清单**：Phase 1（宿主侧统一投影）
> 的施工图，也是以后每一次改动时间线的**回归宪法**——改时间线必须先对表，测试锁缺失的规则
> 必须在同一个改动里补上锁。

## 1. 背景与原则

**官方时间线 = 会话日志的纯投影。** DeepSeek Harness 网页端没有第二事实源：

- 会话是追加式事件日志（seq 单调），事件词汇表固定（`dsh-session/lib/index.js:1054` 的
  `KNOWN_SESSION_EVENT_TYPES`）；
- 客户端时间线由 `ConversationLocationIndex` 按 seq 顺序摄入、用 `turn/start|end` /
  `step/start|end` 边界划定归属（`dsh-client-ui-conversation/lib/client.js:2038`）；
- 产物卡由 turn 级累加器 `deliverablesDefinition` 从同一份事件流推导
  （`dsh-client-ui-deliverables/lib/client.js:1160`）；
- DS Bot 插件网页端自己**不维护任何第二投影**，只按槽位覆盖官方 cell
  （`DS-Bot/ds-bot/src/client/index.js:897`）。

我们此前在 ViewModel 里叠了五路自建投影（历史 items、feed message、stream 侧通道、
follow 工具事件、产物边攒边画），各自为政导致反复漂移：占位 seq 漂移、回复跑到问题上面、
产物卡随快照窗口消失、重试行堆山。ec91961 / 6d567ab / 375f67f 已把主要伤口缝上；
本文件把缝线钉死。

**三条原则：**
1. 官方语义以本文件第 2 节的出处为准（npm 0.2.1-rc.4 源码 + DS-Bot v0.2.4 源码 + dsh-Go
   对照注释），不再凭印象改时间线；
2. 每条规则必须有测试锁；改语义必须先改锁再改码；
3. 偏离必须是**有意**的（表格里写明理由），无意偏离一律按漂移处理。

## 2. 规则清单

出处缩写：
- 官方 npm 包（0.2.1-rc.4，本地副本 `/c/Users/邓嘉权/AppData/Local/Temp/dsh-ui-deliv2/` 等临时目录）：
  `conv` = `@deepseek-ai/dsh-client-ui-conversation/lib/client.js`，
  `deliv` = `@deepseek-ai/dsh-client-ui-deliverables/lib/client.js`，
  `present` = `@deepseek-ai/dsh-tool-present/lib/index.js`，
  `agent` = `@deepseek-ai/dsh-agent/lib/index.js`，
  `session` = `@deepseek-ai/dsh-session/lib/index.js`
- `client` = `DS-Bot/ds-bot/src/client/`（v0.2.4 未打包源码），`host` = `DS-Bot/ds-bot/src/host/`
- `我们` = `app/src/main/java/com/dshm/bot/`

| # | 规则描述 | 官方出处 | 我们的实现 | 状态 | 测试锁 |
|---|---|---|---|---|---|
| ① | 排序基准 = seq 单调日志；turn/step 归属由边界事件划 Cursor，turnOrder 按 firstSeq | `session:1054`（事件词汇表）、`conv:2038-2242`（rebuild：`coordinates.set(seq,{turn,step})`、turnOrder 按 firstSeq） | `ui/chat/TimelineMerge.kt:12-42`（按 seq 找位插入，不动无 seq 段）；`ui/chat/BotChatViewModel.kt:915-922`（resortBySeq：无 seq 原地、有 seq 升序、同 seq 稳定） | ✅ | `TimelineMergeTest`（7 例） |
| ② | 乐观气泡 = 本地 echo（`beginSubmission` 的 requestId），服务端 `user/message.source` 回显后**同 id 原位认领**真 seq（向上向下都认），不产生第二行 | `conv:4672-4739`（sendSession：echo 先进快照、nextPaint 后才发）、`conv:2400-2412`（insertAssistantSettlement：持久事件插到可见尾部之前的真位置）、dsh-Go `chat_state.dart:75-81`（"until the matching UserChatItem (same requestId) arrives"）、`chat_rows.dart:27-31`（同 key 原位过渡） | `BotChatViewModel.kt:1151-1199`（send：requestId + 占位 seq 气泡先上屏）、`:891-908`（adoptLocalSeq：按 id 认领真 seq，向下也认）、`:1404-1407`（回显按 id 去重 + 认领） | ✅ | `BotChatTimelinePositionTest#serverEchoAdoptsTheRealSeq`、`#rapidSendsGetDistinctPlaceholdersThatTrackTheRealSeq`、`#serverEchoKeepsOneBubbleInPlace`（2026-11-14 补） |
| ③ | 发送双通道：排队/插话是持久 `agent/inbox/spliced` 事件（`next-turn` / `next-step` 两队列）；steer 消息在下一 step 边界被 claim | `agent:11-160`（Inbox 全文；`splice` 落持久事件、`claim` 在 step 边界）、`conv:15940-15963`（steerQueue：`session/updateQueue {kind:'steer'}`）、dsh-Go `chat_state.dart:96-101`（slot=nextTurn/nextStep） | `data/remote/DshSessionRepository.kt:93-147`（session/prompt 带 mode，默认 queue；updateQueue 支持 steer/remove/edit）；`BotChatViewModel.kt:1209`（steer 走 DS Bot `send` 的 `mode:'steer'`） | ✅ | `DshSessionRepositoryContractTest#promptBuildsContentAndReturnsStableRequestId`、`#updateQueueSteerAndRemoveActions`（2026-11-14 补） |
| ④ | 连发多条 FIFO：占位互不碰撞（占位 seq = max(已见 seq, 本地最大占位)+1），回显按日志序各归其位，发送序 ≠ 日志序也不乱 | `agent:53-63`（claim 顺序）、`conv:16015-16024`（默认 queue）、官方 echo 语义同 ② | `BotChatViewModel.kt:1165-1168`（maxSeenSeq/maxLocalSeq 错开）、`:915-922`（认领后稳定重排） | ✅ | `BotChatTimelinePositionTest#rapidSendsGetDistinctPlaceholdersThatTrackTheRealSeq`、`#rapidDoubleSendWithClosingTextSettlesBySeq`、`#steerAndQueuedMessagesSettleInLogOrder` |
| ⑤ | 附件消息：内容块顺序**附件在前、文本在后**；乐观期用本地字节显示预览，回显 retire 后预览落持久缓存 | `conv:4724-4731`（`content = [...serializeAttachments(), ...text]`）、`conv:4717-4740`（onRetire 'observed'）、`client/user-message.js:61-73`（附件画进气泡栈） | `BotChatViewModel.kt:1212-1222`（先 uploadAttachment 拿 receipt 再 prompt）；`data/remote/DshSessionRepository.kt:106-111`（**text 在前、file 在后**）；`ChatAttachmentCache` 本地缓存 | ❌ **块顺序与官方相反**（官方 attachments 先、text 后；我们 text 先、file 后）。无实际事故记录，Phase 1 对表时改 `DshSessionRepository.prompt` 的 content 顺序 | `DshSessionRepositoryContractTest#promptBuildsContentAndReturnsStableRequestId`（锁了当前形状——修码时同步改断言）；本地预览无锁（缺口，UI 层） |
| ⑥ | 流式草稿：流式与落盘共享同一渲染位（官方 turnDataSource 同节点流式渲染）；delta 只追加 | `client/cells.js:236-237`（"A streaming reply stands in for the activity row"）、`client/cells.js:220-269`（AssistantCell：`data.status==='running'` 即流式） | `BotChatViewModel.kt:1425-1472`（stream 事件走 `streamingText` 侧通道，高频 token 不进主状态）、`:2096-2138`（双路 flush）、`:1411-1413`（消息落地即撤流式气泡） | ⚠️ **有意偏离**：官方是同一节点就地流式，我们是「独立侧通道气泡 + 消息落地后撤下」，为的是主列表不跟 token 重组；观感等价（同一位置先流式后成气泡） | `StreamingMarkdownTest`（节流/追加 12 例）、`BotChatViewModelWiringTest#feedStreamChunkLandsOnSideChannel`、`#feedMessageAppendsBotEntry` |
| ⑦ | 产物卡：**轮闭合才出卡**；位置固定在该轮 `turn/end` 的 seq（收尾文字在卡上面），出卡后不移动；有 present 声明只用它（同轮多次按 path 去重、首现定序、描述取最新），否则按官方 `produced` 口径（write/edit/str_replace_editor 变更类成功结果全算，不看扩展名）；>4 个文件折叠 | `deliv:1267-1271`（presentedForClosing：`file.seq < owner.seq`）、`deliv:1606-1620`（COLLAPSED_PRESENTED_COUNT=4；selectDeliverables）、`deliv:1160-1253`（deliverablesDefinition 累加器）、`present:74-123`（成功才落 `deliverables/presented`）、包描述 "the deliverables row a finished turn ends with"、`client/cells.js:703-708`（tail 从 turn/end 构建） | `ui/chat/ArtifactDerivation.kt:125-223`（eligible 只收 finished 轮；`:219` seq=endSeq；`:199-206` produced 全量口径；`:183-195` 聚合去重）、`ui/chat/BotChatViewModel.kt:820-841`（从会话事件记录重推导，不再边流边攒）、`:724-726`（presented 事件不直接画行） | ✅ | `ArtifactDerivationTest`（9 例：轮末固定/produced 口径/md 不滤/聚合/多轮排序/相对路径/未闭合不出卡）、`BotChatArtifactLiveTest#presentDeclaredInThisTurnShowsTheArtifactCard` 等 3 例、`data/remote/DeliverablesTest`（解析层 6 例） |
| ⑧ | present 工具行：轮内反馈只有它——call 时「正在交付 N 个文件…」，result 到了换「已交付 N 个文件」，失败画「没能交付」；结果行挂在 result 的 seq，call 行撤下 | `deliv:508-563`（PresentRow：preparing/running/ok/error/stopped）、注册 `tool.call.toolview` key `present` | `BotChatViewModel.kt:684-695`（call→正在交付）、`:705-719`（result→已交付并撤 call 行）、`ui/chat/ToolEventLine.kt:109-117`、`:206-215`（present 进 FOLLOW_TOOLS） | ✅ | `BotChatArtifactLiveTest#presentToolRowShowsDeliveringThenDelivered`、`ToolEventLineTest#present row counts the delivered files`、`BotChatArtifactLiveTest#snapshotDropsAPendingPresentRowWhoseResultFellOutside`（快照窗口边缘清理，2026-11-14 补） |
| ⑨ | 事件行：官方 `llm/retry` / 轮失败一行一条 | `client/cells.js:619`（retry 事件行）、dsh-Go 对 retry 行的口径；turn/end reason.kind=error 官方画错误行 | `BotChatViewModel.kt:752-771`（llm/retry → 「模型请求失败，正在重试(n/max)：原因」）、`:729-748`（turnfail 行）、`ui/chat/EventLineFold.kt:29-71` | ⚠️ **有意偏离**：相同文案的连续事件行折叠成一条「 ×N」（retry 重灾区一次十几条把对话堆很高；官方一条一行）。其余文案/分级对齐 | `EventLineFoldTest`（6 例）、`ChatEventToneTest`（7 例）、`BotChatViewModelWiringTest#turnFailureNotDuplicatedWhenFollowAlreadyReported`、`#llmRetryFrameBecomesARetryRow`（2026-11-14 补） |
| ⑩ | 群聊时间线：宿主 `roomItems`（user/post/relay replies）+ `room-progress` 每秒读 + 群产物 `room-artifacts`（presented+扫盘兜底）贴在末尾 | v0.2.5 `host/mobile.js:74-124`（roomItems）、`host/groups.js:1010-1041`（roomArtifacts）、`client/cells.js:872-961`（GroupReplies 轮询）、`ChatRows/stackReplies`（`client/cells.js:313-324,498-546`） | `BotChatViewModel.kt:1503-1518`（room-chat running/speaking）、`:1526+`（room-progress 每秒，3 次拿不到按没在跑）、`:2021-2053`（room-artifacts，since=轮开始）、`:2056-2074`（卡贴末尾 seq=MAX，重载补回）、`ui/chat/RoomModels.kt` | ✅（大方向） | `RoomModelsTest`（progress/成员状态 4 例）、`ChatActivityTest`（群活动行 4 例）；**roomItems 逐 kind 解析、群产物卡聚合无 VM 级锁（缺口，改动大——Phase 0 只列不做）** |
| ⑪ | earlier 分页：游标（part+beforeSeq）回传引擎；新页**插在前面**；已见过的（按 id）去重；翻页后补齐该页范围内的事件行 | `host/parts.js:1272-1324`（partPage/earlierPage：cursor、divider）、`client/cells.js:422-493`（EarlierParts 向上分页、`pages.sort(byPartIndex)`） | `BotChatViewModel.kt:1339-1377`（loadEarlier：`earlier.filterNot{seen} + entries`）、`data/remote/ChatCursor.kt:57`（parseChatPage） | ✅ | `ChatCursorTest`（游标形状 6 例）、`BotChatEarlierPageTest`（VM 级：插前面/去重/收口/补事件行，2026-11-14 补） |
| ⑫ | 审批/提问卡显示身份：审批只认 DS Bot `state.work.approvals`（answerable 决定给不给按钮）；提问按会话归属过滤（群聊成员会话隐藏，只认 DS Bot 关联） | `client/index.js:389`（ApprovalCell 挂 DS Bot state）、dsh-Go `ApprovalMerge` 同口径 | `ui/chat/ApprovalMerge.kt:17-95`（mergeChatApprovals + isRelevant：ourSessions / sessionId==chat / botId 兜底） | ✅ | `ApprovalMergeTest`（6 例：单聊/群聊/旧插件/提问不丢/他人会话忽略） |

补充（非规则、备忘）：**feed/message 落地不再无条件追加**——按 seq 找位插入
（`mergeTimelineEntry`，`TimelineMerge.kt:35-42`），占位气泡挂在末尾时，比它更早落日志的
收尾文字不能被甩到后面（`BotChatViewModel.kt:1414-1422`，锁在
`BotChatTimelinePositionTest#queuedMessageClosingTextSettlesBeforeTheBubble`）。

## 3. 测试矩阵现状（app/src/test/.../ui/chat/ 及相邻）

| 测试类 | 锁住的规则 | 用例数 |
|---|---|---|
| `TimelineMergeTest` | ①（seq 找位、无 seq 不动、幂等、单条插入） | 7 |
| `BotChatTimelinePositionTest` | ①②④（占位防碰撞、回显认领+不重复、排队收尾归位、连发、steer 混发） | 7 |
| `BotChatArtifactLiveTest` | ⑦⑧ + follow 重订（turn/end 出卡、未闭合不出卡、聚合、正在交付→已交付、快照边缘清理、断流重订、永久失败不重试） | 7 |
| `BotChatEarlierPageTest` | ⑪（VM 级：插前面/去重/收口/补事件行） | 2 |
| `ArtifactDerivationTest` | ⑦（produced 口径、聚合、轮末固定、多轮排序、相对路径） | 9 |
| `EventLineFoldTest` | ⑨ 有意偏离（×N 折叠及其边界） | 6 |
| `ToolEventLineTest` | ⑧⑨（工具行文案/目标头像/失败不点/present 计数） | 12 |
| `ChatEventToneTest` | ⑨（失败分级、错误码文案） | 7 |
| `ApprovalMergeTest` | ⑫（归属、按钮、提问不丢） | 6 |
| `RoomModelsTest` | ⑩ 部分（room-progress、成员状态、群/单聊状态来源） | 7 |
| `ChatActivityTest` | 活动行（与官方 Elapsed/短语对齐） | 10 |
| `ChatPendingTest` | ⑫ 相邻（dock 卡解析/蓝图/公式） | 8 |
| `BotChatViewModelWiringTest` | 接线：主机事件→卡片、feed→列表/侧通道、follow 帧主线程顺序、turnfail 去重、diag 行存活、llm/retry 行 | 8 |
| `StreamingMarkdownTest` | ⑥（节流单调、链接、淡入） | 12 |
| `HistoryMemoryTest` | ② 的持久面（磁盘快照按 id/seq 去重） | 6 |
| `data/remote/ChatCursorTest` | ⑪（游标形状与解析） | 6 |
| `data/remote/DeliverablesTest` | ⑦（presented 事件解析：校验/跳坏条目/整条拒绝） | 6 |
| `data/remote/DshSessionRepositoryContractTest` | ③⑤（prompt envelope/mode/content、updateQueue edit/steer/remove） | 7 |

**零测试锁的规则（Phase 0 盘点 → 2026-11-14 补齐后余量）**：
- ~~② 回显不产生重复气泡~~（已补）；~~③ updateQueue steer/remove~~（已补）；~~⑧ 快照边缘 present 清理~~（已补）；~~⑨ llm/retry 行生成~~（已补）；~~⑪ loadEarlier VM 级行为~~（已补）；
- 仍开放：send 的 `mode:'steer'` 透传断言（BotChatTimelinePositionTest 的 FakeApi 未记录 payload.mode，随 ⑩ 群聊 harness 一起补）；⑤ 附件乐观期本地预览（UI 层，无 Robolectric 锁）；
- ⑩ 群聊 `roomItems` 逐 kind、群产物卡聚合（**改动大，Phase 1 做**）。

## 4. Phase 0 待补缺口 + Phase 1 施工要点备忘

### 4.1 本轮已补（2026-11-14，5 个测试类，全量 588 例通过）

| 缺口 | 落点（已落地） | 状态 |
|---|---|---|
| ② 回显不重复 | `BotChatTimelinePositionTest#serverEchoKeepsOneBubbleInPlace` | ✅ 已补 |
| ③ updateQueue steer/remove action | `DshSessionRepositoryContractTest#updateQueueSteerAndRemoveActions` | ✅ 已补 |
| ⑧ 快照边缘悬挂 present 清理 | `BotChatArtifactLiveTest#snapshotDropsAPendingPresentRowWhoseResultFellOutside` | ✅ 已补 |
| ⑨ llm/retry 行生成 | `BotChatViewModelWiringTest#llmRetryFrameBecomesARetryRow` | ✅ 已补 |
| ⑪ loadEarlier VM 级行为 | 新 `BotChatEarlierPageTest`（插前面/去重/收口/补事件行） | ✅ 已补 |

### 4.2 只列不做（Phase 1 或专项）

- ⑩ 群聊 `roomItems` 逐 kind 解析 + 群产物卡聚合的 VM 级锁：需要群聊 harness（room-chat
  FakeApi + 多成员 state），工作量 ≥1 天，等 Phase 1 宿主投影落位后一起收；
- ⑤ 附件块顺序改官方口径（attachments 先、text 后）：主代码改动 + 现有断言同步，Phase 1 做；
- ⑥ 流式共享渲染位（若产品要求气泡原地流式而非侧通道）：大改，等产品拍板。

### 4.3 Phase 1 施工要点（宿主侧统一投影，schema 草案方向）

- **一个投影器，一路输出**：`TimelineProjector(events) -> List<Row>`，输入只有会话事件
  （follow snapshot + 实时帧），取代现在的五路各自为政；历史 `chat`/`room-chat` items
  退化为「离线兜底」，不再是第二事实源；
- Row schema 最小集：`{key, kind, seq, turn, timeMs, payload}`，key 沿官方
  `user:$requestId` / `tool:$seq` / `present:$turn` 惯例；kind 对齐官方
  node keys（user/steering/assistant-step/tool-call/turn-trigger/turn-tail/event）；
- turn 归属与产物卡用纯函数（`turnTraces`/`deriveArtifacts` 已就位），投影器只负责
  「事件流 → 行」，渲染折叠/分组留给 UI；
- 无 seq 行（本地 echo、divider）不参与排序、只随认领收口——保持 `resortBySeq` 的语义。

## 5. 版本与出处备注

- DS-Bot 基线：`DS-Bot/ds-bot`（v0.2.4，带 src/client 未打包源码）+ assets 内 v0.2.5
  （`app/src/main/assets/ds-bot`，仅 host 有差异：新增 `mobile.js` roomItems 与
  `groups.js` roomArtifacts）；两版排序语义无差异。
- 官方 npm 包版本 0.2.1-rc.4（与 DS Bot 0.2.4/0.2.5 的 `dev/harness` pin 一致）；本地副本
  在临时目录（`/tmp/dsh-ui-deliv2/package/lib/` 等），重启即失——出处行号以本文件为准，
  需要重拉：`npm pack @deepseek-ai/dsh-client-ui-deliverables@0.2.1-rc.4` 等三件套。
- 交叉对照：`docs/web-parity-chat.md`（功能覆盖清单，与本文件互补：它管「画什么」，
  本文件管「按什么顺序/时机画」）。
