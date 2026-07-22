package me.egigoka.pomodorough

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.egigoka.pomodorough.ui.PomodoroughScreen
import me.egigoka.pomodorough.ui.PomodoroughTheme
import me.egigoka.pomodorough.ui.PomodoroughViewModel

class MainActivity : ComponentActivity() {
    private val viewModel by viewModels<PomodoroughViewModel> {
        PomodoroughViewModel.Factory(
            (application as PomodoroughApplication).timerRepository,
        )
    }
    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { viewModel.toggleTimer() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        lifecycle.addObserver(LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> viewModel.onForeground()
                Lifecycle.Event.ON_STOP -> viewModel.onBackground()
                else -> Unit
            }
        })
        setContent {
            val state by viewModel.state.collectAsStateWithLifecycle()
            PomodoroughTheme {
                PomodoroughScreen(
                    state = state,
                    onSignIn = { viewModel.signIn(this) },
                    onLogout = viewModel::logout,
                    onToggleTimer = ::startOrToggleTimer,
                    onFinishTimer = viewModel::finishTimer,
                    onCancelTimer = viewModel::cancelTimer,
                    onClearTimer = viewModel::clearTimer,
                    onSelectPhase = viewModel::selectPhase,
                    onChangeDuration = viewModel::changeDuration,
                    onSetAutoStart = viewModel::setAutoStart,
                    onSelectTask = viewModel::selectTask,
                    onAddTask = viewModel::addTask,
                    onDeleteTask = viewModel::deleteTask,
                    onResolveHistory = viewModel::resolveHistory,
                    onRecoverHistoryResolution = viewModel::recoverHistoryResolution,
                    onConfirmAccountSwitch = viewModel::confirmAccountSwitch,
                    onCancelAccountSwitch = viewModel::cancelAccountSwitch,
                    onDismissConflict = viewModel::dismissConflict,
                    onDismissNotice = viewModel::dismissNotice,
                )
            }
        }
    }

    private fun startOrToggleTimer() {
        val needsPermission = Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED &&
            viewModel.state.value.timer?.status != "running"
        if (needsPermission) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            viewModel.toggleTimer()
        }
    }
}
