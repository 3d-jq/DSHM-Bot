# DS Bot 安卓版：时间线架构与能力对齐设计

> 状态：设计稿（2026-11-15）。用户定的总方向：**沿袭 dsh-Go 的一致性模型 + 官方显示什么我们显示什么**，目标是「可以直接说是 DS Bot 的安卓版」。
> 上游依据：`docs/timeline-parity.md`（宪章）、`docs/phase1-design.md`、`docs/web-parity-chat.md`。

## 0. 原则与取舍（用户 2026-11-15 明确）

1. **地基优先**：先换时间线一致性模型，再堆能力。之前反复出问题（产物卡不及时、任务错位）都是因为「一次性消费的 feed 增量帧当权威」，客户端无自愈能力。
2. **对齐的是「信息/能力」，不是 DOM/像素**。网页端 hover/右键/多窗口/桌面快捷键属于平台形态，有意不做；dsh-Go（Flutter 移动端）是**形态基准**，DS-Bot 网页端是**能力/信息基准**。
3. **浏览器能力已对齐（移动端增强）**：BotBrowserStore / BrowserReadBridge / BrowserGuard / BrowserShotStore 一整套，不动。
4. **中间过程按 DS Bot 网页端：隐藏工具 I/O**（用户 2026-11-15 定稿）；要补的是官方的「代码产物」= 收尾回复里产出文件的可点链接 + 轮末产物卡，不是 dsh-Go 的全展开过程。
5. **每个「不做/等价替代」记录在案**，避免以后被当成漏做。

## 1. 现状与根因

```
宿主: session/event → timelineDelta() → published 游标推进 → 帧只发一次
客户端: feed SSE → LinkedHashMap<key, Row>（显示顺序 = 帧到达顺序）
                    ↑ 丢一帧 = 那行永久缺，只能等快照重拉
```

dsh-Go 的模型（调查结论）：
```
session/follow (snapshot + 增量 event) → _SessionWatch (seq 连续校验/_fill 补洞/resync)
   → ChatTranscript.apply → _log (SplayTreeMap<seq, event>, 唯一事实源)
   → items (_DurableFold 全量重算) → deriveRows (纯函数) → ChatList
```
- `_log[seq] = event` 幂等覆盖、`SplayTreeMap` 乱序自动归位
- `items` 惰性 `_build()` 全量重算，`_durable = null` 失效
- 断线三层兜底：快照 + `_fill` 补洞 + resync 合并；**durable 事件不会永久缺**
- 乐观气泡 `p$requestId` 同 key、尾部 12 条有界扫描认领

**唯一不能直接照搬的点**：dsh-Go 单会话全局 seq；我们有 part（`ChatCursor`），part 之间 seq 空间独立 → 日志键必须带 part 维度，earlier 页整页前插、不跨 part 排序。

## 2. 一期：TimelineEventLog（有序事实源）

### 数据结构
```kotlin
// ui/chat/TimelineEventLog.kt（纯 JVM 可测，无 Android 依赖）
class TimelineEventLog {
    // 每个 part 一份按 seq 升序的日志；当前 part 由 timeline 快照的 sessionId/cursor 决定
    private val parts = LinkedHashMap<String, TreeMap<Long, TimelineRow>>()
    // 无 seq 的行（inbox / divider / echo）按宿主给的位置序挂尾部
    private val tailRows = LinkedHashMap<String, TimelineRow>()
    // 幂等写入：同 seq 覆盖；迟到/乱序由 TreeMap 自动归位
    fun put(part: String, row: TimelineRow)
    fun reset(snapshotRows: List<TimelineRow>)        // 非 resync 快照：清空重建
    fun merge(snapshotRows: List<TimelineRow>)        // resync / since 重放：按 seq 合并
    fun prependPage(rows: List<TimelineRow>)          // earlier：整页前插到最早 part 之前
    fun rows(): List<TimelineRow>                     // 按 part 顺序 + 段内 seq 升序 + tail
    fun maxSeq(): Long?
}
```

