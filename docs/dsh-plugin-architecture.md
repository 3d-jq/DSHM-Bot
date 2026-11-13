# DSH 插件架构研究（2026-11-13，样本 0.2.2-rc.1）

> 目的：用户定了大目标 —— **不再「我们打补丁适配上游」，而是「让 DSH / DS Bot 适配我们」**。
> 本文是可行性研究：DSH 到底怎么组合、能改到什么程度、代价分别是什么。
>
> 样本：`@deepseek-ai/dsh@0.2.2-rc.1`（npm 最新；我们 rootfs manifest 里钉的是 `0.2.1-rc.4`）。
> 只读研究，包解在仓库外（`/tmp`），没有入库，也没有执行包里的任何代码。

## 一句话结论

**DSH 本身就是一个 Cordis 插件组合**：`dsh` 这个 npm 包只有 20 个文件、只是个引导壳，
真正的能力全在 `@deepseek-ai/dsh-*` 的独立包里（工具、提示词、会话、凭据、UI…）。
**每个内建工具都是一个可替换的插件**，profile 是「有序的 patch 层栈」——
所以「魔改」的正确姿势是 **写插件 / 改 profile**，不是改 `node_modules`。

## 1. 组合模型

`dsh` 包依赖里能直接看到拆分粒度（节选）：

```
@deepseek-ai/dsh-tool-present        ← present 工具（独立包！）
@deepseek-ai/dsh-tool-bash / -web / -ask-user / -fs / -str-replace-editor / -subagent …
@deepseek-ai/dsh-agent-instructions  ← AGENTS.md / CLAUDE.md 读取
@deepseek-ai/dsh-persona             ← 人设（prefix / suffix）
@deepseek-ai/dsh-system-prompt       ← 系统提示注册表
@deepseek-ai/dsh-skill / -skill-filesystem / -skill-office
@deepseek-ai/dsh-plugin-manager / dsh-hmr
@deepseek-ai/cordis + cordis-plugin-loader / -include   ← 插件底座
@deepseek-ai/dsh-experimental-*      ← 语音、自动审查、CoT 翻译、会话标题、终端、插件市场…
```

profile 的组装顺序（`lib/profile-boot-*.js` 的注释与 README）：

```
dsh.profile.bundles（按顺序，每个 bundle 自带 patch）
  → 该 profile 的 cordis.patch.yml（我们自己的 patch 层）
  → $DSH_HOME/cordis.patch.yml（机器级）
  → 命令行 --patch 覆盖层
```

`dsh plugin --profile <name> …` 就是在这个 profile 目录里转发 pnpm，
所以**加/删/替换插件是官方支持的一等操作**，不需要动 `node_modules`。
安装/启动还会校验 `engines.dsh` / peer 范围（不兼容要显式 `version-exemptions`）。

## 2. 提示词的四级扩展点（代价从低到高）

| 级别 | 机制 | 能力 | 代价 |
|---|---|---|---|
| 1 | **AGENTS.md**（`dsh-agent-instructions`） | 工作区规则；支持 `AGENTS.md` / `CLAUDE.md` / `AGENTS.local.md` / `CLAUDE.local.md`、`$DSH_HOME/AGENTS.md`、`agents-global/` | 零（官方支持，我们已用它注入手机操控） |
| 2 | **`ctx.systemPrompt.section({name, order, text, complete?})`** | 往系统提示里插/替换有序分段；`complete:true` 表示「整份系统提示只有我」 | 写个 Cordis 插件（几十行） |
| 3 | **`system-prompt/assemble` waterfall 事件** | 拿到整份 assembly（sections / contexts / variables），**返回的文本是权威的**，可整体改写；注意：注册了 `complete` section 的 scope，waterfall 之后会被还原 | 写插件 + 理解 scope |
| 4 | **替换官方插件包** | 例如用我们自己的 `tool-present` 插件（保持 `name = "present"`）替换官方包，改工具描述/参数/行为 | profile 里换包 + 跟进上游 |

