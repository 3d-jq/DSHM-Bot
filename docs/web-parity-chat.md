# 对话界面与 DS Bot 网页版的对齐清单（2026-11-09）

> 目标：**网页版对话里出现的东西，原生界面必须全部出现**（反之不要求）。做完这份清单，
> 才可以把「网页版入口」去掉。
>
> 基线代码：
> - 宿主条目流：`app/src/main/assets/ds-bot/src/host/parts.js`（`displayItems`，单聊 `chat`）
>   与 `src/host/mobile.js`（`roomItems`，群聊 `room-chat`）
> - 网页版渲染：`app/src/main/assets/ds-bot/client.js`（`chatRow` / `ChatRows` / `GroupReplies`）
> - 通道契约：`src/host/channel.js`（含 `file-text`）、`src/host/mobile.js`
>
> 状态：☑️ 已对齐 / 🟡 部分 / ⬜ 未做 / 🚫 有意不做（用户明确否掉）

## A. 历史条目（`chat` / `room-chat` 的 items）

| # | kind | 网页版画法 | 我们 | 备注 |
|---|---|---|---|---|
| 1 | `user` | `EarlierUser`：气泡 + 折叠 + 「N images」计数（`client.js:7339`） | ☑️ | 图片本身宿主只给计数，网页版也只有计数 |
| 2 | `bot` | `Bubbles`：多段文本、折叠、`whole` 附件、群聊还带名字/时间/耗时（`client.js:6961/7273`） | ☑️ | 正文 / 折叠 / 附件都对齐；群聊的**时间 / 耗时**按 2026-11-09 决定**有意不做**（🚫 见下） |
| 3 | `answer`（questions） | `AnsweredCell` → `SettledQuestion`：问题 + 你的回答（`client.js:4009/3928`） | ☑️ | 本轮新增 `ChatEntryKind.ANSWERED` |
| 4 | `answer`（views） | `AnsweredView` → `WidgetCard(settled)`：把回答过的视图连当时填写一起只读回放（`client.js:6008/5941`） | ☑️ | 本轮补：`AnsweredViewCard` 按 `values.selected/fields/inputs/confirmed` 填回 |
| 5 | `view` | `WidgetCell`：只读视图卡（`client.js:7456` 附近） | ☑️ | 本轮新增只读视图卡（复用 `ViewNode`） |
| 6 | `event`（role: request/reply/report/stopped） | `EventLine` / `GroupEvent`，可点开对方对话（`client.js:7215/7452/7530`） | ☑️ | |
| 7 | `event`（schedule） | 定时任务提醒行 | 🟡 | 文案在，待真机确认 |
| 8 | `event`（round: done/stopped/working/approval/failed/missed/lookup/resumed/cut） | `GroupReplies`（`client.js:7764-7806`），`failed` 是错误行 | ☑️ | 本轮补错误分级 + 失败码文案 |
| 9 | `divider` | `PartLine`：一条时间分隔线（`client.js:7330`） | ☑️ | 本轮新增 `PartDividerRow` |
| 10 | `bot.whole`（长文） | `Bubbles` + 「Show all」按 `file-text` 读全文（`channel.js:200`、`delivery.js:143`） | ☑️ | 本轮新增附件行 + 弹窗看全文 |
| 11 | `btw`（旁白卡） | `BtwCardView`（`withBtwRows`，`client.js:7440` 附近） | 🚫 | 2026-11-09 决定不做：网页版对话里也没有创建入口，卡片本身是别的入口产出的 |

## B. 工具调用卡片（网页版 `chatRow` 里的 `DRAWN` 工具）

网页版按工具名画事件行或卡片（`client.js` 的 `DRAWN` / `CARDS` 集合）：
`message_bot / stop_bot / create_bot / update_bot / group_relay / create_group / update_group /
delete_group / post_to_group / ask_user / request_secret / ask_user_question / show_view /
read_group_chat / remember / forget`。

| 组 | 网页版 | 我们 | 备注 |
|---|---|---|---|
| 中转类（`message_bot` / `stop_bot` / `create_bot` / `update_bot` / 群操作） | 事件行（`Messaged X` / `Stopped X` / `Couldn't message X` / `Couldn't stop X` / `Created group "X"`…），可点开对方对话 | ☑️ | 本轮核对完（`ToolEventLineTest` 覆盖）：群聊类工具从结果里解析群名（网页版同一个正则）；`create_bot`/`update_bot` 失败时我们多画一行「创建/更新 Bot 失败」（网页版什么都不画），属于「多给信息」，保留 |
| 卡片类（`ask_user` / `request_secret` / `show_view`） | 待办卡（`ask_user_question` 与 `show_view` 在 dock；答过之后回放） | 🟡 | dock 已有；`show_view` 只读回放本轮补上；`ask_user` 答过的回放（`ANSWERED`）已有 |
| 记忆类（`remember` / `forget`） | 事件行 + DS Bot 记忆图标 | ☑️ | 图标与文案已对齐 |
| `read_group_chat` | 事件行（阅读群聊，带成员头像堆叠） | ☑️ | |

## C. 实时态

| 事件 | 网页版 | 我们 | 备注 |
|---|---|---|---|
| 流式文本 | `Bubbles` + `stream` 帧（`feed.js:94`） | ☑️ | 本轮还统一了与落地气泡的间距 |
| 活动行（思考 / 工具短语） | `bt-activity` + Shimmer | ☑️ | |
| 群聊「谁在发言」 | `bt-activity`（`{name} is typing…`） | ☑️ | 群聊活动状态行已做 |
| 新消息分隔 | `NewDivider` | 🚫 | 用户明确要求去掉 |
| 消息悬停工具栏 | `MessageTools`（hover 复制/引用） | ☑️ | 移动端等价物：长按复制 |
| 长文折叠 | `FOLD_HUGE` + 「Show all」 | ☑️ | |
| 反应 / 表情 | `reactions`（`useUi`） | 🚫 | 2026-11-09 决定不做：网页版的反应存在浏览器本地（`ds-bot:reactions`），不发引擎、无法同步 |

## D. 分页与其它

| 项 | 网页版 | 我们 | 备注 |
|---|---|---|---|
| 更早的消息 | `EarlierParts` + 游标（`client.js:7357`） | ☑️ | `earlier` + `loadEarlier` |
| 一段会话的分隔 | `PartLine` | ☑️ | 本轮补 |
| 长文全文 | `file-text` | ☑️ | 本轮补 |
| 静默失败 | 诊断里记录，群里 `{name} could not answer` | ☑️ | 见 `bugfix-chat-2026-11-09.md` |

## 收口（2026-11-09）

清单里已没有 ⬜：网页版对话会画的东西，我们要么已对齐（☑️），要么是**有意不做**（🚫）：

| 有意不做 | 决定与理由 |
|---|---|
| 群聊消息的时间 / 耗时 | 2026-11-09：手机上不需要（耗时更是噪音） |
| BTW 旁白卡（旁路问答） | 2026-11-09：网页版对话里也没有创建入口，卡片是别的入口产出的 |
| reactions（表情回应） | 2026-11-09：网页版存在浏览器本地、不发引擎，无法同步 |

> 也就是说：**对话界面的「网页版有、我们没有」这条已经清零**，可以把网页版入口去掉了。
> 升级插件（`0.2.5` → `0.3.x`）之后，这份表要按新版 `client.js` / `mobile.js` 重新核一遍。

> 说明：表格里的行号对应当前 `client.js` / `channel.js` / `mobile.js` 的版本（插件 `0.2.5`），
> 升级插件后需要重新核对一遍。