### 写入点（全部改成**只写日志 + 触发 rebuild**，不再直接改显示缓冲）
| 来源 | 动作 |
|---|---|
| `refreshTimelineSnapshot(reset=true)` | `log.reset(snapshot.rows)` |
| `refreshTimelineSnapshot(reset=false)` (since 重放 / hello / 断档) | `log.merge(snapshot.rows)` |
| feed `timeline` 增量帧 | `log.put(currentPart, row)` 每行；帧只当通知 |
| `loadEarlier()` | `log.prependPage(snapshot.rows)` |
| 群聊 reset 帧 | `log.reset(...)` |

### 显示
`rebuildParityEntries()` 永远 `log.rows()` → `chatEntryOf()` 映射 → `_uiState`，**不再依赖插入序**。
- `paritySeq` 只作 since 游标，不决定显示
- `parityRenderKeys` / `pendingEchoRows` / `browserShots` 是叠加在日志之上的客户端瞬时层（对齐 dsh-Go `_pending`/`_approvals` 的定位）
- 断档检测（`abb70ab`）保留：帧 `from > paritySeq` 时主动重放，加速补齐

### 自愈保证
- 任何一次通信（快照/增量/重连/earlier）把某行带回来 → 写进日志 → 立刻出现在正确位置
- 重复/乱序/迟到幂等，不再有「删掉旧的才显示新的」
- **最后一帧丢了也能补**：客户端在 `running→false` / `notice done` 时主动 `refreshTimelineSnapshot(reset=false)` 重放一次（不依赖下一帧触发断档检测）

### 验收
- 高强度：切后台/锁屏/开关网络/连续多轮，产物卡与各行列实时且不错位
- JVM 测试：乱序写入归位、同 seq 覆盖、resync 合并不清空、earlier 前插顺序、part 隔离、丢帧后重放回补

## 3. 二期：代码产物卡（对齐 **DSH 的 ChangedFiles 卡**）

> 用户 2026-11-15 澄清：「不是可点链接吧 人家也是有张卡的呀；dsh bot 这个你要结合 dsh 一起看的，
> 因为他是用在 dsh 上的一个插件」。机制已从官方包实读（`@deepseek-ai/dsh-client-ui-deliverables@0.2.1-rc.4`，
> `lib/client.js`），不是推测。

### 机制（官方）
| 项 | 官方实现 |
|---|---|
| 事件 | **DSH** 发持久事件 `workspace/changes` `{turn}`（DSH 本地 git 影子仓库算，设备 temp 里 `dsh-workspace-changes-*/objects/` 即其物证）；`isChangesEvent` 只校验 `turn` 为正整数（`client.js:81`） |
| 数据 | 客户端调 DSH 路由 **`GET /api/changes.summary?sessionId=..&seq=..`**（`CHANGED_FILES_PATH`，`client.js:13`）→ `{files:[{path,display,binary,oversized}], total, added, deleted}` |
| 卡片 | `ChangedFiles`（`client.js:1351`）：文件图标（单文件按 path 取 FileTypeIcon）+ 标题 `changes.title`「Edited {count} files」/ `changes.singleTitle`「Edited {name}」+ 增删行数 `Counts`（binary/oversized 特判）+ 预览提示；点 header 进 review；`>COLLAPSED_ROWS` 折叠 |
| 位置 | 与 presented 同属轮末 tail 卡（`Deliverables`，`client.js:1642`）：**changes 在上、presented 在下** |
| 依赖 | 需 `showCodeDiff` 开启才画 changes 行；diff 走 `/api/changes.diff`，打开走 `/api/changes.open` |

