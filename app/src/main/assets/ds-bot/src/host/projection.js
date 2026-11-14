// The host-side unified timeline projection (Phase 1a, docs/phase1-design.md).
//
//   timeline rows  = official `displayItems` rows (user/bot/answer/view/event/divider,
//                    wrapped as-is) + the rows the official web client draws in the
//                    browser: tool rows (ToolCell/renderCall semantics — `present`
//                    makes none: its deliverable card is the one surface),
//                    deliverable cards (the `dsh-client-ui-deliverables`
//                    accumulator: presentedForClosing, produced, first-seen order)
//                    and failure rows (llm/retry, a turn that ended in error) +
//                    inbox rows (what sits in the agent inbox: queued or steered
//                    input, read straight off `agent.inbox`).
//
// A row is `{ key, kind, seq, turn, timeMs, payload }`:
//   - `key` is the durable identity (`user:<seq>`, `bot:<seq>`, `tool:<seq>`,
//     `present:<turn>`, `retry:<seq>`, `turnfail:<seq>`, `event:<seq>`,
//     `answer:<seq>`, `view:<callId>`, `inbox:<itemId>`); a client only ever
//     replaces a row by key, never re-sorts the table itself.
//   - rows with a seq are strictly ascending in seq; rows without one (inbox) are
//     appended at the tail.
//   - unknown payload fields must be ignored by clients (additive evolution).
//
// Pure functions come first (unit-testable without a harness); `install(rt)` wires
// the per-session event buffer, the `timeline` endpoint and the feed delta.
import { HISTORY_TIMEOUT_MS } from './constants.js'
import { textOf } from './text.js'
import { replyItem, roomItems } from './mobile.js'

// Events a timeline page covers, and the ring sizes of the per-session buffer.
export const TIMELINE_PAGE = 200
const BUFFER_EVENTS = 800
const BUFFER_SESSIONS = 64
// Rows the client folds for display (official `COLLAPSED_PRESENTED_COUNT`); the
// payload always carries every file — folding is the client's rendering choice.
export const COLLAPSED_ARTIFACTS = 4

// The tools whose calls become rows (the client's FOLLOW set; `remember`/`forget`
// stay out as it does — the activity row shows them). `group_relay` is absent too:
// its replies are rows of their own (room items), not a tool row. `present` is
// absent by the user's call: no Delivering/Delivered row — the deliverable card is
// its one surface (2026-11-14).
const TOOL_ROWS = new Set(['message_bot', 'stop_bot', 'create_bot', 'update_bot', 'create_group', 'update_group', 'delete_group', 'post_to_group', 'read_group_chat'])

// Group results quote the group's name; the web client parses it with this regex.
const GROUP_RESULT_NAME = /^(?:Created group|Updated|Nothing changed in|Deleted|Posted in) "([^"]+)"/

// ---------------------------------------------------------------------------
// Pure helpers

const isRecord = value => typeof value === 'object' && value !== null && !Array.isArray(value)

/** `path` as an absolute POSIX path; relative paths join `cwd`. (Official util-workspace-path.) */
export function resolveSessionPath(path, cwd) {
  let p = String(path ?? '').trim()
  if (p.startsWith('file://')) { try { p = decodeURIComponent(p.slice('file://'.length)) } catch { /* keep raw */ } }
  if (/^[A-Za-z]:[/\\]/.test(p) || p.startsWith('\\\\')) return p
  if (!p.startsWith('/')) {
    if (cwd === undefined || cwd === null || !String(cwd).startsWith('/')) return p
    p = `${String(cwd).replace(/[/\\]+$/, '')}/${p}`
  }
  const out = []
  for (const segment of p.split('/')) {
    if (segment === '' || segment === '.') continue
    if (segment === '..') out.pop()
    else out.push(segment)
  }
  return `/${out.join('/')}`
}

/** Official `isPresentedData`: a `deliverables/presented` event's data, validated. */
export function isPresentedData(value) {
  if (!isRecord(value)) return false
  const { turn, callId, files } = value
  return typeof turn === 'number' && Number.isSafeInteger(turn) && turn >= 1
    && typeof callId === 'string' && callId.length > 0
    && Array.isArray(files)
}

/** Official `isPresentedFile`. */
export function isPresentedFile(value) {
  if (!isRecord(value)) return false
  const { path, description } = value
  return typeof path === 'string' && path.trim().length > 0 && (description === undefined || typeof description === 'string')
}

/** Every turn's boundaries from `turn/start` / `turn/end`, oldest first. */
export function turnTracesOf(events) {
  const order = new Map()
  for (const event of events) {
    const turn = event.data?.turn
    if (typeof turn !== 'number' || !Number.isSafeInteger(turn) || turn < 1) continue
    const seq = event.seq
    if (typeof seq !== 'number') continue
    const previous = order.get(turn)
    if (event.type === 'turn/start') {
      order.set(turn, { turn, startSeq: previous?.startSeq !== undefined && previous.startSeq < seq ? previous.startSeq : seq, endSeq: previous?.endSeq })
    } else if (event.type === 'turn/end') {
      order.set(turn, { turn, startSeq: previous?.startSeq ?? seq, endSeq: seq })
    }
  }
  return [...order.values()].sort((left, right) => left.startSeq - right.startSeq)
}

