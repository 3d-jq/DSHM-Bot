# 参考基线：DS Bot 与 dsh-Go 的边界

> 校订：2026-11-05。本项目的组成关系：**DSH 层（引擎、协议、会话、设置、运行时）照 dsh-Go 写；
> Bot 能力层（Bot / 群聊、面板、详情、卡片）照 DS-Bot 写**。两条基线不要混用，也不要互相代替。

## DS Bot 能力 → 以 DS-Bot 为准

凡是 **DS Bot 自己的界面与行为**（主界面的 Bot 列表、Bot 对话、名字胶囊、
底部 Bot 面板、Bot 详情/设置、头像/角色形状、运行状态、记忆提示、气泡样式、卡片），
参考本仓库里的：

- `DS-Bot/` —— 插件本体。`/api/bot` 分派在 `ds-bot/src/host/channel.js`（约 88 个 endpoint），
  feed 在 `ds-bot/src/host/feed.js`（`hello` / `revision` / `live` / `message` / `stream`）。
  **完整 UI 组件在 `ds-bot/src/client/`（79 个 js），不是 `app/ui/src/`**：
  `header.js`（胶囊 + 3.2 秒记忆提示）、`activity-row.js`（小头像 +「理一下思路…」+ 秒数）、
  `details-drawer.js`（底部面板 8 项）、`bot-settings.js`、`group-settings.js`、
  `create-bot.js`、`at-menu.js`、`questions.js`、`widget-cell.js`、`approval-notices.js`、`cells.js`。
  `app/ui/src/` 只是 795 行的精简 Web 壳，`mobile/App.tsx` 是尚未接线的 Expo 占位。
- `DS-Bot-mobile-api/` —— **移动端协议分支，Bot 能力以它为准**。相对主分支新增
  `hello`（`API_VERSION=1`、17 项 capabilities 版本协商）、`notices`、`avatar`、
  `room-chat`、`usage-summary`、`memory-edit/add`，feed 多 `notice` / `room` 事件，
  另有 `dsBot/call` Remote 通道；但缺少主分支的 `avatar-library/import/delete`、
  `stop-room-work`、`browser-takeover/give-back/stop`、`learning-answer` 等，需从主分支补齐。

本项目当前用的 `room-chat`、`usage-summary`、`room` 事件**只存在于 mobile-api 分支**；
**版本协商已接入**（`DsBotCapabilities` + `HomeViewModel.setEndpoint()` 发起 `hello`，
`room-chat` / `usage-summary` 按 `supports()` 裁决，见 `docs/roadmap.md`）；
`notices`、`avatar` 引用、`notice` 事件、`dsBot/call` 仍未接入。

已确认的 DS Bot 行为：

- 对话顶栏胶囊：**平时只有头像 + 名字**，不常驻显示角色（如 `Chief of Staff`）；
  记忆刚更新时，胶囊内短暂出现「更新记忆」提示（约 3.2 秒），随后收回。
- 模型运行/思考状态显示在对话流里的独立活动行（小头像 +「理一下思路…」+ 秒数），
  不是三点气泡，也不是胶囊里的常驻标签。
- 底部 Bot 面板：详情 / 浏览器、模型、权限、工作文件夹、记忆、用量、定时任务、说明。
- Bot 完整设置页：资料、模型、工作文件夹、隐私、说明、记忆、定时任务、
  主 Bot、复制、删除；这些操作都要真正调用 `/api/bot`，不能只画界面。

不要用 dsh-Go 的 Bot 页面来替代 DS Bot 的设计。

## DSH 引擎 / 运行时 → 以 dsh-Go 为准

只有做 **DSH 本身**（引擎启动/停止、运行时/rootfs、DSH 设置 schema、模型渠道、
权限预设、插件 bundler、DSH Web、语音 `speech/*`、会话/工具协议）时，才参考
`dsh-Go/`（Flutter 客户端与 `packages/dsh_client`）。

例如：

- 引擎启动/停止、日志、rootfs、proot、DSH profile → `dsh-Go/`
- `settings/describe`、`settings/mutate`、`plugins/*`、`speech/*` → `dsh-Go/packages/dsh_client/`
- `session/*` 方法签名 → `dsh-Go/packages/dsh_client/lib/src/session_api.dart`
- 消息 Part（`text`/`reasoning`/`tool-call`/`image`/`file`）、`StreamChunk`、日志事件、
  `session/follow` 帧 → `dsh-Go/packages/dsh_client/lib/src/models.dart`
