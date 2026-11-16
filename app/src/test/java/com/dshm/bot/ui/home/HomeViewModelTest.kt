package com.dshm.bot.ui.home

import com.dshm.bot.MainDispatcherRule
import com.dshm.bot.TestTime
import com.dshm.bot.data.remote.DshSearchHit
import com.dshm.bot.domain.model.AvatarGlyph
import com.dshm.bot.domain.model.BotSummary
import com.dshm.bot.domain.repository.BotRepository
import java.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private fun bot(
        id: String,
        name: String,
        lastActiveAt: Instant,
        preview: String = "preview",
        pinned: Boolean = false,
        unread: Int = 0,
    ) = BotSummary(
        id = id,
        name = name,
        preview = preview,
        lastActiveAt = lastActiveAt,
        glyph = AvatarGlyph.BOT,
        pinned = pinned,
        unreadCount = unread,
    )

    private fun viewModel(bots: List<BotSummary>) = HomeViewModel(
        repository = FakeBotRepository(bots),
        clock = TestTime.clock,
        zoneId = TestTime.zone,
        locale = TestTime.locale,
    )

    @Test
    fun loadsRowsSortedByPinnedThenRecent() = runTest(mainDispatcherRule.testDispatcher) {
        val viewModel = viewModel(
            listOf(
                bot("older", "Older", Instant.parse("2026-11-01T10:00:00Z")),
                bot("pinned", "Pinned", Instant.parse("2026-10-01T10:00:00Z"), pinned = true),
                bot("newer", "Newer", Instant.parse("2026-11-01T12:00:00Z")),
            ),
        )
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertFalse(state.hasError)
        assertEquals(listOf("pinned", "newer", "older"), state.rows.map { it.id })
    }

    @Test
    fun formatsRowTimeWithFixedClock() = runTest(mainDispatcherRule.testDispatcher) {
        val viewModel = viewModel(listOf(bot("a", "A", Instant.parse("2026-11-01T13:00:00Z"))))
        advanceUntilIdle()

        assertEquals("21:00", viewModel.uiState.value.rows.single().timeLabel)
    }

    @Test
    fun searchFiltersByNameIgnoringCaseAndTrimming() = runTest(mainDispatcherRule.testDispatcher) {
        val viewModel = viewModel(
            listOf(
                bot("a", "Office Bot", Instant.parse("2026-11-01T10:00:00Z")),
                bot("b", "Mint", Instant.parse("2026-11-01T11:00:00Z"), preview = "office notes"),
                bot("c", "Other", Instant.parse("2026-11-01T12:00:00Z")),
            ),
        )
        advanceUntilIdle()

        viewModel.setQuery("  office ")
        advanceUntilIdle()
        assertEquals(listOf("b", "a"), viewModel.uiState.value.rows.map { it.id })

        viewModel.setQuery("mInT")
        advanceUntilIdle()
        assertEquals(listOf("b"), viewModel.uiState.value.rows.map { it.id })
    }

    @Test
    fun clearingQueryRestoresEveryRow() = runTest(mainDispatcherRule.testDispatcher) {
        val viewModel = viewModel(
            listOf(
                bot("a", "A", Instant.parse("2026-11-01T10:00:00Z")),
                bot("b", "B", Instant.parse("2026-11-01T11:00:00Z")),
            ),
        )
        advanceUntilIdle()

        viewModel.setQuery("a")
        advanceUntilIdle()
        assertEquals(1, viewModel.uiState.value.rows.size)

        viewModel.setQuery("")
        advanceUntilIdle()
        assertEquals(2, viewModel.uiState.value.rows.size)
        assertFalse(viewModel.uiState.value.showNoResults)
    }

    @Test
    fun searchWithoutMatchExposesNoResultsState() = runTest(mainDispatcherRule.testDispatcher) {
        val viewModel = viewModel(listOf(bot("a", "A", Instant.parse("2026-11-01T10:00:00Z"))))
        advanceUntilIdle()

        viewModel.setQuery("missing")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state.rows.isEmpty())
        assertTrue(state.showEmptyState)
        assertTrue(state.showNoResults)
    }

    @Test
    fun emptyRepositoryShowsEmptyStateWithoutError() = runTest(mainDispatcherRule.testDispatcher) {
        val viewModel = viewModel(emptyList())
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertFalse(state.hasError)
        assertTrue(state.showEmptyState)
        assertFalse(state.showNoResults)
    }

    @Test
    fun repositoryFailureExposesErrorState() = runTest(mainDispatcherRule.testDispatcher) {
        val viewModel = HomeViewModel(
            repository = FailingBotRepository,
            clock = TestTime.clock,
            zoneId = TestTime.zone,
            locale = TestTime.locale,
        )
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertTrue(state.hasError)
        assertFalse(state.showEmptyState)
    }

    @Test
    fun unreadTotalSumsVisibleRows() = runTest(mainDispatcherRule.testDispatcher) {
        val viewModel = viewModel(
            listOf(
                bot("a", "A", Instant.parse("2026-11-01T10:00:00Z"), unread = 2),
                bot("b", "B", Instant.parse("2026-11-01T11:00:00Z"), unread = 3),
            ),
        )
        advanceUntilIdle()

        assertEquals(5, viewModel.uiState.value.unreadTotal)
    }

    @Test
    fun rowsWithoutPickedColorUseDsBotDefaultColor() = runTest(mainDispatcherRule.testDispatcher) {
        val viewModel = viewModel(
            listOf(
                bot("bot-1", "A", Instant.parse("2026-11-01T10:00:00Z")),
                bot("group-42", "B", Instant.parse("2026-11-01T11:00:00Z")),
            ),
        )
        advanceUntilIdle()

        val inks = viewModel.uiState.value.rows.associate { it.id to it.inkId }
        assertEquals("blue", inks["bot-1"])
        assertEquals("orange", inks["group-42"])
    }


    /**
     * 正文搜索命中要映射回「哪一行」：Bot 的当前段、Bot 的更早分段、群聊房间 id 都算；
     * 映射不到的行（比如 DSH Agent 会话）直接丢掉，不显示成无名条目。
     */
    @Test
    fun searchHitsMapBackToTheOwningRow() {
        val vm = HomeViewModel()
        val rows = listOf(
            bot("bot-1", "小助手", Instant.parse("2026-01-01T00:00:00Z")).copy(
                sessionId = "sess-1",
                parts = listOf("sess-0"),
            ),
            bot("group-9", "项目群", Instant.parse("2026-01-01T00:00:00Z")).copy(
                isGroup = true,
                sessionId = "group-9",
            ),
        )
        val hits = vm.mapSearchHits(
            listOf(
                DshSearchHit("sess-1", "命中的片段"),
                DshSearchHit("sess-0", "命中更早的分段"),
                DshSearchHit("group-9", "群里的片段"),
                DshSearchHit("unknown-agent-session", "映射不到"),
            ),
            rows,
        )
        assertEquals(3, hits.size)
        assertEquals(listOf("bot-1", "bot-1", "group-9"), hits.map { it.rowId })
        assertEquals(listOf("小助手", "小助手", "项目群"), hits.map { it.name })
        assertEquals("命中的片段", hits.first().snippet)
    }

    private class FakeBotRepository(private val bots: List<BotSummary>) : BotRepository {
        override fun observeBots(): Flow<List<BotSummary>> = flowOf(bots)
    }

    private object FailingBotRepository : BotRepository {
        override fun observeBots(): Flow<List<BotSummary>> = flow { throw IllegalStateException("boom") }
    }
}