/** Official accumulator: each turn's `deliverables/presented` declarations,
 * deduped by path — first-seen position, latest description wins. */
export function presentedByTurn(events) {
  const byTurn = new Map()
  for (const event of events) {
    if (event.type !== 'deliverables/presented' || !isPresentedData(event.data)) continue
    const files = byTurn.get(event.data.turn) ?? new Map()
    for (let index = 0; index < event.data.files.length; index += 1) {
      const file = event.data.files[index]
      if (!isPresentedFile(file)) continue
      // A Map keeps the first insertion's position when a key is set again.
      files.set(file.path, { path: file.path, description: file.description, seq: event.seq, index })
    }
    byTurn.set(event.data.turn, files)
  }
  return byTurn
}

/** The file a mutation call targets (`write`/`edit` take `file_path`; a mutating
 * `str_replace_editor` takes `path`). Official `mutationPath`. */
function argumentPathOf(name, args) {
  if (!isRecord(args)) return null
  if (name === 'write' || name === 'edit') return typeof args.file_path === 'string' && args.file_path.trim() !== '' ? args.file_path : null
  if (name === 'str_replace_editor') {
    if (args.command === 'create') return typeof args.file_text === 'string' ? args.path : null
    if (args.command === 'str_replace') return typeof args.old_str === 'string' && args.old_str.length > 0 ? args.path : null
    if (args.command === 'insert') return typeof args.insert_line === 'number' && Number.isInteger(args.insert_line) && args.insert_line >= 0 ? args.path : null
    return null
  }
  return null
}

/** Official `mutationResultPath`: an absolute `meta.path` from the result wins. */
function resultPathOf(argumentPath, meta, cwd) {
  if (isRecord(meta) && typeof meta.path === 'string' && meta.path.startsWith('/')) return meta.path
  if (typeof argumentPath !== 'string') return null
  const resolved = resolveSessionPath(argumentPath, cwd)
  return resolved.startsWith('/') ? resolved : null
}

const textContentOf = value => {
  const array = Array.isArray(value) ? value : []
  return array.map(block => (block?.type === 'text' && typeof block.text === 'string' ? block.text : '')).join('').trim()
}

/** Local markdown link targets of a text, in order, resolved against `cwd`;
 * web links, anchors and off-machine schemes drop. */
export function linkedPathsOf(text, cwd) {
  const out = []
  const seen = new Set()
  const pattern = /\]\(\s*(?:<([^>\n]+)>|([^)\s]+))/g
  for (const match of String(text ?? '').matchAll(pattern)) {
    let target = (match[1] ?? match[2] ?? '').trim()
    if (target === '') continue
    if (/^[A-Za-z][A-Za-z0-9+.-]*:/.test(target) && !/^file:/i.test(target)) continue
    if (/^[A-Za-z]:[\\/]/.test(target)) target = target.split('#')[0]
    const path = resolveSessionPath(target, cwd)
    if (!path.startsWith('/') || seen.has(path)) continue
    seen.add(path)
    out.push(path)
  }
  return out
}

// ---------------------------------------------------------------------------
// Row builders

const row = (key, kind, seq, turn, timeMs, payload) => ({ key, kind, seq: seq ?? null, turn: turn ?? null, timeMs: timeMs ?? null, payload })

/** Attachment names of every user message, in the official wire order (attachment
 * blocks first, then text) — the payload keeps names; bytes stay client-side. */
export function userAttachmentsOf(events) {
  const bySeq = new Map()
  for (const event of events) {
    if (event.type !== 'user/message') continue
    if (event.data?.source?.kind !== 'user') continue
    const content = Array.isArray(event.data.content) ? event.data.content : []
    const attachments = []
    for (const block of content) {
      if ((block?.type === 'image' || block?.type === 'file') && block.attachment !== undefined) {
        attachments.push({ type: block.type, name: block.attachment?.name ?? null })
      }
    }
    if (attachments.length > 0 && typeof event.seq === 'number') bySeq.set(event.seq, attachments)
  }
  return bySeq
}

/** Map one official `displayItems` item to a row. `viewSeqOf` backfills the seq a
 * `show_view` row is missing (displayItems keys views by callId only). */
