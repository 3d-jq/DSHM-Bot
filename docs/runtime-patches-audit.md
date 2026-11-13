# runtime patches 评估表（2026-11-13，阶段 1 分析产出）

> 目标：逐个判断 11 个**真实存在**的 `runtime/patches/*.diff` 能否迁成「插件 / profile」，
> 迁不掉的才继续 patch。对照 DSH **0.2.1-rc.4（我们当前）** 与 **0.2.2-rc.1（最新）**。
>
> ⚠️ 账目：**`app/src/main/assets/runtime/manifest.json` 的 `dshPatches` 写了 13 个，目录里只有 11 个。**
> 多出的 `file-upload-resolver-dispose.diff` / `zzzzzzz-input-recovery.diff` 在 git 历史里从未出现，
> 属于从 dsh-Go 版本清单搬过来时留下的陈旧字段；`build-runtime.sh` 实际扫目录生成清单（只会是 11）。
> 评估第一步就应把 committed manifest 矫正为 11（或说明这 2 个已合入别处）。

## 对照上游 0.2.2 的意外收获

拉 0.2.2 对应包比对，发现**部分补丁已经被上游吸收**：

| 补丁 | 0.2.2 证据 | 结论 |
|---|---|---|
| `plugin-manager-active-run-guard.diff` | 0.2.2 `dsh-plugin-manager` 自带 `activeRecordedRun(dir)`，并在 pnpm 启动前 `if (active !== void 0) { … return exitCode 1 }` | **升级即删** |
| `zzz-title-cache-refresh.diff` | 0.2.2 `dsh-api-session-controller` 自带 `summarizeCold(...)` / `coldHints` | **升级即删** |
| `fs-create-exclusive.diff` | 0.2.2 有 `FS_NOT_OBSERVED` 与 `writeFileAtomic` 注释，但**没有** EPERM/EXDEV/ENOTSUP 的 Android 硬链接回退 | 保留（未完全吸收） |
| `plugin-manager-cancel-tree-confirmation.diff` | 0.2.2 有 `grouped` / `alive(-pid)` / `targetOf`，但未见 `ownedRunGuard` 那种「确认进程树停止」的完整实现 | 保留（待逐段核） |
| `zzzzzz-session-deletion.diff` | 0.2.2 的 session 相关事件里**没有** `session/delete` / `deleteSession` | 保留 |

→ **所以「升级到 0.2.2」本身就是一次减补丁的机会**，比迁移还省事。

## 逐个评估（11 个）

