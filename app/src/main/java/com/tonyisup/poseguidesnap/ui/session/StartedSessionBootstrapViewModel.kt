package com.tonyisup.poseguidesnap.ui.session

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tonyisup.poseguidesnap.ui.editor.StartedSessionHandle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

internal class StartedSessionBootstrapViewModel(
    private val handle: StartedSessionHandle,
    private val workflow: StartedSessionBootstrapWorkflowPort,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    closeAuthority: () -> Unit,
) : ViewModel() {
    private val lock = Any()
    private val _state = MutableStateFlow<StartedSessionBootstrapState>(
        StartedSessionBootstrapState.Loading,
    )
    val state: StateFlow<StartedSessionBootstrapState> = _state
    private var loadJob: Job? = null
    private var loadGeneration = 0L
    private var generationExhausted = false
    private var cleared = false
    private var closeAuthority: (() -> Unit)? = closeAuthority

    init {
        load()
    }

    fun retry() {
        load()
    }

    private fun load() {
        val nextJob = synchronized(lock) {
            if (cleared || !canLoad(_state.value)) return
            loadJob?.cancel()
            if (generationExhausted || loadGeneration == Long.MAX_VALUE) {
                generationExhausted = true
                loadJob = null
                _state.value = StartedSessionBootstrapState.Unavailable(canRetry = false)
                null
            } else {
                loadGeneration += 1L
                val generation = loadGeneration
                _state.value = StartedSessionBootstrapState.Loading
                viewModelScope.launch(dispatcher, start = CoroutineStart.LAZY) {
                    val result = try {
                        workflow.load(handle)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: RuntimeException) {
                        StartedSessionBootstrapState.Unavailable(canRetry = true)
                    }
                    complete(generation, result)
                }.also { loadJob = it }
            }
        } ?: return
        nextJob.start()
    }

    private fun canLoad(state: StartedSessionBootstrapState): Boolean = when (state) {
        StartedSessionBootstrapState.Loading -> true
        is StartedSessionBootstrapState.Unavailable -> state.canRetry
        is StartedSessionBootstrapState.Ready,
        StartedSessionBootstrapState.Completed,
        StartedSessionBootstrapState.ReconciliationRequired,
        StartedSessionBootstrapState.Missing,
        -> false
    }

    private fun complete(generation: Long, result: StartedSessionBootstrapState) {
        synchronized(lock) {
            if (cleared || generationExhausted || loadGeneration != generation) return
            loadJob = null
            _state.value = result
        }
    }

    override fun onCleared() {
        val close = synchronized(lock) {
            if (cleared) {
                null
            } else {
                cleared = true
                loadJob?.cancel()
                loadJob = null
                closeAuthority.also { closeAuthority = null }
            }
        }
        viewModelScope.cancel()
        try {
            close?.invoke()
        } finally {
            super.onCleared()
        }
    }

    override fun toString(): String = "StartedSessionBootstrapViewModel(redacted)"
}
