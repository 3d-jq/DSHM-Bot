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
- 输入框与键盘/系统栏之间留 `spacing.composerBottomInset = 8dp`；用
  `consumeWindowInsets(innerPadding) + imePadding()` 避免与 Scaffold 内边距重复，键盘弹出时紧贴键盘上方。
- 对话页顶栏按「头像 + 胶囊」的真实高度留位，消息列表从胶囊下方开始，不会压到胶囊。

## 测试

- `ComposerRulesTest`：空白/全空格不可发送，非空可发送。
- `ChatComposerTest`（插桩）：空白时发送键禁用；输入后发送键可用并触发回调；附件键触发回调。
- `ChatComposerHeightTest`（插桩）：3 行时输入框高度比 1 行高出 > 40px（保证向上长高）。
