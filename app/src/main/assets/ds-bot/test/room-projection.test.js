// Phase 1b/1c group-chat projection tests (frozen schema: room rows share the
// private-chat kind set and key rules; the group paints **no** deliverable card —
// 2026-11-14, the official UI has none; round events map to event rows with the
// shared eventKind vocabulary). Fixtures use the real room-session event shapes
// (docs/timeline-parity.md rule ⑩).
//
// Run:  node --test --import ./test/stub-resolve.mjs test/room-projection.test.js
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { install as installParts } from '../src/host/parts.js'
import { install as installTurns } from '../src/host/turns.js'
import { install as installChannel } from '../src/host/channel.js'
import {
  composeRoomRows,
  inboxRowsOf,
  roomInboxRowsOf,
} from '../src/host/projection.js'
import { replyItem, roomItems } from '../src/host/mobile.js'

let seq = 0
const reset = () => { seq = 0 }
const event = (type, data, extra = {}) => ({ type, seq: extra.seq ?? ++seq, time: extra.time ?? 1_700_000_000_000 + (extra.seq ?? seq), data })
const keysOf = rows => rows.map(row => row.key)

const RELAY_REPLIES = JSON.stringify({
  replies: [
    { kind: undefined, botId: 'b2', name: '写手', text: '我来', time: 5 },
    { kind: 'user', text: '插一句', rpcId: 'r-7', time: 6 },
    { kind: 'done', botId: 'b3', name: '画师', time: 7 },
    { kind: 'failed', botId: 'b4', name: '译员', code: 'rate_limit', text: '限流', time: 8 },
  ],
})

// A room's own session log: the user's message, one group_relay call whose result
// carries the round's replies, and a member's post logged into a running round
// (its `logged` seq keeps it inside the round instead of a user row of its own).
const ROOM_EVENTS = () => [
  event('user/message', { source: { kind: 'user', rpcId: 'r-1' }, content: [{ type: 'text', text: '大家看一下' }] }),
  event('tool/call', { turn: 1, callId: 'relay-1', name: 'group_relay', arguments: {} }),
  event('tool/result', { turn: 1, message: { toolCallId: 'relay-1', content: [{ type: 'text', text: RELAY_REPLIES }] } }),
  event('user/message', { source: { kind: 'ds-bot', role: 'post', senderSessionId: 'b2', senderName: '写手', logged: 3 }, content: [{ type: 'text', text: '[Group post from 写手]\n第二轮开工' }] }),
]

const baseRows = (events, { live = false } = {}) => {
  const { items, open } = roomItems(events)
  if (live) {
    for (const { callId, at } of open.reverse()) {
      // A running round's replies so far, spliced at the relay call.
      const liveReplies = [
        replyItem({ kind: undefined, botId: 'b2', name: '写手', text: '先说结论', time: 9 }, `${callId}:0`, { live: true }),
      ].filter(Boolean)
      items.splice(at, 0, ...liveReplies)
    }
  }
  return composeRoomRows({ events, baseItems: items })
}

test('round replies become rows under the frozen key rules, member identity in the payload', () => {
  reset()
  const rows = baseRows(ROOM_EVENTS())
  assert.deepEqual(keysOf(rows), ['user:1', 'bot:relay-1:0', 'user:relay-1:1', 'event:relay-1:2', 'event:relay-1:3'])
  const member = rows[1]
  assert.equal(member.kind, 'bot')
  assert.equal(member.payload.botId, 'b2')
  assert.equal(member.payload.name, '写手')
  assert.equal(member.seq, null, 'relay 回复行没有自己的 seq（位置语义，来自 roomItems）')
  const userReply = rows[2]
  assert.equal(userReply.kind, 'user')
  assert.equal(userReply.payload.requestId, 'r-7')
  // Round events use the shared eventKind vocabulary (done / failed …).
  assert.equal(rows[3].payload.eventKind, 'done')
  assert.equal(rows[3].payload.name, '画师')
  assert.equal(rows[4].payload.eventKind, 'failed')
  assert.equal(rows[4].payload.code, 'rate_limit')
})

