# 设置页设计（参照 dsh-Go settings IA）

入口：主界面左上角「头像」圆钮 → 设置页（与其它页面一样走 iOS 整屏 push/pop）。

> 2026-11-05 校订：早期版本写「剩余行为 DSH 接入后的占位入口（点击提示即将上线）」，已不成立。
> 设置子页现在大多接真实 DSH RPC，是 bug 高发区；修 bug 的调用链见 [../roadmap.md](../roadmap.md)。

## 结构

- 顶部：返回圆钮 + 标题「设置」。
- 概览卡：Bot 图标 + 应用名 + 引擎状态（本机 · 运行中 / 未安装 / 启动失败）+ DSH 版本。
- 按意图分组（每组一整块圆角卡片，行左侧图标、右侧当前值）：

| 组 | 行 | 数据来源 |
|---|---|---|
| AI | 模型、权限、个性化 | `DshEngineSettingsRepository` / `SettingsRepository` |
| 能力 | 手机操控、联网搜索、插件 | `pluginInventory/list`、`pluginManager/*`、无障碍服务 |
| 外观 | 主题、对话字号、宠物 | `SharedPrefsSettingsRepository` |
| 通用 | 界面（原生 / 网页）、语言、通知、语音输入、性能、后台运行 | 本地设置 + `speech/*` |
| 主机 | 本机引擎、引擎参数 | `RuntimeState` / `settings/describe` |
| 关于 | 版本、DSH 版本、开源许可 | `BuildConfig` / `RuntimeManifest` |

- 行图标用 `spacing.settingsRowIcon`，与 `typography.settingsRowTitle` 垂直居中。
- 行高 ≥ `spacing.rowMinHeight` 相关下限，行间 `spacing.hairline` 发丝分割线（`colors.divider`）。
- **每个分组是一整块悬浮卡片**（不是按行拆）：`shadowCard` + `spacing.cardElevation` 的柔和阴影，
  与顶栏 / 输入框的悬浮按钮同一质感。
- 子页用栈导航（`DshmBotApp.kt` 的 `settingsStack`），返回时回到上一页而不是直接回设置首页。

## 数据与状态

```
ui/settings/*Screen
   └─> EngineSettingsViewModel（页面状态、变更提交、expectedRevision 乐观锁）
         └─> DshEngineSettingsRepository
               ├─ settings/describe → SettingsDescription(namespaces, writable, hasDocument)
               ├─ settings/mutate  → SettingsNamespace（带新 revision）
               ├─ pluginInventory/list、pluginManager/listBundles、setBundleEnabled
               ├─ permissionPresets/catalog、credentials/describe|set|unset
               └─ speech/* → RemoteSpeechRepository
                     └─> DshClient（cookie + rpc + SSE）
```

- `domain/model/AppSettings.kt` + `data/settings/SharedPrefsSettingsRepository.kt`：主题、语言、字号、
  通知、性能模式、宠物、手机操控等本地偏好持久化。
- `data/remote/EngineSettingsModels.kt`：schema → 表单控件（toggle / text / secret / choice / list / number / group）、
  namespace 的 value/base/user/secrets 分层、`SettingsOp.set/unset`、ProviderSettings。
- `data/settings/ApiKeyStore.kt`：API Key 加密存储，值永不回显；`TextMasking` 负责日志打码。
- `ui/settings/SettingsCopy.kt`：多语言文案，避免页面里散落字符串。

## 2026-11-05 按 dsh-Go 重做（当前状态）

老大确认的范围：**设置子页按 dsh-Go 逐页重做；搜索不做、远程主机不做、图像与视频不做、宠物不做、性能不做**。
`PetsSettingsScreen` / `PerformanceSettingsScreen` 保持原样，其余 13 个子页与设置首页已重排。

- 首页：六个 `SettingsSection(title = ...)` 分组标题（AI / 能力 / 外观 / 通用 / 主机 / 帮助与关于），
  并修正「主题」行误用 `settings_group_appearance` 当行标题的问题；每行右侧值仍来自真实状态。
