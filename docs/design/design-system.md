# DSHM Bot 设计系统（Design System）

> **这份文档是本项目界面布局的唯一依据。** 所有新页面、新组件都按这里写；已有页面与它不一致时，
> 以本文档为准去改代码（除非本文档明确写「参考 dsh-Go / DS-Bot 的例外」）。
>
> 基准样板：**设置页**（`ui/settings/`）。它已经跑通了这套规范，其余页面照它对齐。
> 数值全部来自代码里的真实令牌（`app/src/main/java/com/dshm/bot/core/design/`），不是另画一套。

---

## 0. 怎么用这份文档

1. **先选骨架**（§8 页面骨架模板），再往里填组件。不要从零搭页面。
2. **所有尺寸、颜色、字号、圆角必须来自令牌**（§2–§6）。界面代码里出现裸 `dp`、颜色字面量、
   字号数字 = 违规。
3. **改设计先改令牌**（`core/design/*.kt`），再让各页面跟着变；不要在页面里打补丁。
4. **新增组件先在 `ui/settings/SettingsComponents.kt` 落成可复用组件**，再在页面里用。

---

## 1. 硬规则（十条，违反即返工）

| # | 规则 |
|---|---|
| 1 | 界面里**不允许**出现裸 `16.dp`、颜色字面量、字号数字；一律用 `Dshm.spacing / colors / radius / typography` |
| 2 | 颜色只按**语义名**用（`textPrimary`、`accent`、`danger`…），不按「看起来是蓝的」用 |
| 3 | 同类元素**必须同尺寸**：两个输入框一样高、两个图标一样大、两个卡片一样圆角 |
| 4 | 不用 Material3 组件的**默认尺寸**参与布局：`TextButton` 最小高 40dp、`IconButton` 48dp，<br>要小就换紧凑变体（见 §7 已知坑） |
| 5 | 每个子页只允许**一个**主操作（底部条里的那个），其余动作进卡片或菜单 |
| 6 | 未接入的能力**显式标注「当前版本不可用」**，不做点了没反应的假开关、假行 |
| 7 | 表单字段一律用**字段块**结构（§8.2），不裸排标题 + 控件 |
| 8 | 列表页每行右侧的**值**必须来自真实状态，不写死占位文案 |
| 9 | 对齐 dsh-Go / DS-Bot 时**不擅自增删行**，缺能力就标不可用（别自己加） |
| 10 | 改完 UI 要**真机截图核对**（§11），不能只看代码 |

---

## 2. 颜色系统

来源：`core/design/DshmColors.kt`。只有语义名，界面不碰 HEX。

| 语义名 | 浅色 | 深色 | 用途 |
|---|---|---|---|
| `background` | `#FFFFFF` | `#0E0F12` | 窗口底 |
| `pageBackground` | `#F1F2F4` | `#0E0F12` | **页面底色**（列表页/设置页统一用它） |
| `surface` | `#FFFFFF` | `#14161A` | **卡片 / 输入框 / 弹层底** |
| `surfaceMuted` | `#F1F2F4` | `#1C1F25` | 次级面：头像底、图标底、分段控件底 |
| `textPrimary` | `#0B0B0F` | `#F2F3F5` | 标题、正文 |
| `textSecondary` | `#8A8F98` | `#9AA0AA` | 副标题、说明、右侧值 |
| `textTertiary` | `#B3B8C0` | `#6C727C` | 三级信息、占位符、提示 |
| `divider` | `#ECEEF1` | `#24282F` | 发丝分割线、输入框描边 |
| `accent` | `#1F4FD8` | `#6A84FF` | **主色**：主按钮、链接、选中、焦点、进度、加载 |
| `onAccent` | `#FFFFFF` | `#0B0D12` | 主色上的文字 |
| `iconTint` | `#111318` | `#F2F3F5` | 行内图标 |
| `badge` | `#FF3E51` | `#FF5A7A` | 未读角标 |
| `danger` | `#D93025` | `#FF6B60` | 危险文案、删除动作 |
| `success` | `#2E7D32` | `#6FD07A` | 成功状态点 |
| `warning` | `#B26A00` | `#FFC46B` | 警告状态点 |
| `scrim` | `#66000000` | `#99000000` | 模态遮罩 |
| `shadow` | `#8F000000` | `#E6000000` | 通用投影 |
| `shadowSoft` | `#14000000` | `#59000000` | 玻璃件柔影 |
| `shadowCard` | `#40000000` | `#A6000000` | 大卡片悬浮投影（`cardElevation`） |
| `bubbleAssistant` | `#F2F3F5` | `#1C1F25` | 对话里 AI 气泡 |
| `bubbleUserStart` / `bubbleUserEnd` | `#4D8DFF` / `#2F6BFF` | 同 | 用户气泡渐变 |
| `onInk` | `#FFFFFF` | `#FFFFFF` | 深色面上的文字 |
| `terminalBackground` / `terminalText` | `#15161A` / `#E8E8EA` | `#0B0C0E` / `#E8E8EA` | 日志井 |

