// Oracle generator for the client-side fold (四期 4b).
//
// Runs the **host's** real fold (`parts.js` `displayItems` + `projection.js`
// `composeTimelineRows`) over canned event lists and prints the resulting rows as
// JSON. The Kotlin fold (`TimelineFold.kt`) must reproduce these rows exactly; the
// generated table is embedded in
// `app/src/test/java/com/dshm/bot/ui/chat/TimelineFoldTest.kt`.
//
// Regenerate after changing the fold semantics:
//   node test/gen-display-items.mjs
import { install as installParts } from '../src/host/parts.js'
import { composeTimelineRows } from '../src/host/projection.js'

/** A minimal rt: displayItems only needs `state.exchangeTurns` and a `ctx.get`. */
function fakeRt(exchangeTurns = {}) {
  const rt = {
    ctx: {
      get: () => undefined,
      effect: () => {},
      on: () => {},
      sessionController: {},
      logger: { info() {}, warn() {} },
    },
    config: {},
    state: { exchangeTurns },
    chatOwners: new Map([['sess-1', 'bot-1']]),
    pastOwners: new Map(),
    botOf: () => ({ id: 'bot-1', name: '小助手' }),
  }
  installParts(rt)
  return rt
}

const text = value => ({ type: 'text', text: value })
const block = (type, extra = {}) => ({ type, ...extra })