- 子页：**保留全部已接 DSH RPC 的逻辑**（provider CRUD、插件开关与配置、引擎参数 schema 表单、
  `speech` 目录与准备、联网搜索切换、权限预设、rootfs `AGENTS.md` 读写），按 dsh-Go 补小节与行。
  所有 `@Composable` 签名不变，`DshmBotApp.kt` 路由无需改动。
- **未接入能力一律不做假开关**：用 `SettingsRow(enabled = false, subtitle = settings_unavailable_hint,
  value = settings_unavailable)` 标注「当前版本不可用」。涉及分享链接 / 分享配置 / 匿名统计 / 上传日志、
  检查更新 / 自动更新、使用帮助 / 社区 / 诊断、当前对话权限 / 访问手机文件、可视化回复 / Agent 预设、
  手机操控的读屏 / 悬浮条 / 应用名单 / 不打扰模式等。
- 真实新增的本地偏好 `DshGoPreferences`：通知四类（`notify_*`）与 `foldProcess`，
  经 `SettingsRepository` → `SharedPrefsSettingsRepository` → `SettingsViewModel` 持久化，有 JVM 单测；
  通知页四类开关受「允许通知」总开关控制。
- 验收：25 测试类 / 130 用例全绿，`compileDebugKotlin` / `lintDebug` / `assembleDebug` 全通过。

**2026-11-05 收口（已完成）**：
- `DshGoPreferences` 只保留设置页真实暴露的 5 项（通知四类 + 完成后收起过程）；
  玻璃模糊 / 界面动效 / 刷新率 / 宠物安静模式、`RefreshRateMode` 枚举、repository 与 ViewModel 的
  `setRefreshRate` 接口、以及 9 条对应 string 资源全部删除，全项目检索无残留。
- 本轮重写的 9 个页面里所有 `if (zh) … else …` 内联双语文案与 `dshGoLabel` helper 已清零，
  统一改为 `stringResource`；新增 42 条中英资源（两个 `strings.xml` 的 KEY 与顺序完全一致）。
  `ModelProvidersScreen` 的 `attemptSave()` 是非 Composable 局部函数，改为在外层 Composable
  作用域预取字符串后再引用，避免「@Composable invocations can only happen from…」编译错误。
- 仍保留内联写法的三个文件：`SettingsCopy.kt`（集中文案映射表）、`EngineSettingsForm.kt`（表单格式化）、
  `PetsSettingsScreen.kt`（宠物页按范围不做）。它们本轮未重写，不属于这次收口范围。

## 2026-11-06 账号页 UI/UX 专项（按 dsh-Go 结构，配色用本项目令牌）

老大用两张截图对比：我们的「未登录卡 + API Key 大说明块 + 蓝色底部按钮」与 dsh-Go 的
「账号主卡（图标 + 标题 + 副标题 + 三条带图标说明 + 卡内深色按钮）+ API Key 单行入口」差别大。
要求：**颜色保持本项目黑白灰令牌，只把 dsh-Go 的样式搬过来**；登录逻辑结合 dsh-Go 源码核对。

- 结构：未登录主卡（圆形图标 + 「DeepSeek 账号 / 登录后无需 API Key」+ 三条说明 + 卡内深色按钮）；
  登录中同一张卡切成阶段提示 + 取消登录；已登录切成账号概览 + 余额分组（充值 / 用量并排按钮）+
  退出登录。API Key 收敛为「API Key」分组下的单行入口，点开才编辑。
- 颜色全走 `Dshm.colors`：图标底 `surfaceMuted`、**主按钮 `accent` 蓝底 + `onAccent` 白字**
  （沿用我们原有的蓝色，不是 dsh-Go 的黑色按钮），没有引入 dsh-Go 的浅蓝图标底。
  按钮高度用 `spacing.iconButton`（44dp），避免过大。
- 余额卡对齐 dsh-Go `_Wallets`：**充值余额 / 赠送余额并排两列**（分别来自 getBalance 的
  `value` 与 `bonusWallets`），充值 / 用量两个按钮同为蓝色；分组与上方账号卡之间留
  `spacing.xxl` 间距，标题不再贴着卡片。