**用色规则**

- 一屏里主色（`accent`）面积 **≤5%**：只给主按钮、链接、选中态、加载环、焦点。
- 状态色一律低饱和（`success` 墨绿、`warning` 赭、`danger` 砖红），不要用鲜绿鲜红。
- 卡片底用 `surface`（实心白），**不要**用 `surfaceMuted` 当卡片底（会糊成一片）。
- 深色主题不是简单反色：`pageBackground` 与 `background` 同为 `#0E0F12`，层次靠 `surface` 比背景亮一档 + `divider` 细线。

---

## 3. 字体系统

来源：`core/design/DshmTypography.kt`。**字重只有两档：Normal(400) / Medium(500)/SemiBold(600 用于大标题)**。

| 令牌 | 字号 / 行高 / 字重 | 用途 |
|---|---|---|
| `pageTitle` | 22 / 28 / SemiBold | 页面大标题（设置页标题、弹层主标题区） |
| `sheetTitle` | 17 / 23 / SemiBold | 弹层标题、账号卡标题 |
| `screenTitle` | 16 / 21 / SemiBold | 顶栏标题 |
| `settingsRowTitle` | 16 / 22 / Medium | **设置行主标题** |
| `rowTitle` | 15 / 20 / Medium | 列表行主标题、对话气泡标题 |
| `settingsRowValue` | 15 / 20 / Normal | 设置行右侧的值 |
| `formFieldLabel` | 14 / 20 / Medium | **表单字段标题**（缩进 4dp） |
| `settingsRowSubtitle` | 14 / 19 / Normal | 行副标题、字段下说明 |
| `rowPreview` | 13 / 18 / Medium | 空态标题 |
| `emptyHint` | 13 / 19 / Normal | 空态说明、提示文案 |
| `settingsSectionTitle` | 13 / 18 / Medium | **分组标题**（卡片外的小灰字） |
| `rowTime` | 11 / 14 / Normal | 时间戳 |
| `composerText` | 16 / 23.2 / Normal | 对话输入框正文（行高 1.45，`trim = None` 让光标满高） |
| `sheetAction` | 15 / 21 / Normal | 弹层动作按钮 |

**规则**

- 行标题 15/16，行副标题 14，行右侧值 15 —— **不要同一行里出现三种字号**。
- 分组标题（`settingsSectionTitle`）永远是 `textSecondary`，13sp Medium，缩进 `spacing.lg`(16)。
- 字段标题（`formFieldLabel`）永远 `textPrimary`，14sp Medium，缩进 4dp。

---

## 4. 圆角系统

来源：`core/design/DshmRadius.kt`。

| 令牌 | 值 | 用途 |
|---|---|---|
| `xs` | 6 | 小图标底、标签 |
| `sm` | 10 | 小按钮、内层块 |
| `md` | 14 | **输入框**、普通按钮 |
| `lg` | 20 | 大卡（列表行卡）、分段控件 |
| `settingsCard` | 24 | **设置页的分组卡片** |
| `xl` | 28 | 底部弹层、对话框；输入框胶囊 `composer` |
| `pill` | 999 | 胶囊（状态胶囊、登录胶囊） |