function itemToRow(item, index, viewSeqOf, userAttachments) {
  const timeMs = item.time ?? null
  switch (item.kind) {
    case 'user': {
      const attachments = userAttachments.get(item.seq) ?? []
      return row(`user:${item.seq}`, 'user', item.seq, item.turn, timeMs, {
        text: item.text,
        images: item.images ?? 0,
        ...(attachments.length > 0 ? { blocks: attachments } : {}),
        ...(item.rpcId ? { requestId: item.rpcId } : {}),
      })
    }
    case 'bot':
      return row(`bot:${item.seq}`, 'bot', item.seq, item.turn, timeMs, {
        text: item.text,
        ...(item.botId ? { botId: item.botId } : {}),
        ...(item.name ? { name: item.name } : {}),
        ...(item.duration !== undefined ? { durationMs: item.duration } : {}),
        ...(item.whole ? { whole: item.whole } : {}),
      })
    case 'answer':
      return row(`answer:${item.seq ?? `i${index}`}`, 'answered', item.seq, item.turn, timeMs, {
        ...(item.questions !== undefined ? { questions: item.questions } : {}),
        ...(item.answers !== undefined ? { answers: item.answers } : {}),
        ...(item.views !== undefined ? { views: item.views } : {}),
        ...(item.handoff !== undefined ? { handoff: item.handoff } : {}),
        ...(item.text !== undefined ? { text: item.text } : {}),
      })
    case 'view':
      return row(`view:${item.callId ?? `i${index}`}`, 'view', viewSeqOf(item.callId), item.turn, timeMs, {
        title: item.title ?? null,
        view: item.view,
        callId: item.callId ?? null,
      })
    case 'event':
      return row(`event:${item.seq}`, 'event', item.seq, item.turn, timeMs, {
        eventKind: item.role ?? item.event ?? null,
        ...(item.code ? { code: item.code } : {}),
        ...(item.botId ? { botId: item.botId } : {}),
        ...(item.name ? { name: item.name } : {}),
        ...(item.roomId ? { roomId: item.roomId } : {}),
        ...(item.text !== undefined ? { text: item.text } : {}),
      })
    case 'divider':
      return row(`divider:${timeMs ?? `i${index}`}`, 'divider', item.seq, item.turn, timeMs, {})
    default:
      return row(`unknown:${item.seq ?? index}`, 'event', item.seq, item.turn, timeMs, { eventKind: null })
  }
}

/** Tool rows for one event list: a running call holds its row, the result replaces
 * it at the result's seq. A call whose result is not in this list keeps its
 * `running` row — a live snapshot's edge. `present` makes no row at all: the
 * deliverable card is the one surface for it (2026-11-14, user's call). */
export function toolRowsOf(events, { exchangeTurns = [], resolveBot, botName } = {}) {
  const hidden = new Set(exchangeTurns)
  const calls = new Map()
  const settled = new Set()
  const rows = []
  const nameOf = raw => (resolveBot !== undefined && raw !== '' ? resolveBot(raw) ?? raw : raw)
  for (const event of events) {
    if (event.type === 'tool/call') {
      const name = String(event.data?.name ?? '')
      if (!TOOL_ROWS.has(name)) continue
      const callId = String(event.data?.callId ?? '')
      if (callId === '') continue
      if (typeof event.data?.turn === 'number' && hidden.has(event.data.turn)) continue
      calls.set(callId, { name, args: isRecord(event.data?.arguments) ? event.data.arguments : undefined, seq: event.seq, turn: event.data?.turn })
      continue
    }
    if (event.type !== 'tool/result') continue
    const message = event.data?.message
    const callId = String(message?.toolCallId ?? event.data?.callId ?? '')
    const pending = calls.get(callId)
    if (pending === undefined) continue
    if (typeof pending.turn === 'number' && hidden.has(pending.turn)) continue
    settled.add(callId)
    const isError = event.data?.isError === true || message?.isError === true || event.data?.error !== undefined
    const args = pending.args
    const raw = ['to', 'bot', 'name', 'group'].map(key => (isRecord(args) ? String(args[key] ?? '').trim() : '')).find(value => value !== '') ?? ''
    const result = textContentOf(message?.content)
    let label
    let target = raw
    switch (pending.name) {
      case 'message_bot':
        label = isError ? (raw !== '' ? `没能给 ${raw} 发消息` : '没能给其他 Bot 发消息') : (raw !== '' ? `消息发给了 ${nameOf(raw)}` : '消息发给了其他 Bot')
        target = raw
        break
      case 'stop_bot':
        label = isError ? (raw !== '' ? `没能叫停 ${raw}` : '没能叫停其他 Bot') : (raw !== '' ? `叫停了 ${nameOf(raw)}` : '叫停了其他 Bot')
        target = raw
        break
      case 'create_bot':
        label = isError ? '创建 Bot 失败' : `创建了 ${raw !== '' ? nameOf(raw) : 'Bot'}`
        break
      case 'update_bot': {
        const self = raw !== '' ? nameOf(raw) : (botName ?? '')
        label = isError ? '更新 Bot 失败' : `更新了 ${self}`
        target = self
        break
      }
      case 'create_group':
      case 'update_group':
      case 'delete_group':
      case 'post_to_group': {
        const named = GROUP_RESULT_NAME.exec(result.trim())?.[1] ?? (raw !== '' ? raw : null)
        const verbs = {
          create_group: ['创建了群聊', '创建群聊失败'],
          update_group: ['更新了群聊', '更新群聊失败'],
          delete_group: ['删除了群聊', '删除群聊失败'],
          post_to_group: ['在', '没能在群里发言'],
        }[pending.name]
        if (isError) label = verbs[1]
        else if (pending.name === 'post_to_group') label = `在${named ? ` ${named} ` : '群里'}发言`
        else label = `${verbs[0]}${named ? `：${named}` : ''}`
        target = named ?? ''
        break
      }
      case 'read_group_chat':
        label = isError ? '没能读群聊' : (raw !== '' ? `阅读了群聊「${raw}」` : '阅读了群聊')
        target = raw
        break
      default:
        continue
    }
    rows.push(row(`tool:${event.seq}`, 'tool', event.seq, pending.turn, event.time, {
      tool: pending.name, state: isError ? 'error' : 'ok', callId,
      ...(typeof target === 'string' && target !== '' && !isError ? { target } : {}),
      label,
    }))
  }
  // A call whose result is in this same list already made its result row: the
  // running row from the call drops (the official swap, per turn).
  return rows.filter(entry => !(entry.payload.state === 'running' && settled.has(entry.payload.callId)))
}

