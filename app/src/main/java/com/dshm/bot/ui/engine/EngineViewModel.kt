package com.dshm.bot.ui.engine

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.dshm.bot.data.runtime.RuntimeManager
import com.dshm.bot.data.runtime.RuntimeState
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** 本机引擎页状态层：包装 [RuntimeManager]，界面只读状态、发动作。 */
class EngineViewModel(private val manager: RuntimeManager) : ViewModel() {

    val state: StateFlow<RuntimeState> = manager.state

    fun install(url: String) {
        viewModelScope.launch { manager.installFromUrl(url) }
    }

    companion object {
        fun factory(context: Context): ViewModelProvider.Factory = viewModelFactory {
            initializer { EngineViewModel(RuntimeManager(context.applicationContext)) }
        }
    }
}
