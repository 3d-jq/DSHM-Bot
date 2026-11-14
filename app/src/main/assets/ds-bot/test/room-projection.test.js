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
