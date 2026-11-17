// The window side of the Bot browser channel (src/host/browser.js): the app holds a long
// poll (`browser-wait`) and answers with `browser-result`. These lock the two contracts the
// Android app relies on and the 0.3.6 fork upgrade once broke: a request may reach the app
// before that Bot has any page open (so a Bot can open its first page with the panel
// closed), and an answer carries the action result, not only a page.
//
// Run:  node --test --import ./test/stub-resolve.mjs test/
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { createBrowserChannel } from '../src/host/browser.js'

const channelOf = () => createBrowserChannel({
  waitMs: 50,
  readTimeoutMs: 1_000,
  actionTimeoutMs: 1_000,
  staleMs: 30_000,
})

test('an action reaches the app before that Bot reports any page', async () => {
  const channel = channelOf()
  // The app polls with nothing open: this is the state right after start-up.
  const poll = channel.wait('app-1', {})
  const answered = channel.call('bot1', { kind: 'open', url: 'https://example.com', newTab: false })
  const items = await poll
  assert.equal(items.length, 1)
  assert.equal(items[0].kind, 'open')
  assert.equal(items[0].botId, 'bot1')
  assert.equal(items[0].url, 'https://example.com')
  channel.result({ id: items[0].id, ok: true, result: { url: 'https://example.com', title: 'Example' } })
  assert.deepEqual(await answered, { page: undefined, result: { url: 'https://example.com', title: 'Example' } })
  channel.dispose()
})

test('a read carries kind "read", which the app treats as the page read', async () => {
  const channel = channelOf()
  const poll = channel.wait('app-1', { bot1: { url: 'https://example.com', title: 'Example' } })
  const answered = channel.read('bot1')
  const items = await poll
  assert.equal(items.length, 1)
  assert.equal(items[0].kind, 'read')
  channel.result({ id: items[0].id, ok: true, page: { url: 'https://example.com', title: 'Example', text: 'hi' } })
  const value = await answered
  assert.equal(value.page.text, 'hi')
  channel.dispose()
})

test('an action failure comes back as the app’s error', async () => {
  const channel = channelOf()
  const poll = channel.wait('app-1', {})
  const answered = channel.call('bot1', { kind: 'click', selector: '#go' })
  const items = await poll
  channel.result({ id: items[0].id, ok: false, error: 'no-page' })
  assert.deepEqual(await answered, { error: 'no-page' })
  channel.dispose()
})

test('with no app polling at all the call reports the window absent', async () => {
  const channel = channelOf()
  assert.deepEqual(await channel.call('bot1', { kind: 'open', url: 'https://example.com' }), { absent: true })
  channel.dispose()
})

test('a request goes to the window that reports that Bot’s page, not just the newest', async () => {
  const channel = channelOf()
  // One window holds bot1's page; a newer one polls with nothing open.
  await channel.wait('app-old', { bot1: { url: 'https://a.example', title: 'A' } })
  const newer = channel.wait('app-new', {})
  const mine = channel.wait('app-old', { bot1: { url: 'https://a.example', title: 'A' } })
  const answered = channel.call('bot1', { kind: 'read' })
  const items = await mine
  assert.equal(items.length, 1)
  assert.equal(items[0].botId, 'bot1')
  assert.equal((await newer).length, 0)
  channel.result({ id: items[0].id, ok: true, page: { url: 'https://a.example', title: 'A', text: '' } })
  assert.equal((await answered).page.url, 'https://a.example')
  channel.dispose()
})
