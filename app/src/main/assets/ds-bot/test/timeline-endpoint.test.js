// The `timeline` endpoint wired through projection.install(rt) on a fake rt whose
// displayItems is the REAL official one (parts.js installed on the same fake rt).
// Also proves the channel wiring: `timeline` sits in the READS set (read-only
// hosts answer it like `chat`) and dispatches through `case 'timeline'`.
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { test } from 'node:test'
import { install as installParts } from '../src/host/parts.js'
import { install as installProjection } from '../src/host/projection.js'

const pluginRoot = fileURLToPath(new URL('../', import.meta.url))

// A canned session log: two turns — the first delivers a file with `present`, the
// second writes one and ends in error, with a retry in between and a queued
// message waiting in the agent's inbox.
const EVENTS = [
  { type: 'turn/start', seq: 1, time: 1000, data: { turn: 1 } },
  { type: 'user/message', seq: 2, time: 1100, data: { source: { kind: 'user', rpcId: 'r-1' }, content: [{ type: 'text', text: '帮我做一版 PPT' }] } },
  { type: 'tool/call', seq: 3, time: 1200, data: { turn: 1, callId: 'p1', name: 'present', arguments: { files: [{ path: '报告.pptx', description: '初稿' }] } } },
  { type: 'tool/result', seq: 4, time: 1300, data: { turn: 1, message: { toolCallId: 'p1', content: [{ type: 'text', text: 'Presented /root/w/报告.pptx' }] } } },
  { type: 'deliverables/presented', seq: 5, time: 1400, data: { turn: 1, callId: 'p1', files: [{ path: '报告.pptx', description: '定稿' }] } },
  { type: 'assistant/message', seq: 6, time: 1500, data: { turn: 1, message: { content: [{ type: 'text', text: '做好了。' }] } } },
  { type: 'turn/end', seq: 7, time: 1600, data: { turn: 1, reason: { kind: 'completed' } } },
  { type: 'turn/start', seq: 8, time: 2000, data: { turn: 2 } },
  { type: 'user/message', seq: 9, time: 2100, data: { source: { kind: 'user' }, content: [{ type: 'text', text: '再写个纪要' }] } },
  { type: 'llm/retry', seq: 10, time: 2200, data: { retry: 1, maxRetries: 3, failure: { code: 'rate_limit', message: '限流' } } },
  { type: 'tool/call', seq: 11, time: 2300, data: { turn: 2, callId: 'w1', name: 'write', arguments: { file_path: '纪要.md', content: '# 纪要' } } },
  { type: 'tool/result', seq: 12, time: 2400, data: { turn: 2, message: { toolCallId: 'w1', content: [{ type: 'text', text: 'ok' }] }, meta: { path: '/root/w/纪要.md' } } },
  { type: 'assistant/message', seq: 13, time: 2500, data: { turn: 2, message: { content: [{ type: 'text', text: '纪要好了。' }] } } },
  { type: 'turn/end', seq: 14, time: 2600, data: { turn: 2, reason: { kind: 'error', error: { code: 'boom', message: '后续步骤失败' } } } },
]

function fakeRt() {
  const sessionListeners = []
  const ctx = {
    on(name, handler) { if (name === 'session/event') sessionListeners.push(handler) },
    effect() {},
    get() { return undefined },
    sessionController: {
      page: async (request) => {
        const through = request.throughSeq ?? Infinity
        const before = request.beforeSeq ?? Infinity
        const events = EVENTS.filter(item => item.seq <= through && item.seq < before).slice(-request.maxMessages)
        const last = events.at(-1)?.seq ?? 0
        return { records: events.map(event => ({ event })), hasMore: events.length > 0 && events[0].seq > 1 }
      },
    },
  }
  const state = { exchangeTurns: {} }
  const rt = {
    ctx,
    config: {},
    state,
    warn() {},
    load: async () => {},
    refresh: async () => {},
    isBotSession: id => id === 'sess-1',
    roomOf: id => (id === 'room-1' ? { id: 'room-1', name: '项目群' } : undefined),
    chatOf: botId => (botId === 'bot-1' ? 'sess-1' : botId),
    chatOwners: new Map([['sess-1', 'bot-1']]),
    pastOwners: new Map(),
    botOf: id => (id === 'bot-1' ? { id: 'bot-1', name: '小助手' } : undefined),
    resolveAgent: async id => ({
      session: { seq: 15, header: { cwd: '/root/w' } },
      inbox: { nextTurn: [{ id: 'q-1', message: { content: [{ type: 'text', text: '排队的问题' }], source: { kind: 'user', rpcId: 'r-9' } } }], nextStep: [] },
    }),
    roomProgress: new Map([['call-x', { roomId: 'room-1', replies: [], speaking: 'b2' }]]),
    workDirOf: () => '/root/w',
  }
  installParts(rt)
  installProjection(rt)
  return { rt, sessionListeners }
}

