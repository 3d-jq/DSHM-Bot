package com.dshm.bot.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.dshm.bot.data.remote.DsBotRepository
import com.dshm.bot.data.remote.DshClient
import com.dshm.bot.data.remote.EmptyBotRepository
import com.dshm.bot.data.runtime.RuntimeEndpoint
import com.dshm.bot.domain.model.BotInks
import com.dshm.bot.domain.model.BotSummary
import com.dshm.bot.domain.repository.BotRepository
import com.dshm.bot.ui.format.RelativeTimeFormatter
import java.time.Clock
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 主界面状态层：从 [BotRepository] 读数据，负责排序、搜索过滤与时间文案，
 * UI 只消费 [uiState]。搜索/排序规则集中在这里，便于单测。
 */
class HomeViewModel(
    repository: BotRepository = EmptyBotRepository(),
    clock: Clock = Clock.systemDefaultZone(),
    zoneId: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault(),
) : ViewModel() {

    private var repository: BotRepository = repository
    private var collectJob: Job? = null
    private val bots = MutableStateFlow<List<BotSummary>>(emptyList())
    private val query = MutableStateFlow("")
    private val loading = MutableStateFlow(true)
    private val failed = MutableStateFlow(false)

    private val timeFormatter = RelativeTimeFormatter(zoneId = zoneId, clock = clock, locale = locale)

    val uiState: StateFlow<HomeUiState> = combine(bots, query, loading, failed) { list, currentQuery, isLoading, hasError ->
        HomeUiState(
            isLoading = isLoading,
            hasError = hasError,
            query = currentQuery,
            rows = buildRows(list, currentQuery),
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = HomeUiState(),
    )

    init {
        start(repository)
    }

    /** 本机引擎端点变化时切换到真实 DS Bot 数据源；null 表示引擎未运行。 */
    fun setEndpoint(endpoint: RuntimeEndpoint?) {
        start(if (endpoint == null) EmptyBotRepository() else DsBotRepository(DshClient(endpoint)))
    }

    private fun start(source: BotRepository) {
        collectJob?.cancel()
        repository = source
        bots.value = emptyList()
        loading.value = true
        failed.value = false
        collectJob = viewModelScope.launch {
            repository.observeBots()
                .catch {
                    failed.value = true
                    loading.value = false
                }
                .collect { list ->
                    bots.value = list
                    loading.value = false
                }
        }
    }

    fun setQuery(value: String) {
        query.value = value
    }

    /** 当前正在展示的 Bot id，用于搜索时保留上下文（暂未使用，供后续跳转恢复）。 */
    fun botIds(): List<String> = uiState.value.rows.map { it.id }

    private fun buildRows(list: List<BotSummary>, rawQuery: String): List<ChatRowUi> {
        val needle = rawQuery.trim()
        val filtered = if (needle.isEmpty()) {
            list
        } else {
            list.filter { bot ->
                bot.name.contains(needle, ignoreCase = true) || bot.preview.contains(needle, ignoreCase = true)
            }
        }
        return filtered
            .sortedWith(compareByDescending<BotSummary> { it.pinned }.thenByDescending { it.lastActiveAt })
            .map { bot ->
                ChatRowUi(
                    id = bot.id,
                    name = bot.name,
                    preview = bot.preview,
                    timeLabel = timeFormatter.format(bot.lastActiveAt),
                    // DS Bot 规则：没手选颜色的 Bot 由 id 决定一个固定颜色，所有设备一致。
                    inkId = bot.inkId ?: BotInks.defaultColorId(bot.id),
                    glyph = bot.glyph,
                    pinned = bot.pinned,
                    isGroup = bot.isGroup,
                    unreadCount = bot.unreadCount,
                    avatarImage = bot.avatarImage,
                    avatarShape = bot.avatarShape,
                )
            }
    }

    companion object {
        fun factory(repository: BotRepository = EmptyBotRepository()): ViewModelProvider.Factory = viewModelFactory {
            initializer { HomeViewModel(repository) }
        }
    }
}
