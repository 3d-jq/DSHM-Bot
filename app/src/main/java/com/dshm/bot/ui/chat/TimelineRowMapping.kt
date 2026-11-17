package com.dshm.bot.ui.chat

import com.dshm.bot.data.remote.ChangesSummary
import com.dshm.bot.data.remote.TimelineFile
import com.dshm.bot.data.remote.TimelineRow
import com.dshm.bot.data.remote.TimelineRowPayload
import org.json.JSONArray
import org.json.JSONObject

/**
 * TimelineRow → ChatEntry 的映射（Phase 1b 主管线切换）。
 *
 * 各 kind 对齐现有渲染分支：user/bot/tool/artifact/event/divider/view/answered/inbox
 * （`docs/phase1-design.md` §2 冻结 schema，群聊同构——key 里带 roomId 的行同样按
 * key 规则映射，群成员 botId/name 走 payload）。渲染键规则（设计 §2 规则 1）：
 * user 行认领本地 echo 时渲染键保留 `p:<requestId>`，由调用方传 [renderKey]。
 * 未知/未展开的 kind（Raw：turntail 等）返回 null，调用方跳过。
 */
internal fun chatEntryOf(
    row: TimelineRow,
    botName: String,
    renderKey: String? = null,
    attachmentItems: List<ChatAttachmentItem> = emptyList(),
    /** `changes` 行的摘要（宿主 `/api/changes.summary` 的解析结果）；未拉到时为 null。 */
    changesSummary: ChangesSummary? = null,
): ChatEntry? {
    val id = renderKey ?: row.key
    val seq = row.seq
    val time = row.timeMs
    return when (val payload = row.payload) {
    is TimelineRowPayload.User -> ChatEntry(
        id = id,
        kind = ChatEntryKind.USER,
        text = payload.text,
        attachments = payload.blocks.mapNotNull { it.optString("name").takeIf { name -> name.isNotEmpty() } },
        attachmentItems = attachmentItems,
        timeMs = time,
        seq = seq,
        images = payload.images,
        live = payload.live,
    )

    is TimelineRowPayload.Bot -> ChatEntry(
        id = id,
        kind = ChatEntryKind.BOT,
        text = payload.text,
        author = payload.author ?: botName,
        botId = payload.botId,
        timeMs = time,
        durationMs = payload.durationMs,
        seq = seq,
        whole = payload.whole?.let(::chatWholePostOf),
        live = payload.live,
    )

        is TimelineRowPayload.Tool -> ChatEntry(
            id = id,
            kind = ChatEntryKind.EVENT,
            text = payload.label,
            author = botName,
            timeMs = time,
            seq = seq,
            target = payload.target,
            tool = payload.tool,
        )

        is TimelineRowPayload.Artifact -> ChatEntry(
            id = id,
            kind = ChatEntryKind.ARTIFACTS,
            text = "",
            timeMs = time,
            seq = seq,
            artifacts = payload.files.map { file -> ChatArtifact(path = file.path, description = file.description) },
            artifactsDelivered = payload.delivered,
        )

        is TimelineRowPayload.Event -> ChatEntry(
            id = id,
            kind = ChatEntryKind.EVENT,
            text = payload.raw.optString("text"),
            author = payload.name ?: botName,
            botId = payload.botId,
            timeMs = time,
            eventKind = payload.eventKind.takeIf { it.isNotEmpty() },
            eventCode = payload.code,
            seq = seq,
        )

        is TimelineRowPayload.Divider -> ChatEntry(
            id = id,
            kind = ChatEntryKind.DIVIDER,
            text = "",
            timeMs = payload.timeMs ?: time,
            seq = seq,
        )

        is TimelineRowPayload.View -> ChatEntry(
            id = id,
            kind = ChatEntryKind.VIEW,
            text = payload.view.optString("title"),
            timeMs = time,
            seq = seq,
            view = ChatViewCard(
                id = payload.view.optString("callId").ifEmpty { id },
                title = payload.view.optString("title").takeIf { it.isNotBlank() },
                view = requireNotNull(payload.view.optJSONObject("view")),
            ),
        )

        is TimelineRowPayload.Answered -> {
            val answeredView = chatAnsweredViewOf(payload.answered)
            val answered = if (answeredView == null) chatAnsweredQuestionsOf(payload.answered) else emptyList()
            when {
                answeredView != null -> ChatEntry(
                    id = id,
                    kind = ChatEntryKind.ANSWERED,
                    text = "",
                    timeMs = time,
                    seq = seq,
                    answeredView = answeredView,
                )

                answered.isNotEmpty() -> ChatEntry(
                    id = id,
                    kind = ChatEntryKind.ANSWERED,
                    text = "",
                    timeMs = time,
                    seq = seq,
                    answered = answered,
                )

                else -> null
            }
        }

        is TimelineRowPayload.Inbox -> ChatEntry(
            id = id,
            kind = ChatEntryKind.PENDING,
            text = payload.text,
            author = payload.name,
            botId = payload.botId,
            timeMs = time,
            seq = null,
            pendingSlot = payload.slot,
            pendingRequestId = payload.requestId,
            pendingSourceKind = payload.sourceKind,
            pendingName = payload.name,
        )

        // `changes` 行：摘要还没拉到（或宿主已不提供）就不出行 —— 官方也是
        // 「摘到且 files 非空才画卡」；拉到后 ViewModel 会重算一次 entries。
        is TimelineRowPayload.Changes -> changesSummary?.let { summary ->
            ChatEntry(
                id = id,
                kind = ChatEntryKind.CHANGES,
                text = "",
                timeMs = time,
                seq = seq,
                changes = summary,
            )
        }

        // turntail / streaming 等未展开 kind：1c 再接，不渲染。
        is TimelineRowPayload.Raw -> null
    }
}

