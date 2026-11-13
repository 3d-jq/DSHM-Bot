# DSHM Bot

基于 [DeepSeek Harness (DSH)](https://github.com/deepseek-ai/deepseek-harness) 的个人 Agent **原生 Android 客户端**。
界面与产品定位按 Bot 来做（参考 [DS-Bot](https://github.com/Fei2411/DS-Bot)），底层接入 DSH
（参考 [dsh-Go](https://github.com/ccch1mneyyy/dsh-Go) 的引擎与协议实现）。

> **状态（2026-11-11 校订）**：M0 工程化已跑通，M1 / M2 主体也已落地 —— 本机 PRoot 引擎、
> DS Bot `/api/bot` + SSE 真实数据、聊天与流式输出、完整设置体系都已接通，**数据层不再是演示实现**。
> 手机操控也已完成从读屏、点击、输入、交接、审批到「eta 风格结果卡 + 回到界面」的本机闭环。
> 当前工作重点是继续对齐 DS Bot 网页端细节并打磨稳定性，见 [docs/roadmap.md](docs/roadmap.md) 的「当前工作重点」。
> 尚未完成的缺口与已知风险同样列在路线图和 [docs/architecture.md](docs/architecture.md)，文档一律以代码为准。

## 技术栈

| 层 | 选型 |
|---|---|
| UI | Kotlin + Jetpack Compose（Material 3），单 Activity，手写整屏 push / pop 路由 |
| 状态 | ViewModel + `StateFlow<UiState>` 单向数据流；`DshRemoteApi` 接口隔离 HTTP 实现，便于替换假实现 |
| 数据 | `BotRepository`：`DsBotRepository`（真实，`state` 快照 + `feed` SSE 增量 + `BotCache` 冷启动快照）、`EmptyBotRepository`（引擎未启动）、`DemoBotRepository`（仅演示） |
| 引擎 | 本机 PRoot + Node 运行时（`runtime/`），`DshClient` 用 `HttpURLConnection` 换 `dsh-auth-*` cookie、发 `/api/bot` 与 RPC、读 SSE |
| 设置 | `DshEngineSettingsRepository`（`settings/describe` + `settings/mutate`、插件、权限预设、凭据）+ `EngineSettingsViewModel` |
| 构建 | Gradle 8.14 + AGP 8.11.1 + Kotlin 2.2.20，`compileSdk 36` / `minSdk 26` / `targetSdk 28` |
| 测试 | JUnit4（JVM）+ Compose UI Test（插桩）+ Python 图标 / 运行时管线测试 |

## 目录

```
app/                          Android 单模块
  src/main/java/com/dshm/bot/
    core/design/              设计令牌（颜色 / 间距 / 圆角 / 字体 / 动效 / 主题）
    domain/                   模型、Bot 颜色与形状算法、Repository 与设置接口
    data/runtime/             本机引擎：manifest、下载安装、PRoot 启动、guest 初始化、日志、插件安装
    data/remote/              DSH HTTP/SSE 客户端、DS Bot 解析、引擎设置、语音、账号、缓存
    data/settings/            SharedPreferences、API Key（加密存储）、个性化
    data/notify/              引擎就绪通知
    service/                  无障碍手机操控（eta 风格点击校验、鼠标指针、结果卡）
    ui/home/                  主界面、搜索、列表行、顶栏
    ui/chat/                  对话、输入器、Markdown、Bot 详情 / 浏览器、语音
    ui/engine/                开屏安装引导、引擎状态页
    ui/settings/              设置首页 + 20 余个子页
    ui/account/               账号与余额
  src/test/                   JVM 单元测试
  src/androidTest/            Compose 插桩测试
runtime/                      Node/JS 运行时：PRoot 补丁、dsh-go-bridge 原生桥接、dsh-media 插件、依赖锁
design/                       图标源文件与裁切产物
tools/                        图标生成脚本与其测试
docs/                         架构、路线图、界面与设置说明
docs/design/design-system.md  设计系统：界面布局的唯一依据（颜色/字体/圆角/间距/组件）
DS-Bot/ DS-Bot-mobile-api/    参考基线（只读，本地存放且被 .gitignore 排除，不入库）
dsh-Go/ DSHM-Bot-ref/         参考基线（同上）
```

## 构建与测试

需要 JDK 17+（推荐 21）与 Android SDK。`local.properties` 里写 `sdk.dir`（不入库）。

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21.0.10'

.\gradlew.bat :app:assembleDebug        # 构建调试 APK
.\gradlew.bat :app:testDebugUnitTest    # JVM 单元测试
.\gradlew.bat :app:lintDebug            # Android Lint
.\gradlew.bat :app:connectedDebugAndroidTest   # 插桩测试（需模拟器/真机）
```

约定：

- **测试覆盖**：每完成一个功能，至少包含正常路径、边界（空 / 超长 / 未来时间等）、异常路径与回归用例；
  改动必须配套测试，Gradle 测试与 lint 全绿才提交。
- **设计令牌**：界面代码不允许出现颜色 / 间距 / 圆角 / 字号字面量，统一 `Dshm.colors/spacing/radius/typography`。
  文档只引用令牌名，不重复抄数值，数值以 `core/design` 为准。
- **versionCode**：取构建时间（`gradle.properties` 的 `dshm.versionCodeZoneOffset` 控制时区，默认 `+08:00`），
  保证每次打包都能覆盖安装并刷新启动器图标缓存。
- **lint**：`abortOnError = true` 保证 error 阻断构建；`dshm.lintWarningsAsErrors` 已置 `true`，
  **warning 也算 error**，现在是「零 warning 才构建通过」。故意保留的告警在 `app/lint.xml` 里
  按文件定向豁免，每条都写了理由；能改的（NewApi / ObsoleteSdkInt / DefaultLocale / UseKtx /
  未使用资源 / Compose 规范等）直接改代码，不要在 lint.xml 里图方便。
- **targetSdk 28**：为绕开 Android 10+ 对 PRoot 执行原生二进制的限制而有意保留，
  lint 里显式豁免 `ExpiredTargetSdkVersion`。**上架 Play / 提高 targetSdk 前必须重新评估本机引擎方案**，详见架构文档。

## 手机操控

手机操控由用户指定唯一 Bot 使用，采用 Android 无障碍服务执行，不依赖 Root：

- **读屏与定位**：按屏幕快照输出可操作元素编号；动作前重新校验活动窗口、包名、节点身份和可见区域，
  目标移动或界面切换时拒绝旧编号，避免点到错误位置。
- **输入**：优先对焦点输入框执行 `ACTION_SET_TEXT`，回读不一致时点击聚焦重试，最后才用系统剪贴板粘贴兜底。
- **悬浮胶囊**：显示当前 Bot 头像、真实动作文案、停止 / 继续 / 允许 / 拒绝；点击指示使用鼠标指针效果，
  不再显示步数、三色呼吸环或活动条。
- **完成态**：任务结束后显示 eta 风格底部结果卡，展示本轮结果，并提供「回到界面」「关闭」。
- **安全边界**：密码、验证码、支付类控件拒绝代填；敏感或高风险动作走确认门，可通过「不碰名单」排除应用。

## 图标

启动图标由 `design/app-icon-source.png` 生成（legacy + adaptive，含圆形变体）：

```powershell
python tools/generate_app_icons.py
python -m unittest discover -s tools -p "test_*.py"
```

生成结果直接写入 `app/src/main/res/mipmap-*`；资源尺寸与清单接线由 `AppIconResourcesTest` 守护。
界面图标统一用 Lucide（ISC），由 `tools/fetch_lucide_icons.py` 从钉死版本转 VectorDrawable，`LucideIconResourcesTest` 守护。

## 许可

本项目基于 dsh-Go（AGPL-3.0）与 DS-Bot（Apache-2.0）的设计与协议实现二次开发，
以 **AGPL-3.0** 发布。来源与第三方许可见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。
