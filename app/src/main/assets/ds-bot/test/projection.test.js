// Phase 1a host projection tests (docs/phase1-design.md §2, charter
// docs/timeline-parity.md ⑦⑧⑨). Fixtures use the real session-log event shapes.
//
// Run:  node --test --import ./test/stub-resolve.mjs test/
// (from app/src/main/assets/ds-bot/). `stub-resolve` stands in for the
// @deepseek-ai peers a real harness profile would supply.
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { roomItems } from '../src/host/mobile.js'
import {
  artifactRowsOf,
  composeRoomRows,
  composeTimelineRows,
  failureRowsOf,
  inboxRowsOf,
  isPresentedData,
  linkedPathsOf,
  presentedByTurn,
  resolveSessionPath,
  toolRowsOf,
  turnTracesOf,
} from '../src/host/projection.js'

let seq = 0
const event = (type, data, extra = {}) => ({ type, seq: extra.seq ?? ++seq, time: extra.time ?? 1_700_000_000_000 + (extra.seq ?? seq), data })
const turnStart = turn => event('turn/start', { turn })
const turnEnd = (turn, reason = { kind: 'completed' }) => event('turn/end', { turn, reason })
const userMessage = (text, rpcId, attachments = []) => event('user/message', {
  source: { kind: 'user', ...(rpcId ? { rpcId } : {}) },
  content: [...attachments, { type: 'text', text }],
})
const assistant = (turn, text) => event('assistant/message', { turn, message: { content: [{ type: 'text', text }] } })
const toolCall = (turn, callId, name, args) => event('tool/call', { turn, callId, name, arguments: args })
const toolResult = (turn, callId, { meta, isError, text } = {}) => event('tool/result', {
  turn,
  ...(isError ? { isError: true } : {}),
  message: { toolCallId: callId, content: [{ type: 'text', text: text ?? 'ok' }] },
  ...(meta ? { meta } : {}),
})
const presented = (turn, callId, files) => event('deliverables/presented', { turn, callId, files })

const keysOf = rows => rows.map(row => row.key)
const reset = () => { seq = 0 }

// A stand-in for the official displayItems output (the wrapper itself runs against
// the real displayItems in timeline-endpoint.test.js); these keep the pure
// composition cases self-contained.
const base = (kind, text, seq, extra = {}) => ({ kind, text, time: 1_700_000_000_000 + seq, seq, ...extra })

test('rows keep the official vocabulary and keys, ascending by seq', () => {
  reset()
  const events = [
    turnStart(1),
    userMessage('帮我做一版 PPT', 'r-1'),
    assistant(1, '好的'),
    turnEnd(1),
  ]
  const baseItems = [
    base('user', '帮我做一版 PPT', events[1].seq, { rpcId: 'r-1' }),
    base('bot', '好的', events[2].seq),
  ]
  const rows = composeTimelineRows({ events, baseItems })
  assert.deepEqual(keysOf(rows), ['user:2', 'bot:3'])
  assert.equal(rows[0].kind, 'user')
  assert.equal(rows[0].payload.requestId, 'r-1')
  assert.equal(rows[1].kind, 'bot')
})

test('attachment names ride the payload in their original order (attachments before text)', () => {
  reset()
  const events = [
    userMessage('看这张图', 'r-2', [
      { type: 'image', attachment: { name: 'a.png' } },
      { type: 'file', attachment: { name: 'b.pdf' } },
    ]),
  ]
  const rows = composeTimelineRows({ events, baseItems: [base('user', '看这张图', events[0].seq, { rpcId: 'r-2' })] })
  assert.deepEqual(rows[0].payload.blocks, [{ type: 'image', name: 'a.png' }, { type: 'file', name: 'b.pdf' }])
})

test('a plain file write makes no tool row (the activity row shows it); mutations feed artifacts only', () => {
  reset()
  const events = [
    turnStart(1),
    toolCall(1, 'c1', 'write', { file_path: '报告.md', content: '# 报告' }),
    assistant(1, '写好了'),
    toolResult(1, 'c1', { meta: { path: '/root/w/报告.md' } }),
    turnEnd(1),
  ]
  assert.deepEqual(toolRowsOf(events, { botName: '小助手' }), [])
})

