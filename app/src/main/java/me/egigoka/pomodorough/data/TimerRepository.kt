package me.egigoka.pomodorough.data

import android.app.Activity
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.io.IOException
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.egigoka.pomodorough.data.api.ApiException
import me.egigoka.pomodorough.data.api.PomodoroughService
import me.egigoka.pomodorough.data.auth.AuthSession
import me.egigoka.pomodorough.data.auth.AuthenticationRequired
import me.egigoka.pomodorough.data.local.LocalStateEntity
import me.egigoka.pomodorough.data.local.PendingCommandEntity
import me.egigoka.pomodorough.data.local.PendingDurationOperationEntity
import me.egigoka.pomodorough.data.local.PendingTaskOperationEntity
import me.egigoka.pomodorough.data.local.TimerDao
import me.egigoka.pomodorough.domain.TaskReducer
import me.egigoka.pomodorough.domain.TimerReducer
import me.egigoka.pomodorough.timer.TimerAlarmScheduler
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener

enum class AuthStatus { Loading, SignedOut, SigningIn, SignedIn }

enum class SyncStatus { Checking, Synced, Queued, Syncing, Retrying, Offline, Conflict }

data class AppState(
    val ready: Boolean = false,
    val authStatus: AuthStatus = AuthStatus.Loading,
    val user: User? = null,
    val timer: CanonicalTimer? = null,
    val history: List<HistoryItem> = emptyList(),
    val tasks: List<FocusTask> = emptyList(),
    val knownTasks: List<FocusTask> = emptyList(),
    val taskSummaries: List<TaskDailySummary> = emptyList(),
    val selectedTaskId: String? = null,
    val settings: TimerSettings = TimerSettings(),
    val pendingCount: Int = 0,
    val syncStatus: SyncStatus = SyncStatus.Checking,
    val conflict: String? = null,
    val notice: String? = null,
    val deviceId: String = "",
)

private data class SyncAttempt(
    val accountGeneration: Long,
    val request: SyncRequest,
)

private class SyncProtocolException(message: String) : Exception(message)