**规则**：设置页分组卡一律 `settingsCard`(24)；输入框 `md`(14)；弹层 `xl`(28)；胶囊 `pill`。
同一个 App 里不允许「一个 20 一个 24」两种大卡圆角。

---

## 5. 间距系统

来源：`core/design/DshmSpacing.kt`。阶梯只有这些，不许自造。

| 令牌 | 值 | 典型用途 |
|---|---|---|
| `none` | 0 | 需要「不要留白」时也走令牌 |
| `xxs` | 2 | 极小间隙（分段控件内部） |
| `xs` | 4 | 标题→字段、字段标题缩进、图标与文字 |
| `sm` | 8 | 图标与文字、行内小间距、输入框上下内边距 |
| `md` | 12 | **卡片内首个字段的顶部留白**、提示行 |
| `lg` | 16 | **页面左右边距**、字段块下方、组间距 |
| `xl` | 20 | 大留白 |
| `xxl` | 24 | 卡片之间的组间距、列表行间距 |
| `xxxl` | 32 | 页面底部留白 |

**尺寸类令牌**（也是间距，别自己写数字）

| 令牌 | 值 | 用途 |
|---|---|---|
| `topBarHeight` | 60 | 顶栏高 |
| `iconButton` | 44 | 圆形图标按钮（顶栏返回） |
| `avatar` | 44 | 列表头像 |
| `settingsAvatar` | 56 | 账号 / 详情页大头像 |
| `avatarGlyph` | 20 | 头像内图标 |
| `icon` | 24 | 通用图标 |
| `topBarIcon` / `settingsRowIcon` | 20 | 顶栏图标 / 设置行图标 |
| `statusDot` | 8 | 状态点直径 |
| `inputHeight` | 44 | **所有输入框统一高度** |
| `rowMinHeight` | 64 | 列表行最小高 |
| `capsuleHeight` | 32 | 快捷胶囊高度（容量预设） |
| `segmentedHeight` / `segmentedInset` | 36 / 3 | 分段控件高度 / 滑块与底槽的间隙 |
| `levelRowHeight` | 44 | 思考档位行高度 |
| `selectionTick` | 20 | 勾选圆点直径 |
| `hairline` | 1 | 分割线 / 描边 |
| `cardElevation` | 16 | 大卡片投影高 |
| `composerKey` / `composerKeyGap` / `composerMinHeight` / `composerSendIcon` | 40 / 8 / 56 / 22 | 对话输入条 |
| `menuWidth` | 220 | 下拉菜单面板宽 |
| `floatingElevation` | 10 | 顶栏悬浮钮投影 |
| `listBottomPadding` | 24 | 列表底部留白 |
| `petInsetEnd` / `petInsetBottom` | 16 / 96 | 宠物挂件位置 |

---

## 6. 图标

- 统一 Lucide VectorDrawable，资源在 `res/drawable/ic_lucide_*.xml`，由 `LucideIconsResourcesTest` 守护。
- **尺寸只有三档**：`20`（设置行 / 顶栏）、`24`（通用 / 空态）、`44`（按钮容器）。
- 图标颜色：`iconTint`；禁用态 `textTertiary`；危险动作 `danger`。
- 图标与文字间距 `spacing.sm`(8)；设置行图标槽 `settingsRowIcon`(20)。

---

## 7. 动效

来源：`core/design/DshmMotion.kt`。

| 令牌 | 值 | 用途 |
|---|---|---|
| `screenTransitionMs` | 450ms | 整屏 push / pop（iOS 风格） |
| `fadeMs` | 300ms | 伴随淡入淡出 |
| `standard` | `(0.32, 0.72, 0, 1)` | 推入/弹出主曲线 |
| `exit` | `(0.05, 0.7, 0.1, 1)` | 旧页面退场 |
| `outgoingParallaxFraction` | 0.25 | 旧页向左退的屏宽比例 |