- 退出登录对齐 dsh-Go 的 `Center(TextButton.icon)`：图标 + 文字**居中**、danger 色、
  **不带右侧 `>` 箭头**。
- 登录逻辑对齐 `dsh-Go/app/lib/state/account_api.dart` 与 `account_state.dart`：
  - `DshAccountRepository` 解析 `attempt.phase / expiresAt / errorCode`，新增
    `account/cancelSignIn`、`account/hasRunningAccountTasks`、`session/initializeDefaultModel`。
  - `AccountViewModel` 用阶段状态机（PREPARING / WAITING_BROWSER / FINISHING）驱动界面，
    支持取消登录、退出前任务检查确认、成功后初始化默认模型、错误码本地化。
  - 新增 `DshAccountRepositoryTest`(7) 覆盖 wire 解析与 phase 映射。
- **仍未对齐（待确认）**：
  1. dsh-Go 用 `account/watch` 流驱动阶段，本项目尚未接入 mux 流，暂用有上限的 `getState` 轮询。
  2. dsh-Go 用 Chrome Custom Tab 打开授权页（共享浏览器登录态、RFC 8252），我们用
     `Intent.ACTION_VIEW`；接 Custom Tab 需新增 `androidx.browser` 依赖。
  3. `noteAppResumed()` 回前台刷新已在 ViewModel 备好，UI 暂未接线
     （避免引入 deprecated `LocalLifecycleOwner` warning）。
- 验收：26 测试类 / 137 用例全绿，compile / lint / assemble 全通过；
  已覆盖安装到真机 `PKB110`（`versionCode=11061225`）。

## 2026-11-06 模型页按 dsh-Go 重做（第 1 批：列表页）

依据 `dsh-Go/app/lib/ui/providers/providers_page.dart` + `provider_widgets.dart`。**配色继续用本项目
`Dshm.colors`**（老大确认：dsh-Go 的颜色只作理解参考，不照搬）。

- 顶栏去掉「N 个渠道 · M 个可用」统计副标题（老大明确不要）。
- 卡内行间**去掉分割线**，靠行内边距分组（dsh-Go 的 ListPageCard 不画行间线）。
- 渠道头像改成 **Monogram 圆形字母块**（32dp，中性面底 + 深墨字）。
- 状态从「绿/灰两点」升级为 dsh-Go 的**四态**：可用 / 缺少 API Key / 配置有误 / 未启用，
  用「8dp 色点 + 文字副标题」表达。判定集中在 `ProviderHealthEvaluator`（纯逻辑，有单测）。
- 订阅未登录改成 tonal「登录」胶囊；本项目还没接引擎的 appAuth 登录桥，胶囊置灰并注明原因，
  不做点了没反应的假按钮。
- 「添加渠道」图标改主色、无箭头。
- 新增 `DshmSpacing.statusDot` 令牌，替掉裸 `8.dp`。

**第 2 批仍未做**：独立只读详情页（Hero + 连接卡 + 模型卡 + 居中删除）、获取模型的选择弹层、
模型设置页（上下文 / 最大输出 / 输入类型 / 思考档位）、表单底部固定保存条。

### 2026-11-06 模型页第 2 批（详情页 / 模型选择弹层 / 底部保存条）

- 新增 `ProviderDetailPage.kt`，对齐 dsh-Go `provider_detail_page.dart`：
  Hero（56dp 字母块 + 状态点 + 状态词）→「连接」卡（Base URL / API Key / 测试连接，
  测试中转圈、通过显成功色、失败显错误色）→「模型 · N」卡（逐个模型列出上下文 /
  最大输出 / 输入类型；单模型设置依赖引擎 `modelOverrides`，本项目未接入故标注不可用）
  →底部居中「删除渠道」（danger）+ 确认弹窗。
- 路由改成三层：**列表 → 详情 → 编辑**（`ProviderTarget.Detail` / `.Editor`）。
  点自定义渠道先进详情，详情页右上「编辑」再进表单。
