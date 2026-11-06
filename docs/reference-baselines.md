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
版本协商、`notices`、`avatar` 引用、`notice` 事件、`dsBot/call` 尚未接入。

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
