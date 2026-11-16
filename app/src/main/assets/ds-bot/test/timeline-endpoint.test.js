// The `timeline` endpoint wired through projection.install(rt) on a fake rt whose
// displayItems is the REAL official one (parts.js installed on the same fake rt).
// Also proves the channel wiring: `timeline` sits in the READS set (read-only
// hosts answer it like `chat`) and dispatches through `case 'timeline'`.
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { test } from 'node:test'
import { install as installFeed } from '../src/host/feed.js'
import { install as installParts } from '../src/host/parts.js'
import { install as installProjection } from '../src/host/dshm-timeline.js'

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
  const logLines = []
  const ctx = {
    on(name, handler) { if (name === 'session/event') sessionListeners.push(handler) },
    effect() {},
    get() { return undefined },
    logger: { info: (template, ...args) => logLines.push(String(template).replace('%s', args.join(' '))), warn() {} },
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
      // Only the Bot's own chat queues through its agent inbox; a room's queue is
      // the plugin's roomInbox (see the room tests).
      inbox: id === 'sess-1'
        ? { nextTurn: [{ id: 'q-1', message: { content: [{ type: 'text', text: '排队的问题' }], source: { kind: 'user', rpcId: 'r-9' } } }], nextStep: [] }
        : { nextTurn: [], nextStep: [] },
    }),
    roomProgress: new Map([['call-x', { roomId: 'room-1', replies: [], speaking: 'b2' }]]),
    // The plugin's own room queue (turns.js): posts waiting for the next relay.
    roomInbox: new Map(),
    workDirOf: () => '/root/w',
    // 0.3.6 feed.js requirements.
    onSaved: () => () => {},
    selfOf: () => undefined,
    // 0.3.6 has no feedWatched; the tests walk `timelineDelta` by hand, so the
    // listener must not consume the cursor (old fake feed reported no watchers).
    feedWatched: () => false,
  }
  installParts(rt)
  installProjection(rt)
  installFeed(rt)
  // 0.3.6 feed.js exposes taps as `rt.feedTap`; the tests subscribe through the
  // old name.
  rt.subscribeFeed = rt.feedTap
  return { rt, sessionListeners, logLines }
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
  // …plus the rows the web client draws in the browser. `present` draws NO tool
  // row (user's call, 2026-11-14): the deliverable card is its one surface.
  assert.ok(!keys.includes('tool:3') && !keys.includes('tool:4'), `present 不该再发工具行：${keys}`)
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
  assert.ok(!answer.rows.some(row => row.kind === 'tool' && row.payload.tool === 'present'), 'present 不得以任何工具行形态出现')
})

