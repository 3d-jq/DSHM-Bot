// The Bot's own browser tools, for a window that drives a browser itself instead of handing
// it to playwright: on Android the app keeps one off-screen WebView per Bot, and every tool
// here becomes one request on the `browser-wait` channel (`kind` says what to do). The page
// lives in the app, so it survives the panel being closed — a Bot opens a page, the user
// sees it whenever they open the panel, and nothing needs the panel to be open first.
//
// `read_browser` (reading the page) stays in `browser.js`, next to the channel. The desktop
// app's playwright plugin (`browser-plugins.js`) owns this whole group where it is
// configured; the two sets must never be registered together.
import { defineTool } from '@deepseek-ai/dsh-tools'
import { output } from './tools.js'
import { NO_APP, NO_BROWSER } from './browser.js'

// A hand-over can take as long as a person needs; the call polls the window while it waits.
const HAND_OVER_MS = 15 * 60 * 1000
// The window says it has no page for this Bot: that is a state, not a failure.
const describeError = message => (message === 'no-page' ? NO_BROWSER : `That did not work: ${message}`)

/**
 * @param {any} rt
 */
export function install(rt) {
  // Nothing to drive: the window side is the channel `browser.js` installs, and a
  // playwright plugin (when configured) registers this same tool group itself.
  if (!rt.browserEnabled || rt.browserChannel === undefined) return
  if (rt.config.browserPlugin || rt.config.browserPluginApi) return
  const channel = rt.browserChannel

  const botOf = async exec => {
    await rt.load()
    const botId = rt.selfOf(exec.agent?.id)
    return botId !== undefined && rt.botOf(botId) !== undefined ? botId : undefined
  }

  // One tool = one request: the window runs a small script against the page it holds for
  // this Bot and answers `result` or `error`. `open` may reach a window that has no page for
  // this Bot yet — that is how a Bot loads its first page without the panel being open.
  const browserTool = (name, description, parameters, build) => rt.teamTools.push(defineTool({
    name,
    description,
    parameters,
    output,
    async execute(args, exec) {
      const botId = await botOf(exec)
      if (botId === undefined) return 'Only Bots on the team have a browser.'
      const request = build(args ?? {})
      if (request.error !== undefined) return request.error
      const answer = await channel.call(botId, request)
      if (answer.absent) return NO_APP
      if (answer.error) return describeError(answer.error)
      const value = answer.result
      if (value !== null && typeof value === 'object' && typeof value.error === 'string') {
        return describeError(value.error)
      }
      return value === undefined ? 'Done.' : JSON.stringify(value)
    },
  }))

  const selectorOf = (args) => {
    const selector = typeof args.selector === 'string' ? args.selector.trim() : ''
    return selector === '' ? { error: 'Give a CSS selector that names the element, for example #submit or button[aria-label="Next"].' } : { selector }
  }

  browserTool(
    'browser_open',
    'Load a page in your own browser. Give a full address such as https://example.com, or words to search for. It loads in your current tab, or in a new tab with new_tab: true. The page stays in your browser even when the panel is closed, so the user sees it when they look; open one before reading a site, or when the user asks you to look something up. Returns the address and title once the page has loaded.',
    {
      url: { type: 'string', required: true, description: 'Address to load, or words to search for' },
      new_tab: { type: 'boolean', description: 'Load in a new tab instead of the current one' },
    },
    (args) => {
      const url = typeof args.url === 'string' ? args.url.trim() : ''
      if (url === '') return { error: 'Give an address or a search phrase.' }
      return { kind: 'open', url, newTab: args.new_tab === true }
    },
  )
  browserTool(
    'browser_click',
    'Click one element on the page in your own browser; the user watches it happen. Give a CSS selector that names one element (#submit, a[href*="pricing"], button[aria-label="Next"]). Read the page first with read_browser to see its links and controls. A click whose label says pay, send or delete asks the user for a yes first; do not work around that. Do not click anything that deletes, pays, sends or submits unless the user asked for exactly that.',
    {
      selector: { type: 'string', required: true, description: 'CSS selector of the element to click' },
    },
    (args) => {
      const picked = selectorOf(args)
      return picked.error === undefined ? { kind: 'click', selector: picked.selector } : picked
    },
  )
  browserTool(
    'browser_type',
    'Type into a field on the page in your own browser. The field keeps the text and the page sees input and change events, as if the user typed it; what the field held is replaced. Give a CSS selector for the field and the text to put in it. Read the page first to find the field. Password and verification-code fields are refused: for those, call browser_hand_over and let the user type it themselves.',
    {
      selector: { type: 'string', required: true, description: 'CSS selector of the input, textarea or editable element' },
      text: { type: 'string', required: true, description: 'Text to put in the field' },
    },
    (args) => {
      const picked = selectorOf(args)
      if (picked.error !== undefined) return picked
      return { kind: 'type', selector: picked.selector, text: typeof args.text === 'string' ? args.text : '' }
    },
  )
  browserTool(
    'browser_scroll',
    'Scroll the page in your own browser. Use it to bring more of the page into view or to reach a part that a click needs. Read the page with read_browser afterwards when you need the new part in text.',
    {
      direction: { type: 'string', enum: ['down', 'up', 'top', 'bottom'], description: 'Which way to scroll; down by default' },
      amount: { type: 'integer', description: 'Pixels to scroll for down or up; 600 by default' },
    },
    (args) => ({
      kind: 'scroll',
      direction: typeof args.direction === 'string' ? args.direction : 'down',
      amount: Number.isInteger(args.amount) ? Math.max(0, Math.min(args.amount, 5000)) : 600,
    }),
  )
  browserTool(
    'browser_back',
    'Go back one page in your own browser.',
    {},
    () => ({ kind: 'back' }),
  )
  browserTool(
    'browser_forward',
    'Go forward one page in your own browser, after browser_back.',
    {},
    () => ({ kind: 'forward' }),
  )
  browserTool(
    'browser_reload',
    'Reload the page in your own browser, for a page that changed.',
    {},
    () => ({ kind: 'reload' }),
  )
  browserTool(
    'browser_tabs',
    'List the tabs in your own browser: each tab id, title, address, and which one is active.',
    {},
    () => ({ kind: 'tabs' }),
  )
  browserTool(
    'browser_close_tab',
    'Close one tab in your own browser, by the id browser_tabs shows.',
    {
      tab_id: { type: 'string', required: true, description: 'Tab id from browser_tabs' },
    },
    (args) => {
      const id = typeof args.tab_id === 'string' ? args.tab_id.trim() : String(args.tab_id ?? '')
      return id === '' ? { error: 'Give the tab id from browser_tabs.' } : { kind: 'close', tabId: id }
    },
  )
  browserTool(
    'browser_hover',
    'Move the pointer over one element in your own browser, for menus that open on hover.',
    { selector: { type: 'string', required: true, description: 'CSS selector of the element' } },
    (args) => {
      const picked = selectorOf(args)
      return picked.error === undefined ? { kind: 'hover', selector: picked.selector } : picked
    },
  )
  browserTool(
    'browser_execute_js',
    'Run a snippet of JavaScript on the page in your own browser and get its value back. Only a synchronous value comes back; a Promise is refused (read the page afterwards instead). Use it for what the other tools cannot do.',
    { script: { type: 'string', required: true, description: 'JavaScript to run in the page' } },
    (args) => {
      const script = typeof args.script === 'string' ? args.script : ''
      return script.trim() === '' ? { error: 'Give the JavaScript to run.' } : { kind: 'executeJs', script }
    },
  )
  browserTool(
    'browser_get_backbone',
    'Describe the structure of the page in your own browser: title, landmarks, headings, and the controls with a CSS selector for each. Read this when the page is big and you need to know what can be clicked.',
    {},
    () => ({ kind: 'backbone' }),
  )
  browserTool(
    'browser_fetch',
    'Request an address from inside the page you have open, with its cookies, and get the status and body back as text. Use it for JSON, or a page you only need as data.',
    {
      url: { type: 'string', required: true, description: 'Address to request; a relative path resolves against the page' },
      method: { type: 'string', description: 'HTTP method; GET by default' },
      body: { type: 'string', description: 'Request body for POST or PUT, sent as JSON' },
    },
    (args) => {
      const url = typeof args.url === 'string' ? args.url.trim() : ''
      if (url === '') return { error: 'Give a URL to fetch.' }
      return {
        kind: 'fetch',
        url,
        method: typeof args.method === 'string' ? args.method : 'GET',
        body: typeof args.body === 'string' ? args.body : '',
      }
    },
  )
  browserTool(
    'browser_wait_for_dom_stable',
    'Wait until the page in your own browser stops changing (its text, elements and height stay the same), for a page that renders after it loads. Returns whether it settled and how long it waited.',
    {
      quiet_ms: { type: 'integer', description: 'How long nothing must change; 800 by default' },
      timeout_ms: { type: 'integer', description: 'Give up after this long; 15000 by default' },
    },
    (args) => ({
      kind: 'waitStable',
      quietMs: Number.isInteger(args.quiet_ms) ? args.quiet_ms : 800,
      timeoutMs: Number.isInteger(args.timeout_ms) ? args.timeout_ms : 15000,
    }),
  )
  browserTool(
    'browser_scroll_and_collect',
    'Scroll the page in your own browser to the bottom, loading anything that appears as it goes, then read the whole page as Markdown. Use it for a long list or a feed instead of scrolling by hand.',
    {},
    () => ({ kind: 'scrollCollect' }),
  )
  browserTool(
    'browser_screenshot',
    'Take a picture of the page in your own browser and save it as a PNG file, returning its path. It works while the page is on screen; a page that was never shown may come out blank.',
    {},
    () => ({ kind: 'screenshot' }),
  )
  browserTool(
    'browser_get_cookies',
    'List the cookies your own browser has for the current page, as they are sent to that site.',
    {},
    () => ({ kind: 'getCookies' }),
  )
  browserTool(
    'browser_set_cookie',
    'Set one cookie in your own browser, for the current page or a given address. Use it when a site needs a flag or locale set before it works.',
    {
      name: { type: 'string', required: true, description: 'Cookie name' },
      value: { type: 'string', required: true, description: 'Cookie value' },
      url: { type: 'string', description: 'Address the cookie belongs to; the current page by default' },
    },
    (args) => {
      const name = typeof args.name === 'string' ? args.name.trim() : ''
      if (name === '') return { error: 'Give the cookie name.' }
      return {
        kind: 'setCookie',
        name,
        value: typeof args.value === 'string' ? args.value : '',
        url: typeof args.url === 'string' ? args.url : '',
      }
    },
  )
  browserTool(
    'browser_user_agent',
    'Read the user agent of your own browser, or set a new one (an empty value restores the default). It applies to every tab of this Bot and reloads the page.',
    {
      value: { type: 'string', description: 'User agent to use; omit to read the current one, empty to restore the default' },
    },
    (args) => (typeof args.value === 'string' ? { kind: 'userAgent', value: args.value } : { kind: 'userAgent' }),
  )
  browserTool(
    'browser_viewport',
    'Lay the page in your own browser out for a given CSS width, as a device of that width would see it, and reload. Use it when a site switches layout on width.',
    { width: { type: 'integer', required: true, description: 'Viewport width in CSS pixels' } },
    (args) => (Number.isInteger(args.width)
      ? { kind: 'viewport', width: args.width }
      : { error: 'Give the viewport width in CSS pixels.' }),
  )

  // Handing the page to the person: a login, a code, a payment, a CAPTCHA. The app brings
  // the browser forward, this call waits (polling, so no request stays open for minutes),
  // and the Bot reads the page again afterwards. It never sees what the user typed.
  rt.teamTools.push(defineTool({
    name: 'browser_hand_over',
    description: 'Give your browser page to the user and stop acting. Use it when the page needs a person: a login, a password, a verification code, a payment or a CAPTCHA. The app brings your browser forward and the user works in it; this call returns once they tap Done (or after 15 minutes). Then read the page again with read_browser and carry on. Never ask for the password or code in the chat.',
    parameters: {
      reason: { type: 'string', required: true, description: 'One short line telling the user what to do, such as "Sign in with your account"' },
    },
    output,
    async execute(args, exec) {
      const botId = await botOf(exec)
      if (botId === undefined) return 'Only Bots on the team have a browser.'
      const reason = typeof args.reason === 'string' ? args.reason.trim().slice(0, 200) : ''
      if (reason === '') return 'Say what the user needs to do in the page.'
      const started = await channel.call(botId, { kind: 'handOver', reason })
      if (started.absent) return NO_APP
      if (started.error) return describeError(started.error)
      const id = started.result?.id
      if (typeof id !== 'string' || id === '') return 'The browser could not be handed over.'
      const deadline = Date.now() + HAND_OVER_MS
      while (Date.now() < deadline) {
        await new Promise(resolve => setTimeout(resolve, 2_000))
        if (exec.signal?.aborted) return 'The hand-over was cancelled.'
        const state = await channel.call(botId, { kind: 'handOverWait', handOverId: id })
        if (state.absent) return NO_APP
        if (state.error) return `The hand-over ended: ${state.error}`
        const status = state.result?.status
        if (status === 'done') return 'The user finished. Read your browser page again with read_browser to go on.'
        if (status === 'gone') return 'The hand-over ended before the user finished. Ask them whether to try again.'
      }
      return 'The user did not finish within 15 minutes. Ask them whether to carry on.'
    },
  }))
}
