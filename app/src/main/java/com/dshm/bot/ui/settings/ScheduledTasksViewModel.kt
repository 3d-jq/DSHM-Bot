package com.dshm.bot.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.dshm.bot.data.remote.DsBotApi
import com.dshm.bot.data.remote.DshClients
import com.dshm.bot.data.remote.ScheduleList
import com.dshm.bot.data.remote.ScheduleTarget
import com.dshm.bot.data.runtime.RuntimeEndpoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 定时任务页的状态。 */
data class ScheduledTasksUiState(
    val loading: Boolean = false,
    val available: Boolean = true,
    val tasks: List<com.dshm.bot.data.remote.ScheduledTask> = emptyList(),
    val targets: List<ScheduleTarget> = emptyList(),
    val error: String? = null,
)

/**
 * 「定时任务」页状态层：读清单、删任务。
 *
 * 端点在 DS Bot 的 `/api/bot` 通道上（`schedules` / `schedule-delete`）。
 */
class ScheduledTasksViewModel(
    private val appContext: Context,
) : ViewModel() {

    private var repository: DsBotApi? = null
    private val _ui = MutableStateFlow(ScheduledTasksUiState())
    val ui: StateFlow<ScheduledTasksUiState> = _ui.asStateFlow()

    /** 引擎连上后才有 DS Bot 通道。 */
    fun setEndpoint(endpoint: RuntimeEndpoint?) {
        repository = endpoint?.let { DsBotApi(DshClients.of(it)) }
        refresh()
    }

    fun refresh() {
        val repo = repository ?: return
        _ui.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            runCatching { repo.schedules() }
                .onSuccess { list ->
                    _ui.update { it.copy(loading = false, available = list.available, tasks = list.tasks) }
                    loadTargets(repo)
                }
                .onFailure { failure ->
                    _ui.update { it.copy(loading = false, error = failure.message ?: "读取失败") }
                }
        }
    }

    private fun loadTargets(repo: DsBotApi) {
        viewModelScope.launch {
            val targets = runCatching { repo.targets() }.getOrDefault(emptyList())
            _ui.update { it.copy(targets = targets) }
        }
    }

    /** 删一个任务。 */
    fun delete(task: com.dshm.bot.data.remote.ScheduledTask, onDone: (String?) -> Unit = {}) {
        val repo = repository ?: return
        viewModelScope.launch {
            runCatching { repo.deleteSchedule(task.sessionId, task.id) }
                .onSuccess { list ->
                    _ui.update { it.copy(available = list.available, tasks = list.tasks) }
                    onDone(null)
                }
                .onFailure { failure -> onDone(failure.message ?: "删除失败") }
        }
    }

    companion object {
        fun factory(context: Context): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                ScheduledTasksViewModel(context.applicationContext)
            }
        }
    }
}