/** Rows a finished turn's deliverables make (official `deliverablesDefinition`
 * accumulator + `presentedForClosing`): declarations win over produced files;
 * within a turn each path appears once at its first declaration, carrying the
 * latest description; a declaration (or a produced file) settled after the
 * closing reply is not on the card. */
export function artifactRowsOf(events, { exchangeTurns = [], cwd } = {}) {
  const hidden = new Set(exchangeTurns)
  const traces = turnTracesOf(events).filter(trace => trace.endSeq !== undefined && !hidden.has(trace.turn))
  if (traces.length === 0) return []
  const eligible = new Set(traces.map(trace => trace.turn))
  const scans = new Map()
  const closingOf = new Map()
  for (const event of events) {
    const turn = event.data?.turn
    if (typeof turn !== 'number' || !eligible.has(turn)) continue
    const seq = event.seq
    if (typeof seq !== 'number') continue
    if (event.type === 'assistant/message') {
      if (textContentOf(event.data?.message?.content) !== '') closingOf.set(turn, Math.max(closingOf.get(turn) ?? 0, seq))
      continue
    }
    if (event.type !== 'tool/call' && event.type !== 'tool/result') continue
    const scan = scans.get(turn) ?? { calls: new Map(), written: [], results: [], reply: '' }
    scans.set(turn, scan)
    if (event.type === 'tool/call') {
      const callId = String(event.data?.callId ?? '')
      if (callId === '') continue
      scan.calls.set(callId, {
        name: String(event.data?.name ?? ''),
        argumentPath: argumentPathOf(String(event.data?.name ?? ''), event.data?.arguments),
        argsText: JSON.stringify(event.data?.arguments ?? ''),
      })
      continue
    }
    if (event.data?.isError === true || event.data?.message?.isError === true) continue
    const callId = String(event.data?.message?.toolCallId ?? event.data?.callId ?? '')
    const call = scan.calls.get(callId)
    scan.results.push(textContentOf(event.data?.message?.content))
    if (call === undefined || call.argumentPath === null || call.argumentPath === undefined) continue
    const path = resultPathOf(call.argumentPath, event.data?.meta, cwd)
    if (path !== null) scan.written.push({ path, seq })
  }
  const presented = presentedByTurn(events)
  const rows = []
  for (const trace of traces) {
    // The closing reply is the turn's last assistant message; declarations and
    // mutations settled after it are off the card (official `presentedForClosing`).
    const closingSeq = closingOf.get(trace.turn) ?? trace.endSeq
    const declared = presented.get(trace.turn)
    const files = new Map()
    if (declared !== undefined) {
      for (const file of declared.values()) {
        if (file.seq >= closingSeq) continue
        const path = resolveSessionPath(file.path, cwd)
        files.set(path, { path, description: typeof file.description === 'string' && file.description.trim() !== '' ? file.description.trim() : null })
      }
    } else {
      const scan = scans.get(trace.turn)
      if (scan !== undefined) {
        for (const written of scan.written) {
          if (written.seq > closingSeq || files.has(written.path)) continue
          files.set(written.path, { path: written.path, description: null })
        }
        for (const path of linkedPathsOf(scan.reply, cwd)) {
          if (files.has(path)) continue
          const name = path.split('/').at(-1)
          const mentioned = [...scan.calls.values()].some(call => call.argsText.includes(name)) ||
            scan.results.some(result => result.includes(name))
          if (!mentioned) continue
          files.set(path, { path, description: null })
        }
      }
    }
    if (files.size === 0) continue
    rows.push(row(`present:${trace.turn}`, 'artifact', trace.endSeq, trace.turn, null, {
      files: [...files.values()],
      delivered: declared !== undefined,
      collapsedCount: COLLAPSED_ARTIFACTS,
    }))
  }
  return rows
}

/** `llm/retry` and a turn that ended in error, as event rows (one per event; the
 * client folds identical consecutive lines itself — an intentional deviation). */