/** 长文消息的附件（`whole.file`）：网页版「显示全部」按它调 `file-text`。 */
internal fun chatWholePostOf(whole: JSONObject): ChatWholePost? {
    val file = whole.optJSONObject("file") ?: return null
    return ChatWholePost(
        name = file.optString("name").takeIf { it.isNotBlank() } ?: "长文",
        length = whole.optInt("length", 0),
        file = file,
    )
}

/**
 * `answer` 行里的问答对。
 *
 * 形状来自插件（`parts.js`）：`questions: [asked]` / `answers: [given]`，
 * 作答里 `selected` 是选中的选项文案，`custom` 是自定义输入（对齐网页版 `SettledQuestion`）。
 */
internal fun chatAnsweredQuestionsOf(item: JSONObject): List<ChatAnsweredQuestion> {
    val questions = item.optJSONArray("questions") ?: return emptyList()
    if (questions.length() == 0) return emptyList()
    val answers = item.optJSONArray("answers")
    return buildList {
        for (index in 0 until questions.length()) {
            val question = questions.optJSONObject(index) ?: continue
            val prompt = question.optString("question")
                .ifBlank { question.optString("prompt") }
                .ifBlank { question.optString("title") }
            val id = question.optString("id")
            val given = (0 until (answers?.length() ?: 0))
                .mapNotNull { answers?.optJSONObject(it) }
                .firstOrNull { it.optString("id") == id }
            val picked = buildList {
                given?.optJSONArray("selected")?.let { selected ->
                    for (at in 0 until selected.length()) add(selected.optString(at))
                }
                given?.optString("custom")?.takeIf { it.isNotBlank() }?.let(::add)
            }
            add(ChatAnsweredQuestion(question = prompt, answers = picked))
        }
    }
}

/** `answer` 行里回答过的视图卡（网页版 `AnsweredView`）。 */
internal fun chatAnsweredViewOf(item: JSONObject): ChatAnsweredView? {
    val views = item.optJSONArray("views") ?: return null
    val shown = (0 until views.length())
        .mapNotNull { views.optJSONObject(it) }
        .firstOrNull { it.optJSONObject("view") != null }
        ?: return null
    val id = shown.optString("id")
    val answers = item.optJSONArray("answers")
    val given = (0 until (answers?.length() ?: 0))
        .mapNotNull { answers?.optJSONObject(it) }
        .let { list -> list.firstOrNull { it.optString("id") == id } ?: list.firstOrNull() }
    return ChatAnsweredView(
        card = ChatViewCard(
            id = id,
            title = shown.optString("title").takeIf { it.isNotBlank() },
            view = requireNotNull(shown.optJSONObject("view")),
        ),
        action = given?.optString("action").orEmpty(),
        values = given?.optJSONObject("values") ?: JSONObject(),
    )
}