补充：
- **`dsh-persona`** 是 `ctx.systemPrompt` 的一行（prefix / suffix），挂在 agent preset 上会**覆盖该会话的部署人设**——这正是「每个 Bot 一套人设」的官方入口，比我们现在从 DS Bot 的 role 字段转述更直接。
- **包级 `locale/*.json`**：每个插件自带 `de/en/es/fr/ja/pt/ru/zh`，说明 DSH 已有多语言骨架（但**工具描述本身仍是硬编码英文**，locale 只覆盖元数据）。
- 注册是「同名必须唯一」：`section()` / `tool.register()` 重复注册会抛错 → 想改官方段，要么别加载那个插件，要么走 waterfall。

## 3. 我们那 13 个 patch 的定位

现状（见 `app/src/main/assets/runtime/manifest.json` 的 `dshPatches`）：13 个 diff 打在构建产物
`node_modules/@deepseek-ai/*/lib/index.js` 上，全是功能性补丁，**没有一处改 prompt**。

按上面的模型，其中多数属于「本可以做成插件/Profile 层」的：
- 路由守卫、插件管理守卫、安装日志脱敏、配置读取可读性 → 更像**自研插件**（`dsh-go-bridge` 就是这么做的，4,263 行）；
- `fs-create-exclusive`、`session-deletion`、`title-cache-refresh` 改了包内部实现 → 确实得 patch，但可以评估是否只是「缺一个插件能拿到的事件/钩子」。

**行动建议**：逐个 patch 评估「插件能替代吗」；能迁的迁走，剩下的才继续 patch。
目标是**把 patch 数量降下来**，降低每次上游升级的 merge 成本。

## 4. 落到我们的具体目标（示例）

### 4.1 让 `present` 适配我们（这次产物卡问题的根治）

官方 `dsh-tool-present` 的工具描述（模型看到的那段）是：

> Declare existing files as final deliverables for the user. Hosts may show presented files only
> after your turn ends, so call it once the files are final. … **Use it when the user needs a
> separate file, especially Office documents, spreadsheets, and slide decks; prefer your final
> response when that suffices.**

**「especially Office documents」直接解释了模型为什么给 docx 调 `present`、给图片不调。**

做法：自写一个 Cordis 插件（`name = "present"`，`inject = ["tools","fs","agents","workingDirectory"]`），
`ctx.tools.register(defineTool({ name: "present", description: "中文描述…任何交付物（文档/图片/视频）都应声明…" }))`，
在 profile 里用它替换官方 `@deepseek-ai/dsh-tool-present`（或直接加一条 AGENTS.md 规则，
成本更低，先试这个）。

### 4.2 提示词本地化 / 产品化

- 新增我们自己的 systemPrompt section（中文）：交付规范、语气、目录习惯、团队规则；
- 或者用 `system-prompt/assemble` waterfall 做「中文优先」的整体改写；
- 每个 Bot 的人设走 `dsh-persona` 的 prefix/suffix（替代当前从 DS Bot `role` 转述）。

### 4.3 工具集裁剪

profile 里移除用不到的官方包（`experimental-*` 那一串：语音、自动审查、CoT 翻译、liveshare…），
直接减少工具 schema 的 token 和模型的选择困难。

## 5. 约束与风险

- **profile 是 per-installation 的**：App 每次启动要写入/更新 profile（我们已有 patch yml 注入机制），
  升级时要保证不覆盖用户的 `cordis.patch.yml`；
- **上游升级要跟**：替换官方包意味着要跟它的 `name`/`inject`/输出契约；版本用 `engines.dsh` 卡住；
- **`complete` section 唯一**：全 scope 只能有一个生效，注册两个会失败；
- **同名 section / tool 重复注册会抛错**：替换必须先保证官方那个没被加载；
- **locale 不是工具描述**：想中文化工具描述只能改插件（这也是为什么要自写 `present`）；
- **DS Bot 插件同理**：它自己也是 DSH 的一个插件（`app/src/main/assets/ds-bot`），
  `prompt.js`/`tools.js` 也可以在插件层替换/扩展，而不是 merge 上游 diff。