export function failureRowsOf(events, { exchangeTurns = [], botId, botName } = {}) {
  const hidden = new Set(exchangeTurns)
  const rows = []
  for (const event of events) {
    const turn = event.data?.turn
    if (typeof turn === 'number' && hidden.has(turn)) continue
    if (event.type === 'llm/retry') {
      const retry = Math.max(Number(event.data?.retry ?? 0) || 1, 1)
      const max = Number(event.data?.maxRetries ?? 0)
      const failure = isRecord(event.data?.failure) ? event.data.failure : {}
      const reason = typeof failure.message === 'string' && failure.message.trim() !== '' ? failure.message
        : (typeof failure.code === 'string' && failure.code !== '' ? failure.code : undefined)
      const retrySuffix = max > 0 ? `（${retry}/${max}）` : `（${retry}）`
      const reasonSuffix = reason !== undefined ? `：${reason}` : ''
      rows.push(row(`retry:${event.seq}`, 'event', event.seq, turn, event.time, {
        eventKind: 'retry', code: typeof failure.code === 'string' ? failure.code : null,
        ...(botId ? { botId } : {}), ...(botName ? { name: botName } : {}),
        text: `模型请求失败，正在重试${retrySuffix}${reasonSuffix}`,
      }))
      continue
    }
    if (event.type !== 'turn/end') continue
    const reason = event.data?.reason
    if (reason?.kind !== 'error') continue
    const error = isRecord(reason.error) ? reason.error : {}
    const code = typeof error.code === 'string' && error.code !== '' ? error.code : 'UNKNOWN'
    const message = code === 'AUTH' || typeof error.message !== 'string' ? '' : error.message.trim().slice(0, 500)
    rows.push(row(`turnfail:${event.seq}`, 'event', event.seq, turn, event.time, {
      eventKind: 'failed', code, ...(botId ? { botId } : {}), ...(botName ? { name: botName } : {}),
      text: message,
    }))
  }
  return rows
}

/** What sits in the agent's inbox, as tail rows: queued input waits for its own
 * turn (`next-turn`), steered input for the running turn's next step (`next-step`). */
export function inboxRowsOf(inbox) {
  const rows = []
  const take = (slot, items) => {
    for (const item of Array.isArray(items) ? items : []) {
      const id = String(item?.id ?? '')
      if (id === '') continue
      const source = item?.message?.source
      rows.push(row(`inbox:${id}`, 'inbox', null, null, null, {
        slot, itemId: id,
        requestId: typeof source?.rpcId === 'string' ? source.rpcId : (typeof source?.requestId === 'string' ? source.requestId : null),
        sourceKind: source?.kind ?? null,
        text: textOf(item?.message?.content),
      }))
    }
  }
  take('next-turn', inbox?.nextTurn)
  take('next-step', inbox?.nextStep)
  return rows
}

/** The projection entry: official `displayItems` rows wrapped as-is, plus the
 * tool / failure / artifact / inbox rows. Rows with a seq are ascending; rows
 * without one (inbox) are appended at the tail. */
export function composeTimelineRows({ events, baseItems, exchangeTurns = [], resolveBot, botName, cwd, inbox }) {
  const viewSeqOf = callId => {
    if (callId === null || callId === undefined) return null
    for (const event of events) {
      if (event.type === 'tool/call' && event.data?.callId === callId) return event.seq ?? null
    }
    return null
  }
  const userAttachments = userAttachmentsOf(events)
  const rows = (baseItems ?? []).map((item, index) => itemToRow(item, index, viewSeqOf, userAttachments))
  rows.push(...toolRowsOf(events, { exchangeTurns, resolveBot, botName }))
  rows.push(...failureRowsOf(events, { exchangeTurns, botName }))
  rows.push(...artifactRowsOf(events, { exchangeTurns, cwd }))
  const ordered = rows
    .map((entry, index) => ({ entry, index }))
    .sort((left, right) => {
      const leftSeq = left.entry.seq ?? Number.POSITIVE_INFINITY
      const rightSeq = right.entry.seq ?? Number.POSITIVE_INFINITY
      if (leftSeq !== rightSeq) return leftSeq - rightSeq
      // The same seq (a turn's end can carry both a failure row and the card):
      // events first, then the artifact card.
      const leftRank = left.entry.kind === 'artifact' ? 1 : 0
      const rightRank = right.entry.kind === 'artifact' ? 1 : 0
      return leftRank - rightRank || left.index - right.index
    })
    .map(entry => entry.entry)
  ordered.push(...inboxRowsOf(inbox))
  return ordered
}

/** A group chat's own Session as rows (official `roomItems` wrapped): the round's
 * replies are rows already. The official order here is positional — a running
 * round's live replies splice in at their relay call — so the base order passes
 * through untouched; failure rows join at the end. Member identity rides the
 * payload (`botId`/`name`, as roomItems carries it). */