### 我们怎么接（三步，都在同端口，不需要自己算 git）
1. **宿主 `projection.js`**：在 `session/event` 里认 `workspace/changes` → 产出**轻量行** `kind:'changes'`（`key:'changes:<seq>'`, `seq`, `turn`，payload 为空/仅 turn）。
   位置与产物卡同区（轮末、artifact 行之前，对齐「changes 在上」）。
2. **客户端**：收到 `changes` 行后调 **`/api/changes.summary?sessionId&seq`**（走 `DshClient`，与 `/api/session/*` 同 cookie），拿 files/total/added/deleted 缓存在行上。
3. **渲染 `ChangedFilesCard`**：文件图标 + 「编辑了 N 个文件 / 编辑了 X」+ `+N −M`；>5 行折叠；点开走现有「打开文件」链路（`FileProvider` → `ACTION_VIEW`）。
   与 `ArtifactCard` 竖排（changes 卡在产物卡之上），合成轮末 tail。

### 取舍
- **不接** `changes.diff` 内联 diff 预览、`changes.open` 宿主桌面打开、review 侧栏（网页平台形态；我们要看改了什么，点文件走系统应用打开即可）。
- **不接** `showCodeDiff` 设置项（我们默认就显示这张卡；官方默认关是为了桌面噪音，手机上一张折叠卡不吵）。
- 工具过程（bash/read/write 命令与输出）**仍不画**，与官方一致。

### 验收
- 跑一个改文件的回合：轮末出现「编辑了 N 个文件」卡，显示文件名与增删行数，点文件能打开；
- 与产物卡上下并排；不出现命令与输出。

## 4. 三期：能力收尾

### 已对齐（不动）
聊天/流式/产物卡/审批/提问/视图卡/附件/**浏览器整套**/语音/手机操控/通知栏、20+ 设置子页、模型渠道、插件、权限、定时任务、记忆、诊断。

### 真缺口（宿主 88 端点盘点后剩余）
| 端点 | 能力 | 计划 |
|---|---|---|
| `add-agent` / `remove-agent` / `agent-session` / `agent-adopt` / `agent-archive` / `agent-rename` | Bot 多会话段（新建/接管/归档/改标题） | 先出小设计，Bot 详情页加入口 |
| `capability-save` | 保存自定义能力配置 | 能力页补保存 |
| `repo-stars` | About 页装饰 | 可选 |

### 有意不做（记录在案）
- `side-*` BTW 旁路问答卡（网页端有入口但移动端无场景）
- `update-*` / `dsh-update-install`（我们是 APK 内 runtime，CI 产 rootfs）
- `provider-add/key/remove/models`（走 `settings/mutate` 等价路径）
- `team-workspace`（走 `folders` 等价路径）
- `room-chat` / `room-artifacts`（timeline 取代）
- 网页 hover/右键/多窗口/桌面快捷键（平台形态）

## 5. 分期与验收

| 期 | 内容 | 独立验收 |
|---|---|---|
| **一期** | TimelineEventLog + 全量重算 + 主动重放 | 高强度测试无「不及时/错位」，606+ 测试绿 |
| **二期** | 收尾回复 fileMentions（产出文件可点链接）+ 产物卡保持；不画工具 I/O | 回复里文件名可点、对话保持净版 |
| **三期** | agent-* / capability-save / repo-stars | 「安卓版」能力清单核对 |

每期：全量 Kotlin + JS 测试、lint 零 warning、assemble、装机、用户高强度验收。

## 6. 有意偏离清单（更新宪章）
| 偏离 | 理由 | 用户确认 |
|---|---|---|
| 名字扫光替代头像状态圈 | 用户要求 | 2026-11-14 |
| 事件行不画 `working`/`approval` | 与审批面板/dock 重复 | 2026-11-15 |
| 中间过程按网页端隐藏、不画工具 I/O | 官方刻意隐藏（隐私/噪音），用户定稿 | 2026-11-15 |
| 群卡只认 present、按轮持久化 | 官方无群卡，用户要求产物可见 | 2026-11-14 |