| # | 补丁 | 改什么（目标包） | 能否迁插件/profile | 结论与方案 |
|---|---|---|---|---|
| 1 | `fs-create-exclusive.diff` | `dsh-fs-local`：硬链接不支持时（EPERM/EXDEV/ENOTSUP/EMLINK）退回 `open("wx")` 独占创建 | ❌ 包内部实现 | **保留 patch**。它改的是 `writeFileAtomic` 的内部发布路径，没有对外钩子；`dsh-fs` 的 service 接口不暴露这个原语。 |
| 2 | `install-log-redaction.diff` | `dsh-plugin-manager`：pnpm 日志参数里去掉 URL userinfo（token） | 🟡 **可能可迁** | 目标包 `inject=["loader","profileContext"]`，没暴露日志 sink。但**我们的 `dsh-go-bridge` 已经是一个自研插件**且参与启动 overlay；「脱敏」可以在我们自己的安装入口做（我们调用插件管理时自己拼参数），不必改官方包。→ **评估：迁到 bridge / App 侧调用层**，删 patch。 |
| 3 | `plugin-manager-active-run-guard.diff` | `dsh-plugin-manager`：安装前查 `activeRecordedRun` 防并发 | ✅ **上游已吸收** | **升级 0.2.2 直接删**；不迁插件（改的是内部变更流程）。 |
| 4 | `plugin-manager-cancel-tree-confirmation.diff` | `dsh-plugin-manager`：取消时确认子进程树真的停了（179 行，`ownedRunGuard`） | ❌ 包内部实现 | **保留 patch**（或按 0.2.2 的 `grouped`/`alive` 重写得更薄）。这是进程生命周期，插件拿不到。 |
| 5 | `route-guard.diff` | `dsh-host-webserver`：给所有路由/upgrade 加 Connection 校验（Host/Origin fence + cookie） | 🟡 **部分可迁** | webserver 有 `registerUpgrade` / 路由表，但**「每个请求都过一遍」的全局守卫没有官方钩子**，所以 patch 里才要在 dispatch 入口插判断。我们的 `dsh-go-bridge` 已加载，可把它做成 bridge 的 webserver 服务装饰（若 webserver 暴露 service）。→ **先验证 webserver service 能否被插件包装**；能则迁 bridge，不能保留。 |
| 6 | `zz-direct-runtime.diff` | 7 个包：`globalThis[Symbol.for("dsh.go.direct")]` 的路径边界 / L2S 拒绝 / node-pty | ⚠️ **对我们可能是死代码** | 它服务的是 dsh-Go 的 **direct 引擎**（`runtime/direct/preload.mjs` 设置该标记）。**我们用的是 proot**（`ProotCommand` / `dsh web`），启动 overlay 不加载 direct preload → 这个标记根本不存在，补丁全是 no-op。→ **建议：确认后删除**（这能一次减掉 7 个文件的补丁面）。 |
| 7 | `zzz-config-editor-path.diff` | `dsh-app-boot` / `dsh-config-editor`：让 ConfigEditor 用 boot 的 patch parser | ❌ 内部函数 | **保留 patch**。但注意 0.2.2 的 plugin-manager 已经 import `parsePatchList`/`readProfilePatches` from `dsh-app-boot` —— **升级后可能自动作废**，列为「升级时复核」。 |
| 8 | `zzz-title-cache-refresh.diff` | 4 个包：冷投影 / 标题缓存刷新 | ✅ **上游已吸收** | **升级 0.2.2 直接删**。 |
| 9 | `zzzz-plugin-config-readability.diff` | 3 个包：只读 profile ownership 探针、plugin-config JSON 503 | 🟡 可能可迁 | 0.2.2 的 `dsh-plugin-manager` 已有 `readOnlyReason`（`management-required` / `unaddressable`）与 `readProfilePatches` —— **升级后大概率自动作废**。→ 列为「升级时复核」，不急迁。 |
| 10 | `zzzzz-plugin-config-bundles.diff` | `dsh-config-editor`：在 HMR/profile 锁内 await config authorizers | ❌ 内部锁 | **保留 patch**（6 行，但涉及锁时序，插件碰不到）。 |
| 11 | `zzzzzz-session-deletion.diff` | 6 个包：安全删除会话（生命周期、持久化 fencing、派生状态清理） | ❌ 包内部实现 | **保留 patch**。0.2.2 仍无 `session/delete` 事件/服务，是最大的一块（133 行）。 |

## 汇总

| 处理 | 数量 | 补丁 |
|---|---:|---|
| **升级 0.2.2 直接删** | 2 | `plugin-manager-active-run-guard`、`zzz-title-cache-refresh` |
| **确认死代码后删** | 1 | `zz-direct-runtime`（direct 引擎专用，我们用 proot） |
| **升级时复核（可能自动作废）** | 2 | `zzz-config-editor-path`、`zzzz-plugin-config-readability` |
| **可迁到自研 bridge / App 侧** | 2 | `install-log-redaction`、`route-guard`（后者需先验证 webserver service 可包装） |
| **必须继续 patch** | 4 | `fs-create-exclusive`、`plugin-manager-cancel-tree-confirmation`、`zzzzz-plugin-config-bundles`、`zzzzzz-session-deletion` |

**理想结果**：升 0.2.2 + 删 direct 死代码 + 复核 2 个 → **13（账面）→ 实际 11 → 可能只剩 4–6 个**。
每少一个，上游升级就少一次冲突。

## 落地顺序（下一步，未执行）