export function composeRoomRows({ events, baseItems, botName }) {
  const rows = []
  for (const item of baseItems) {
    const seq = /^\d+$/.test(String(item.id ?? '')) ? Number(item.id) : null
    const timeMs = item.time ?? null
    switch (item.kind) {
      case 'user':
        rows.push(row(`user:${item.id}`, 'user', seq, null, timeMs, { text: item.text, images: item.images ?? 0, ...(item.rpcId ? { requestId: item.rpcId } : {}), ...(item.live ? { live: true } : {}) }))
        break
      case 'bot':
        rows.push(row(`bot:${item.id}`, 'bot', seq, null, timeMs, { text: item.text, ...(item.botId ? { botId: item.botId } : {}), ...(item.name ? { name: item.name } : {}), ...(item.whole ? { whole: item.whole } : {}), ...(item.live ? { live: true } : {}) }))
        break
      case 'event':
        rows.push(row(`event:${item.id}`, 'event', seq, null, timeMs, { eventKind: item.event ?? null, ...(item.code ? { code: item.code } : {}), ...(item.botId ? { botId: item.botId } : {}), ...(item.name ? { name: item.name } : {}), ...(item.text !== undefined ? { text: item.text } : {}), ...(item.live ? { live: true } : {}) }))
        break
      default:
        break
    }
  }
  rows.push(...failureRowsOf(events, { botName }))
  return rows
}

/** The group's deliverable card was removed (2026-11-14): the official UI never
 * renders one (the upstream `room-artifacts` endpoint has no consumer anywhere in
 * DS-Bot, web client included), and its feeders cannot tell group work from a Bot's
 * private work in the shared workspace. Group members' deliveries show in their own
 * private chats, exactly as officially. */



/** What a group still waits to relay, as tail rows. A room's queued posts live in
 * the plugin's own `roomInbox` (they drain into the next `group_relay`); the room
 * Session's agent inbox joins when it holds anything. */
export function roomInboxRowsOf(posts, { roomId } = {}) {
  const rows = []
  const list = Array.isArray(posts) ? posts : []
  list.forEach((post, index) => {
    if (post?.kickoff || post?.resume) return
    const id = typeof post?.id === 'string' && post.id !== '' ? post.id : `roominx:${roomId ?? ''}:${index}`
    rows.push(row(`inbox:${id}`, 'inbox', null, null, null, {
      slot: 'next-turn', itemId: id,
      requestId: typeof post?.rpcId === 'string' ? post.rpcId : null,
      sourceKind: post?.botId ? 'bot' : 'user',
      ...(post?.botId ? { botId: post.botId } : {}),
      ...(post?.name ? { name: post.name } : {}),
      text: String(post?.text ?? ''),
    }))
  })
  return rows
}

// ---------------------------------------------------------------------------
// install: per-session buffer, the `timeline` endpoint, the feed delta

