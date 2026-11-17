// The Bot's own browser tools (src/host/browser-tools.js). Locks the regression a plugin
// upgrade once caused: the group must register whenever the app is the browser window
// (`DS_BOT_APP=1`), and each tool must speak the protocol the Android app answers
// (`kind`, then `browser-result`). Without this, the model sees `unknown tool "browser_open"`.
//
// Run:  node --test --import ./test/stub-resolve.mjs test/
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { install } from '../src/host/browser-tools.js'

/** A runtime with the app as the window: the channel is a recorder, no dsh involved. */
const runtimeOf = ({ browserPlugin, browserPluginApi } = {}) => {
  const sent = []
  const rt = {
    config: { ...(browserPlugin ? { browserPlugin } : {}), ...(browserPluginApi ? { browserPluginApi } : {}) },
    browserEnabled: true,
    browserChannel: {
      async call(botId, request) {
        sent.push({ botId, ...request })
        if (request.kind === 'handOver') return { result: { id: 'handOver-1' } }
        if (request.kind === 'handOverWait') return { result: { status: 'done' } }
        return { result: { done: request.kind } }
      },
    },
    teamTools: [],
    async load() {},
    selfOf(id) { return id === 'agent-1' ? 'bot1' : undefined },
    botOf(id) { return id === 'bot1' ? { id: 'bot1' } : undefined },
  }
  return { rt, sent }
}

const exec = { agent: { id: 'agent-1' } }
const toolOf = (rt, name) => rt.teamTools.find(tool => tool.name === name)

test('the group registers and browser_open sends the app an open request', async () => {
  const { rt, sent } = runtimeOf()
  install(rt)
  const names = rt.teamTools.map(tool => tool.name)
  for (const name of ['browser_open', 'browser_click', 'browser_type', 'browser_scroll', 'browser_tabs', 'browser_screenshot', 'browser_hand_over']) {
    assert.ok(names.includes(name), `${name} must be registered`)
  }
  const value = await toolOf(rt, 'browser_open').execute({ url: 'https://example.com', new_tab: true }, exec)
  assert.deepEqual(sent[0], { botId: 'bot1', kind: 'open', url: 'https://example.com', newTab: true })
  assert.equal(value, JSON.stringify({ done: 'open' }))
})

test('a tool says so when the app is not polling, and what to do with no page', async () => {
  const gone = runtimeOf()
  gone.rt.browserChannel.call = async () => ({ absent: true })
  install(gone.rt)
  const absent = await toolOf(gone.rt, 'browser_click').execute({ selector: '#go' }, exec)
  assert.match(absent, /open the app/)

  const empty = runtimeOf()
  empty.rt.browserChannel.call = async () => ({ error: 'no-page' })
  install(empty.rt)
  const noPage = await toolOf(empty.rt, 'browser_click').execute({ selector: '#go' }, exec)
  assert.match(noPage, /browser_open/)
})

test('a playwright browser plugin owns the group instead, so nothing is registered twice', () => {
  const injected = runtimeOf({ browserPluginApi: { id: 'refs' } })
  install(injected.rt)
  assert.equal(injected.rt.teamTools.length, 0)
  const named = runtimeOf({ browserPlugin: 'refs' })
  install(named.rt)
  assert.equal(named.rt.teamTools.length, 0)
})

test('an unknown Bot gets no browser, and a bad argument is explained', async () => {
  const { rt } = runtimeOf()
  install(rt)
  const stranger = await toolOf(rt, 'browser_open').execute({ url: 'https://example.com' }, { agent: { id: 'agent-2' } })
  assert.match(stranger, /Only Bots on the team/)
  const empty = await toolOf(rt, 'browser_open').execute({ url: '  ' }, exec)
  assert.match(empty, /address or a search phrase/)
})

test('browser_hand_over starts a hand-over and waits for the user to finish', async () => {
  const { rt, sent } = runtimeOf()
  install(rt)
  const value = await toolOf(rt, 'browser_hand_over').execute({ reason: 'Sign in' }, exec)
  assert.deepEqual(sent[0], { botId: 'bot1', kind: 'handOver', reason: 'Sign in' })
  assert.equal(sent[1].kind, 'handOverWait')
  assert.equal(sent[1].handOverId, 'handOver-1')
  assert.match(value, /Read your browser page again/)
})