- Bot 对话的 UI 形状、胶囊、状态文案 → `DS-Bot/`，不是 dsh-Go

一句话：**做 DS Bot 看 DS Bot，做 DSH 看 dsh-Go；不要用 dsh-Go 的 Bot 页面冒充 DS Bot。**

## dsh-Go 的「本地化」盘点（2026-11-13，为「提示词 + DSH 大改」做的参考）

> 用户方向：本项目接下来要**大改提示词（prompt）和 DSH**。先把 dsh-Go 到底做了多少本地化盘清楚，
> 避免我们重复造轮子，也避免照着它的做法踩它的坑。以下数字来自本地 `dsh-Go/` 只读基线。

### 结论：它的「本地化」核心不是翻译，而是「中文优先的 agent 产品化」

| 层面 | 规模 |
|---|---|
| UI 文案 | 46 个文案域、92 个 ARB（zh/en 各 46），**5,441 key × 2 语言**；生成 Dart 约 69,874 行；另有 219 行中文-only 的插件设置文案 |
| Agent 注入指令 | AGENTS.md（`GuestSetup.kt` 约 55 行/1.1k 汉字）、安全模式修复简报（`SafeMode.kt` 约 125 行/1.4k 汉字）、手机操控段（`PhoneGuest.kt`） |
| Agent 可见工具/观测 | 13 个手机 MCP 工具（描述+参数+返回全中文）、`dsh-media` 5 个媒体工具 + 7 条中文 `systemPrompt.section`；手机目录 65 文件/15,264 行/6,209 汉字 |
| 自研 DSH 插件 | `dsh-go-bridge` 4,263 行 + `dsh-media` 8,073 行（**新增插件，不 fork DSH**） |
| DSH 本体 | **11 个 build-time patch、22 个产物文件、约 +524/−60 行，全部是功能性补丁**（rootfs/插件管理/会话删除等），**没有一处改 prompt / 内建工具描述** |
| 技能/文档 | 4 个中文教学技能（60 文件/11,406 行/21,601 汉字）；指南 zh/en 各 12 页 |

一个很说明问题的细节：全仓 **618 处 `l10n:keep` 豁免标注，其中 462 处是 `agent …` / `model …`**，
即「这是给模型看的文案，别当 UI 漏翻处理」。

### 值得借鉴的三点

1. **把 agent 提示词当产品资产**：AGENTS.md、安全简报、工具目录、屏幕序列化格式都自己写、
   自己版本化、自己审计（`l10n:keep` 扫描器 + 托管标记 `<!-- xxx:managed`，用户删标记才停止覆盖）。
2. **改 DSH 用 build-time patch，不 fork**：11 个 diff 打在 `node_modules/@deepseek-ai/*/lib/index.js` 上，
   版本/哈希钉死在 `pins.json`；我们 `runtime/patches` 也是同一套思路。
3. **agent 行为的改造主要靠「外挂」而不是改内核**：AGENTS.md / skills / MCP / `ctx.systemPrompt.section`。
   这四样都不需要动 DSH 源码，升级上游时几乎零 merge 成本。

### 它**没有**做的（避免误判）

- 没有 zh/en 之外的 locale；
- **没有改 DSH 内建的 system prompt 和工具描述**（`bash` / `web_search` / `present` / `ask_user` 全是原样）；
- 没有 fork / vendor 任何 DSH 包，也没有 vendored `node_modules`；
- 没有英文版 AGENTS.md / 手机工具描述 / 媒体工具描述（这些是中文-only）；
- 没有默认人设 prompt（人设/预设由用户在宿主侧编辑，内容来自 DSH 默认）；
- 指南里没有专门讲「提示词结构 / 工具描述定制 / prompt 调试」的页面。

### 对我们的含义

- 想改「`present` 什么时候被调用」这类**模型行为**，最轻的路是 **AGENTS.md / 技能 / systemPrompt.section**，
  不用动 DSH；dsh-Go 就是这么做的，而且它的 AGENTS.md 注释明确写着「把 DS Bot 的
  `show_view` 描述和 `prompt.js` 规则重写成中文指令」——**上游英文 prompt 重写成中文**本身就是它的做法。
- 只有确实改不动（比如内建工具描述、系统提示的硬性行为）时，才考虑给 DSH 打 patch；
  那意味着每次上游升级都要重打 + 回归（我们已经有 13 个 dsh patch 的负担）。