export function install(rt) {
  const { ctx, state } = rt
  if (rt.displayItems === undefined) return

  /** Per Session: the events the buffer holds (a ring) — the source of the feed
   * delta and of a `since` replay; older reads page the Session log instead. */
  const buffers = new Map()
  const published = new Map()
  /** One diagnostic line per Session and turn, at the moment a declared turn closes. */
  const cardsLogged = new Set()

  const bufferOf = sessionId => {
    let buffer = buffers.get(sessionId)
    if (buffer === undefined) {
      if (buffers.size >= BUFFER_SESSIONS) buffers.delete(buffers.keys().next().value)
      buffer = { events: new Map() }
      buffers.set(sessionId, buffer)
    }
    return buffer
  }

  const record = (sessionId, event) => {
    if (typeof event?.seq !== 'number') return
    const buffer = bufferOf(sessionId)
    buffer.events.set(event.seq, event)
    while (buffer.events.size > BUFFER_EVENTS) buffer.events.delete(buffer.events.keys().next().value)
  }

  ctx.on('session/event', (session, event) => {
    const id = session?.id
    if (id === undefined) return
    if (!rt.isBotSession(id) && rt.roomOf(id) === undefined) return
    record(id, event)
  })

  const eventsOf = buffer => [...buffer.events.values()]

  // Diagnostic logging while the delta path is under observation (a phone saw a
  // deliverable card only after a restart): engine.log through the host logger,
  // at most one line per Session per TIMELINE_LOG_MS so a burst of events stays
  // one line. `rt.timelineLog(sessionId, message)`.
  const TIMELINE_LOG_MS = 5_000
  const logStamps = new Map()
  const logLine = (sessionId, message) => {
    try {
      rt.ctx.logger?.info?.('%s', `[ds-bot:timeline] ${sessionId} ${message}`)
      console.info(`[ds-bot:timeline] ${sessionId} ${message}`)
    } catch { /* a logging host is optional */ }
  }
  rt.timelineLog = (sessionId, message) => {
    const now = Date.now()
    if (now - (logStamps.get(sessionId) ?? 0) < TIMELINE_LOG_MS) return
    logStamps.set(sessionId, now)
    logLine(sessionId, message)
  }

  /** A running round's replies so far, spliced at their relay call exactly the way
   * `mobile.js roomChat` places them (same item ids, so a client swaps the live
   * row for the settled one by key). */
  const withLiveReplies = (events, cursorless) => {
    const { items, open } = roomItems(events)
    if (cursorless) {
      for (const { callId, at } of open.reverse()) {
        const round = rt.roomProgress.get(callId)
        if (round === undefined) continue
        const live = round.replies.map((reply, index) => replyItem(reply, `${callId}:${index}`, { live: true })).filter(Boolean)
        items.splice(at, 0, ...live)
      }
    }
    return items
  }

  /** The round a room is running right now (`room-progress`). */
  const roomStateOf = roomId => {
    const rounds = [...rt.roomProgress.entries()].filter(([, entry]) => entry.roomId === roomId)
    return { running: rounds.length > 0, speaking: rounds.map(([, entry]) => entry.speaking).find(Boolean) ?? null }
  }

  /** Rows of one event list, the way this Session shows them. */
  const rowsOf = (sessionId, events, session, extra = {}, { cursorless = false } = {}) => {
    if (rt.roomOf(sessionId) !== undefined) {
      return composeRoomRows({ events, baseItems: withLiveReplies(events, cursorless) })
    }
    const botId = rt.chatOwners.get(sessionId)
    return composeTimelineRows({
      events,
      baseItems: rt.displayItems(sessionId, events, session),
      exchangeTurns: state.exchangeTurns[sessionId] ?? [],
      resolveBot: ref => rt.botOf(ref)?.name ?? (typeof ref === 'string' && ref !== '' ? ref : undefined),
      botName: botId !== undefined ? rt.botOf(botId)?.name : undefined,
      cwd: extra.cwd,
      inbox: extra.inbox,
    })
  }

  /** Rows the feed has not carried yet, plus the buffer's reach. A client that
   * cannot bridge its own cursor to `from` re-reads the snapshot; one that cannot
   * bridge at all (the buffer rotated) gets `reset`. Rows without a seq (a room's
   * live round replies) ride along while their key is new — the client swaps them
   * in place when the settled row lands under the same key. */
  rt.timelineDelta = sessionId => {
    const buffer = buffers.get(sessionId)
    if (buffer === undefined) return { rows: [], seq: 0, from: 0, bufferFrom: 0 }
    const events = eventsOf(buffer)
    const bufferFrom = events.length > 0 ? events[0].seq : 0
    const seq = events.length > 0 ? events.at(-1).seq : 0
    const last = published.get(sessionId) ?? { seq: 0, keys: new Set() }
    published.set(sessionId, { seq: Math.max(last.seq, seq), keys: last.keys })
    if (last.seq > 0 && last.seq < bufferFrom - 1) {
      const base = { rows: [], seq, from: last.seq, bufferFrom, reset: true }
      return rt.roomOf(sessionId) !== undefined ? { ...base, ...roomStateOf(sessionId) } : base
    }
    const rows = rowsOf(sessionId, events, undefined, {
      // The delta path is sync, so the Session header's cwd is out of reach here —
      // the Bot's configured working folder is the same resolution the client makes.
      cwd: rt.workDirOf?.({ botId: rt.chatOwners.get(sessionId) }),
    }).filter(entry => {
      if (typeof entry.seq === 'number') return entry.seq > last.seq
      if (last.keys.has(entry.key)) return false
      last.keys.add(entry.key)
      return true
    })
    // The moment a declared turn closes: did the card make it into the delta? One
    // line per Session and turn — the evidence for (or against) the "card only
    // after a restart" report, straight at the scene.
    if (rt.roomOf(sessionId) === undefined) {
      for (const event of events) {
        if (event.type !== 'deliverables/presented' || !isPresentedData(event.data)) continue
        const turn = event.data.turn
        const logged = `${sessionId}:${turn}`
        if (cardsLogged.has(logged)) continue
        // "刚落": the turn's own turn/end is part of this delta's new span.
        const justEnded = events.some(item => item.type === 'turn/end' && item.data?.turn === turn && typeof item.seq === 'number' && item.seq > last.seq)
        if (!justEnded) continue
        cardsLogged.add(logged)
        const card = rows.find(entry => entry.kind === 'artifact' && entry.turn === turn)
        logLine(sessionId, `turn ${turn} closed with a declaration: card ${card ? `generated (${card.key}, ${card.payload.files.length} files, delivered=${card.payload.delivered})` : 'NOT generated'}; delta rows=${rows.length} seq=${seq}`)
      }
    }
    if (rt.roomOf(sessionId) !== undefined) return { rows, seq, from: last.seq, bufferFrom, ...roomStateOf(sessionId) }
    return { rows, seq, from: last.seq, bufferFrom }
  }

  /** The round a group chat is running right now, for the delta frames and the
   * snapshot answer (`room-progress` read through the projection's own face). */
  rt.timelineRoomState = roomId => roomStateOf(roomId)

  /** The `timeline` endpoint. Request `{botId|roomId, since?, cursor?, max?}`;
   * the answer carries ascending rows, the Session's top seq, and (rooms) the
   * round's running/speaking state. */
  rt.timelineEndpoint = async (body, signal) => {
    await rt.load()
    const roomId = typeof body.roomId === 'string' && body.roomId !== '' ? body.roomId : undefined
    const since = Number.isInteger(body.since) && body.since >= 0 ? body.since : undefined
    const cursor = isRecord(body.cursor) && Number.isInteger(body.cursor.beforeSeq) ? { beforeSeq: body.cursor.beforeSeq } : undefined
    const max = Number.isInteger(body.max) && body.max > 0 && body.max <= 400 ? body.max : TIMELINE_PAGE
    if (roomId !== undefined) {
      const room = rt.roomOf(roomId)
      if (room === undefined) throw Object.assign(new Error('Unknown group chat'), { code: 'bot/not-found' })
      const agent = await rt.resolveAgent(room.id)
      const page = await ctx.sessionController.page({
        address: { kind: 'session', sessionId: room.id },
        throughSeq: Number(agent.session.seq) - 1,
        ...(cursor ?? {}),
        maxMessages: max,
      }, signal ?? AbortSignal.timeout(HISTORY_TIMEOUT_MS))
      const events = page.records.map(record => record.event)
      // A running round's replies so far, spliced at their relay call (same ids as
      // the settled rows, so the client swaps them in place when the result logs).
      const baseItems = withLiveReplies(events, cursor === undefined)
      const rows = composeRoomRows({ events, baseItems })
      // What the round queued for its next relay (the plugin's own room inbox), and
      // anything the room Session's agent inbox holds, as tail rows.
      const queued = typeof rt.roomInbox?.get === 'function' ? rt.roomInbox.get(room.id) : undefined
      rows.push(...roomInboxRowsOf(queued, { roomId: room.id }))
      rows.push(...inboxRowsOf(agent.inbox === undefined ? undefined : { nextTurn: agent.inbox.nextTurn, nextStep: agent.inbox.nextStep }))
      // 群聊**不画产物卡**（2026-11-14 定案，对齐官方）。上游 `room-artifacts` 端点
      // 在 DS-Bot 全仓（含网页端 client.js）没有任何消费方 —— 官方群聊界面根本不显示
      // 群级产物卡，所以是我们接上去的额外 UI。它的两条喂料在共用工作区上都不可信：
      // 成员会话取最新一页里**每一个** present；`recentFiles` 按 mtime 扫
      // `/root/.dsh/bot/workspace` —— 那是私聊与群聊**共用**的目录，谁写的分不出来。
      // 结果就是把 Bot 私聊做的文档/图片全列进群卡（真机实证：6 个文件 mtime 全在
      // 房间最后一轮之后、`delivered=false`）。群成员交付的文件看各自的私聊（官方同）。
      const roundState = roomStateOf(room.id)
      return {
        roomId: room.id,
        seq: Number(agent.session.seq) - 1,
        rows,
        cursor: page.hasMore && events.length > 0 ? { beforeSeq: events[0].seq } : null,
        running: roundState.running,
        speaking: roundState.speaking,
      }
    }
    const bot = rt.botOf(String(body.botId ?? ''))
    if (bot === undefined) throw Object.assign(new Error('Unknown Bot'), { code: 'bot/not-found' })
    const sessionId = rt.chatOf(bot.id)
    const agent = await rt.resolveAgent(sessionId)
    const cwd = agent.session.header?.cwd ?? rt.workDirOf?.({ botId: bot.id })
    const inbox = agent.inbox === undefined ? undefined : { nextTurn: agent.inbox.nextTurn, nextStep: agent.inbox.nextStep }
    // A `since` the buffer still reaches bridges without touching the Session log;
    // anything older replays from the log (capped — a client that far behind just
    // re-reads from the top and merges by key).
    if (since !== undefined && cursor === undefined) {
      const buffer = buffers.get(sessionId)
      if (buffer !== undefined && buffer.events.size > 0) {
        const events = eventsOf(buffer)
        if (since >= events[0].seq - 1) {
          const rows = rowsOf(sessionId, events, agent.session, { cwd, inbox }).filter(entry => typeof entry.seq === 'number' && entry.seq > since)
          return { sessionId, seq: Number(agent.session.seq) - 1, rows, cursor: null, replayed: true }
        }
      }
    }
    const page = await ctx.sessionController.page({
      address: { kind: 'session', sessionId },
      throughSeq: Number(agent.session.seq) - 1,
      ...(cursor ?? {}),
      maxMessages: max,
    }, signal ?? AbortSignal.timeout(HISTORY_TIMEOUT_MS))
    const events = page.records.map(record => record.event)
    const rows = rowsOf(sessionId, events, agent.session, { cwd, inbox })
    return {
      sessionId,
      seq: Number(agent.session.seq) - 1,
      rows,
      cursor: page.hasMore && events.length > 0 ? { beforeSeq: events[0].seq } : null,
      ...(since !== undefined && events.length > 0 && events[0].seq > since + 1 ? { partial: true } : {}),
    }
  }

  rt.timelineBufferFrom = sessionId => {
    const buffer = buffers.get(sessionId)
    if (buffer === undefined || buffer.events.size === 0) return 0
    return eventsOf(buffer)[0].seq
  }
}