**规则**：页面切换只有一种动效（全屏 push/pop + 视差），别在别的页面发明第二种转场；
「减少动效」模式（`PerformanceMode.LIGHT` 或系统动画关闭）下 `screenTransitionMs` 传 0。

### ⚠️ 已知坑（今天真实踩过）

| 坑 | 现象 | 正确做法 |
|---|---|---|
| `TextButton` 默认最小高 40dp | 放在标题行会把整行撑高，标签被推离输入框 | 用紧凑文本（`settingsRowSubtitle` + `clickable`），dsh-Go 同款 `FormSmallButton` |
| `IconButton` 默认 48dp | 放进输入框尾部会把**这一个框**撑高，和别的框高矮不一 | 用 `Icon` + `Modifier.size(20.dp)` + `clickable` |
| 输入框上下内边距给 12dp | 框高 47dp，看着「上下很高」 | 上下 `spacing.sm`(8)，并用 `heightIn(min = spacing.inputHeight)` 统一所有输入框高度 |
| Material3 `Scaffold(bottomBar=)` 是**非空**参数 | 传可空参数编译不过 | `bottomBar = { bottomBar?.invoke() }` |
| `ModalBottomSheet` 默认半屏可上拉 | 候选多时底部按钮被半屏挡住 | `skipPartiallyExpanded = true` + 内容区 `height(屏高×0.7)` |
| 用 `confirmValueChange = { false }` 锁面板高度 | **列表的滑动被面板手势吃掉，轻轻一滑就关闭** | 不要锁状态，用固定高度的内容区定死；原生把手保留 |

---

## 8. 页面骨架模板

### 8.1 设置子页模板（基准）

```kotlin
SettingsPage(
    title = stringResource(R.string.xxx),
    onBack = onBack,
    actions = { SettingsRefreshButton(onClick = viewModel::refresh) },  // 可选
    bottomBar = { /* 可选：唯一主操作 */ },
) {
    item { SettingsSection(title = stringResource(R.string.group_xxx)) { /* 行 */ } }
}
```

- **页面左右边距**：`spacing.lg`(16)（由 `SettingsPage` 的 `LazyColumn` contentPadding 统一给）。
- **卡片之间组间距**：`spacing.xxl`(24)（`SettingsPage` 的 `verticalArrangement`）。
- **卡片内左右内边距**：`spacing.lg`(16)（行内 padding）。
- **分组标题**：在卡片**外面**，用 `SettingsSection(title = …)` 的 title 参数（13sp Medium / `textSecondary`）。
- **卡片圆角**：`settingsCard`(24)，描边用 `divider` 1dp 发丝线，投影 `shadowCard` + `cardElevation`(16)。
- **卡底留白**：默认 **0**（卡片内容自然贴底，和其它卡片保持一致）；只有**独立浮在卡片里的主操作按钮**
  这类元素要自己留 `spacing.lg`(16)（dsh-Go 的主按钮下方有 16）。
  **不要给整张卡统一加底距**——那会让所有卡片都凭空多出一圈空白。
- **行高**：≥ `rowMinHeight - spacing.sm` = 56dp。
- **行内左右**：`spacing.lg`(16)；图标 20dp；图标↔文字 `spacing.sm`(8)。
- **行间分割线**：**默认不画**（dsh-Go 的 `SettingsSection` 用 `SheetGroup`，行间只有 2dp 间距，
  靠行内边距分组）。需要分隔时用 `SettingsRowDivider()`（1dp `divider`，左右各缩进 `spacing.lg`(16)）。
- **右侧值**：`settingsRowValue` + `textSecondary`；有值时**不要**再画 `>` 箭头（`trailing = {}` 可显式去掉）。
- **标签不许被值挤扁**：值很长时标签保持自然宽度，值自己省略（`maxLines = 1` + Ellipsis），
  不要让「版本」这种标签被压成一列竖排的字。版本串按 dsh-Go 的 `shortRuntimeVersion` 缩写。

### 8.2 表单字段块模板（表单页）

```
[卡片]
  md(12)  ← 卡片内顶部留白（每张卡第一个字段才有）
  字段标题  formFieldLabel 16+4(缩进)
  xs(4)
  输入框  高 inputHeight(44)，圆角 md(14)，描边 divider 1dp
  lg(16)  ← 字段块下方
  下一个字段…
[卡片]
```

