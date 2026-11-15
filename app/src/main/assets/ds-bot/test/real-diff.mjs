// 真机真实数据上的差分（4c 影子双轨的离线版）：
// 同一份 DSH 会话事件日志，宿主折叠一遍，客户端折叠一遍，逐行比。
//
// 用法：
//   node --import ./test/stub-resolve.mjs test/real-diff.mjs <session.jsonl> [out.json]
import { readFileSync, writeFileSync } from 'node:fs'
import { install as installParts } from '../src/host/parts.js'
import { composeTimelineRows } from '../src/host/projection.js'

const path = process.argv[2]
if (!path) {
  console.error('用法：node --import ./test/stub-resolve.mjs test/real-diff.mjs <session.jsonl> [out.json]')
  process.exit(1)
}

const lines = readFileSync(path, 'utf8').split('\n').filter(Boolean)
const header = JSON.parse(lines[0])
const events = []
for (const line of lines.slice(1)) {
  let event
  try { event = JSON.parse(line) } catch { continue }
  if (typeof event.seq === 'number' && typeof event.type === 'string') events.push(event)
}
events.sort((left, right) => left.seq - right.seq)
console.error(`事件 ${events.length} 条（seq ${events[0]?.seq}..${events.at(-1)?.seq}），cwd=${header.cwd}`)

const exchangeTurns = header.exchangeTurns ?? []
const rt = {
  ctx: { get: () => undefined, effect: () => {}, on: () => {}, sessionController: {}, logger: { info() {}, warn() {} } },
  config: {},
  state: { exchangeTurns: {} },
  chatOwners: new Map(),
  pastOwners: new Map(),
  botOf: () => undefined,
}
installParts(rt)
const baseItems = rt.displayItems('sess-1', events, undefined)
const rows = composeTimelineRows({
  events,
  baseItems,
  exchangeTurns,
  resolveBot: ref => ref,
  botName: '大总管',
  cwd: header.cwd,
})

// 给客户端折叠用的输入：事件 + 区间 + cwd + 隐藏轮。
const out = {
  cwd: header.cwd,
  exchangeTurns,
  eventFrom: events[0]?.seq ?? null,
  eventTo: events.at(-1)?.seq ?? null,
  events: events.map(event => ({ seq: event.seq, type: event.type, time: event.time ?? null, data: event.data ?? null })),
  hostRows: rows,
}
const target = process.argv[3]
if (target) {
  writeFileSync(target, JSON.stringify(out))
  console.error(`已写出 ${target}：${rows.length} 行宿主折叠结果 + ${out.events.length} 事件`)
}