test('a member post logged into a running round stays inside the round, not a user row', () => {
  reset()
  const rows = baseRows(ROOM_EVENTS())
  assert.ok(!keysOf(rows).some(key => key.startsWith('user:4')), 'logged post 不该再画一条用户气泡')
})

test('the live round reply splices at its relay position and swaps into place by key', () => {
  reset()
  // A round still running: the relay call is in the log, its result is not —
  // live replies splice at the call's position (mobile.js roomChat's splice).
  const liveEvents = ROOM_EVENTS().slice(0, 2)
  const rows = baseRows(liveEvents, { live: true })
  assert.deepEqual(keysOf(rows), ['user:1', 'bot:relay-1:0'])
  const live = rows[1]
  assert.equal(live.payload.live, true)
  assert.equal(live.payload.text, '先说结论')
  assert.equal(live.payload.name, '写手')
  // The settled round lands the result under the SAME key family: swap by key.
  const settled = baseRows(ROOM_EVENTS())
  assert.equal(settled[1].key, 'bot:relay-1:0')
  assert.equal(settled[1].payload.live, undefined)
  assert.equal(settled[1].payload.text, '我来')
})

test('round events never fold into a tool row: group_relay speaks through its replies', () => {
  reset()
  const rows = baseRows(ROOM_EVENTS())
  assert.ok(rows.every(row => row.kind !== 'tool'), '群聊不该有 group_relay 工具行')
})

test('the room paints no deliverable card at all (official UI has none)', () => {
  reset()
  // 2026-11-14: the group card was removed — the upstream `room-artifacts` endpoint
  // has no consumer in DS-Bot (web client included) and its feeders cannot tell
  // group work from a Bot's private work in the shared workspace. The room rows must
  // therefore never carry an `artifact` row, whatever the endpoint would return.
  const events = [
    event('turn/start', { turn: 3 }),
    event('user/message', { source: { kind: 'user' }, content: [{ type: 'text', text: '做吧' }] }),
    event('turn/end', { turn: 3, reason: { kind: 'completed' } }),
  ]
  const rows = baseRows(events)
  assert.ok(rows.every(row => row.kind !== 'artifact'), '群聊不该有产物卡行')
})

test('the room queue (roomInbox) projects as tail inbox rows; kickoff and resume posts do not', () => {
  reset()
  const rows = roomInboxRowsOf([
    { text: '排队的第二条', rpcId: 'r-11' },
    { text: '', kickoff: true },
    { text: '', resume: true },
    { text: '画师：先看图', botId: 'b3', name: '画师' },
  ], { roomId: 'room-1' })
  assert.deepEqual(keysOf(rows), ['inbox:roominx:room-1:0', 'inbox:roominx:room-1:3'])
  assert.equal(rows[0].payload.slot, 'next-turn')
  assert.equal(rows[0].payload.requestId, 'r-11')
  assert.equal(rows[1].payload.sourceKind, 'bot')
  assert.equal(rows[1].payload.botId, 'b3')
  // The private-chat inbox reader stays untouched and empty-safe.
  assert.deepEqual(inboxRowsOf(undefined), [])
})

test('the private chat keeps its own key vocabulary; the room adds the roomId form', () => {
  reset()
  // 群聊没有产物卡（2026-11-14 删）：私聊的 `present:<turn>` 是唯一产物卡键来源。
  // User rows: private keys by seq, the room by its item id (numeric seq for plain
  // messages, relay-scoped for round replies) — both under the `user:` prefix.
  reset()
  const roomRows = baseRows(ROOM_EVENTS())
  assert.equal(roomRows[0].key, 'user:1')
  assert.equal(roomRows[2].key, 'user:relay-1:1')
})

// ---------------------------------------------------------------------------
// ① The user's queued posts carry stable ids and can be withdrawn (`room-queue`).

