# 「DS Bot 安卓版」能力对齐清单（施工总纲）

> 2026-11-15 定稿。依据两份系统研究：`docs/dsh-study.md`（DSH 底层，0.2.1-rc.4，377 行）、
> `docs/ds-bot-study.md`（DS Bot 插件 + 网页端，v0.2.5，519 行）。
> 本文是**唯一的对齐总纲**：做什么、为什么、出处在哪、我们现状如何。设计细节见 `docs/dsh-go-parity-design.md`。

## 0. 两条基准线（用户 2026-11-15 定）

1. **能力/信息基准 = DS Bot 网页端 + DSH**：官方显示什么信息、能做什么操作，我们要有。
2. **形态基准 = dsh-Go（Flutter 移动端）**：交互怎么放、手机上怎么好用，学它。
3. **取舍**：平台形态型（hover/右键/多窗口/桌面快捷键）有意不做；浏览器/手机操控/语音等**已对齐**。

## 1. 已对齐（不要动）

| 能力 | 说明 |
|---|---|
| 聊天时间线（地基） | 一期已换为 dsh-Go 式**有序事件日志 + 全量重算**（`4751309`） |
| 产物卡（presented） | 轮末固定、按轮持久化、present 优先 |
| 审批 / 提问 / 视图卡 / 密钥卡 | mux `$events` + `state.questions/views/secretRequests` |
| 附件、长文（whole）、图片查看器 | 本地缓存 + FileProvider |
| **浏览器整套** | WebView 池/多标签/下载/`read_browser`/BrowserGuard/缩略图卡 |
| 语音输入、手机操控（无障碍） | 独立通道 |
| 20+ 设置子页、模型渠道、插件、权限、定时任务、记忆、诊断、账号 | |
| 群聊（成员 dock、round 状态、群产物卡按轮） | |
| 气泡宽度上限（助手/用户各留白） | 官方 80% / 78%（`.bt-bubble` / `.bt-ububble`）；**当前取 85%/85% 观察中**（用户 2026-11-16 试值，改 `BubbleWidth.kt` 一处即可回官方） |
| 引用追问（长按「引用」→ Markdown 引用块） | 对齐 dsh-Go `composer_quote` + 网页 hover 的 Reply（2026-11-16） |
| 通知栏（回合完成/错误/审批/提问） | |
| 正文搜索（`session/search`，结果聚成「消息」段） | 对齐网页端 CommandPalette 的 Messages 段（2026-11-16） |

## 2. 必修（官方有、我们缺；按建议优先级）

### ★★★ P1：代码产物卡（ChangedFiles）
- **官方出处**：DSH `workspace/changes` 事件（只带 `{turn}`）+ 路由 `GET /api/changes.summary?sessionId=..&seq=..` → `{files:[{path,display,binary,oversized}], total, added, deleted}`；`/api/changes.diff`、`/api/changes.open`。
  卡在 `dsh-client-ui-deliverables` 的 tail 里（`ChangedFiles`，标题「Edited N files / Edited {name}」+ `+N −M`），**在 presented 行之上**。
- **我们现状**：宿主 `projection.js` **完全不认 `workspace/changes`**；客户端无此卡。
- **做法**：宿主认事件产轻量行 `kind:'changes'`（key/seq/turn）→ 客户端调 `/api/changes.summary`（同端口，同 cookie）→ 渲染 `ChangedFilesCard`（文件图标 + 标题 + 增删行数 + 折叠 >5），点文件走现有「打开文件」链路。**不接** diff 内联预览 / `.open` 宿主桌面打开（平台取舍）。

### ★★☆ P2：DSH Agent 管理（多会话段）
- **官方出处**：`state.agents` + `add-agent` / `agent-session` / `agent-adopt` / `agent-archive` / `agent-rename` / `remove-agent`。
- **我们现状**：6 个端点全未接；`state.agents` 只在别处读。
- **做法**：Bot 详情页加「会话」入口（列 Agent 会话、新建段、接管、归档、改标题）。需先出小设计（UI 放哪、与现有 session/\* 的关系）。

