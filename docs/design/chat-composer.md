# 对话输入框设计（dsh-Go 风格）

参照 dsh-Go 的 `app/lib/ui/chat/composer.dart`、`ui/chat/widgets/attachments.dart`（AttachButton）、
`ui/theme/glass.dart` + `ui/theme/surfaces.dart`（chrome 玻璃）：

## 结构

```
┌──────────────────────────────────────────────┐
│ (＋)  给办公专家发消息…              (↑)   │   单行：胶囊高 56dp，圆角 28dp
└──────────────────────────────────────────────┘
```

- 胶囊容器：白底（`colors.surface`）+ 1dp 发丝描边（`colors.divider`）+ **柔和外投影**
  （`spacing.composerElevation = 12dp`、`colors.shadowSoft`，对应 dsh-Go chrome 玻璃的
  24 模糊 / 下移 8 / 8% 阴影）。圆角 `radius.composer = 28dp`，内边距 `spacing.composerKeyGap = 8dp`。
- 单行总高 = 40dp 圆键 + 上下各 8dp = 56dp。
- 左侧 40dp 圆形「＋」附件键（`colors.surfaceMuted` 6% 淡底），图标 **24dp**（对应 AttachButton）。
- 中间多行输入：**单行时 56dp；文字变多自动向上长高，最多 6 行**（dsh-Go 1–6 行行为）。
  正文字号 **16sp、行高 ×1.45（23.2sp）**，上下内边距 `spacing.composerTextPad = 8dp` 让单行在 40dp 内居中；
  空白时显示提示（`给{名字}发消息…`）。
- 右侧 40dp 圆形发送键（图标 **22dp**，对应 dsh-Go `_SendButton`）：
  - 有内容：主色实心（`colors.accent`）+ 白色 ↑（`ic_lucide_arrow_up`）
  - 无内容：淡底（`colors.surfaceMuted`）+ 灰箭头，且不可点击（禁用）

## 行为约束

- 只允许非空白文本发送（`ComposerRules.canSend`）。
- 输入框与**系统导航栏**之间留 `spacing.composerBottomInset`，与**输入法**之间留
  `spacing.composerImeGap` —— 两个间隙各自独立（键盘下贴得比静止态紧得多，合成一个值就两头不讨好）；
  用 `consumeWindowInsets(innerPadding) + imePadding()` 避免与 Scaffold 内边距重复，键盘弹出时紧贴键盘上方。
  数值一律以 `core/design/DshmSpacing.kt` 为准，本文不抄。
- **底部渐变磨砂**：照对话顶栏那层 `Brush.verticalGradient` 的写法，在消息列表上叠一块
  底部遮罩（上下透明、输入框一带最浓、物理底边也透明，不盖住沉浸式小白条）。
  `Modifier.blur(spacing.composerBlurRadius)` 做均匀模糊（API 31+ 生效，低版本自动退化为纯渐变），
  渐变 alpha 负责「越靠近输入框越淡出」的观感。不动 Compose BOM，不用 `BlurRadiusSpec`。
- **悬浮式**：消息列表铺满整屏（只让位给系统 UI），输入框与自己的留白一起叠在列表上方；
  列表的 `contentPadding.bottom` 按「输入框实测高度 + 留白」避开这块，让内容能从输入框下方透过去。
  **不要把 `composerBottomInset` 加到内容区的 `padding(bottom=)` 上** —— 那会把列表一起抬高，
  底下露出 Scaffold 的白底，消息滑到下面就被裁断。
- **沉浸式小白条**：无输入法时内容区**不设底部 padding**，消息一直画到物理底边、
  小白条浮在内容上（照 DeepSearch `ChatScreen.kt:2725` + `NanoMuseHome.kt:510` 的
  `imePadding()` / `consumeWindowInsets`）；只有键盘弹出才由 `imePadding()` 顶开内容区。
  系统栏本身用 `SystemBarStyle.auto(TRANSPARENT, TRANSPARENT)` 压掉浅色主题的自动 scrim。
  输入框自己按「导航条高度 + `composerBottomInset`」让开小白条，不会压住手势区。
- **IME 回落不抖**：输入框离物理底边取 `maxOf(imeInset + composerImeGap, 静止位)`，
  静止位 = `navigationBars + composerBottomInset`。键盘高时贴着键盘走，回落到静止位
  就钉住，之后 IME 再收也不动（主流 App 的「一下子固定住」手感）。
  **不能用 `if (imeInset > navigationBars)` 在两种 padding 之间二选一** —— 分支翻转那一帧
  padding 从 `composerImeGap` 跳到 `navigationBars + composerBottomInset`，输入框在下落途中
  被凭空抬起约 28dp，于是抖一下。规则在 `ComposerBottomInset.kt`，
  `ComposerBottomInsetTest` 逐帧守着连续、单调与钉住点。
- 对话页顶栏按「头像 + 胶囊」的真实高度留位，消息列表从胶囊下方开始，不会压到胶囊。

## 测试

- `ComposerRulesTest`：空白/全空格不可发送，非空可发送。
- `ChatComposerTest`（插桩）：空白时发送键禁用；输入后发送键可用并触发回调；附件键触发回调。
- `ChatComposerHeightTest`（插桩）：3 行时输入框高度比 1 行高出 > 40px（保证向上长高）。
