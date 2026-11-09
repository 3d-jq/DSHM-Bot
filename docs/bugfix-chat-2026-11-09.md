# 对话界面待修 bug（2026-11-09）

> 状态（2026-11-09 晚）：**已按 3 → 2 → 1 → 4 全部动手**，单测通过；真机装机待手机重新连上后确认。
> - Bug 3：`bubbleUserStart/End` 换成实心 `bubbleUser` + 新增 `onBubbleUser`（深色主题下 `onAccent` 是深色，蓝底必须用白字）。
> - Bug 2：`ChatSkeleton` 增加 `topPadding`，调用处传 `innerPadding.calculateTopPadding()`。
> - Bug 1：抽出纯函数 `chatItemSpacing`（`ChatSpacing.kt`，带 `ChatSpacingTest`），流式气泡 / 思考行 / 正式条目共用；
>   流式气泡改用与最终条目相同的 `BotBubble` 参数（群聊里一样有头像 + 名字）。
> - Bug 4：新增 `ChatEventTone` + `failureLabel`（对齐 DS Bot `FAILURES`，带 `ChatEventToneTest`），失败行用危险色；
>   中转工具（`group_relay` / `message_bot` / `post_to_group` / `stop_bot`）结果到达后去抖重拉会话，
>   让「谁没能回答」这类行及时出现；一轮结束没有任何产出时查 `diagnostics` 的 `source: 'turn'` 条目，
>   找到就补一条失败行；`notice` 除 `approval` 外再处理 `done / question / view / group`。
> 基线：DS Bot 网页版 `app/src/main/assets/ds-bot/`（对话 UI 以它为准），
> 间距令牌见 `core/design/DshmSpacing.kt`。

---

## Bug 1 · 流式气泡与用户气泡的间距不是同一套规则 → 落定时抖一下

### 现象
模型开始输出时，流式气泡与上面的用户气泡之间是「一个间距」；这一轮结束时气泡
「强行」变成正式消息，间距换成另一个值，视觉上抖一下（群聊里还会多出头像 / 名字行，更明显）。

### 根因
- 流式气泡 / 思考行是列表里临时加的 item，各自写死 `top = Dshm.spacing.sm`（8dp）：
  - 思考行 `BotChatPlaceholderScreen.kt:797-807`
  - 流式气泡 `BotChatPlaceholderScreen.kt:808-818`
- 正式消息走的是「按**上方**那条算换人」的规则（`BotChatPlaceholderScreen.kt:819-841`）：
  换人 12dp（`turnGap`）/ 同人 4dp（`xs`）。令牌：`DshmSpacing.kt:16`（xs 4dp）、
  `:17`（sm 8dp）、`:88`（turnGap 12dp）。
- 于是「流式中（8dp）」→「落地（用户→Bot 换人 = 12dp）」差 4dp；群聊还要加上
  「头像 / 名字行」和「组内第二条 4dp」的差异，抖动更明显。
- 附带：思考行消失、流式气泡换成正式气泡时 item 数量变化，也会带一次位置跳变。

### 修法（建议）
1. 抽一个纯函数 `bubbleTopPadding(entries, isGroup, index): Dp`（或 `ChatSpacing`），
   让 **流式气泡 / 思考行 / 正式条目** 都调它，永远只用一套规则。
2. 流式气泡按「它将成为的那条 BOT 条目」参与计算：
   - 单聊：算「上一个正式条目是不是 USER」→ 12dp，否则 4dp；
   - 群聊：同时算「是不是同一 Bot 的组内第二条」，并**预留**名字行 / 头像行的高度，
     这样落地时不会因为多出一行而整体位移。
3. 思考行（`typing`）与流式气泡同时在时，只保留一个占位（DS Bot 是「状态行 → 气泡」二选一）。
4. 别动既有规则本身（同一人 4dp / 换人 12dp / 最后一条到输入框 16dp），只统一入口。

### 验证
- 单聊：发一条 → 观察「用户气泡 → 状态行 → 流式气泡 → 正式气泡」全程，气泡不应上下跳。
- 群聊：让两个 Bot 连续发言，确认组内 4dp、换人 12dp，且流式→正式不位移。
- 补一个 `ChatSpacingTest`：给定 entry 序列，断言流式态与落地态的 top padding 相同。

---

## Bug 2 · 对话骨架屏没有避让顶栏的名字胶囊

### 现象
打开对话时的加载骨架从屏幕最上面开始铺，被悬浮的名字胶囊（含顶栏渐变）压住。

### 根因
- `Scaffold` 的 `innerPadding` 没有应用到内容容器上（内容区是 edge-to-edge 的）：
  `BotChatPlaceholderScreen.kt:366-378`。
- 消息列表自己用 `contentPadding.top = topPadding + Dshm.spacing.md` 让开顶栏：
  `BotChatPlaceholderScreen.kt:780-785`。
- 骨架屏 `ChatSkeleton` 却直接 `fillMaxSize()` + `padding(horizontal, vertical = lg)`：
  `ChatSkeleton.kt:56-68`，调用处也没传顶部留白：`BotChatPlaceholderScreen.kt:390-392`。

### 修法（建议）
- 给 `ChatSkeleton` 加 `topPadding: Dp`（以及可选 `bottomPadding`），内部 `Column`
  用 `Modifier.padding(top = topPadding + Dshm.spacing.md, ...)`；
- 调用处传 `innerPadding.calculateTopPadding()`，与消息列表用同一个常量，
  避免以后顶栏高度变了这里又漏改；
- 顺便确认骨架的行数（现在 6 行）在顶到输入框时不会被 composer 遮住
  （输入框是悬浮的，列表靠 `bottomPadding` 避开）。