### ★☆☆ P3：会话列表实时（`session/control`）
- 官方用它让侧栏会话列表实时更新；我们靠轮询 `state`。收益中等。

### ★☆☆ P3：工作区文件（`workspaceFiles/*` / `workspace/*` CRUD / `directoryPicker`）
- 官方有工作区浏览/增删/选目录。我们部分走 `folders`/`folder-pick`。按需补。

### ★☆☆ P3：其余 DSH 已有未接
`skills/list`（技能列表）、`terminal/*`、`job/*`、`fileReferences/list`、`image/offload`、`session/attachment`、`session/openWorkspacePath`。
以及 DS Bot 侧：`capability-save`（新建/编辑能力项）、`team-workspace`、插件/DSH 自更新 UI（`update-*`/`repo-stars`）。

## 3. 有意不做（记录在案，避免反复翻出来）

| 项 | 理由 |
|---|---|
| **侧聊 / BTW 旁路问答**（`state.sideChats` + `side-new/ask/cancel/close`） | **用户 2026-11-15 定为不做**（官方有整套侧栏面板，我们不做） |
| **present 原生打开**（DSH `/api/present.open` / `/api/present.host`） | **用户 2026-11-15 定为不做**：我们已有「下载到 cacheDir → FileProvider → ACTION_VIEW」，够用且更简单；DSH 那套是宿主桌面打开 |
| reactions（表情回应） | 网页端存浏览器本地、不发引擎 |
| 群聊消息的时间/耗时 | 手机上无价值 |
| 新消息分隔线 | 用户明确要求去掉 |
| `update-check` / `update-install` / `dsh-update-install` | 我们是 APK 内 runtime（CI 产 rootfs） |
| `provider-add/key/remove/models` | 走 DSH `settings/mutate` 等价路径 |
| 不显示 `present` 两态工具行 | **用户 2026-11-14 要求**（官方在 DSH 层确有 `PresentRow`，属用户定偏离） |
| 不画群轮 `working`/`approval` 事件行 | 用户要求（与审批面板/dock 重复） |

## 4. 平台取舍（网页特有，不做）

hover 工具栏、右键菜单、多窗口/并排、桌面快捷键、`changes.diff` 内联 diff 预览、`changes.open` 宿主桌面打开、review 侧栏、`Sidebar` 资源体系、CSS 动效细节。
→ Android 用等价交互：长按代替 hover、底部面板代替弹窗、系统应用打开代替宿主打开。

## 5. 分期建议

| 期 | 内容 | 验收 |
|---|---|---|
| **一期 ✅** | 时间线有序日志 + 全量重算 + 轮末无条件重放 | 已装机，高强度测试无「不及时/错位」 |
| **二期** | ChangedFiles 代码产物卡 + 交付文件原生打开（present.host 探测） | 改文件的回合出「编辑了 N 个文件」卡、点开能打开 |
| **三期** | DSH Agent 管理 + session/control + 其余 P3 | 「安卓版」能力清单核对 |
| **四期** | 插件化接管（DSH 公开扩展点：槽位/RPC）——按 roadmap「大目标」推进 | 上游能力长成我们的形状 |

## 6. 关键结论（来自研究，重要）

- **DSH 的扩展点是公开的**：DS Bot 覆盖 ~20 个槽位 + 注册 `/api/bot` 路由，**不改内核、不加事件类型** → 我们（作为插件/或插件化接管）能走同一条路，这是「让上游适配我们」的可行性基础。
- **`workspace/changes` / `changes.summary` / `present.*` 是 DSH 提供的现成能力**：改动卡与交付卡**不需要自己算 git**，直接调 DSH 路由。
- **多条能力在 DSH 层而非 DS Bot 层**（present 工具行、changes 卡、present 打开）——只看 DS Bot 会漏，必须两边一起看（用户判断正确）。