const SCENARIOS = {
  // 一轮完整的问答：turn 上下文、user 行（seq/turn）、bot 行。
  basicTurn: [
    { type: 'turn/start', seq: 1, time: 1000, data: { turn: 1 } },
    { type: 'user/message', seq: 2, time: 1100, data: { source: { kind: 'user' }, content: [text('你好')] } },
    { type: 'assistant/message', seq: 3, time: 1200, data: { turn: 1, message: { content: [text('在的')] } } },
    { type: 'turn/end', seq: 4, time: 1300, data: { turn: 1 } },
  ],
  // 一条 assistant 消息里的多个文本块 → 每块一行；空白块不出行。
  multiBlock: [
    { type: 'turn/start', seq: 1, time: 1000, data: { turn: 3 } },
    { type: 'assistant/message', seq: 2, time: 1100, data: { turn: 3, message: { content: [text('第一段'), text('   '), { type: 'reasoning', text: '想一想' }, text('第二段')] } } },
  ],
  // 被隐藏的 exchange 轮（Bot 之间互答）：assistant 行整轮不画，user 行照画。
  hiddenTurn: [
    { type: 'turn/start', seq: 1, time: 1000, data: { turn: 2 } },
    { type: 'user/message', seq: 2, time: 1100, data: { source: { kind: 'user' }, content: [text('用户消息')] } },
    { type: 'assistant/message', seq: 3, time: 1200, data: { turn: 2, message: { content: [text('不该出现')] } } },
  ],
  // 附件与回显：images 计数 + rpcId（本地乐观认领键）+ 附件块名。
  withAttachments: [
    { type: 'turn/start', seq: 1, time: 1000, data: { turn: 1 } },
    { type: 'user/message', seq: 2, time: 1100, data: { source: { kind: 'user', rpcId: 'r-9' }, content: [text('看这两张'), block('image'), block('image', { attachment: { name: 'a.png' } })] } },
  ],
  // 多个 text 块 → 按 `\n` 连接（`textOf`），整体 trim。
  multiTextUser: [
    { type: 'turn/start', seq: 1, time: 1000, data: { turn: 1 } },
    { type: 'user/message', seq: 2, time: 1100, data: { source: { kind: 'user' }, content: [text('第一行'), text('第二行')] } },
  ],
  // 首尾空白块：trim 后只留实义文本。
  trimmedUser: [
    { type: 'turn/start', seq: 1, time: 1000, data: { turn: 1 } },
    { type: 'user/message', seq: 2, time: 1100, data: { source: { kind: 'user' }, content: [text('  '), text('实义')] } },
  ],
  // 重试行：llm/retry 的文案（retry/maxRetries/失败原因）。
  retryRow: [
    { type: 'turn/start', seq: 1, time: 1000, data: { turn: 1 } },
    { type: 'llm/retry', seq: 2, time: 1100, data: { turn: 1, retry: 2, maxRetries: 5, failure: { code: 'rate_limit', message: '限流' } } },
  ],
  // 轮失败：turn/end 的 reason.kind === 'error'。
  turnFailed: [
    { type: 'turn/start', seq: 1, time: 1000, data: { turn: 1 } },
    { type: 'turn/end', seq: 2, time: 1100, data: { turn: 1, reason: { kind: 'error', error: { code: 'boom', message: '后续步骤失败' } } } },
  ],
  // 改动卡：workspace/changes → 一行轻量标记（摘要由客户端另外拉）。
  changesRow: [
    { type: 'turn/start', seq: 1, time: 1000, data: { turn: 1 } },
    { type: 'workspace/changes', seq: 2, time: 1100, data: { turn: 1 } },
  ],
  // 交付卡：deliverables/presented → present:<turn> 行（贴在 turn/end 的 seq 上）。
  deliverableCard: [
    { type: 'turn/start', seq: 1, time: 1000, data: { turn: 1 } },
    { type: 'user/message', seq: 2, time: 1100, data: { source: { kind: 'user' }, content: [text('做一份 PPT')] } },
    { type: 'deliverables/presented', seq: 3, time: 1200, data: { turn: 1, callId: 'p1', files: [{ path: '报告.pptx', description: '定稿' }] } },
    { type: 'assistant/message', seq: 4, time: 1300, data: { turn: 1, message: { content: [text('做好了')] } } },
    { type: 'turn/end', seq: 5, time: 1400, data: { turn: 1 } },
  ],
  // 工具行：message_bot 的成功/失败两种文案。
  toolRow: [
    { type: 'turn/start', seq: 1, time: 1000, data: { turn: 1 } },
    { type: 'tool/call', seq: 2, time: 1100, data: { turn: 1, callId: 'c1', name: 'message_bot', arguments: { to: '小助手' } } },
    { type: 'tool/result', seq: 3, time: 1200, data: { turn: 1, message: { toolCallId: 'c1', content: [text('ok')] } } },
  ],
  // produced 口径：该轮**没有** present 声明，但工具写了文件 → 卡上列出写过的文件
  //（描述为空、路径按 cwd 解析）。
  producedCard: [
    { type: 'turn/start', seq: 1, time: 1000, data: { turn: 1 } },
    { type: 'user/message', seq: 2, time: 1100, data: { source: { kind: 'user' }, content: [text('写个文件')] } },
    { type: 'tool/call', seq: 3, time: 1200, data: { turn: 1, callId: 'w1', name: 'write', arguments: { file_path: '纪要.md', content: '# 纪要' } } },
    { type: 'tool/result', seq: 4, time: 1300, data: { turn: 1, message: { toolCallId: 'w1', content: [text('ok')] }, meta: { path: '/root/w/纪要.md' } } },
    { type: 'assistant/message', seq: 5, time: 1400, data: { turn: 1, message: { content: [text('写好了')] } } },
    { type: 'turn/end', seq: 6, time: 1500, data: { turn: 1 } },
  ],
  // produced 口径 + 相对路径：没有 meta.path 时按 cwd 解析参数路径。
  producedRelativePath: [
    { type: 'turn/start', seq: 1, time: 1000, data: { turn: 1 } },
    { type: 'tool/call', seq: 2, time: 1100, data: { turn: 1, callId: 'w1', name: 'write', arguments: { file_path: '笔记.md' } } },
    { type: 'tool/result', seq: 3, time: 1200, data: { turn: 1, message: { toolCallId: 'w1', content: [text('ok')] } } },
    { type: 'turn/end', seq: 4, time: 1300, data: { turn: 1 } },
  ],
  // 声明在闭合那一句之后 → 不上卡（`presentedForClosing` 边界）。
  presentedAfterClosing: [
    { type: 'turn/start', seq: 1, time: 1000, data: { turn: 1 } },
    { type: 'assistant/message', seq: 2, time: 1100, data: { turn: 1, message: { content: [text('好了')] } } },
    { type: 'deliverables/presented', seq: 3, time: 1200, data: { turn: 1, callId: 'p1', files: [{ path: '晚到的.pdf', description: '太晚' }] } },
    { type: 'turn/end', seq: 4, time: 1300, data: { turn: 1 } },
  ],
}

const out = {}
for (const [name, events] of Object.entries(SCENARIOS)) {
  const rt = fakeRt(name === 'hiddenTurn' ? { 'sess-1': [2] } : {})
  const baseItems = rt.displayItems('sess-1', events, undefined)
  out[name] = composeTimelineRows({
    events,
    baseItems,
    exchangeTurns: name === 'hiddenTurn' ? [2] : [],
    resolveBot: ref => (ref === 'bot-1' ? '小助手' : ref),
    botName: '小助手',
    cwd: '/root/w',
  })
}
console.log(JSON.stringify(out, null, 2))
