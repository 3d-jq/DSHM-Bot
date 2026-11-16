// Stub for @deepseek-ai/dsh-llm: the host modules import a handful of names that a
// real DSH profile supplies. Our tests never call into them, so each name is a
// harmless placeholder that can also be constructed (`new X()`).

const placeholder = () => ({})
const placeholderClass = class {}

export const BlockAssembler = placeholderClass
export const HarnessError = class extends Error {}
export const LlmAdapter = placeholderClass
export const LlmError = class extends Error {}
export const boundContextSummary = placeholder
export const createAssistantMessage = placeholder
export const createSystemMessage = placeholder
export const createToolResultMessage = placeholder
export const createUserMessage = placeholder
export const resolveRetryPolicy = placeholder