test('present makes no tool row in any state — the deliverable card is its one surface (2026-11-14)', () => {
  reset()
  const callOnly = [
    turnStart(3),
    toolCall(3, 'p1', 'present', { files: [{ path: '/root/w/a.docx' }, { path: '/root/w/b.png' }] }),
  ]
  assert.deepEqual(toolRowsOf(callOnly), [], '「正在交付」行已按用户要求移除')

  const settled = [...callOnly.slice(0, 2), toolResult(3, 'p1', { text: 'Presented /root/w/a.docx' })]
  assert.deepEqual(toolRowsOf(settled), [], '「已交付」行已按用户要求移除')

  // The card still carries the delivery (accumulator口径不变).
  const events = [...callOnly, toolResult(3, 'p1', { text: 'Presented' }), presented(3, 'p1', [{ path: '/root/w/a.docx' }, { path: '/root/w/b.png' }]), assistant(3, '交付完成'), turnEnd(3)]
  const rows = composeTimelineRows({ events, baseItems: [base('bot', '交付完成', events[5].seq)] })
  const cards = rows.filter(row => row.kind === 'artifact')
  assert.equal(cards.length, 1)
  assert.deepEqual(cards[0].payload.files.map(file => file.path), ['/root/w/a.docx', '/root/w/b.png'])
  assert.equal(cards[0].payload.delivered, true)
})

test('message_bot names its target; a failed relay carries none', () => {
  reset()
  const rows = toolRowsOf([
    toolCall(1, 'm1', 'message_bot', { to: '同事', text: '在吗' }),
    toolResult(1, 'm1', { text: 'ok' }),
    toolCall(1, 'm2', 'message_bot', { to: '同事', text: '在吗' }),
    toolResult(1, 'm2', { isError: true, text: 'refused' }),
  ], { resolveBot: () => '同事 Bot' })
  assert.equal(rows[0].payload.label, '消息发给了 同事 Bot')
  assert.equal(rows[0].payload.target, '同事')
  assert.equal(rows[1].payload.label, '没能给 同事 发消息')
  assert.equal(rows[1].payload.target, undefined)
})

test('group tools read the group name out of the result, like the web client', () => {
  reset()
  const rows = toolRowsOf([
    toolCall(1, 'g1', 'create_group', { name: '项目群' }),
    toolResult(1, 'g1', { text: 'Created group "项目群"' }),
  ])
  assert.equal(rows[0].payload.label, '创建了群聊：项目群')
  assert.equal(rows[0].payload.target, '项目群')
})

test('llm/retry becomes a retry row with the count and the reason', () => {
  reset()
  const rows = failureRowsOf([
    event('llm/retry', { retry: 2, maxRetries: 4, failure: { code: 'rate_limit', message: '配额限流' } }),
  ])
  assert.deepEqual(keysOf(rows), ['retry:1'])
  assert.equal(rows[0].kind, 'event')
  assert.equal(rows[0].payload.eventKind, 'retry')
  assert.equal(rows[0].payload.text, '模型请求失败，正在重试（2/4）：配额限流')
  assert.equal(rows[0].payload.code, 'rate_limit')
})

test('a turn that ended in error becomes a turnfail row, ahead of the artifact card at the same seq', () => {
  reset()
  const events = [
    turnStart(7),
    toolCall(7, 'c1', 'write', { file_path: 'out.md', content: 'x' }),
    toolResult(7, 'c1', { meta: { path: '/root/w/out.md' } }),
    assistant(7, '写了一半'),
    turnEnd(7, { kind: 'error', error: { code: 'boom', message: '配额耗尽' } }),
  ]
  const baseItems = [base('bot', '写了一半', events[3].seq)]
  const rows = composeTimelineRows({ events, baseItems, cwd: '/root/w' })
  assert.deepEqual(keysOf(rows), ['bot:4', 'turnfail:5', 'present:7'])
  assert.equal(rows[1].payload.eventKind, 'failed')
  assert.equal(rows[1].payload.code, 'boom')
  assert.equal(rows[2].kind, 'artifact')
})