test('the endpoint answers with ascending wrapped rows: official displayItems plus tool/artifact/failure/inbox', async () => {
  const { rt } = fakeRt()
  const answer = await rt.timelineEndpoint({ botId: 'bot-1' })
  assert.equal(answer.sessionId, 'sess-1')
  assert.equal(answer.seq, 14)
  assert.equal(answer.cursor, null)
  const keys = answer.rows.map(row => row.key)
  // Official rows (real displayItems): user bubbles and the closing texts…
  assert.ok(keys.includes('user:2') && keys.includes('user:9'), `缺官方 user 行：${keys}`)
  assert.ok(keys.includes('bot:6') && keys.includes('bot:13'), `缺官方 bot 行：${keys}`)
  // …plus the rows the web client draws in the browser:
  assert.ok(keys.includes('tool:4'), `缺 present 已交付行：${keys}`) // call 行已被 result 行换下
  assert.ok(!keys.includes('tool:3'), '结果到了「正在交付」行还应撤下')
  assert.ok(keys.includes('present:1'), '缺第一轮交付卡')
  assert.ok(keys.includes('retry:10'), '缺重试行')
  assert.ok(keys.includes('turnfail:14'), '缺轮失败行')
  assert.ok(keys.includes('present:2'), '缺第二轮产物卡（produced 口径）')
  assert.ok(keys.includes('inbox:q-1'), '缺收件箱行')
  // Order: all sequenced rows ascend; inbox sits at the tail.
  const seqs = answer.rows.filter(row => row.seq !== null).map(row => row.seq)
  assert.deepEqual(seqs, [...seqs].sort((left, right) => left - right))
  assert.equal(answer.rows.at(-1).kind, 'inbox')

  const card = answer.rows.find(row => row.key === 'present:1')
  assert.deepEqual(card.payload.files.map(file => file.path), ['/root/w/报告.pptx'])
  assert.deepEqual(card.payload.files.map(file => file.description), ['定稿'])
  assert.equal(card.payload.delivered, true)
  const card2 = answer.rows.find(row => row.key === 'present:2')
  assert.deepEqual(card2.payload.files.map(file => file.path), ['/root/w/纪要.md'])
  assert.equal(card2.payload.delivered, false)
  const deliveredRow = answer.rows.find(row => row.key === 'tool:4')
  assert.equal(deliveredRow.payload.state, 'ok')
  assert.equal(deliveredRow.payload.label, '已交付 1 个文件')
})

test('the running row of a present survives only when its result is outside the window', async () => {
  const { rt } = fakeRt()
  // A page that stops before the result: the snapshot edge keeps 「正在交付」.
  const events = EVENTS.slice(0, 3)
  rt.ctx.sessionController.page = async () => ({ records: events.map(event => ({ event })), hasMore: true })
  const answer = await rt.timelineEndpoint({ botId: 'bot-1' })
  const pendingRow = answer.rows.find(row => row.key === 'tool:3')
  assert.ok(pendingRow, '窗口边缘的「正在交付」行应在')
  assert.equal(pendingRow.payload.state, 'running')
  assert.equal(pendingRow.payload.label, '正在交付 1 个文件…')
})

