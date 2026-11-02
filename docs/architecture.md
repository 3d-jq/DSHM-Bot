# DSHM Bot 架构

> 更新：M0（工程化跑通）。后续接入 DSH / DS Bot 时在此文档追加，不另起炉灶。

## 定位

- **底层是 DSH**：Agent 的会话、模型、工具、消息都由 DSH 引擎负责；App 是原生客户端，不自己跑模型循环。
- **界面与定位是 Bot**：主界面是 Bot / 群聊列表，进而是与某个 Bot 的私聊、群聊、审批与卡片。
  产品语义参考 DS-Bot（长驻 Bot、群聊、记忆、审批），交互参考 dsh-Go 的移动端设计。
- **原生 Android**：Kotlin + Compose 重写界面；不复用 Flutter 界面，只复用 dsh-Go 的服务端协议、
  引擎运行时（Kotlin 层）与产品设计文档。

## 分层

```
ui/           只渲染 + 收集状态；不调接口、不做业务判断
state/        ViewModel：持有状态、响应事件、把领域数据格式化为 UiState
domain/       模型、颜色算法、Repository 接口（业务规则）
data/         Repository 实现（演示 / DSH / DS Bot）
core/design/  设计令牌：颜色、间距、圆角、字体、主题
```

规则：

1. **单一数据源**：界面只消费 `StateFlow<UiState>`；UI 事件回调到 ViewModel。
2. **令牌强制**：界面代码不允许出现颜色 / 间距 / 圆角 / 字号字面量，统一 `Dshm.colors/spacing/radius/typography`。
   有 `DesignTokensTest` 守护可读性与尺寸一致性。
3. **可测**：时间、时钟、时区、语言全部注入；Repository 是接口，测试用假实现。
4. **资源释放**：协程绑定 `viewModelScope`，流式连接断开时显式取消（M1 起）。

## 与 DSH 的接入路线（M1+）

参考 dsh-Go：

| 能力 | 参考实现 | 我们的做法 |
|---|---|---|
| HTTP API / cookie | `packages/dsh_client`（Dart）的 `Carrier`/`Rpc` | Kotlin 客户端：`GET /?token=` 换 cookie，`POST /api/<ns>/<method>` |
| 会话流 | `session/follow`（SSE/WS mux） | OkHttp SSE + WebSocket，事件归并为聊天条目 |
| Bot 团队 | DS Bot `POST /api/bot` + `GET /api/bot/feed` | Kotlin `BotRepository`：`state` 快照 + SSE 增量（按 revision 同步） |
| 本机引擎 | dsh-Go 的 Kotlin `runtime/`（PRoot 内跑 `dsh web`） | 后续移植 Kotlin 运行时层；M1 先支持远程主机（局域网 / SSH） |
| 远程连接 | dsh-Go `packages/dsh_remote`（Noise IK + Relay） | 后续按需移植 |

DS Bot 的颜色算法已经按 DS-Bot / dsh-Go 移植（`BotInks`），并有参考向量测试，
保证同一个 Bot 在所有客户端颜色一致。

## 数据流

```
DSH / DS Bot ──HTTP/SSE──> BotRepository ──Flow──> HomeViewModel ──StateFlow──> HomeScreen
                                  ▲                                            │
                                  └──────────── 用户操作（发送/新建）◄──────────┘
```

## 主界面（M0）

对应设计稿：顶部个人资料 / 搜索 / 新建；列表行 = 头像（DS Bot 颜色 + 占位图标）、名字、时间、最后一句。
时间规则：今天 `21:53`、昨天 `昨天`、一周内 `周一`、今年 `10/21`、更早 `2025/10/21`；
规则集中在 `RelativeTimeFormatter`，有边界测试。

## 测试策略

| 层 | 测试 |
|---|---|
| 领域算法 | `BotInkTest`（与 dsh-Go / DS Bot 参考向量对照）、`RelativeTimeFormatterTest`（时区/边界/未来时间） |
| 状态 | `HomeViewModelTest`（排序、搜索、空态、错误、未读） |
| 设计令牌 | `DesignTokensTest`（对比度、尺寸单调性） |
| 资源 | `AppIconResourcesTest`（图标尺寸/ adaptive 描述 / 清单接线）、`tools/test_generate_app_icons.py` |
| UI | `HomeScreenTest`（插桩：渲染、点击、搜索、空态、错误态） |

## 构建与签名

- 当前只有 debug 构建；正式发布前必须配独立的 release keystore（`key.properties`，不入库），
  并把 `versionCode`/`versionName` 纳入构建流水线。
- 目标 `minSdk 26`、`compileSdk 36`。本机引擎（PRoot）方案需要重新评估 `targetSdk` 与 exec 限制。