/** `present` 产物卡文件条目 → 渲染卡的附件模型。 */
internal fun List<TimelineFile>.toChatArtifacts(): List<ChatArtifact> =
    map { file -> ChatArtifact(path = file.path, description = file.description) }

/** JSON 数组辅助（载荷原样形状的容错读取）。 */
internal fun JSONArray?.objectListOrEmpty(): List<JSONObject> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

/**
 * user 行的 `blocks` → 气泡里要画的附件项（纯函数，单测直接锁）。
 *
 * 关键：**按 `type` 认图片**、并把**整份 attachment ref** 带上 —— 只有名字的话
 * 历史里的图片会被画成文件卡、也点不开（真机 2026-11-16）。
 *
 * @param cached 本地已下载过的同一附件（按 attachmentId 查），命中就直接复用。
 */
internal fun attachmentItemsOfBlocks(
    blocks: List<JSONObject>,
    cached: (String) -> ChatAttachmentItem? = { null },
): List<ChatAttachmentItem> = blocks.mapNotNull { block ->
    // ⚠️ `offloaded`（引擎把老图抽成编号，不再喂给模型）**不跳过**：那只是「不给模型看」，
    // 附件本身还在（`{...block, offloaded:true}`，ref 原样保留、字节也读得到）。
    // 早先按它跳过 → 老图全变成一张点不开的图标（真机 2026-11-17）。
    val raw = block.optJSONObject("attachment")
    val name = block.optString("name").takeIf { it.isNotEmpty() }
        ?: raw?.optString("name")?.takeIf { it.isNotEmpty() }
        ?: return@mapNotNull null
    val id = raw?.optString("attachmentId")?.takeIf { it.isNotEmpty() }
    val image = block.optString("type") == "image" ||
        com.dshm.bot.data.remote.artifactKindOf(name) == com.dshm.bot.data.remote.ArtifactKind.IMAGE
    id?.let(cached) ?: ChatAttachmentItem(
        name = name,
        path = "",
        isImage = image,
        attachmentId = id,
        rawAttachment = raw?.toString(),
    )
}

/**
 * 本地 echo 行插回列表的下标：**它属于发送那一刻的位置**。
 *
 * 从后往前找第一条 `seq <= anchor` 的行，插在它后面 —— 也就是「这一轮之后的
 * 一切（回答 / 工具行 / 卡片）都排在它后面」。以前 echo 一律追加到末尾，落定那
 * 一刻流式气泡一退，用户的气泡就成了最后一条（掉到回答下面），随后被认领摘掉又
 * 消失，界面闪一下（真机 2026-11-17）。
 *
 * 没有锚（老路径 / 拿不到游标）就退回追加末尾；`seq` 为 null 的行（浏览器卡、
 * 别的 echo）不参与比较。
 */
internal fun echoInsertIndex(entries: List<ChatEntry>, anchor: Long?): Int {
    if (anchor == null) return entries.size
    for (index in entries.indices.reversed()) {
        val seq = entries[index].seq ?: continue
        if (seq <= anchor) return index + 1
    }
    return entries.size
}

/**
 * 把**本地已有的副本路径**贴回附件项（气泡能画缩略图 / 点开看）。
 *
 * 为什么要按「行里的 ref」为骨架、只贴 path：ref 是权威的（type/id 都在），
 * 本地那份只是字节；两边按**下标**再按**名字**对齐（发送时 blocks 与 draft 同序）。
 *
 * 真机（2026-11-17）：进程重启后 `pendingEchoAttachments`（内存）没了，而落盘的
 * `sentAttachments[requestId]` 还在 —— 之前只查内存表，自己发的图就退化成一张
 * 点不开的图标。
 */
internal fun mergeLocalAttachmentPaths(
    items: List<ChatAttachmentItem>,
    local: List<ChatAttachmentItem>,
): List<ChatAttachmentItem> {
    if (local.isEmpty()) return items
    val usable = local.filter { it.path.isNotEmpty() }
    if (usable.isEmpty()) return items
    return items.mapIndexed { index, item ->
        if (item.path.isNotEmpty()) return@mapIndexed item
        val hit = usable.getOrNull(index)?.takeIf { it.name == item.name || usable.size == items.size }
            ?: usable.firstOrNull { it.name == item.name }
            ?: return@mapIndexed item
        item.copy(path = hit.path)
    }
}