test('an artifact card waits for turn/end, sits at the end seq, and present declarations win', () => {
  reset()
  const events = [
    turnStart(1),
    toolCall(1, 'w1', 'write', { file_path: 'notes.md', content: '# 笔记' }),
    toolResult(1, 'w1', { meta: { path: '/root/w/notes.md' } }),
    presented(1, 'p1', [{ path: '/root/w/报告.pptx', description: '定稿' }]),
    assistant(1, '做完了'),
    turnEnd(1),
  ]
  const rows = composeTimelineRows({ events, baseItems: [base('bot', '做完了', events[4].seq)], cwd: '/root/w' })
  const cards = rows.filter(row => row.kind === 'artifact')
  assert.equal(cards.length, 1)
  assert.equal(cards[0].key, 'present:1')
  assert.equal(cards[0].seq, 6)
  assert.deepEqual(cards[0].payload.files.map(file => file.path), ['/root/w/报告.pptx'])
  assert.equal(cards[0].payload.delivered, true)
})

test('a finished turn without present lists the produced files, extension-agnostic, edits included', () => {
  reset()
  const events = [
    turnStart(2),
    toolCall(2, 'w1', 'write', { file_path: 'report.pptx', content: 'x' }),
    toolResult(2, 'w1', { meta: { path: '/root/w/report.pptx' } }),
    toolCall(2, 'w2', 'write', { file_path: 'old.md', content: 'y' }),
    toolResult(2, 'w2', { meta: { path: '/root/w/old.md' } }),
    assistant(2, '两份都好了'),
    turnEnd(2),
  ]
  const rows = artifactRowsOf(events, { cwd: '/root/w' })
  assert.equal(rows.length, 1)
  assert.deepEqual(rows[0].payload.files.map(file => file.path), ['/root/w/report.pptx', '/root/w/old.md'])
  assert.equal(rows[0].payload.delivered, false)
})

test('a read (view) is not a mutation and makes no artifact', () => {
  reset()
  const events = [
    turnStart(3),
    toolCall(3, 'v1', 'str_replace_editor', { command: 'view', path: 'other.md' }),
    toolResult(3, 'v1'),
    assistant(3, '看完了'),
    turnEnd(3),
  ]
  assert.deepEqual(artifactRowsOf(events, { cwd: '/root/w' }), [])
})

test('multiple presents aggregate: first-seen order, latest description', () => {
  reset()
  const events = [
    turnStart(4),
    presented(4, 'p1', [{ path: '/root/w/a.docx', description: '初稿' }, { path: '/root/w/b.png' }]),
    presented(4, 'p2', [{ path: '/root/w/a.docx', description: '终稿' }]),
    assistant(4, '交付'),
    turnEnd(4),
  ]
  const rows = artifactRowsOf(events, { cwd: null })
  assert.deepEqual(rows[0].payload.files.map(file => file.path), ['/root/w/a.docx', '/root/w/b.png'])
  assert.deepEqual(rows[0].payload.files.map(file => file.description), ['终稿', null])
})

test('declarations after the closing reply stay off the card (presentedForClosing)', () => {
  reset()
  const events = [
    turnStart(5),
    assistant(5, '说完了'),
    presented(5, 'p1', [{ path: '/root/w/late.docx' }]),
    turnEnd(5),
  ]
  assert.deepEqual(artifactRowsOf(events, { cwd: '/root/w' }), [])
})

test('a malformed presented event is dropped wholesale; a bad file entry is skipped', () => {
  reset()
  assert.equal(isPresentedData({ turn: 0, callId: 'x', files: [] }), false)
  assert.equal(isPresentedData({ turn: 1, callId: '', files: [] }), false)
  assert.equal(isPresentedData({ turn: 1, callId: 'x', files: 'no' }), false)
  assert.equal(isPresentedData({ turn: 1, callId: 'x', files: [] }), true)
  const byTurn = presentedByTurn([
    presented(1, 'p1', [{ path: '  ' }, { path: '/root/w/ok.md', description: 42 }, { path: '/root/w/ok.md', description: '好的' }]),
  ])
  assert.deepEqual([...byTurn.get(1).values()].map(file => file.description), ['好的'])
})

