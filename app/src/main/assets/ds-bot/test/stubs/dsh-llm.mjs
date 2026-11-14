// Inert stand-in for `@deepseek-ai/dsh-llm`: the projection test paths import the
// module but never call into it (displayItems and the row builders only read
// session events). Names must exist so the imports bind.
export class BlockAssembler {
  constructor() {}
}

export function createUserMessage() {
  throw new Error('stubs/dsh-llm: createUserMessage must not run in projection tests')
}