test(`since replay bridges from the event buffer; a gap falls back to the log with a partial hint`, async () => {
  const { rt, sessionListeners } = fakeRt()
  for (const item of EVENTS) for (const handler of sessionListeners) handler({ id: 'sess-1' }, item)

  const answer = await rt.timelineEndpoint({ botId: 'bot-1', since: 7 })
  assert.equal(answer.replayed, true)
  const keys = answer.rows.map(row => row.key)
  assert.ok(keys.includes('retry:10') && keys.includes('turnfail:14') && keys.includes('present:2'), `缓冲重放缺行：${keys}`)
  assert.ok(!keys.includes('user:2'), 'since 之前的行不该重发')
  // Older than the buffer reaches (ring rotated past seq 1): the log replay covers
  // the newest window it can and marks the gap with `partial`.
  for (let at = 100; at < 1000; at += 1) {
    for (const handler of sessionListeners) handler({ id: 'sess-1' }, { type: 'thinking/heartbeat', seq: at, time: at, data: {} })
  }
  const older = await rt.timelineEndpoint({ botId: 'bot-1', since: 0, max: 5 })
  assert.equal(older.partial, true)
})

test('the feed delta publishes only new rows, and a rotated buffer declares reset', async () => {
  const { rt, sessionListeners } = fakeRt()
  const first = rt.timelineDelta('sess-1')
  assert.deepEqual(first.rows, [])
  for (const item of EVENTS) for (const handler of sessionListeners) handler({ id: 'sess-1' }, item)
  const delta = rt.timelineDelta('sess-1')
  assert.ok(delta.rows.length > 0)
  assert.equal(delta.from, 0)
  const again = rt.timelineDelta('sess-1')
  assert.deepEqual(again.rows, [])
  assert.equal(again.from, delta.seq)
})

test('a room answers with room rows and the round state; unknown bots are refused', async () => {
  const { rt } = fakeRt()
  const roomEvents = [
    { type: 'user/message', seq: 1, time: 1000, data: { source: { kind: 'user' }, content: [{ type: 'text', text: '大家看一下' }] } },
    { type: 'tool/call', seq: 2, time: 1100, data: { turn: 1, callId: 'relay-1', name: 'group_relay', arguments: {} } },
    { type: 'tool/result', seq: 3, time: 1200, data: { turn: 1, message: { toolCallId: 'relay-1', content: [{ type: 'text', text: JSON.stringify({ replies: [{ kind: undefined, botId: 'b2', name: '写手', text: '我来' }] }) }] } } },
  ]
  rt.ctx.sessionController.page = async () => ({ records: roomEvents.map(event => ({ event })), hasMore: false })
  const answer = await rt.timelineEndpoint({ roomId: 'room-1' })
  assert.equal(answer.roomId, 'room-1')
  assert.equal(answer.running, true)
  assert.equal(answer.speaking, 'b2')
  assert.deepEqual(answer.rows.map(row => row.key), ['user:1', 'bot:relay-1:0'])
  await assert.rejects(() => rt.timelineEndpoint({ botId: 'ghost' }), /Unknown Bot/)
})

test('channel wiring: timeline is a READ endpoint answered by case timeline', () => {
  const source = readFileSync(`${pluginRoot}src/host/channel.js`, 'utf8')
  const reads = /const READS = new Set\(\[([^\]]*)\]\)/.exec(source)?.[1] ?? ''
  assert.ok(reads.includes("'timeline'"), 'READS 集合没有 timeline（只读主机会拒绝它）')
  assert.ok(/case 'timeline':/.test(source), 'handle 没有分流 timeline 端点')
  const mobile = readFileSync(`${pluginRoot}src/host/mobile.js`, 'utf8')
  assert.ok(/'timeline'/.test(mobile), 'mobile CAPABILITIES 没有声明 timeline')
  const feed = readFileSync(`${pluginRoot}src/host/feed.js`, 'utf8')
  assert.ok(/publish\('timeline'/.test(feed), 'feed 没有发布 timeline 事件')
})