test('a present whose result is outside the window makes no row at all (no pending row to hang)', async () => {
  const { rt } = fakeRt()
  // A page that stops before the result: with the two-state rows gone, there is
  // nothing to hang and nothing to clean — the card waits for turn/end.
  const events = EVENTS.slice(0, 3)
  rt.ctx.sessionController.page = async () => ({ records: events.map(event => ({ event })), hasMore: true })
  const answer = await rt.timelineEndpoint({ botId: 'bot-1' })
  assert.ok(answer.rows.every(row => row.kind !== 'tool'), '窗口边缘不该有 present 工具行')
  assert.ok(answer.rows.every(row => row.kind !== 'artifact'), '轮没闭合不该有产物卡')
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

test('a running room splices live replies, queues roomInbox rows, and shows the round card from presents only', async () => {
  const { rt } = fakeRt()
  // The round is still running: the relay call is in the log, its result is not,
  // and two posts wait in the plugin's room inbox for the next relay.
  const roomEvents = [
    { type: 'turn/start', seq: 1, time: 1000, data: { turn: 4 } },
    { type: 'user/message', seq: 2, time: 1100, data: { source: { kind: 'user' }, content: [{ type: 'text', text: '大家看一下' }] } },
    { type: 'tool/call', seq: 3, time: 1200, data: { turn: 4, callId: 'relay-9', name: 'group_relay', arguments: {} } },
  ]
  rt.ctx.sessionController.page = async () => ({ records: roomEvents.map(event => ({ event })), hasMore: false })
  rt.roomProgress.set('relay-9', { roomId: 'room-1', replies: [{ kind: undefined, botId: 'b2', name: '写手', text: '先说结论', time: 1300 }], speaking: 'b2' })
  rt.roomInbox.set('room-1', [{ text: '排队的第二条', rpcId: 'r-11' }])
  // 群卡只认 present 声明（2026-11-14 取证定案）：扫盘必须关掉，
  // 端点回来的 changed（私聊产物）一个字都不能进卡。
  const consulted = []
  rt.roomArtifacts = async (roomId, since, options) => {
    consulted.push({ roomId, since, options })
    return {
      files: [{ path: '/root/w/AI行业动态.docx', description: '近两周动态', botId: 'b2', name: '大总管', at: 1000 }],
      changed: [{ path: '/root/w/kitten2.png' }],
    }
  }
  try {
    const answer = await rt.timelineEndpoint({ roomId: 'room-1' })
    assert.deepEqual(answer.rows.map(row => row.key), ['user:2', 'bot:relay-9:0', 'inbox:roominx:room-1:0', 'present:room-1:4'])
    const card = answer.rows[3]
    assert.equal(card.payload.delivered, true)
    assert.deepEqual(card.payload.files.map(file => file.path), ['/root/w/AI行业动态.docx'], '扫盘的 changed 不能进卡')
    assert.equal(card.payload.files[0].description, '近两周动态')
    assert.equal(consulted.length, 1)
    assert.equal(consulted[0].options?.scan, false, '扫盘必须显式关掉')
    assert.equal(consulted[0].since, 0, '不再用「本轮起点」截窗口——改为按轮分桶（否则开新一轮就把上一轮的卡弄没）')
    const live = answer.rows[1]
    assert.equal(live.payload.live, true)
    assert.equal(live.payload.text, '先说结论')
    assert.equal(answer.rows[2].payload.slot, 'next-turn')
    assert.equal(answer.running, true)
    assert.equal(answer.speaking, 'b2')
  } finally {
    rt.roomProgress.delete('relay-9')
    rt.roomInbox.delete('room-1')
    delete rt.roomArtifacts
  }
})

test('each round keeps its own deliverable card (cards persist as rounds move on)', async () => {
  const { rt } = fakeRt()
  const roomEvents = [
    { type: 'turn/start', seq: 1, time: 1000, data: { turn: 4 } },
    { type: 'turn/end', seq: 5, time: 1500, data: { turn: 4 } },
    { type: 'turn/start', seq: 6, time: 5000, data: { turn: 5 } },
    { type: 'turn/end', seq: 9, time: 5500, data: { turn: 5 } },
  ]
  rt.ctx.sessionController.page = async () => ({ records: roomEvents.map(event => ({ event })), hasMore: false })
  rt.roomArtifacts = async () => ({
    files: [
      { path: '/root/w/第一轮.docx', description: '第一轮交付', at: 1200 },
      { path: '/root/w/第二轮.docx', description: '第二轮交付', at: 5200 },
    ],
    changed: [],
  })
  try {
    const answer = await rt.timelineEndpoint({ roomId: 'room-1' })
    const cards = answer.rows.filter(row => row.kind === 'artifact')
    assert.deepEqual(cards.map(row => row.key), ['present:room-1:4', 'present:room-1:5'], '每轮各一张卡')
    assert.deepEqual(cards.map(row => row.seq), [5, 9], '卡贴各自的 turn/end')
    assert.deepEqual(cards[0].payload.files.map(file => file.path), ['/root/w/第一轮.docx'])
    assert.deepEqual(cards[1].payload.files.map(file => file.path), ['/root/w/第二轮.docx'])
  } finally {
    delete rt.roomArtifacts
  }
})

test('a room feed delta carries the live rows plus the round state', async () => {
  const { rt, sessionListeners } = fakeRt()
  rt.feedWatched = () => true
  const seen = []
  rt.subscribeFeed?.((event, data) => { if (event === 'timeline') seen.push(data) })
  for (const handler of sessionListeners) handler({ id: 'room-1' }, { type: 'user/message', seq: 1, time: 1000, data: { source: { kind: 'user' }, content: [{ type: 'text', text: '大家看一下' }] } })
  const frames = seen.filter(frame => frame.roomId === 'room-1')
  assert.ok(frames.length > 0, '房间事件没有发 timeline 增量')
  const last = frames.at(-1)
  assert.deepEqual(last.rows.map(row => row.key), ['user:1'])
  // The fake rt's roomProgress keeps a round for room-1 (speaking 'b2'): the frame
  // carries that state alongside the rows.
  assert.equal(last.running, true)
  assert.equal(last.speaking, 'b2')
})

test('the delta path delivers the deliverable card live: cursor walking over turn/start → presented → turn/end', async () => {
  // 对抗用例（用户报「产物卡要重启才出现」）：事件逐条进缓冲、timelineDelta 逐次
  // 调用模拟客户端游标推进——turn/end 落盘的那一次 delta 必须带着 present:<turn> 卡。
  const { rt, sessionListeners, logLines } = fakeRt()
  const sequence = [
    { type: 'turn/start', seq: 1, time: 1000, data: { turn: 9 } },
    { type: 'user/message', seq: 2, time: 1100, data: { source: { kind: 'user' }, content: [{ type: 'text', text: '做一份 PPT' }] } },
    { type: 'tool/call', seq: 3, time: 1200, data: { turn: 9, callId: 'p9', name: 'present', arguments: { files: [{ path: '报告.pptx', description: '定稿' }] } } },
    { type: 'tool/result', seq: 4, time: 1300, data: { turn: 9, message: { toolCallId: 'p9', content: [{ type: 'text', text: 'Presented /root/w/报告.pptx' }] } } },
    { type: 'deliverables/presented', seq: 5, time: 1400, data: { turn: 9, callId: 'p9', files: [{ path: '报告.pptx', description: '定稿' }] } },
    { type: 'assistant/message', seq: 6, time: 1500, data: { turn: 9, message: { content: [{ type: 'text', text: '做好了。' }] } } },
    { type: 'turn/end', seq: 7, time: 1600, data: { turn: 9, reason: { kind: 'completed' } } },
  ]
  let cardDelta = false
  for (const item of sequence) {
    for (const handler of sessionListeners) handler({ id: 'sess-1' }, item)
    const delta = rt.timelineDelta('sess-1')
    const card = delta.rows.find(row => row.key === 'present:9')
    if (item.type === 'turn/end') {
      assert.ok(card, `turn/end 那次 delta 必须带产物卡（实收：${delta.rows.map(row => row.key).join(', ') || '无'}）`)
      assert.equal(card.seq, 7, '卡贴在该轮 turn/end')
      assert.deepEqual(card.payload.files.map(file => file.path), ['/root/w/报告.pptx'])
      assert.equal(card.payload.delivered, true)
      cardDelta = true
    } else {
      assert.equal(card, undefined, 'turn/end 之前不出卡（轮闭合才出）')
    }
  }
  assert.ok(cardDelta, '整个序列跑完没见到卡')

  // The turn-close diagnostic fired once, at the turn/end delta, and says the card
  // was generated — the on-device evidence line for the delay report.
  const diagnostics = logLines.filter(line => line.includes('CARD t=') && line.includes('turn 9 closed'))
  assert.equal(diagnostics.length, 1, `出卡定点日志应恰好一条：${logLines.join(' | ')}`)
  assert.ok(diagnostics[0].includes('card generated'), `日志应说卡已生成：${diagnostics[0]}`)

  // A repeat walk does not log the turn again (one line per Session and turn).
  rt.timelineDelta('sess-1')
  assert.equal(logLines.filter(line => line.includes('turn 9 closed')).length, 1, '定点日志不该重复')
})

/** A fake rt wired in the *production* install order: feed first, projection after
 * (index.js MODULES puts feed before channel). Under the old arrangement feed's
 * `session/event` listener ran before the record, so its delta lagged one event. */
function fakeFeedFirstRt() {
  const sessionListeners = []
  const logLines = []
  const ctx = {
    on(name, handler) { if (name === 'session/event') sessionListeners.push(handler) },
    effect() {},
    get() { return undefined },
    logger: { info: (template, ...args) => logLines.push(String(template).replace('%s', args.join(' '))), warn() {} },
    sessionController: { page: async () => ({ records: [], hasMore: false }) },
  }
  const rt = {
    ctx,
    config: {},
    state: { exchangeTurns: {} },
    warn() {},
    load: async () => {},
    refresh: async () => {},
    isBotSession: id => id === 'sess-1',
    roomOf: () => undefined,
    chatOwners: new Map([['sess-1', 'bot-1']]),
    pastOwners: new Map(),
    chatOf: id => (id === 'bot-1' ? 'sess-1' : id),
    botOf: id => (id === 'bot-1' ? { id: 'bot-1', name: '小助手' } : undefined),
    resolveAgent: async () => ({ session: { seq: 0, header: { cwd: '/root/w' } }, inbox: { nextTurn: [], nextStep: [] } }),
    roomProgress: new Map(),
    workDirOf: () => '/root/w',
    // 0.3.6 feed.js requirements.
    onSaved: () => () => {},
    selfOf: () => undefined,
    // Production order asserts published frames: the feed looks watched.
    feedWatched: () => true,
  }
  installParts(rt)
  installFeed(rt)
  // 0.3.6 feed.js exposes taps as `rt.feedTap`; the tests subscribe through the
  // old name.
  rt.subscribeFeed = rt.feedTap
  installProjection(rt)
  return { rt, sessionListeners, logLines }
}

/** A turn that declares a deliverable and closes with it. */
const DELIVER_TURN = [
  { type: 'turn/start', seq: 1, time: 1000, data: { turn: 9 } },
  { type: 'user/message', seq: 2, time: 1100, data: { source: { kind: 'user' }, content: [{ type: 'text', text: '做一份 PPT' }] } },
  { type: 'tool/call', seq: 3, time: 1200, data: { turn: 9, callId: 'p9', name: 'present', arguments: { files: [{ path: '报告.pptx', description: '定稿' }] } } },
  { type: 'tool/result', seq: 4, time: 1300, data: { turn: 9, message: { toolCallId: 'p9', content: [{ type: 'text', text: 'Presented /root/w/报告.pptx' }] } } },
  { type: 'deliverables/presented', seq: 5, time: 1400, data: { turn: 9, callId: 'p9', files: [{ path: '报告.pptx', description: '定稿' }] } },
  { type: 'assistant/message', seq: 6, time: 1500, data: { turn: 9, message: { content: [{ type: 'text', text: '做好了。' }] } } },
  { type: 'turn/end', seq: 7, time: 1600, data: { turn: 9, reason: { kind: 'completed' } } },
]

test('regression: in the production install order the turn/end frame itself carries the deliverable card', () => {
  // 用户真机报「做 PPT，代码产物卡立即显示、产物卡不显示」。根因是装配顺序：
  // index.js 的 MODULES 里 feed 在 channel 之前 → feed 的 session/event 监听器先注册、
  // 先执行，旧实现里它在「当前事件写进缓冲之前」就算增量 → 增量永远慢一拍；而产物卡的
  // 行骑在 turn/end 的 seq 上，于是必须等下一条消息触发的那一帧才被顺带带出。
  const { rt, sessionListeners, logLines } = fakeFeedFirstRt()
  const frames = []
  rt.subscribeFeed((event, data) => { if (event === 'timeline') frames.push(data) })

  for (const item of DELIVER_TURN) for (const handler of sessionListeners) handler({ id: 'sess-1' }, item)

  // 卡必须在 turn/end 触发的那一帧就发出去——不依赖任何后续事件。
  const cardFrame = frames.find(frame => frame.sessionId === 'sess-1' && frame.rows.some(row => row.key === 'present:9'))
  assert.ok(cardFrame, `turn/end 之后没有任何一帧带产物卡（帧内容：${JSON.stringify(frames.map(frame => frame.rows.map(row => row.key)))})`)
  const card = cardFrame.rows.find(row => row.key === 'present:9')
  assert.equal(card.seq, 7, '卡贴在该轮 turn/end')
  assert.deepEqual(card.payload.files.map(file => file.path), ['/root/w/报告.pptx'])
  assert.equal(card.payload.delivered, true)
  assert.ok(!frames.some(frame => frame.sessionId === 'sess-1' && frame.reset === true), '正常路径不该触发兜底重读')

  // 出卡定点日志也随这一帧落下（真机取证线）。
  const diagnostics = logLines.filter(line => line.includes('CARD t=') && line.includes('turn 9 closed'))
  assert.equal(diagnostics.length, 1, `出卡定点日志应恰好一条：${logLines.join(' | ')}`)
  assert.ok(diagnostics[0].includes('card generated'), `日志应说卡已生成：${diagnostics[0]}`)
})

test('fallback: a card that the delta drops is recovered by a resync frame at turn/end', () => {
  // 兜底路径（防以后行序/游标再出岔）：本轮有卡、但这一帧没带上它 → turn/end 收口时
  // 必须发一帧 reset，让客户端重读快照。这里用猴补丁让某一次 delta 少发卡来模拟。
  const { rt, sessionListeners } = fakeFeedFirstRt()
  const frames = []
  rt.subscribeFeed((event, data) => { if (event === 'timeline') frames.push(data) })

  const realDelta = rt.timelineDelta
  let dropped = false
  rt.timelineDelta = sessionId => {
    const delta = realDelta(sessionId)
    if (!dropped && delta.rows.some(row => row.kind === 'artifact')) {
      dropped = true
      return { ...delta, rows: delta.rows.filter(row => row.kind !== 'artifact') }
    }
    return delta
  }

  for (const item of DELIVER_TURN) for (const handler of sessionListeners) handler({ id: 'sess-1' }, item)

  assert.ok(dropped, '测试前提：产物卡确实被漏发过一次')
  const reset = frames.find(frame => frame.sessionId === 'sess-1' && frame.reset === true)
  assert.ok(reset, `卡被漏发后 turn/end 兜底应发一帧 reset（帧：${JSON.stringify(frames.map(frame => ({ reset: !!frame.reset, keys: frame.rows?.map(row => row.key) })))}）`)
})

test('regression: a Session with no routable identity publishes no timeline frame (no cross-chat leak)', () => {
  // 真机回归（2026-11-15）：「群聊对应的 bot 回答出现在私聊界面」。原因是帧的发布从
  // feed.js 搬到 projection 时丢了「必须有 botId」这道闸 —— 没有身份字段的帧到了
  // 客户端会被**任何打开着的聊天**接受。成员会话（以及已删除 Bot 的旧段）正是这种
  // 「是 bot 会话、但不对应任何打开的私聊」的会话，必须一帧都不发。
  const { rt, sessionListeners } = fakeFeedFirstRt()
  const frames = []
  rt.subscribeFeed((event, data) => { if (event === 'timeline') frames.push(data) })
  rt.isBotSession = id => id === 'sess-1' || id === 'sess-ghost'
  for (const handler of sessionListeners) {
    handler({ id: 'sess-ghost' }, { type: 'user/message', seq: 1, time: 1000, data: { source: { kind: 'user' }, content: [{ type: 'text', text: '群里的活' }] } })
  }
  assert.deepEqual(frames, [], `无身份会话不该发 timeline 帧：${JSON.stringify(frames)}`)
})

test('resync frames carry their routing identity (and an unknown Session sends none)', () => {
  const { rt } = fakeFeedFirstRt()
  const frames = []
  rt.subscribeFeed((event, data) => { if (event === 'timeline') frames.push(data) })
  rt.timelineResync('sess-1')
  assert.equal(frames.length, 1)
  assert.equal(frames[0].botId, 'bot-1', '私聊 resync 必须带 botId')
  assert.equal(frames[0].reset, true)
  rt.timelineResync('sess-ghost')
  assert.equal(frames.length, 1, '无身份会话的 resync 不该发帧')
})

test('4a: every durable event rides the feed with its seq; only fold-relevant types carry data', () => {
  const { rt, sessionListeners } = fakeFeedFirstRt()
  const frames = []
  rt.subscribeFeed((event, data) => { if (event === 'session') frames.push(data) })
  const events = [
    { type: 'turn/start', seq: 1, time: 1000, data: { turn: 1 } },
    { type: 'thinking/delta', seq: 2, time: 1001, data: { huge: 'x'.repeat(1000) } },
    { type: 'user/message', seq: 3, time: 1002, data: { source: { kind: 'user' }, content: [{ type: 'text', text: 'hi' }] } },
  ]
  for (const item of events) for (const handler of sessionListeners) handler({ id: 'sess-1' }, item)
  const sessionFrames = frames.filter(frame => frame.sessionId === 'sess-1')
  assert.deepEqual(sessionFrames.map(frame => frame.seq), [1, 2, 3], '每个 durable 事件都要转发（连续性可判定）')
  assert.equal(sessionFrames[0].type, 'turn/start')
  assert.ok(sessionFrames[0].data !== undefined, '折叠需要的类型带 data')
  assert.equal(sessionFrames[1].data, undefined, '不需要折叠的类型只发占位，不带大 payload')
  assert.equal(sessionFrames[1].type, 'thinking/delta')
  assert.deepEqual(sessionFrames[2].data.content[0].text, 'hi')
})

test('4a: the events query fills a gap from the projection buffer', async () => {
  const { rt, sessionListeners } = fakeFeedFirstRt()
  const events = [
    { type: 'turn/start', seq: 10, time: 1000, data: { turn: 1 } },
    { type: 'user/message', seq: 11, time: 1001, data: { source: { kind: 'user' }, content: [{ type: 'text', text: 'a' }] } },
    { type: 'assistant/message', seq: 12, time: 1002, data: { turn: 1, message: { content: [{ type: 'text', text: 'b' }] } } },
  ]
  for (const item of events) for (const handler of sessionListeners) handler({ id: 'sess-1' }, item)
  const answer = await rt.sessionEventsQuery({ sessionId: 'sess-1', from: 11, through: 12 })
  assert.deepEqual(answer.events.map(event => event.seq), [11, 12])
  assert.equal(answer.oldestSeq, 11)
  assert.equal(answer.newestSeq, 12)
})

test('4a: the events query pages the Session log when the buffer no longer holds the range', async () => {
  const { rt } = fakeFeedFirstRt()
  const LOG = [
    { type: 'user/message', seq: 5, time: 1005, data: { source: { kind: 'user' }, content: [{ type: 'text', text: 'old' }] } },
    { type: 'assistant/message', seq: 6, time: 1006, data: { turn: 1, message: { content: [{ type: 'text', text: 'old reply' }] } } },
  ]
  rt.ctx.sessionController.page = async request => {
    const before = request.beforeSeq ?? Infinity
    const through = request.throughSeq ?? Infinity
    const visible = LOG.filter(item => item.seq < before && item.seq <= through)
    const window = visible.slice(-(request.maxMessages ?? 10))
    return { records: window.map(event => ({ event })), hasMore: visible.length > window.length }
  }
  const answer = await rt.sessionEventsQuery({ sessionId: 'sess-1', from: 5, through: 6 })
  assert.deepEqual(answer.events.map(event => event.seq), [5, 6])
  assert.equal(answer.events[0].type, 'user/message')
  assert.equal(answer.events[1].type, 'assistant/message')
})

test('4b: the forwarded payload types cover every event type the folds read', () => {
  // 地基自校验：折叠逻辑（parts.js / projection.js）读到的每一种事件类型，都必须在
  // TIMELINE_EVENT_TYPES 里 —— 否则客户端只拿到占位帧，折叠不出对应的行。以后有人在
  // 折叠里加了新类型却忘了改转发清单，这条会立刻红（而不是等真机出问题）。
  const parts = readFileSync(`${pluginRoot}src/host/parts.js`, 'utf8')
  const projection = readFileSync(`${pluginRoot}src/host/dshm-timeline.js`, 'utf8')
  const declared = /export const TIMELINE_EVENT_TYPES = new Set\(\[([\s\S]*?)\]\)/.exec(projection)?.[1] ?? ''
  const forwarded = new Set([...declared.matchAll(/'([^']+)'/g)].map(match => match[1]))
  assert.ok(forwarded.size >= 10, `转发清单解析失败：${declared}`)
  const read = new Set()
  for (const source of [parts, projection]) {
    for (const match of source.matchAll(/event\??\.type\s*(?:===|!==)\s*'([^']+)'/g)) read.add(match[1])
  }
  const missing = [...read].filter(type => !forwarded.has(type))
  assert.deepEqual(missing, [], `折叠读了但没转发 payload 的事件类型：${missing.join(', ')}`)
})

test('4c 影子双轨：快照回 cwd 与事件区间（客户端要比同一批事件）', async () => {
  const { rt, sessionListeners } = fakeFeedFirstRt()
  const events = [
    { type: 'turn/start', seq: 10, time: 1000, data: { turn: 1 } },
    { type: 'user/message', seq: 11, time: 1001, data: { source: { kind: 'user' }, content: [{ type: 'text', text: 'a' }] } },
    { type: 'assistant/message', seq: 12, time: 1002, data: { turn: 1, message: { content: [{ type: 'text', text: 'b' }] } } },
  ]
  for (const item of events) for (const handler of sessionListeners) handler({ id: 'sess-1' }, item)
  rt.ctx.sessionController.page = async () => ({ records: events.map(event => ({ event })), hasMore: false })
  const answer = await rt.timelineEndpoint({ botId: 'bot-1' })
  assert.equal(answer.cwd, '/root/w', '快照要回 cwd（客户端折叠产物卡路径要用）')
  assert.equal(answer.eventFrom, 10, '快照要回这批行覆盖的事件区间起点')
  assert.equal(answer.eventTo, 12, '快照要回这批行覆盖的事件区间终点')
  assert.deepEqual(answer.exchangeTurns, [], '快照要回本会话被隐藏的轮')
})

test('4c 影子双轨：同一批事件上，客户端折叠与宿主逐行一致', async () => {
  // 影子双轨的**契约**：宿主与客户端折叠对同一批事件必须逐行相等。
  // 这里直接验宿主侧：同一批事件，宿主自己的两行快照与增量给出的行必须一致。
  const { rt, sessionListeners } = fakeFeedFirstRt()
  // 这个用例手动走 `timelineDelta`，观察者视角的增量发布要让开游标。
  rt.feedWatched = () => false
  const events = [
    { type: 'turn/start', seq: 1, time: 1000, data: { turn: 1 } },
    { type: 'user/message', seq: 2, time: 1100, data: { source: { kind: 'user' }, content: [{ type: 'text', text: '你好' }] } },
    { type: 'assistant/message', seq: 3, time: 1200, data: { turn: 1, message: { content: [{ type: 'text', text: '在的' }] } } },
    { type: 'turn/end', seq: 4, time: 1300, data: { turn: 1 } },
  ]
  for (const item of events) for (const handler of sessionListeners) handler({ id: 'sess-1' }, item)
  rt.ctx.sessionController.page = async () => ({ records: events.map(event => ({ event })), hasMore: false })
  const snapshot = await rt.timelineEndpoint({ botId: 'bot-1' })
  const delta = rt.timelineDelta('sess-1')
  const shape = row => `${row.key}|${row.kind}|${row.seq}|${row.turn}|${row.timeMs}|${JSON.stringify(row.payload)}`
  assert.deepEqual(
    delta.rows.map(shape).sort(),
    snapshot.rows.map(shape).sort(),
    '同一批事件：增量与快照给出的行必须一致（影子双轨的前提）',
  )
})

test('channel wiring: timeline is a READ endpoint answered by case timeline', () => {
  const source = readFileSync(`${pluginRoot}src/host/channel.js`, 'utf8')
  const reads = /const READS = new Set\(\[([^\]]*)\]\)/.exec(source)?.[1] ?? ''
  assert.ok(reads.includes("'timeline'"), 'READS 集合没有 timeline（只读主机会拒绝它）')
  assert.ok(/case 'timeline':/.test(source), 'handle 没有分流 timeline 端点')
  // `hello` 由 mobile.js 回答，我们的模块在运行时补声明 timeline/events（不改上游源码）。
  const mobile = readFileSync(`${pluginRoot}src/host/mobile.js`, 'utf8')
  const capabilities = readFileSync(`${pluginRoot}src/host/dshm-timeline.js`, 'utf8')
  assert.ok(/\[.*'timeline', 'events'/.test(capabilities), 'hello 能力没有补声明 timeline/events')
  // 兼容自证：旧群聊端点原样保留（官方网页端与旧管线都还在用它们）。
  assert.ok(/async function roomChat\(/.test(mobile), 'room-chat 端点丢了')
  assert.ok(/case 'room-chat': return roomChat\(body\)/.test(mobile), 'room-chat 分流丢了')
  // 时间线帧的发布者是 projection（日志拥有者），不是 feed：feed 的 session/event
  // 监听器先于记录注册，增量会慢一拍（曾把产物卡拖到下一条消息才出现）。
  const projection = readFileSync(`${pluginRoot}src/host/dshm-timeline.js`, 'utf8')
  assert.ok(/publishFeed\?\.\('timeline'/.test(projection), 'projection 没有发布 timeline 事件')
  assert.ok(/roomId: sessionId/.test(projection), 'projection 的房间分支没有发群聊 timeline 增量')
  const feed = readFileSync(`${pluginRoot}src/host/feed.js`, 'utf8')
  assert.ok(!/publish\('timeline'/.test(feed), 'feed 不该再发布 timeline（已归 projection 日志拥有者）')
  const channel = readFileSync(`${pluginRoot}src/host/channel.js`, 'utf8')
  assert.ok(/case 'room-progress':/.test(channel) && /case 'room-chat':/.test(channel), 'channel 的旧群聊端点丢了')
  // 回声认领：0.3.6 的 send 原本不收 requestId，导致客户端认领不了自己刚发的气泡
  // （每条消息画两遍，真机 2026-11-16）。适配器必须把它透传给 sendUser。
  assert.ok(/body\.requestId/.test(channel), 'send 没有透传客户端 requestId（回声会重复）')
  assert.ok(/mode === 'steer' \? 'steer' : 'queue'/.test(channel), 'sendUser 丢了 steer 模式（插话）')
})

// ---------------------------------------------------------------------------
// ② Parts pagination (private chat): `cursor.part` walks a Bot's parts, each its
// own Session with its own seq space; `cursor.beforeSeq` walks inside one part.

const PARTS = [
  { sessionId: 's0', number: 1, startedAt: 1000 },
  { sessionId: 's1', number: 2, startedAt: 2000 },
  { sessionId: 's2', number: 3, startedAt: 3000 },
]

const PART_EVENTS = {
  s0: [
    { type: 'user/message', seq: 1, time: 1001, data: { source: { kind: 'user' }, content: [{ type: 'text', text: '最早的问题' }] } },
    { type: 'assistant/message', seq: 2, time: 1002, data: { turn: 1, message: { content: [{ type: 'text', text: '最早的回复' }] } } },
  ],
  s1: [
    { type: 'user/message', seq: 10, time: 2001, data: { source: { kind: 'user' }, content: [{ type: 'text', text: '第二轮问题' }] } },
    { type: 'assistant/message', seq: 11, time: 2002, data: { turn: 2, message: { content: [{ type: 'text', text: '第二轮回复' }] } } },
  ],
  s2: [
    { type: 'user/message', seq: 20, time: 3001, data: { source: { kind: 'user' }, content: [{ type: 'text', text: '当前问题' }] } },
    { type: 'assistant/message', seq: 21, time: 3002, data: { turn: 3, message: { content: [{ type: 'text', text: '当前回复' }] } } },
  ],
}

/** A Bot with several parts: each part is its own Session/log. */
function fakePartsRt(parts = PARTS) {
  const sessionListeners = []
  const ctx = {
    on(name, handler) { if (name === 'session/event') sessionListeners.push(handler) },
    effect() {},
    get() { return undefined },
    logger: { info() {}, warn() {} },
    sessionController: {
      page: async (request) => {
        const all = PART_EVENTS[request.address.sessionId] ?? []
        const through = request.throughSeq ?? Infinity
        const before = request.beforeSeq ?? Infinity
        const visible = all.filter(item => item.seq <= through && item.seq < before)
        const window = visible.slice(-request.maxMessages)
        return { records: window.map(event => ({ event })), hasMore: visible.length > window.length }
      },
    },
  }
  const state = { exchangeTurns: {}, questions: {}, views: {}, bots: {}, rooms: {}, exchanges: [], prefs: {} }
  const rt = {
    ctx,
    config: {},
    state,
    warn() {},
    save: async () => {},
    load: async () => {},
    refresh: async () => {},
    readOnly: () => undefined,
    isBotSession: id => id in PART_EVENTS,
    roomOf: () => undefined,
    chatOf: () => 's2',
    chatOwners: new Map([['s2', 'bot-1']]),
    pastOwners: new Map(),
    botOf: id => (id === 'bot-1' ? { id: 'bot-1', name: '小助手' } : undefined),
    selfOf: () => undefined,
    resolveAgent: async id => ({
      session: { seq: (PART_EVENTS[id]?.at(-1)?.seq ?? 0) + 1, header: { cwd: '/root/w' } },
      inbox: { nextTurn: [], nextStep: [] },
    }),
    roomProgress: new Map(),
    roomInbox: new Map(),
    workDirOf: () => '/root/w',
    switchJobs: () => [],
  }
  installParts(rt)
  // The fake's part list, standing in for bot.segments-derived partsOf.
  rt.partsOf = () => parts
  installProjection(rt)
  return { rt, sessionListeners }
}

test('parts pagination: the current part, then each earlier part behind a divider, then null', async () => {
  const { rt } = fakePartsRt()
  // Current part (index 2): its page carries its own opening line (index > 0).
  const first = await rt.timelineEndpoint({ botId: 'bot-1' })
  assert.equal(first.sessionId, 's2', 'the chat is still named by its current part')
  assert.deepEqual(first.rows.map(row => row.key), ['divider:part:2', 'user:20', 'bot:21'])
  assert.equal(first.rows[0].kind, 'divider')
  assert.equal(first.rows[0].seq, null)
  assert.equal(first.rows[0].timeMs, 3000, '分隔行带该 part 的 startedAt')
  assert.deepEqual(first.cursor, { part: 1 }, '当前 part 翻完 → 上一个 part')

  // Entering the middle part: its own divider first (the frozen key), its rows.
  const middle = await rt.timelineEndpoint({ botId: 'bot-1', cursor: { part: 1 } })
  assert.deepEqual(middle.rows.map(row => row.key), ['divider:part:1', 'user:10', 'bot:11'])
  assert.equal(middle.rows[0].timeMs, 2000)
  assert.deepEqual(middle.cursor, { part: 0 })

  // The oldest part: nothing before it, so no divider and no cursor.
  const oldest = await rt.timelineEndpoint({ botId: 'bot-1', cursor: { part: 0 } })
  assert.deepEqual(oldest.rows.map(row => row.key), ['user:1', 'bot:2'])
  assert.equal(oldest.rows[0].payload.text, '最早的问题')
  assert.equal(oldest.cursor, null, '没有更早的 part 了')
})

test('parts pagination: beforeSeq walks one part first, then the cursor steps to the previous part', async () => {
  const { rt } = fakePartsRt([
    { sessionId: 's0', number: 1, startedAt: 1000 },
    { sessionId: 's2', number: 2, startedAt: 3000 },
  ])
  // A page of one event: the current part has two, so the first page is partial.
  const page1 = await rt.timelineEndpoint({ botId: 'bot-1', max: 1 })
  assert.deepEqual(page1.rows.map(row => row.key), ['bot:21'], '本页只给最后一条')
  assert.deepEqual(page1.cursor, { part: 1, beforeSeq: 21 }, '同一 part 内继续往前')

  // Walking back inside the part lands on its oldest event and closes the part:
  // now its opening line appears, and the cursor steps to the previous part.
  const page2 = await rt.timelineEndpoint({ botId: 'bot-1', max: 1, cursor: { part: 1, beforeSeq: 21 } })
  assert.deepEqual(page2.rows.map(row => row.key), ['divider:part:1', 'user:20'])
  assert.deepEqual(page2.cursor, { part: 0 })
})

test('parts pagination: a cursor pointing outside the parts is an empty, terminal page', async () => {
  const { rt } = fakePartsRt()
  for (const part of [-1, 3, 99]) {
    const answer = await rt.timelineEndpoint({ botId: 'bot-1', cursor: { part } })
    assert.deepEqual(answer.rows, [])
    assert.equal(answer.cursor, null)
  }
})

test('parts pagination: an earlier part is its own seq space and its own history', async () => {
  const { rt, sessionListeners } = fakePartsRt()
  // The live buffer (and therefore `since`) covers the current part only.
  for (const item of PART_EVENTS.s2) for (const handler of sessionListeners) handler({ id: 's2' }, item)
  const replayed = await rt.timelineEndpoint({ botId: 'bot-1', since: 20 })
  assert.equal(replayed.replayed, true)
  assert.deepEqual(replayed.rows.map(row => row.key), ['bot:21'], 'since 只覆盖当前 part')
  // Earlier parts never appear in a `since` replay, only through a part cursor.
  const page = await rt.timelineEndpoint({ botId: 'bot-1', cursor: { part: 0 } })
  assert.deepEqual(page.rows.map(row => row.key), ['user:1', 'bot:2'])
})