### 验证
- 冷启动后马上点进一个没打开过的 Bot，骨架首行应停在名字胶囊下面，不重叠。
- 群聊（带头像）与单聊各看一次。

---

## Bug 3 · 用户气泡是渐变，应该改纯色

### 现象
用户气泡用的是蓝色渐变（上浅下深），看起来不像 DS Bot 的实心气泡。

### 根因
- `BotChatPlaceholderScreen.kt:1155-1160`：
  `Brush.linearGradient(listOf(Dshm.colors.bubbleUserStart, Dshm.colors.bubbleUserEnd))`。
- 令牌：`DshmColors.kt:66-67`（亮色 `#4D8DFF → #2F6BFF`）、`:95-96`（深色同值）。

### 修法（建议）
- 改成纯色 `Dshm.colors.bubbleUser`（新增令牌，取 `bubbleUserEnd` 那一档，避免浅色偏白），
  保留 `onAccent` 文字色；`bubbleUserStart/End` 可以留着不用，或在这次一起删掉。
- 附件区（图片缩略图 / 文件卡）现在用 `onAccent.copy(alpha = 0.16f)` 打底，换纯色后
  重新对一遍对比度。

### 验证
- 发一条文字 + 一张图片 + 一个文件，看气泡底、缩略图边框、文件卡在纯色底上是否清楚。

---

## Bug 4 · 失败 / 其它信息静默，用户完全不知情

### 现象
DS Bot 网页版会显示的一些「非正常结果」信息（成员没能回答、被重启打断、没及时回应等），
在我们的界面里完全没有；轮次悄悄结束，界面上跟「正常做完」一模一样。

### DS Bot 的基线（对齐用）
- 群里成员的各种结果行（`failed / lookup / missed / paused / resumed / cut / approval /
  working / stopped / done`）在 `client.js:7764-7806` 渲染；其中
  **`failed` 是错误行**（`bt-event-error`，文案 `{name} could not answer: {reason}`），
  其余是普通事件行。
- 数据来源：插件 `mobile.js:32` 的 `ROUND_EVENTS`，`:57-68` 的 `replyItem` 把它变成
  `{ kind: 'event', event: <kind>, text, code }`，`:115-118` 由 `group_relay` 的结果塞进 items。
- 自己这一轮的模型调用失败：插件 `turns.js:340-352` 走 `rt.noteFailure({ source: 'turn', … })`
  → 只进 **diagnostics**（`diagnostics.js`），群里另有一句给发送方 Bot 的
  `could not answer …`（`turns.js:577-586`），用户自己的私聊里**没有 chat 行**。

### 我们这边现在的缺口
1. `parseItem` 已经能把 `kind: 'event'` 解析成 `ChatEntryKind.EVENT`
   （`BotChatViewModel.kt:1376-1391`），`eventLineLabel` 也覆盖了
   `failed / missed / cut / resumed / stuck / lookup / done / stopped / working / approval`
   （`BotChatPlaceholderScreen.kt:1553-1578`）——文案在，但**没有任何错误语义**：
   所有事件行都是同一套灰字（`EventLine`，`BotChatPlaceholderScreen.kt:1435+`），
   失败看起来跟「查阅了自己的对话」一样，等于静默。
2. `code` / `status` 已经解析进 `ChatEntry.eventCode`，但没有显示，
   排查时看不到原因（DS Bot 把它放在 `title` 里）。
3. 自己 turn 失败（`noteFailure` 那条路）我们**完全没有入口**：
   - `watchForRunEnd`（`BotChatViewModel.kt:1285-1320`）只是把 `running/activity/stopVisible`
     清掉，失败与正常结束无法区分；
   - 对话里没有失败提示（Toast / 行 / 卡片都没有）；
   - 诊断页有 error 列表，但用户不会去看。
4. `feed` 的 `notice` 分支只处理 `approval`（`BotChatViewModel.kt:935-940`），
   `message / group / done / question / view` 全丢；后台完成、卡片等待这些
   「不在看的时候也该知道」的信息一律没有反馈。

### 修法（建议，分三步）
1. **事件行分级**：`EventLine` 增加 `tone`（`normal` / `error`），
   `failed` → error（红色 + `code/status` 放进长按/详情）；
   `missed / cut / paused` 也按「异常」处理。文案继续走 `eventLineLabel`。
2. **轮次失败要有落点**：`watchForRunEnd` 结束前查一次
   （`state` 或 `diagnostics` 里的错误条目），有失败就给一条错误事件行
   （或 toast + 行），文案对齐 DS Bot 的 `{name} could not answer: {reason}`，
   `failureLabel` 的映射（`client.js:939-950`）可直接抄成 Kotlin。
3. **notices 接进状态**：`notice` 分支至少处理
   `done / question / view / message / group` → 刷新对应 bot 的 `refreshBotStatus()`，
   需要时给「有更新」提示；`approval` 保持现状。

### 验证
- 断网 / 用错误的 API Key 发一条，确认界面上出现一条明确的失败行（不是静默）。
- 群里让一个成员失败，确认只有那一行走错误样式，其它行不变。
- 后台完成一条（切到别的页面再回来），确认有「完成」反馈。
- 单测：给 `EventLine` 的文案/分级写纯函数测试（kind → tone / label）。

---

## 顺序与注意
1. **Bug 3**（纯色，最小）→ 2. **Bug 2**（骨架避让）→ 3. **Bug 1**（统一间距入口）
   → 4. **Bug 4**（静默失败，改动最大）。
- Bug 1 与 Bug 4 都会碰 `BotChatPlaceholderScreen.kt` 的事件行 / 间距，建议不要并行改。
- 全部改完跑 `testDebugUnitTest` + 装机，并更新 `docs/roadmap.md`。