/** turns.js on a bare runtime: its session/event listener is what stamps ids. */
function fakeTurnsRt() {
  const listeners = []
  const ctx = {
    on(name, handler) { if (name === 'session/event') listeners.push(handler) },
    effect() {},
    get() { return undefined },
    inject() {},
  }
  // btw/side wrap rt.load and walk state.bots/state.rooms on the way through.
  const state = { exchangeTurns: {}, questions: {}, views: {}, bots: {}, rooms: {}, exchanges: [], prefs: {} }
  const rt = {
    ctx,
    state,
    config: {},
    warn() {},
    noteFailure() {},
    save: async () => {},
    load: async () => {},
    refresh: async () => {},
    readOnly: () => undefined,
    roomProgress: new Map(),
    pastOwners: new Map(),
    workDirOf: () => undefined,
    isBotSession: () => false,
    roomOf: id => (id === 'room-1' ? { id: 'room-1', name: '项目群' } : undefined),
    groupOwners: new Map(),
    chatOwners: new Map(),
    botOf: () => undefined,
    selfOf: () => undefined,
    chatOf: id => id,
    resolveAgent: async () => ({ session: { seq: 0 }, inbox: { nextTurn: [], nextStep: [] } }),
    switchJobs: () => [],
  }
  installTurns(rt)
  return { rt, listeners }
}

const driveRoom = (listeners, event) => { for (const handler of listeners) handler({ id: 'room-1' }, event) }
const roomUserMessage = (seq, text, rpcId) => ({
  type: 'user/message', seq, time: 1_700_000_000_000 + seq,
  data: { source: { kind: 'user', ...(rpcId ? { rpcId } : {}) }, content: [{ type: 'text', text }] },
})

test('a user post queued for the next relay gets a stable, unique, monotone id', () => {
  const { rt, listeners } = fakeTurnsRt()
  driveRoom(listeners, roomUserMessage(1, '第一条', 'r-1'))
  driveRoom(listeners, roomUserMessage(2, '第二条'))
  const items = rt.roomInbox.get('room-1')
  assert.deepEqual(items.map(item => item.id), ['roomq:room-1:1', 'roomq:room-1:2'])
  assert.equal(items[0].rpcId, 'r-1', 'rpcId rides along when the Host gave one')
  assert.equal(items[1].rpcId, undefined)
  assert.equal(new Set(items.map(item => item.id)).size, 2, 'ids are unique')
})

test('Bot posts, kickoffs, resumes and reminders stay id-less (not addressable)', () => {
  const { rt, listeners } = fakeTurnsRt()
  // A Bot's post into the room.
  driveRoom(listeners, {
    type: 'user/message', seq: 1, time: 1,
    data: { source: { kind: 'bot', role: 'post', senderSessionId: 'b2', senderName: '写手' }, content: [{ type: 'text', text: '开工' }] },
  })
  // Kickoff / resume / a schedule reminder.
  driveRoom(listeners, { type: 'user/message', seq: 2, time: 2, data: { source: { kind: 'bot', role: 'kickoff' }, content: [] } })
  driveRoom(listeners, { type: 'user/message', seq: 3, time: 3, data: { source: { kind: 'bot', role: 'resume' }, content: [] } })
  driveRoom(listeners, { type: 'user/message', seq: 4, time: 4, data: { source: { kind: 'schedule' }, content: [{ type: 'text', text: 'reminder_prompt_json: "喝水"' }] } })
  const items = rt.roomInbox.get('room-1')
  assert.equal(items.length, 4)
  assert.ok(items.every(item => item.id === undefined), `只有用户自己的排队项可变址：${JSON.stringify(items)}`)
  // And the projection shows them under the fallback keys, never as withdrawable ids.
  const rows = roomInboxRowsOf(items, { roomId: 'room-1' })
  // kickoff/resume are not rows at all (they carry no text a client can show);
  // the post and the reminder are, keyed by the fallback index — never `roomq:`.
  assert.deepEqual(keysOf(rows), ['inbox:roominx:room-1:0', 'inbox:roominx:room-1:3'])
  assert.ok(rows.every(row => row.payload.sourceKind !== 'user'))
  assert.ok(rows.every(row => !row.key.startsWith('inbox:roomq:')))
})