- 字段标题与输入框的间距恒为 `spacing.xs`(4)。
- 字段块下方恒为 `spacing.lg`(16)。
- 输入框内部：文字 `settingsRowTitle`(16)，左右内边距 `spacing.md`(12)，上下 `spacing.sm`(8)。
- 输入框尾部控件（显隐、粘贴）**必须**用固定 20dp 图标，不要用 `IconButton`。
- 动作按钮放**字段标题行右侧**，用紧凑文本（`settingsRowSubtitle` + `accent` 色），不要用 `TextButton`。

### 8.3 列表页模板（主界面、搜索、Bot 列表）

- 页面底 `pageBackground`，卡片 `surface`，卡片圆角 `settingsCard`(24)。
- 行：`avatar`(44) 或 Monogram(32) + 标题 `rowTitle` + 副标题 `rowPreview` + 右侧状态点 `statusDot`(8) + 可选 `>`。
- 行高 ≥ 56dp，行间用 `spacing.xxl`(24) 留白，**不画分割线**。
- 顶栏：`topBarHeight`(60)，左返回 `iconButton`(44)，标题 `screenTitle`(16)。

### 8.4 详情页模板（渠道详情、Bot 详情）

- 顶部 Hero 区包成**一张卡**（`SettingsSection`）：`settingsAvatar`(56) 字母块 + 名称 `sheetTitle` + 状态点 + 状态词。
- 下面按「连接」「模型」等语义分组，每组一张卡，组间距 `spacing.xxl`(24)。
- 破坏性操作（删除）放**最后，单独一张卡**，卡内按钮**居中**，用 `SettingsDangerButton`
  （`danger` 色文字按钮 + 删除图标，无箭头）。**不要**把删除按钮裸放在页面背景上，
  也不要给这张卡加太多上下留白（8dp 就够，卡片高度 ≈ 56dp）。

### 8.5 对话页模板

- 顶栏：返回 `iconButton`(44) + 名称胶囊（头像 `avatar`(44) + `rowTitle`）+ 右侧操作。
- 消息列表：`bubbleAssistant` / 用户气泡渐变，圆角 `lg`(20)，左右边距 `spacing.lg`(16)。
- 输入条：圆角 `composer`(28)，发送键 `composerKey`(40)，投影 `composerElevation`(12)，底部 `composerBottomInset`(8)。

---

## 9. 组件清单（全部在 `ui/settings/SettingsComponents.kt`，可直接复用）

| 组件 | 用途 | 关键点 |
|---|---|---|
| `SettingsPage` | 设置类子页骨架 | 统一顶栏（返回 + 标题 + actions）、页面边距 16、组间距 24、可选 `bottomBar` |
| `SettingsSection` | 一张分组卡 | `title` 是卡片**外**的分组标题；`footer` 是卡下说明；`state = DANGER` 变红 |
| `SettingsRow` | 普通行 | `icon`/`leading` 二选一；`value` 右侧值；`trailing` 覆盖默认箭头（传 `{}` 去箭头） |
| `SettingsSwitchRow` | 开关行 | `checked` + `onCheckedChange`；`enabled=false` 置灰 |
| `SettingsRowDivider` | 卡内发丝线 | 1dp `divider`，左右缩进 16 |
| `SettingsInput` | 文本输入 | 统一高 44、圆角 14；`trailing` 放框内小图标 |
| `SettingsPrimaryButton` | 主按钮（蓝） | `danger = true` 变红；宽 `fillMaxWidth` |
| `SettingsDangerButton` | 破坏性文字按钮 | `danger` 色，无背景，用于居中「删除」 |
| `SettingsReadOnlyValue` | 只读值行 | 自动显示「未设置」 |
| `SettingsAnchoredMenu` / `SettingsMenuItem` | 行下弹出选择面板 | 面板宽 `menuWidth`(220)，右对齐 |
| `SettingsRefreshButton` | 顶栏刷新 | 用 `FloatingIconButton` |
| `SettingsLoading` / `SettingsEmpty` / `SettingsHint` / `SettingsError` | 四种状态 | 加载用 `accent` 环；错误用 `danger` 文字 + 可选「重试」 |
| `SettingsSegmentedPill` | 分段控件 | 胶囊底槽 + 会滑动的滑块；各段等宽、文字永远一行 |
| `SettingsCapsuleGrid` / `SettingsChoiceCapsule` | 快捷胶囊网格 | 等宽胶囊按行排布，间距 8dp；选中反色 |
| `SettingsLevelRow` / `SettingsSelectionTick` | 多选行 | 左侧 20dp 勾选圆点 + 行标题，整行可点 |