## 6. 研究样本清单（本次下载，仓库外）

`/tmp/dshstudy/`：`dsh-0.2.2-rc.1`、`dsh-tool-present`、`dsh-agent-instructions`、
`dsh-persona`、`dsh-tools`、`dsh-agent-tool-presentation`、`dsh-system-prompt`（均从 npmmirror 拉取，只读）。
没有执行其中任何代码；需要复看时重新拉即可（版本号 + sha 见 npm registry）。

## 7. 魔改后的 DSH 怎么打包进运行时（2026-11-13）

**结论：可以，而且按「改动在哪一层」分三条路，成本差一个数量级。**

| 改什么 | 打包路径 | 发版产物 | 用户更新成本 |
|---|---|---|---|
| **能力层插件**（`present` 描述、systemPrompt section、persona、技能、AGENTS.md） | **打进 APK `assets/`，引擎启动时装进 rootfs** | 新 APK（~19MB） | 不需要重下 runtime |
| **预装插件**（稳定后固化） | 构建时装进 rootfs（`runtime/build-runtime.sh`） | 新 runtime zip（~158MB） | 下载 rootfs |
| **DSH 本体改动**（fork 包/源码） | 构建时安装我们的副本，重打 rootfs | 新 runtime zip | 下载 rootfs |

### 7.1 已有先例：`DsBotPluginInstaller`（APK → rootfs 的插件安装器）

`data/runtime/DsBotPluginInstaller.kt` 已经把「不改 runtime、只发 APK」这条链路跑通了：

- 把 APK 里的 `assets/ds-bot` 整树拷到 `rootfs/opt/dsh/node_modules/ds-bot`；
- 在启动 overlay `rootfs/etc/dsh-app/dsh-web.patch.yml` 里 **insert** 一行插件（当前是 `bot`）；
- 指纹 = 插件版本 + APK `lastUpdateTime`，**APK 一更新就强制覆盖**（改过的 JS 才进得去）；
- 插件行必须写**绝对路径**（Node 不会从 profile 目录解析裸包名）。

我们自己的 DSH 插件可以**原样复用这套**：`assets/dshm/<plugin>/` + installer 写 patch 行。

### 7.2 替换官方包（如 `tool-present`）需要额外一步

- **新增**插件（section / persona / 自定义工具）= 往 patch 层 insert 一行，现成可用；
- **替换**官方包 = 官方那行已经在基础 profile 里，必须先移除/禁用再插我们的。
  我们的 overlay 目前只用了 `- insert:`；patch 层是否支持 `remove` 需要验证（bridge 的
  `cordis.patch.yml` 只示范了 insert）。备选：在 rootfs 构建时改基础 `cordis.yml`，或用我们自己的
  profile 模板。**这一点动手前必须先验证**，否则会撞「同名 section / tool 重复注册抛错」。
- 另外：`present` 这种工具如果只是**加中文描述**，也可以不做插件替换 —— 先试 AGENTS.md
  （零成本），不行再做替换。

### 7.3 DSH 本体（fork）的打包与可追溯

- 构建在 CI：`.github/workflows/runtime.yml`（手动 / `runtime-v*` tag）跑
  `runtime/build-runtime.sh`，产物是 `dshm-runtime-arm64-v8a.zip`，App 从 releases 下载；
- 现在 `assets/runtime/manifest.json` 钉的是 `runtimeVersion` / `dshVersion` / `nodeVersion` / `sha256`
  以及 13 个 `dshPatches`；
- **fork DSH 后要在 manifest 里补一个字段记录 fork（仓库 + commit）**，否则构建不可复现、出问题查不到基线；
- rootfs 已经有 ~786MB（解压后），几个 JS 插件体积可忽略；但**改本体意味着每次都要发 158MB 的 runtime**，
  所以能动插件层就别动本体。

### 7.4 一句话策略

> **高频迭代走「APK assets + 启动安装」（不重打 runtime）；稳定后固化进 rootfs；
> 只有插件层做不到的才 fork 本体，并登记 fork 版本。**
