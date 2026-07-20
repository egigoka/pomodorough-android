package me.egigoka.pomodorough.ui

import android.app.Activity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import me.egigoka.pomodorough.data.TimerRepository
import me.egigoka.pomodorough.data.TimerStatus

class PomodoroughViewModel(
    private val repository: TimerRepository,
) : ViewModel() {
    val state = repository.state
    private var timerTickJob: Job? = null

    init {
        viewModelScope.launch { repository.initialize() }
    }

    fun signIn(activity: Activity) = launch { repository.signIn(activity) }
    fun logout() = launch { repository.logout() }
    fun toggleTimer() = launch { repository.toggleTimer() }
    fun finishTimer() = launch { repository.finishTimer() }
    fun cancelTimer() = launch { repository.cancelTimer() }
    fun clearTimer() = launch { repository.clearTimer() }
    fun selectPhase(phase: String) = launch { repository.selectPhase(phase) }
    fun changeDuration(phase: String, delta: Int) = launch {
        repository.changeDuration(phase, delta)
    }
    fun setAutoStart(enabled: Boolean) = launch { repository.setAutoStart(enabled) }
    fun dismissConflict() = repository.dismissConflict()
    fun dismissNotice() = repository.dismissNotice()
    fun onForeground() {
        repository.onForeground()
        timerTickJob?.cancel()
        timerTickJob = viewModelScope.launch {
            state.map { appState ->
                appState.timer?.takeIf { it.status == TimerStatus.Running }?.id
            }.distinctUntilChanged().collectLatest { timerId ->
                if (timerId == null) return@collectLatest
                while (isActive) {
                    delay(500)
                    repository.finishExpiredTimer()
                }
            }
        }
    }

    fun onBackground() {
        timerTickJob?.cancel()
        timerTickJob = null
        repository.onBackground()
    }

    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }

    class Factory(private val repository: TimerRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return PomodoroughViewModel(repository) as T
        }
    }
}
