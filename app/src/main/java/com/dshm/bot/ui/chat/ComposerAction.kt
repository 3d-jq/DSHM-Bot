package com.dshm.bot.ui.chat

/** 输入框右侧当前画哪颗圆键。 */
internal enum class ComposerTrailingAction {
    STOP_DICTATION,
    TRANSCRIBING,
    STOP_RUN,
    VOICE,
    SEND,
}

/**
 * 右侧圆键的单一判定入口。
 *
 * 停止回复优先于麦克风：只要回合还没稳定结束，空白草稿也不能把停止键换成麦克风，
 * 否则 `state.work` 一次短暂空窗就会让按钮在两者之间跳。
 */
internal fun composerTrailingAction(
    listening: Boolean,
    transcribing: Boolean,
    stopVisible: Boolean,
    draft: String,
    voiceAvailable: Boolean,
): ComposerTrailingAction = when {
    listening -> ComposerTrailingAction.STOP_DICTATION
    transcribing -> ComposerTrailingAction.TRANSCRIBING
    stopVisible -> ComposerTrailingAction.STOP_RUN
    draft.isBlank() && voiceAvailable -> ComposerTrailingAction.VOICE
    else -> ComposerTrailingAction.SEND
}
