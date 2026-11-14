// Inert stand-in for `@deepseek-ai/dsh-tools`: the projection tests never build a
// tool; the runtime smoke test only needs `defineTool` to return a registrable
// value (the fake ctx.tools swallows it).
export function defineTool(definition) {
  return definition
}