- 新增 `ProviderModelPicker.kt`：获取模型后弹层勾选（Checkbox + 列表 + 「添加 N 个模型」），
  不再直接覆盖表单里的模型清单。
- 表单保存条固定底部：`SettingsPage` 新增 `bottomBar` 参数，编辑页的保存按钮从表单流
  移到固定底栏。
- 共用组件抽到 `ProviderUi.kt`：`Monogram` / `StatusTone` / `StatusDot` /
  `ProviderHealth.toStatusTone()`，列表页与详情页共用。

### 2026-11-06 添加 / 编辑渠道表单页按 dsh-Go 重做

依据 `dsh-Go/app/lib/ui/providers/provider_form_page.dart`：

- **模型卡不再是多行文本框**，改成 dsh-Go 的 `_ModelsCard` 结构：
  「获取模型 → 弹层勾选（候选 >8 出搜索框、全选 / 全不选、已添加的标记「已添加」不可重复选）
  → 已选模型逐条列出、可单独删除 → 手动添加（弹窗输入模型 ID）」。
- 发现到的模型是**追加**进渠道，不再覆盖已有清单。
- **Base URL 的 hint 跟协议走**：Anthropic 协议不带 `/v1`，其余协议带。
- **API Key** 已保存时输入框显示 `••••••••` 掩码，下方补 helper「已保存，留空则不修改」。
- **底部保存条**：引擎没连上时按钮显示「等待引擎连接」并禁用；保存中显示「保存中」。
- **表单页没有「测试连接」**：dsh-Go 的测试连接只在**渠道详情页**（用引擎已存的 Key 读模型列表），
  表单页没有这一行，我们多余的那行已删。
- **模型选择面板的默认勾选规则跟 dsh-Go 一致**：候选 ≤8 默认**全选**（排除已添加的），
  >8 才默认空选并给搜索框；顶部显示候选数量，全选按钮在「全选 / 全不选」之间切换。
- **模型卡标题动态**：模型为空时是「模型」，有模型时是「模型 · N」。
- **手动添加模型**会校验模型 ID 重复，重复时在弹窗内报错、不关闭。
- **删掉自加的行**（dsh-Go 表单页没有的）：「暂无模型」占位行、独立的「模型设置」行、
  名称下方的「渠道 ID」提示行。dsh-Go 的模型卡只有「获取模型」+ 已选模型 +「手动添加」。

### 表单字段块规格（对齐 dsh-Go `FormFieldGroup` / `FormFieldBlock`）

- 字段标题用新的 `typography.formFieldLabel`（14 / Medium），缩进 4dp 与字段边缘对齐；
  之前用 `rowTitle`（16 / Medium）当标签既不像标题又偏大。
- 标题到字段 4dp、字段块下方 16dp —— 之前名称 / Base URL 挤在一起就是这个间距问题。
- 每张卡的**第一个字段**顶部留白 12dp（`FormFieldGroup` 的上边距），否则字段标题会顶在卡片上缘。
- 输入框本身：文字 16sp + 上下 8dp 内边距（高约 39dp）。上下给 12dp 时框高 47dp，视觉上「上下很高」。
- 「粘贴」这类动作按钮要**矮**：Material3 的 `TextButton` 默认最小高度 40dp，放在标题行会把整行撑高、
  把标签推离输入框很远；改用紧凑文本（dsh-Go 的 `FormSmallButton` 同理）。
- 「兼容协议」也改成字段块结构（标题在上、三段选择器在下），不再是裸标题 + 控件。
- 详情页顶部的 Hero 区（字母块 + 名称 + 状态）按 dsh-Go 的 `PageHero` 包成一张卡片。

> 排版对齐的验收方式：真机 `uiautomator dump` 出来的节点坐标差可以直接量化间距，
> 比肉眼看截图准（例：Base URL 输入框 → API Key 标题 257px → 187px）。