其它公共件：`FloatingIconButton`（44 圆形按钮）、`BotAvatar`（44 头像）、`PetOverlay`（宠物）、`MarkdownText`（对话正文）。

---

## 10. 界面清单 → 应使用的组件

| 界面 | 文件 | 骨架 | 关键组件 |
|---|---|---|---|
| 主界面 | `HomeScreen.kt` | 列表模板 | `BotRow`、顶栏 `FloatingIconButton` |
| 搜索 | `SearchScreen.kt` | 列表模板 | 复用 `BotRow` |
| 对话 | `BotChatPlaceholderScreen.kt` | 对话模板 | `ChatComposer`、`MarkdownText` |
| Bot 详情 | `BotDetailsScreen.kt` | 详情模板 | Hero 卡 + 分组卡 |
| 引擎页 | `EngineScreen.kt` | 设置模板 | Hero 卡 + 胶囊操作区 + 「引擎启动」卡 + 日志井 + 「更多」卡 + 「运行时」卡 |
| 诊断页 | `DiagnosticsScreen.kt` | 设置模板 | 「运行时」卡 + 「连接」卡 + 「导出」卡 |
| 引擎参数 | `EngineSettingsScreen.kt` | 设置模板 | 「运行」组（子代理 / Agent 循环 / 命令执行）+「插件」组 |
| 开屏引导 | `OnboardingScreen.kt` | 独立 | 按钮用 `SettingsPrimaryButton` |
| 账号页 | `AccountScreen.kt` | 详情模板 | Hero 卡 + 「连接」卡 + 余额两列 |
| 设置首页 | `SettingsScreen.kt` | 设置模板 | 账号概览卡 + 六组 |
| 设置 16 子页 | `ui/settings/*` | 设置模板 | 见 §8.1 / §8.2 |
| 渠道详情 | `ProviderDetailPage.kt` | 详情模板 | Hero 卡 + 连接卡 + 模型卡 + 居中删除 |
| 模型设置 | `ProviderModelPage.kt` | 设置模板 | 基本信息 / Token 上限 / 能力 三张卡 |
| 模型选择面板 | `ProviderModelPicker.kt` | 弹层 | 原生 `ModalBottomSheet`，弹起即占屏高 70% |
| 网页模式 | `WebHostScreen.kt` | 全屏 | `WebView` |

---

## 11. 验收方式

改完 UI 必须做两步，不许只看代码：

1. **真机截图**：`adb -s <设备> shell screencap -p /sdcard/s.png` → pull → 看排版。
2. **坐标量化**：`adb shell uiautomator dump` 拿节点 `bounds`，用坐标差量间距。
   例：Base URL 输入框 → API Key 标题 应 ≈ 标签高 + 4dp；两个输入框的文本节点 y 差应一致。

判据：
- 同一类元素（两个输入框、两个图标、两个卡片）尺寸/圆角/字号完全一致；
- 标签→控件、控件→控件的间距符合 §8；
- 一屏内主色面积 ≤5%。

---

## 12. 相关文档

- `docs/design/settings.md` —— 设置页的领域内容（哪些项、连哪个 RPC）
- `docs/design/main-screen.md` —— 主界面结构
- `docs/reference-baselines.md` —— DS Bot / dsh-Go 的边界（做功能前先看）
- `docs/roadmap.md` —— 进度