class TimerRepository(
    context: Context,
    private val dao: TimerDao,
    private val api: PomodoroughService,
    private val auth: AuthSession,
    private val json: Json,
    private val networkAvailable: (() -> Boolean)? = null,
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val initializeMutex = Mutex()
    private val actionMutex = Mutex()
    private val streamMutex = Mutex()
    private val initialized = CompletableDeferred<Unit>()
    private val networkInitializationStarted = AtomicBoolean(false)
    private val syncSignals = Channel<Unit>(Channel.CONFLATED)
    private val forceSync = AtomicBoolean(false)
    private val alarmScheduler = TimerAlarmScheduler(appContext)
    private val connectivity = appContext.getSystemService(ConnectivityManager::class.java)

    private lateinit var local: LocalStateEntity
    private var pending = emptyList<TimerCommand>()
    private var pendingDurationOperations = emptyList<DurationOperation>()
    private var pendingTaskOperations = emptyList<TaskOperation>()
    private var canonicalTimer: CanonicalTimer? = null
    private var canonicalHistory = emptyList<HistoryItem>()
    private var canonicalTasks = emptyList<FocusTask>()
    private var knownTasks = emptyMap<String, FocusTask>()
    private var tasks = emptyList<FocusTask>()
    private var projection = TimerProjection(null, emptyList())
    private var settings = TimerSettings()
    private var user: User? = null
    private var authStatus = AuthStatus.Loading
    private var syncing = false
    private var retrying = false
    private var terminalSyncError: String? = null
    private var conflict: String? = null
    private var notice: String? = null
    private var online = currentOnlineState()
    private var foreground = false
    private var eventSource: EventSource? = null
    private var accountGeneration = 0L

    private val _state = MutableStateFlow(AppState())
    val state: StateFlow<AppState> = _state.asStateFlow()

    init {
        scope.launch { syncLoop() }
        connectivity.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = updateNetworkState()
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) =
                updateNetworkState()
            override fun onLost(network: Network) = updateNetworkState()
        })
    }

    suspend fun initialize() {
        ensureLocalInitialized()
        if (!networkInitializationStarted.compareAndSet(false, true)) return
        if (auth.hasTokens()) {
            restoreProfile()
            if (authStatus == AuthStatus.SignedIn) {
                requestSync(force = true)
                if (foreground) openRevisionStream()
            }
        }
    }

    private suspend fun ensureLocalInitialized() {
        if (initialized.isCompleted) return
        initializeMutex.withLock {
            if (initialized.isCompleted) return
            val stored = dao.localState()
            local = stored ?: LocalStateEntity(
                deviceId = UUID.randomUUID().toString(),
                settingsJson = json.encodeToString(TimerSettings()),
            ).also { dao.insertState(it) }
            pending = dao.pendingCommands().map(PendingCommandEntity::toModel)
            pendingDurationOperations = dao.pendingDurationOperations()
                .map(PendingDurationOperationEntity::toModel)
            pendingTaskOperations = dao.pendingTaskOperations().map(PendingTaskOperationEntity::toModel)
            val highestSequence = pending.maxOfOrNull(TimerCommand::deviceSequence) ?: 0
            if (highestSequence > local.deviceSequence) {
                local = local.copy(deviceSequence = highestSequence)
                dao.updateState(local)
            }
            settings = decodeOr(local.settingsJson, TimerSettings())
            settings = replayDurationOperations(settings, pendingDurationOperations)
            canonicalTimer = local.canonicalTimerJson?.let { decodeOr<CanonicalTimer?>(it, null) }
            canonicalHistory = decodeOr(local.historyJson, emptyList())
            canonicalTasks = decodeOr(local.tasksJson, emptyList())
            knownTasks = decodeOr<List<FocusTask>>(local.knownTasksJson, emptyList())
                .plus(canonicalTasks)
                .associateBy(FocusTask::id)
            user = local.userJson?.let { decodeOr<User?>(it, null) }
            rebuildProjections()
            authStatus = if (auth.hasTokens()) AuthStatus.Loading else AuthStatus.SignedOut
            initialized.complete(Unit)
            publish()
            scheduleAlarm()
        }
    }

    suspend fun signIn(activity: Activity) {
        initialize()
        authStatus = AuthStatus.SigningIn
        notice = null
        publish()
        try {
            auth.signIn(activity, local.deviceId)
            val profile = auth.authorized(api::me).user
            bindProfile(profile)
            requestSync(force = true)
            openRevisionStream()
        } catch (error: Exception) {
            authStatus = AuthStatus.SignedOut
            user = null
            notice = error.message ?: "Google sign-in did not complete"
            publish()
        }
    }

    suspend fun logout() {
        initialize()
        actionMutex.withLock {
            accountGeneration += 1
            syncing = false
            retrying = false
            publish()
        }
        try {
            auth.logout()
            closeRevisionStream()
            actionMutex.withLock {
                val clearedSettings = settings.withDurations(DurationsMs())
                val nextLocal = local.copy(
                    revision = 0,
                    canonicalTimerJson = null,
                    historyJson = "[]",
                    tasksJson = "[]",
                    knownTasksJson = "[]",
                    selectedTaskId = null,
                    settingsJson = json.encodeToString(clearedSettings),
                    userJson = null,
                    ownerUserId = null,
                )
                dao.clearAccount(nextLocal)
                local = nextLocal
                canonicalTimer = null
                canonicalHistory = emptyList()
                canonicalTasks = emptyList()
                knownTasks = emptyMap()
                tasks = emptyList()
                projection = TimerProjection(null, emptyList())
                pending = emptyList()
                pendingDurationOperations = emptyList()
                pendingTaskOperations = emptyList()
                settings = clearedSettings
                user = null
                conflict = null
                terminalSyncError = null
                authStatus = AuthStatus.SignedOut
                alarmScheduler.cancel()
                publish()
            }
        } catch (error: Exception) {
            notice = error.message ?: "Logout failed. Local timer data was kept."
            publish()
        }
    }

    suspend fun toggleTimer() {
        initialize()
        when (projection.timer?.status) {
            TimerStatus.Running -> issueCommand(CommandType.Pause)
            TimerStatus.Paused -> issueCommand(CommandType.Resume)
            else -> issueCommand(CommandType.Start)
        }
    }

    suspend fun finishTimer() {
        initialize()
        val phase = projection.timer?.phase
        if (!issueCommand(CommandType.Finish)) return
        if (phase == TimerPhase.Focus && settings.autoStartBreaks) {
            issueCommand(CommandType.Start, nextBreakPhase())
        }
    }

    suspend fun cancelTimer() {
        initialize()
        issueCommand(CommandType.Cancel)
    }

    suspend fun clearTimer() {
        initialize()
        issueCommand(CommandType.Clear)
    }

    suspend fun selectPhase(phase: String) {
        initialize()
        if (phase !in TimerPhase.all || projection.timer?.status in activeStatuses) return
        persistSettings(settings.copy(selectedPhase = phase))
    }

    suspend fun changeDuration(phase: String, delta: Int) {
        initialize()
        if (phase !in TimerPhase.all) return
        var saved = false
        actionMutex.withLock {
            if (projection.timer?.status in activeStatuses) return@withLock
            val currentDurationMs = settings.durationMsFor(phase)
            val nextDurationMs = (
                currentDurationMs / DurationLimits.MinuteMs + delta.toLong()
            ).coerceIn(1L, DurationLimits.MaxMs / DurationLimits.MinuteMs) *
                DurationLimits.MinuteMs
            if (nextDurationMs == currentDurationMs) return@withLock

            val now = System.currentTimeMillis()
            val wallMs = maxOf(now, local.hlcWallMs, 1L)
            val counter = if (wallMs == local.hlcWallMs) local.hlcCounter + 1 else 0
            val operation = DurationOperation(
                id = "duration-operation-${UUID.randomUUID()}",
                phase = phase,
                durationMs = nextDurationMs,
                occurredAt = Instant.ofEpochMilli(now).toString(),
                hlcWallMs = wallMs,
                hlcCounter = counter,
            )
            val nextSettings = settings.withDuration(phase, nextDurationMs)
            val nextLocal = local.copy(
                hlcWallMs = wallMs,
                hlcCounter = counter,
                settingsJson = json.encodeToString(nextSettings),
            )
            dao.persistDurationOperation(PendingDurationOperationEntity.from(operation), nextLocal)
            local = nextLocal
            settings = nextSettings
            pendingDurationOperations = pendingDurationOperations
                .filterNot { it.phase == phase } + operation
            publish()
            saved = true
        }
        if (saved) requestSync()
    }

    suspend fun setAutoStart(enabled: Boolean) {
        initialize()
        persistSettings(settings.copy(autoStartBreaks = enabled))
    }

    suspend fun selectTask(taskId: String?) {
        initialize()
        actionMutex.withLock {
            if (projection.timer?.status in activeStatuses) return@withLock
            if (taskId != null && tasks.none { it.id == taskId }) return@withLock
            local = local.copy(selectedTaskId = taskId)
            dao.updateState(local)
            publish()
        }
    }

    suspend fun addTask(title: String) {
        initialize()
        val task = TaskReducer.taskFromTitle(title)
        if (task == null) {
            notice = "Task must contain printable text and fit within 512 bytes."
            publish()
            return
        }
        val existing = tasks.firstOrNull { it.id == task.id }
        if (existing != null) {
            selectTask(existing.id)
            return
        }
        issueTaskOperation(TaskOperationType.Upsert, task, select = true)
    }

    suspend fun deleteTask(taskId: String) {
        initialize()
        val task = tasks.firstOrNull { it.id == taskId } ?: return
        issueTaskOperation(TaskOperationType.Delete, task)
    }

    suspend fun finishExpiredTimer(): Boolean {
        ensureLocalInitialized()
        val timer = projection.timer ?: return false
        if (timer.status == TimerStatus.Running &&
            TimerReducer.elapsedAt(timer) >= timer.plannedDurationMs
        ) {
            val phase = timer.phase
            if (!issueCommand(CommandType.Finish)) return false
            if (phase == TimerPhase.Focus && settings.autoStartBreaks) {
                issueCommand(CommandType.Start, nextBreakPhase())
            }
            return true
        }
        return false
    }

    suspend fun rescheduleAlarmFromLocal() {
        ensureLocalInitialized()
        scheduleAlarm()
    }

    fun dismissConflict() {
        val shouldRetry = terminalSyncError != null
        terminalSyncError = null
        conflict = null
        publish()
        if (shouldRetry) requestSync(force = true)
    }

    fun dismissNotice() {
        notice = null
        publish()
    }

    fun onForeground() {
        foreground = true
        requestSync(force = true)
        scope.launch { openRevisionStream() }
    }

    fun onBackground() {
        foreground = false
        scope.launch { closeRevisionStream() }
    }

    private suspend fun restoreProfile() {
        try {
            bindProfile(auth.authorized(api::me).user)
        } catch (error: IOException) {
            authStatus = AuthStatus.SignedOut
            user = null
            notice = error.message ?: "Could not verify signed-in account"
        } catch (_: AuthenticationRequired) {
            authStatus = AuthStatus.SignedOut
            user = null
        }
        publish()
    }

    private suspend fun bindProfile(profile: User) = actionMutex.withLock {
        val ownerChanged = local.ownerUserId != profile.id
        val nextSettings = if (ownerChanged) settings.withDurations(DurationsMs()) else settings
        val nextLocal = if (ownerChanged) {
            local.copy(
                revision = 0,
                canonicalTimerJson = null,
                historyJson = "[]",
                tasksJson = "[]",
                knownTasksJson = "[]",
                selectedTaskId = null,
                settingsJson = json.encodeToString(nextSettings),
                userJson = json.encodeToString(profile),
                ownerUserId = profile.id,
            )
        } else {
            local.copy(userJson = json.encodeToString(profile))
        }
        if (ownerChanged) dao.clearAccount(nextLocal) else dao.updateState(nextLocal)
        accountGeneration += 1
        local = nextLocal
        settings = nextSettings
        if (ownerChanged) {
            pending = emptyList()
            pendingDurationOperations = emptyList()
            canonicalTimer = null
            canonicalHistory = emptyList()
            canonicalTasks = emptyList()
            knownTasks = emptyMap()
            tasks = emptyList()
            projection = TimerProjection(null, emptyList())
            pendingTaskOperations = emptyList()
            conflict = null
            alarmScheduler.cancel()
        }
        user = profile
        conflict = null
        terminalSyncError = null
        authStatus = AuthStatus.SignedIn
        publish()
    }

    private suspend fun persistSettings(next: TimerSettings) = actionMutex.withLock {
        settings = next
        local = local.copy(settingsJson = json.encodeToString(settings))
        dao.updateState(local)
        publish()
    }

    private suspend fun issueCommand(type: String, startingPhase: String? = null): Boolean {
        var saved = false
        actionMutex.withLock {
            val now = System.currentTimeMillis()
            val current = projection.timer
            val starting = type == CommandType.Start
            val timerId = if (starting) UUID.randomUUID().toString() else current?.id
            if (timerId == null || !validTransition(type, current)) return@withLock
            val phase = if (starting) {
                startingPhase ?: settings.selectedPhase
            } else {
                current?.phase ?: return@withLock
            }
            val durationMs = if (starting) {
                settings.durationMsFor(phase)
            } else {
                current?.plannedDurationMs ?: return@withLock
            }
            val wallMs = maxOf(now, local.hlcWallMs)
            val counter = if (wallMs == local.hlcWallMs) local.hlcCounter + 1 else 0
            val command = TimerCommand(
                id = UUID.randomUUID().toString(),
                deviceSequence = local.deviceSequence + 1,
                timerId = timerId,
                type = type,
                phase = phase,
                plannedDurationMs = durationMs,
                occurredAt = Instant.ofEpochMilli(now).toString(),
                hlcWallMs = wallMs,
                hlcCounter = counter,
                observedElapsedMs = if (starting) 0 else TimerReducer.elapsedAt(current, now),
                taskId = if (starting && phase == TimerPhase.Focus) {
                    local.selectedTaskId?.takeIf { selected -> tasks.any { it.id == selected } }
                } else {
                    null
                },
            )
            val nextLocal = local.copy(
                deviceSequence = command.deviceSequence,
                hlcWallMs = wallMs,
                hlcCounter = counter,
            )
            dao.persistCommand(PendingCommandEntity.from(command), nextLocal)
            local = nextLocal
            pending = pending + command
            rebuildProjections()
            publish()
            scheduleAlarm()
            saved = true
        }
        if (saved) requestSync()
        return saved
    }

    private suspend fun issueTaskOperation(
        type: String,
        task: FocusTask,
        select: Boolean = false,
    ) {
        var saved = false
        actionMutex.withLock {
            if (select && projection.timer?.status in activeStatuses) return@withLock
            val now = System.currentTimeMillis()
            val wallMs = maxOf(now, local.hlcWallMs)
            val counter = if (wallMs == local.hlcWallMs) local.hlcCounter + 1 else 0
            val operation = TaskOperation(
                id = "task-operation-${UUID.randomUUID()}",
                taskId = task.id,
                type = type,
                title = task.title.takeIf { type == TaskOperationType.Upsert },
                occurredAt = Instant.ofEpochMilli(now).toString(),
                hlcWallMs = wallMs,
                hlcCounter = counter,
            )
            val nextKnownTasks = knownTasks + (task.id to task)
            val nextLocal = local.copy(
                hlcWallMs = wallMs,
                hlcCounter = counter,
                selectedTaskId = when {
                    select -> task.id
                    type == TaskOperationType.Delete && local.selectedTaskId == task.id -> null
                    else -> local.selectedTaskId
                },
                knownTasksJson = json.encodeToString(nextKnownTasks.values.sortedBy(FocusTask::id)),
            )
            dao.persistTaskOperation(PendingTaskOperationEntity.from(operation), nextLocal)
            local = nextLocal
            knownTasks = nextKnownTasks
            pendingTaskOperations = pendingTaskOperations + operation
            rebuildProjections()
            publish()
            saved = true
        }
        if (saved) requestSync()
    }

    private fun validTransition(type: String, timer: CanonicalTimer?): Boolean = when (type) {
        CommandType.Start -> true
        CommandType.Pause -> timer?.status == TimerStatus.Running
        CommandType.Resume -> timer?.status == TimerStatus.Paused || timer?.status == TimerStatus.Superseded
        CommandType.Finish, CommandType.Cancel -> timer?.status in activeStatuses
        CommandType.Clear -> timer != null && timer.status !in activeStatuses
        else -> false
    }

    private fun nextBreakPhase(): String {
        val completedFocus = projection.history.count {
            it.status == TimerStatus.Completed && it.phase == TimerPhase.Focus
        }
        return if (completedFocus > 0 && completedFocus % 4 == 0) {
            TimerPhase.LongBreak
        } else {
            TimerPhase.ShortBreak
        }
    }

    private fun requestSync(force: Boolean = false) {
        if (force) forceSync.set(true)
        syncSignals.trySend(Unit)
    }

    private suspend fun syncLoop() {
        for (signal in syncSignals) {
            initialized.await()
            var forced = forceSync.getAndSet(false)
            var retryDelay = 1_000L
            while (scope.isActive && authStatus == AuthStatus.SignedIn) {
                if (terminalSyncError != null) {
                    publish()
                    break
                }
                if (!online) {
                    publish()
                    break
                }
                if (!forced &&
                    pending.isEmpty() &&
                    pendingTaskOperations.isEmpty() &&
                    pendingDurationOperations.isEmpty()
                ) {
                    retrying = false
                    publish()
                    break
                }
                try {
                    syncOnce()
                    retryDelay = 1_000L
                    forced = false
                    if (pending.isEmpty() &&
                        pendingTaskOperations.isEmpty() &&
                        pendingDurationOperations.isEmpty()
                    ) break
                } catch (_: AuthenticationRequired) {
                    actionMutex.withLock {
                        accountGeneration += 1
                        authStatus = AuthStatus.SignedOut
                        user = null
                        syncing = false
                        retrying = false
                        publish()
                    }
                    break
                } catch (error: SyncProtocolException) {
                    markTerminalSyncError(error.message ?: "Sync protocol validation failed")
                    break
                } catch (error: SerializationException) {
                    markTerminalSyncError(error.message ?: "Sync returned a malformed response")
                    break
                } catch (error: ApiException) {
                    syncing = false
                    if (!error.isRetryable()) {
                        markTerminalSyncError(error.message ?: "Sync rejected (${error.statusCode})")
                        break
                    }
                    retrying = true
                    publish()
                    delay(retryDelay)
                    retryDelay = (retryDelay * 2).coerceAtMost(60_000L)
                    forced = true
                } catch (_: IOException) {
                    syncing = false
                    retrying = true
                    publish()
                    delay(retryDelay)
                    retryDelay = (retryDelay * 2).coerceAtMost(60_000L)
                    forced = true
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    syncing = false
                    retrying = false
                    notice = error.message ?: "Sync stopped after a local failure"
                    publish()
                    break
                }
            }
        }
    }

    private suspend fun syncOnce() {
        val attempt = actionMutex.withLock {
            val sent = pending.take(MaxCommandsPerSync)
            val sentTaskOperations = pendingTaskOperations.take(MaxTaskOperationsPerSync)
            val sentDurationOperations = pendingDurationOperations
                .sortedWith(durationOperationComparator)
                .take(MaxDurationOperationsPerSync)
            if (sentDurationOperations.any { !it.isValidDurationOperation() }) {
                throw SyncProtocolException("Queued duration operation is invalid")
            }
            syncing = true
            retrying = false
            publish()
            SyncAttempt(
                accountGeneration = accountGeneration,
                request = SyncRequest(
                    deviceId = local.deviceId,
                    lastRevision = local.revision,
                    commands = sent,
                    durationOperations = sentDurationOperations,
                    taskOperations = sentTaskOperations,
                ),
            )
        }
        val response = auth.authorized { api.sync(it, attempt.request) }
        actionMutex.withLock {
            if (attempt.accountGeneration != accountGeneration || authStatus != AuthStatus.SignedIn) {
                syncing = false
                publish()
                return
            }
            val sentCommandIds = validateAcknowledgements(
                attempt.request.commands.map(TimerCommand::id),
                response.acknowledgements.map(Acknowledgement::commandId),
                "command",
            )
            val sentTaskOperationIds = validateAcknowledgements(
                attempt.request.taskOperations.map(TaskOperation::id),
                response.taskAcknowledgements.map(TaskAcknowledgement::operationId),
                "task",
            )
            val sentDurationOperationIds = validateAcknowledgements(
                attempt.request.durationOperations.map(DurationOperation::id),
                response.durationAcknowledgements.map(DurationAcknowledgement::operationId),
                "duration",
            )
            if (!response.durationsMs.isValid()) {
                throw SyncProtocolException("Sync returned invalid canonical durations")
            }
            val acknowledgedEntities = attempt.request.commands.map(PendingCommandEntity::from)
            val nextPending = pending.filterNot { it.id in sentCommandIds }
            val acknowledgedTaskEntities = attempt.request.taskOperations
                .map(PendingTaskOperationEntity::from)
            val nextPendingTaskOperations = pendingTaskOperations
                .filterNot { it.id in sentTaskOperationIds }
            val nextPendingDurationOperations = pendingDurationOperations
                .filterNot { it.id in sentDurationOperationIds }
            val nextCanonicalTimer = response.canonicalTimer
            val nextCanonicalHistory = response.history
            val nextCanonicalTasks = response.tasks
            val nextKnownTasks = (knownTasks.values + nextCanonicalTasks).associateBy(FocusTask::id)
            val nextTasks = TaskReducer.replay(nextCanonicalTasks, nextPendingTaskOperations)
            val nextSettings = replayDurationOperations(
                settings.withDurations(response.durationsMs),
                nextPendingDurationOperations,
            )
            val mergedWall = maxOf(System.currentTimeMillis(), local.hlcWallMs, response.serverHlcWallMs)
            val mergedCounter = when {
                mergedWall == local.hlcWallMs && mergedWall == response.serverHlcWallMs ->
                    maxOf(local.hlcCounter, response.serverHlcCounter)
                mergedWall == local.hlcWallMs -> local.hlcCounter
                mergedWall == response.serverHlcWallMs -> response.serverHlcCounter
                else -> 0
            }
            val nextLocal = local.copy(
                revision = response.revision,
                canonicalTimerJson = nextCanonicalTimer?.let { json.encodeToString(it) },
                historyJson = json.encodeToString(nextCanonicalHistory),
                tasksJson = json.encodeToString(nextCanonicalTasks),
                knownTasksJson = json.encodeToString(nextKnownTasks.values.sortedBy(FocusTask::id)),
                selectedTaskId = local.selectedTaskId?.takeIf { selected ->
                    nextTasks.any { it.id == selected }
                },
                settingsJson = json.encodeToString(nextSettings),
                hlcWallMs = mergedWall,
                hlcCounter = mergedCounter,
            )
            dao.applyFullSync(
                acknowledgedEntities,
                acknowledgedTaskEntities,
                sentDurationOperationIds.toList(),
                nextLocal,
            )
            pending = nextPending
            pendingTaskOperations = nextPendingTaskOperations
            pendingDurationOperations = nextPendingDurationOperations
            canonicalTimer = nextCanonicalTimer
            canonicalHistory = nextCanonicalHistory
            canonicalTasks = nextCanonicalTasks
            knownTasks = nextKnownTasks
            settings = nextSettings
            local = nextLocal
            response.acknowledgements.firstOrNull { it.outcome != "applied" }?.let {
                conflict = it.reason.ifBlank { "Command outcome: ${it.outcome}" }
            }
            response.taskAcknowledgements.firstOrNull { it.outcome != "applied" }?.let {
                conflict = it.reason.ifBlank { "Task outcome: ${it.outcome}" }
            }
            response.durationAcknowledgements.firstOrNull { it.outcome != "applied" }?.let {
                conflict = it.reason.ifBlank { "Duration outcome: ${it.outcome}" }
            }
            rebuildProjections()
            syncing = false
            retrying = false
            publish()
            scheduleAlarm()
        }
    }

    private fun ApiException.isRetryable(): Boolean =
        statusCode == 408 || statusCode == 425 || statusCode == 429 || statusCode >= 500

    private fun markTerminalSyncError(message: String) {
        syncing = false
        retrying = false
        terminalSyncError = message
        conflict = message
        publish()
    }

    private fun validateAcknowledgements(
        sentIds: List<String>,
        acknowledgedIds: List<String>,
        kind: String,
    ): Set<String> {
        val sent = sentIds.toSet()
        val acknowledged = acknowledgedIds.toSet()
        if (sent.size != sentIds.size ||
            acknowledged.size != acknowledgedIds.size ||
            acknowledged != sent
        ) {
            throw SyncProtocolException("Sync returned an invalid $kind acknowledgement set")
        }
        return sent
    }

    private suspend fun openRevisionStream() {
        initialized.await()
        streamMutex.withLock {
            if (!foreground || !online || authStatus != AuthStatus.SignedIn || eventSource != null) return
            try {
                eventSource = auth.authorized { token ->
                    api.revisionStream(token, object : EventSourceListener() {
                        override fun onEvent(
                            eventSource: EventSource,
                            id: String?,
                            type: String?,
                            data: String,
                        ) {
                            val revision = data.toLongOrNull() ?: runCatching {
                                json.parseToJsonElement(data)
                                    .jsonObject["revision"]
                                    ?.jsonPrimitive
                                    ?.content
                                    ?.toLong()
                            }.getOrNull()
                            if (revision == null || revision > local.revision) requestSync(force = true)
                        }

                        override fun onClosed(eventSource: EventSource) {
                            handleRevisionStreamEnd(eventSource)
                        }

                        override fun onFailure(
                            eventSource: EventSource,
                            t: Throwable?,
                            response: Response?,
                        ) {
                            handleRevisionStreamEnd(eventSource, response?.code)
                        }
                    })
                }
            } catch (_: AuthenticationRequired) {
                actionMutex.withLock {
                    accountGeneration += 1
                    authStatus = AuthStatus.SignedOut
                    user = null
                    publish()
                }
            }
        }
    }

    private fun handleRevisionStreamEnd(source: EventSource, responseCode: Int? = null) {
        scope.launch {
            val shouldReconnect = streamMutex.withLock {
                if (eventSource !== source) return@withLock false
                eventSource = null
                foreground && online && authStatus == AuthStatus.SignedIn
            }
            if (responseCode == 401) requestSync(force = true)
            if (shouldReconnect) {
                delay(5_000)
                openRevisionStream()
            }
        }
    }

    private suspend fun closeRevisionStream() {
        streamMutex.withLock {
            val source = eventSource
            eventSource = null
            source?.cancel()
        }
    }

    private fun updateNetworkState() {
        val nowOnline = currentOnlineState()
        val restored = !online && nowOnline
        online = nowOnline
        publish()
        if (restored) {
            requestSync(force = true)
            scope.launch { openRevisionStream() }
        } else if (!online) {
            scope.launch { closeRevisionStream() }
        }
    }

    private fun currentOnlineState(): Boolean {
        networkAvailable?.let { return it() }
        val network = connectivity.activeNetwork ?: return false
        val capabilities = connectivity.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun scheduleAlarm() {
        alarmScheduler.update(projection.timer)
    }

    private fun rebuildProjections() {
        projection = TimerReducer.replay(canonicalTimer, canonicalHistory, pending)
        tasks = TaskReducer.replay(canonicalTasks, pendingTaskOperations)
        val pendingUpserts = pendingTaskOperations.mapNotNull { operation ->
            operation.title
                ?.takeIf { operation.type == TaskOperationType.Upsert }
                ?.let(TaskReducer::taskFromTitle)
        }
        knownTasks = (knownTasks.values + canonicalTasks + pendingUpserts).associateBy(FocusTask::id)
        if (local.selectedTaskId != null && tasks.none { it.id == local.selectedTaskId }) {
            local = local.copy(selectedTaskId = null)
        }
    }

    private fun replayDurationOperations(
        base: TimerSettings,
        operations: List<DurationOperation>,
    ): TimerSettings = operations
        .asSequence()
        .filter {
            it.isValidDurationOperation()
        }
        .sortedWith(durationOperationComparator)
        .fold(base) { current, operation ->
            current.withDuration(operation.phase, operation.durationMs)
        }

    private fun DurationOperation.isValidDurationOperation(): Boolean =
        phase in TimerPhase.all &&
            DurationLimits.isValid(durationMs) &&
            ((hlcWallMs == 0L && hlcCounter == 0L) || (hlcWallMs > 0L && hlcCounter >= 0L))

    private fun publish() {
        val syncStatus = when {
            !online -> SyncStatus.Offline
            conflict != null -> SyncStatus.Conflict
            syncing -> SyncStatus.Syncing
            retrying -> SyncStatus.Retrying
            pending.isNotEmpty() ||
                pendingTaskOperations.isNotEmpty() ||
                pendingDurationOperations.isNotEmpty() -> SyncStatus.Queued
            !initialized.isCompleted -> SyncStatus.Checking
            else -> SyncStatus.Synced
        }
        _state.value = AppState(
            ready = initialized.isCompleted,
            authStatus = authStatus,
            user = user,
            timer = projection.timer,
            history = projection.history,
            tasks = tasks,
            knownTasks = knownTasks.values.sortedWith(compareBy<FocusTask> { it.title }.thenBy { it.id }),
            taskSummaries = TaskReducer.summariesToday(tasks, projection.history),
            selectedTaskId = if (::local.isInitialized) local.selectedTaskId else null,
            settings = settings,
            pendingCount = pending.size + pendingTaskOperations.size + pendingDurationOperations.size,
            syncStatus = syncStatus,
            conflict = conflict,
            notice = notice,
            deviceId = if (::local.isInitialized) local.deviceId else "",
        )
    }

    private inline fun <reified T> decodeOr(value: String, fallback: T): T =
        runCatching { json.decodeFromString<T>(value) }.getOrDefault(fallback)

    private companion object {
        const val MaxCommandsPerSync = 256
        const val MaxTaskOperationsPerSync = 256
        const val MaxDurationOperationsPerSync = 256
        val durationOperationComparator = compareBy<DurationOperation>(
            DurationOperation::hlcWallMs,
            DurationOperation::hlcCounter,
            DurationOperation::id,
        )
        val activeStatuses = setOf(TimerStatus.Running, TimerStatus.Paused)
    }
}