test('inbox rows carry slot and requestId, always at the tail', () => {
  reset()
  const events = [turnStart(1), userMessage('排队的问题', 'r-9'), assistant(1, '正在忙')]
  const baseItems = [base('user', '排队的问题', events[1].seq, { rpcId: 'r-9' }), base('bot', '正在忙', events[2].seq)]
  const rows = composeTimelineRows({
    events,
    baseItems,
    inbox: {
      nextTurn: [{ id: 'item-1', message: { content: [{ type: 'text', text: '排队的第二条' }], source: { kind: 'user', rpcId: 'r-10' } } }],
      nextStep: [{ id: 'item-2', message: { content: [{ type: 'text', text: '插话' }], source: { kind: 'user', rpcId: 'r-11' } } }],
    },
  })
  assert.deepEqual(keysOf(rows).slice(-2), ['inbox:item-1', 'inbox:item-2'])
  const queued = rows.at(-2)
  assert.equal(queued.kind, 'inbox')
  assert.equal(queued.seq, null)
  assert.equal(queued.payload.slot, 'next-turn')
  assert.equal(queued.payload.requestId, 'r-10')
  assert.equal(rows.at(-1).payload.slot, 'next-step')
  assert.deepEqual(inboxRowsOf(undefined), [])
})

test('rows without a seq never displace sequenced ones (charter rule ①)', () => {
  reset()
  const events = [userMessage('一号', 'r-1'), userMessage('二号', 'r-2')]
  const baseItems = [base('user', '一号', 1), base('user', '二号', 2)]
  const rows = composeTimelineRows({ events, baseItems, inbox: { nextTurn: [{ id: 'x', message: { content: [{ type: 'text', text: '插队' }] } }] } })
  assert.deepEqual(keysOf(rows), ['user:1', 'user:2', 'inbox:x'])
})

test('hidden exchange turns drop their tool and artifact rows, as displayItems drops their bubbles', () => {
  reset()
  const events = [
    turnStart(9),
    toolCall(9, 'c1', 'message_bot', { to: '同事', text: 'hi' }),
    toolResult(9, 'c1', { text: 'ok' }),
    presented(9, 'p1', [{ path: '/root/w/x.docx' }]),
    assistant(9, '内部交流'),
    turnEnd(9),
  ]
  const rows = composeTimelineRows({ events, baseItems: [], exchangeTurns: [9], cwd: '/root/w' })
  assert.deepEqual(rows, [])
})

test('resolveSessionPath folds relative segments and keeps Windows paths', () => {
  reset()
  assert.equal(resolveSessionPath('a/b.png', '/root/w'), '/root/w/a/b.png')
  assert.equal(resolveSessionPath('./a.png', '/root/w'), '/root/w/a.png')
  assert.equal(resolveSessionPath('a/../ab/c.png', '/root'), '/root/ab/c.png')
  assert.equal(resolveSessionPath('C:\\tmp\\x.png', '/root'), 'C:\\tmp\\x.png')
})

test('linked reply paths resolve; web links drop', () => {
  reset()
  assert.deepEqual(linkedPathsOf('图好了：![chart](chart.png) 和 [文](<out/a.pdf>)，[网](https://x.y) 不算', '/root/w'), ['/root/w/chart.png', '/root/w/out/a.pdf'])
})

test('turn traces sort by start and tolerate a missing start', () => {
  reset()
  const traces = turnTracesOf([turnEnd(2), turnStart(1), turnEnd(1), turnStart(2)])
  // turn 2's end arrived before anything else, so its trace seeds (and ends) there.
  assert.deepEqual(traces.map(trace => trace.turn), [2, 1])
  assert.equal(traces[0].startSeq, 1)
  assert.equal(traces[0].endSeq, 1)
  assert.equal(traces[1].startSeq, 2)
  assert.equal(traces[1].endSeq, 3)
})

test('group rows wrap roomItems: round replies become rows keyed by the relay item id', () => {
  reset()
  const events = [
    userMessage('大家看一下'),
    toolCall(1, 'relay-1', 'group_relay', {}),
    toolResult(1, 'relay-1', { text: JSON.stringify({ replies: [{ kind: 'user', text: '收到' }, { kind: undefined, botId: 'b2', name: '写手', text: '我来', time: 5 }] }) }),
  ]
  const rows = composeRoomRows({ events, baseItems: roomItems(events).items })
  assert.deepEqual(rows.map(row => row.key), ['user:1', 'user:relay-1:0', 'bot:relay-1:1'])
  assert.equal(rows[1].kind, 'user')
  assert.equal(rows[1].payload.text, '收到')
  assert.equal(rows[2].payload.name, '写手')
})
