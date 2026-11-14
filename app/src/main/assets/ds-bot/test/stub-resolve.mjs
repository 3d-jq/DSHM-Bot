// Test resolver for the assets copy: the host modules import their @deepseek-ai
// peers (dsh-llm above all), which a real profile supplies. The projection tests
// never call into them — they only need the imports to resolve — so a local stub
// stands in instead of a harness install (this folder ships inside an APK; no
// node_modules may live here).
//
// Run:  node --test --import ./test/stub-resolve.mjs test/
import { registerHooks } from 'node:module'
import { pathToFileURL, fileURLToPath } from 'node:url'

const here = pathToFileURL(`${fileURLToPath(import.meta.url)}/../`).href
const pluginUrl = pathToFileURL(`${fileURLToPath(import.meta.url)}/../../`).href
const STUBS = new Map([
  ['@deepseek-ai/dsh-llm', new URL('./stubs/dsh-llm.mjs', here).href],
  ['@deepseek-ai/dsh-tools', new URL('./stubs/dsh-tools.mjs', here).href],
  ['@deepseek-ai/dsh-typert-protocol', new URL('./stubs/dsh-typert-protocol.mjs', here).href],
])

registerHooks({
  resolve(specifier, context, nextResolve) {
    if (STUBS.has(specifier) && context.parentURL?.startsWith(pluginUrl)) {
      return { url: STUBS.get(specifier), shortCircuit: true }
    }
    return nextResolve(specifier, context)
  },
})