> 仍未做（依赖引擎能力）：模型设置页（上下文 / 最大输出 / 输入类型 / 思考档位）需要写
> `modelOverrides.<modelId>`，本项目未接该写入口，模型行标注「当前版本不可用」。
> dsh-Go 的 DiscardGuard（未保存改动拦截返回）也还没做。

## 测试

- `ThemeModeTest`（JVM）：SYSTEM 跟随系统、LIGHT / DARK 覆盖系统。
- `EngineSettingsModelsTest`（JVM）：inline / refs 两种 schema、真实 schemastery 数值 uid、
  namespace 分层与凭据引用、ProviderSettings 与 op / JSON 相等性。
- `SpeechCatalogTest`（JVM）：语音目录解析。
- `SettingsScreenTest`（插桩）：分组与当前值渲染、选深色触发回调。
- `DshmBotAppNavigationTest.profileOpensSettingsAndPopsBack`：头像进入设置、返回主界面。
- 待补：`EngineSettingsViewModel` 的状态流转测试（describe 失败、revision 冲突、mutate 回滚）、
  设置页真实 RPC 的契约测试。

## 顶栏规范（必读，踩过的坑）

- edge-to-edge 下，子页 header **必须**放在 `Scaffold(topBar)` 里，并在 header 内加**一次** `statusBarsPadding()`。
- 内容区只消费 `innerPadding`，**禁止**再加 `statusBarsPadding()`；也不要让 header 跟内容一起滚动。
- 否则会出现顶栏被状态栏压住（重叠）或整体下移（双重 inset）。
- 新页面一律使用 `SettingsPageScaffold`（`ui/settings/SettingsPageScaffold.kt`），不要手写 Scaffold + 顶栏。

## 底部栏写法（必读，2026-11-10 定）

设置/详情里的底部弹层一律走 `ui/settings/SettingsSheets.kt` 的 `SettingsSheet`，
不要各页面自己写 `ModalBottomSheet`。规矩如下（都来自这次踩坑）：

- **没有半屏锚点**：`rememberModalBottomSheetState(skipPartiallyExpanded = true)`。
  默认的 `false` 会多出「半屏 ↔ 全屏」两态，一往上拖 sheet 变高、内容跟着变高，
  看起来像「面板能一直往上拉」。对话名字胶囊面板（`BotSettingsSheet` / `RoomSettingsSheet`）
  一直是这样写的，照抄它。
- **定高用绝对高度**：要固定占屏高就写 `Modifier.height(屏幕高 × 比例)`
  （名字胶囊 86%、密钥面板 50%）。**不要**用 `fillMaxHeight(比例)` —— 那个比例是相对
  sheet 自身高度，sheet 一撑它跟着撑。
- **内容自适应**：不指定高度时面板高度由内容决定（连接器、名字胶囊那些面板都是这样）。
  内容超出就在内部滚动。
- **底部操作区**：`SettingsSheet(bottomBar = { … })` 把「取消 / 确定」固定在面板底部，
  内容区自己滚动；两者之间留 `spacing.md`（16dp），不要贴着。
- **底部按钮**：等宽等高 —— 每个按钮 `weight(1f)` + `heightIn(min = spacing.iconButton)`，
  圆角统一 `radius.md`；主按钮用 `SettingsPrimaryButton`，次按钮用 `TextButton` 但要显式
  给同样的高度和圆角，否则大小不一、也不居中对称。
- **输入框**：用 `SettingsInput`（圆角 `radius.md` + 细描边 + 铺满宽度），不要用 M3 的
  `OutlinedTextField` —— 后者描边直角、右侧留白，和本项目其它输入框不一致。
- **选择器**：用无搜索 Combobox（`ExposedDropdownMenuBox` + `OutlinedTextField(readOnly)`，
  圆角 `radius.md`，菜单 `radius.lg` + `colors.surface` + 细描边），
  和 `BotModelComboBox` 同款；**不支持搜索输入**。

反面例子（2026-11-10 修掉）：密钥面板原来用 `fillMaxHeight(0.5f)` + 系统 `AlertDialog`，
结果既能往上无限拉、输入框还是直角、取消/确定大小不一。