test('user queue rows key off the stable id and carry sourceKind=user', () => {
  const { rt, listeners } = fakeTurnsRt()
  driveRoom(listeners, roomUserMessage(1, '排队的问题', 'r-9'))
  const rows = roomInboxRowsOf(rt.roomInbox.get('room-1'), { roomId: 'room-1' })
  assert.deepEqual(keysOf(rows), ['inbox:roomq:room-1:1'])
  assert.equal(rows[0].kind, 'inbox')
  assert.equal(rows[0].seq, null)
  assert.deepEqual(rows[0].payload, {
    slot: 'next-turn', itemId: 'roomq:room-1:1', requestId: 'r-9', sourceKind: 'user', text: '排队的问题',
  })
})

test('removeRoomQueued withdraws by id; an unknown id or a missing queue removes nothing', () => {
  const { rt, listeners } = fakeTurnsRt()
  driveRoom(listeners, roomUserMessage(1, '要撤回的'))
  driveRoom(listeners, roomUserMessage(2, '要留下的'))
  assert.equal(rt.removeRoomQueued('room-1', 'roomq:room-1:1'), true)
  assert.deepEqual(rt.roomInbox.get('room-1').map(item => item.text), ['要留下的'])
  assert.equal(rt.removeRoomQueued('room-1', 'roomq:room-1:1'), false, 'already gone — not an error')
  assert.equal(rt.removeRoomQueued('room-1', 'roomq:room-1:99'), false)
  assert.equal(rt.removeRoomQueued('room-2', 'roomq:room-2:1'), false)
})

test('the room-queue endpoint removes by id and answers {ok, value:{removed}}', async () => {
  const { rt, listeners } = fakeTurnsRt()
  driveRoom(listeners, roomUserMessage(1, '要撤回的'))
  driveRoom(listeners, roomUserMessage(2, '要留下的'))
  const channel = fakeChannelRt(rt)
  const answer = await channel('room-queue', { roomId: 'room-1', id: 'roomq:room-1:1', action: 'remove' })
  assert.equal(answer.ok, true, JSON.stringify(answer))
  assert.deepEqual(answer.value, { removed: true })
  assert.deepEqual(rt.roomInbox.get('room-1').map(item => item.text), ['要留下的'])
  // A second remove of the same id converges: removed:false, still ok.
  const again = await channel('room-queue', { roomId: 'room-1', id: 'roomq:room-1:1', action: 'remove' })
  assert.equal(again.ok, true)
  assert.deepEqual(again.value, { removed: false })
})

test('the room-queue endpoint refuses anything but remove, with a readable reason', async () => {
  const { rt, listeners } = fakeTurnsRt()
  driveRoom(listeners, roomUserMessage(1, '排队'))
  const channel = fakeChannelRt(rt)
  for (const action of ['steer', 'edit', 'whatever']) {
    const answer = await channel('room-queue', { roomId: 'room-1', id: 'roomq:room-1:1', action })
    assert.equal(answer.ok, false, `${action} 不该成功`)
    assert.equal(answer.error.code, 'bot/unsupported')
    assert.match(answer.error.message, /no (steer|")/)
  }
  // The queue is untouched by a refused action.
  assert.equal(rt.roomInbox.get('room-1').length, 1)
  // Argument and identity failures keep their own codes.
  assert.equal((await channel('room-queue', { roomId: 'room-1', id: '', action: 'remove' })).error.code, 'bot/invalid')
  assert.equal((await channel('room-queue', { roomId: 'ghost', id: 'x', action: 'remove' })).error.code, 'bot/not-found')
})

test('the room-queue endpoint is mutating: a read-only host refuses it', async () => {
  const { rt } = fakeTurnsRt()
  const channel = fakeChannelRt(rt)
  rt.readOnly = () => 'This team is open in another DSH host'
  const answer = await channel('room-queue', { roomId: 'room-1', id: 'roomq:room-1:1', action: 'remove' })
  assert.equal(answer.ok, false)
  assert.equal(answer.error.code, 'bot/read-only')
})

/** channel.js on top of parts + turns: `room-queue` goes through the real handle. */
function fakeChannelRt(rt) {
  installParts(rt)
  installChannel(rt)
  return (endpoint, body) => rt.channelCall(endpoint, body)
}