1. **矫正 manifest**：把 `dshPatches` 改成目录实际内容（11），并注明那 2 个陈旧名；
2. **验证 `zz-direct-runtime` 是不是真死代码**：在真机 rootfs 里 grep `dsh.go.direct` 是否被设置
   （若 proot 启动没加载 `preload.mjs`，确认后删）；
3. **升级到 0.2.2-rc.1 的一次性收益**：跑 `patch-dsh` 看哪些补丁 apply 不上（= 上游已吸收），
   删掉它们、更新 `pins.json`；
4. 对 `install-log-redaction` / `route-guard`，验证 `dsh-go-bridge` 能否包装 webserver / 接管安装参数，
   能则迁，不能保留；
5. 剩下的 4 个内部实现补丁保留，登记「为什么不能迁」，以后升级时重点照顾它们。

## 边界

- 本表是**分析产出**，没有改任何 runtime / patch / manifest；
- 「上游已吸收」是按 0.2.2 源码关键字与上下文判断，最终以 `patch-dsh` 实际 apply 结果为准；
- 「可迁 bridge」需要先验证 webserver 的 service 是否可被插件包装，属于下一轮的代码验证。

## 升级 DSH 的决定（2026-11-13）：**暂缓**

用户问「升级是不是有好处」，确认是的（升级 DSH 本体 `0.2.1-rc.4 → 0.2.2-rc.1`，
上游已吸收 2 个补丁，可直接删）。但用户随后说「我没有 Linux 环境呀，那就弄不了」，
并明确 **「那先不更新吧」** → **不升级，保持 0.2.1-rc.4**。

### 已经澄清的事实（避免下次误判为"做不了"）

完整 rootfs 构建**本来就不需要本机 Linux**：仓库有
`.github/workflows/runtime.yml`（`workflow_dispatch` 手动触发 / `runtime-v*` tag），
跑在 `ubuntu-24.04` runner 上，产物 `dshm-runtime-arm64-v8a.zip` 挂 Release。

工作分两半：

| | 在哪做 | 本机 Windows 能否做 |
|---|---|---|
| **A. 补丁重打 / 减补丁 / 版本变更** | 本机（node 22 + npm 10 + python 都有） | ✅ 能：临时目录 `npm install @deepseek-ai/dsh@0.2.2-rc.1` → `rtbuild.py patch-dsh --record` 逐补丁试打/分类 → 生成新 `pins.json` 与 `locks/dsh-0.2.2-rc.1/` → 改 `build-runtime.sh` 的 `DSH_VERSION` 与 `manifest.json` |
| **B. 完整 rootfs 打包** | CI（Linux runner） | 推上去点 "Run workflow" 即可，不需要本机 Linux |

**所以不是"弄不了"，是"本机做 A、CI 做 B"。用户选择现在都不做。**

### 升级若真要做时的完整清单（备忘）

1. 本机临时目录装 `dsh@0.2.2-rc.1`；
2. `patch-dsh --record` 逐补丁分类：打不上 = 上游已吸收（删）或需重写 hunk；
   打得上 = 记录新 before/after sha256；
3. 新 `runtime/locks/dsh-0.2.2-rc.1/package-lock.json`（npm install 产出）；
4. 改 `build-runtime.sh:40` 的 `DSH_VERSION`；
5. 更新 `assets/runtime/manifest.json` 的 `dshVersion` / `dshPatches`（顺带矫正好 13→11 的旧账）；
6. 本机用**非 record** 的 `patch-dsh` 验证 pins 有效；
7. 推上去 → GitHub Actions 手动触发 `runtime.yml` → 出 158MB runtime zip；
8. 真机装新 runtime 验证引擎能起、`zzzzzz-session-deletion` 的伴生 `core.mjs` seam 还在。

### 顺带

`runtime/patches/pins.json` 目前只登记了 `0.2.1-rc.2` / `0.2.1-rc.4` 两个版本；
`patch-dsh` 不带 `--record` 时，没有对应版本 pin 会**直接拒绝构建**（`unvetted DSH version`）。
所以升级时**必须**先跑 `--record`，不能只改版本号。
