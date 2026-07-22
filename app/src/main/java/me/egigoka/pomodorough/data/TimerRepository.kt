package me.egigoka.pomodorough.data

import android.app.Activity
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.io.IOException
import java.net.URI
import java.nio.charset.StandardCharsets
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
import me.egigoka.pomodorough.data.api.BootstrapConflictException
import me.egigoka.pomodorough.data.api.BootstrapConflictKind
import me.egigoka.pomodorough.data.api.PomodoroughService
import me.egigoka.pomodorough.data.auth.AuthSession
import me.egigoka.pomodorough.data.auth.AuthenticationRequired
import me.egigoka.pomodorough.data.local.LocalStateEntity
import me.egigoka.pomodorough.data.local.PendingBootstrapResolutionEntity
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
    val historyResolution: HistoryResolutionState? = null,
    val accountSwitch: AccountSwitchState? = null,
    val conflict: String? = null,
    val notice: String? = null,
    val deviceId: String = "",
)

private data class SyncAttempt(
    val accountGeneration: Long,
    val request: SyncRequest,
)

private data class BootstrapResolutionAttempt(
    val accountGeneration: Long,
    val request: BootstrapResolutionRequest,
)

private data class PendingAccountSwitch(
    val profile: User,
    val bootstrap: SyncResponse,
)

private data class RepositoryAttemptIdentity(
    val accountGeneration: Long,
    val requestId: String?,
)

private class SyncProtocolException(message: String) : Exception(message)
private class ProfileProtocolException(message: String) : Exception(message)

class TimerRepository(
    context: Context,
    private val dao: TimerDao,
    private val api: PomodoroughService,
    private val auth: AuthSession,
    private val json: Json,
    private val networkAvailable: (() -> Boolean)? = null,
) {
    private val appContext = context.applicationContext
    private val strictJson = Json(from = json) { ignoreUnknownKeys = false }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val initializeMutex = Mutex()
    private val actionMutex = Mutex()
    private val streamMutex = Mutex()
    private val initialized = CompletableDeferred<Unit>()
    private val networkInitializationStarted = AtomicBoolean(false)
    private val signInInFlight = AtomicBoolean(false)
    private val syncSignals = Channel<Unit>(Channel.CONFLATED)
    private val forceSync = AtomicBoolean(false)
    private val alarmScheduler = TimerAlarmScheduler(appContext)
    private val connectivity = appContext.getSystemService(ConnectivityManager::class.java)

    private lateinit var local: LocalStateEntity
    private var pending = emptyList<TimerCommand>()
    private var pendingDurationOperations = emptyList<DurationOperation>()
    private var pendingTaskOperations = emptyList<TaskOperation>()
    private var pendingBootstrapResolution: PendingBootstrapResolutionEntity? = null
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
    private var bootstrapSnapshot: SyncResponse? = null
    private var historyResolution: HistoryResolutionState? = null
    private var pendingAccountSwitch: PendingAccountSwitch? = null
    private var accountSwitch: AccountSwitchState? = null
    private var ownerMigrationCorrupted = false

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
        if (ownerMigrationCorrupted) return
        if (!networkInitializationStarted.compareAndSet(false, true)) return
        if (auth.hasTokens()) {
            restoreProfile()
            if (authStatus == AuthStatus.SignedIn && historyResolution == null && accountSwitch == null) {
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
            pendingBootstrapResolution = dao.pendingBootstrapResolution()
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
            user = if (local.ownerUserId == null && local.userJson != null) {
                try {
                    strictJson.decodeFromString<User>(requireNotNull(local.userJson)).also(::validateUser)
                } catch (_: Exception) {
                    ownerMigrationCorrupted = true
                    null
                }
            } else {
                local.userJson?.let { decodeOr<User?>(it, null) }
            }
            if (!ownerMigrationCorrupted && local.ownerUserId == null && user != null) {
                local = local.copy(ownerUserId = user?.id)
                dao.updateState(local)
            }
            rebuildProjections()
            authStatus = if (ownerMigrationCorrupted) {
                AuthStatus.SignedOut
            } else if (auth.hasTokens()) {
                AuthStatus.Loading
            } else {
                AuthStatus.SignedOut
            }
            if (ownerMigrationCorrupted) {
                historyResolution = HistoryResolutionState(
                    localHistoryCount = visibleHistoryCount(projection.history),
                    remoteHistoryCount = 0,
                    corrupted = true,
                    error = "Migrated account owner is corrupted. Account bootstrap and local mutations are blocked.",
                )
            } else if (authStatus == AuthStatus.SignedOut) {
                restorePendingResolutionForSignedOut(
                    "Sign in to finish the saved history choice before making more changes.",
                )
            }
            initialized.complete(Unit)
            publish()
            scheduleAlarm()
        }
    }

    suspend fun signIn(activity: Activity) {
        initialize()
        if (ownerMigrationCorrupted) return
        if (!signInInFlight.compareAndSet(false, true)) return
        lateinit var identity: RepositoryAttemptIdentity
        try {
            actionMutex.withLock {
                authStatus = AuthStatus.SigningIn
                notice = null
                identity = currentAttemptIdentity()
                publish()
            }
            auth.signIn(activity, local.deviceId)
            val profile = fetchValidatedProfile()
            val bootstrap = auth.authorized(api::bootstrap)
            if (!completeAuthentication(profile, bootstrap, identity)) return
            if (historyResolution == null && accountSwitch == null) {
                requestSync(force = true)
                openRevisionStream()
            }
        } catch (_: AuthenticationRequired) {
            handleAuthenticationRequired(identity, "Session expired during sign-in bootstrap.")
        } catch (error: ProfileProtocolException) {
            actionMutex.withLock {
                if (!isCurrent(identity)) return@withLock
                auth.clear()
                authStatus = AuthStatus.SignedOut
                user = null
                notice = error.message
                restorePendingResolutionForSignedOut(
                    "Sign in again to retry the exact saved history choice.",
                )
                publish()
            }
        } catch (error: Exception) {
            actionMutex.withLock {
                if (!isCurrent(identity)) return@withLock
                authStatus = AuthStatus.SignedOut
                user = null
                notice = error.message ?: "Google sign-in did not complete"
                restorePendingResolutionForSignedOut(
                    "Sign in again to retry the exact saved history choice.",
                )
                publish()
            }
        } finally {
            signInInFlight.set(false)
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
                if (local.ownerUserId == null) {
                    user = null
                    authStatus = AuthStatus.SignedOut
                    pendingAccountSwitch = null
                    accountSwitch = null
                    bootstrapSnapshot = null
                    conflict = null
                    terminalSyncError = null
                    restorePendingResolutionForSignedOut(
                        "Sign in again to retry the exact saved history choice.",
                    )
                    publish()
                    return@withLock
                }
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
                pendingBootstrapResolution = null
                historyResolution = null
                pendingAccountSwitch = null
                accountSwitch = null
                bootstrapSnapshot = null
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

    suspend fun confirmAccountSwitch() {
        initialize()
        val candidate = actionMutex.withLock {
            val value = pendingAccountSwitch ?: return@withLock null
            val state = accountSwitch ?: return@withLock null
            if (state.submitting || authStatus != AuthStatus.SignedIn) return@withLock null
            accountSwitch = state.copy(submitting = true, error = null)
            publish()
            value
        } ?: return

        try {
            actionMutex.withLock {
                if (pendingAccountSwitch !== candidate || authStatus != AuthStatus.SignedIn) {
                    return@withLock
                }
                validateUser(candidate.profile)
                validateCanonicalResponse(
                    candidate.bootstrap,
                    "Bootstrap",
                    requireEmptyAcknowledgements = true,
                )
                accountGeneration += 1
                user = candidate.profile
                bootstrapSnapshot = candidate.bootstrap
                installBootstrap(candidate.profile, candidate.bootstrap, clearLocal = true)
                pendingAccountSwitch = null
                accountSwitch = null
                authStatus = AuthStatus.SignedIn
                publish()
                scheduleAlarm()
            }
            if (foreground) openRevisionStream()
        } catch (error: Exception) {
            actionMutex.withLock {
                if (pendingAccountSwitch !== candidate) return@withLock
                accountSwitch = accountSwitch?.copy(
                    submitting = false,
                    error = error.message ?: "Could not switch accounts without risking local data.",
                )
                publish()
            }
        }
    }

    suspend fun cancelAccountSwitch() {
        initialize()
        val candidate = actionMutex.withLock {
            val value = pendingAccountSwitch ?: return@withLock null
            val state = accountSwitch ?: return@withLock null
            if (state.submitting) return@withLock null
            accountGeneration += 1
            accountSwitch = state.copy(submitting = true, error = null)
            publish()
            value
        } ?: return

        val logoutError = runCatching { auth.logout() }.exceptionOrNull()
        auth.clear()
        actionMutex.withLock {
            if (pendingAccountSwitch !== candidate) return@withLock
            pendingAccountSwitch = null
            accountSwitch = null
            bootstrapSnapshot = null
            authStatus = AuthStatus.SignedOut
            user = null
            syncing = false
            retrying = false
            restorePendingResolutionForSignedOut(
                "Sign in with the account that owns this local data to continue syncing.",
            )
            notice = logoutError?.let {
                "New account was removed from this device, but server logout failed: ${it.message.orEmpty()}"
            }
            publish()
        }
        closeRevisionStream()
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
        if (mutationsBlocked() || phase !in TimerPhase.all || projection.timer?.status in activeStatuses) return
        persistSettings(settings.copy(selectedPhase = phase))
    }

    suspend fun changeDuration(phase: String, delta: Int) {
        initialize()
        if (phase !in TimerPhase.all) return
        var saved = false
        actionMutex.withLock {
            if (mutationsBlocked() || projection.timer?.status in activeStatuses) return@withLock
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
            if (mutationsBlocked() || projection.timer?.status in activeStatuses) return@withLock
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

    suspend fun resolveHistory(strategy: BootstrapStrategy) {
        initialize()
        if (pendingBootstrapResolution == null && bootstrapSnapshot == null) {
            val refreshIdentity = actionMutex.withLock { currentAttemptIdentity() }
            val refreshed = try {
                auth.authorized(api::bootstrap)
            } catch (_: AuthenticationRequired) {
                handleAuthenticationRequired(
                    refreshIdentity,
                    "Session expired while refreshing remote history.",
                )
                return
            } catch (error: Exception) {
                actionMutex.withLock {
                    if (!isCurrent(refreshIdentity)) return@withLock
                    historyResolution = historyResolution?.copy(
                        submitting = false,
                        error = error.message ?: "Could not refresh remote history.",
                    )
                    publish()
                }
                return
            }
            val refreshAccepted = actionMutex.withLock {
                if (!isCurrent(refreshIdentity)) return@withLock false
                validateCanonicalResponse(refreshed, "Bootstrap", requireEmptyAcknowledgements = true)
                bootstrapSnapshot = refreshed
                historyResolution = (historyResolution ?: HistoryResolutionState(0, 0)).copy(
                    localHistoryCount = visibleHistoryCount(projection.history),
                    remoteHistoryCount = visibleHistoryCount(refreshed.history),
                    error = null,
                )
                publish()
                true
            }
            if (!refreshAccepted) return
        }

        val attempt = actionMutex.withLock {
            if (authStatus != AuthStatus.SignedIn || historyResolution?.submitting == true) {
                return@withLock null
            }
            val profile = user ?: return@withLock null
            val stored = pendingBootstrapResolution
            if (stored == null) {
                val bootstrap = bootstrapSnapshot ?: return@withLock null
                return@withLock prepareBootstrapResolution(profile, strategy, bootstrap)
            }
            if (stored.ownerUserId != profile.id) {
                historyResolution = corruptedResolutionState()
                publish()
                return@withLock null
            }
            val request = try {
                stored.toRequestStrict()
            } catch (_: Exception) {
                historyResolution = corruptedResolutionState()
                publish()
                return@withLock null
            }
            if (request.strategy != strategy) {
                notice = "Retry the pending ${request.strategy.displayName()} choice before choosing another option."
                publish()
                return@withLock null
            }
            historyResolution = (historyResolution ?: HistoryResolutionState(
                localHistoryCount = visibleHistoryCount(projection.history),
                remoteHistoryCount = visibleHistoryCount(bootstrapSnapshot?.history.orEmpty()),
            )).copy(
                pendingStrategy = request.strategy,
                requestId = request.requestId,
                submitting = true,
                corrupted = false,
                error = null,
            )
            publish()
            BootstrapResolutionAttempt(accountGeneration, request)
        } ?: return

        performBootstrapResolution(attempt)
    }

    suspend fun recoverCorruptedResolution() {
        initialize()
        val recovery = actionMutex.withLock {
            val resolution = historyResolution ?: return@withLock null
            val profile = user ?: return@withLock null
            if (authStatus != AuthStatus.SignedIn ||
                resolution.recovery != ResolutionRecovery.Repreview ||
                resolution.submitting
            ) return@withLock null

            dao.deleteBootstrapResolution()
            pendingBootstrapResolution = null
            bootstrapSnapshot = null
            accountGeneration += 1
            historyResolution = resolution.copy(
                submitting = true,
                error = "Refreshing account history without the corrupted saved request.",
            )
            publish()
            profile to currentAttemptIdentity()
        } ?: return

        val (profile, identity) = recovery
        try {
            val bootstrap = auth.authorized(api::bootstrap)
            completeAuthentication(profile, bootstrap, identity, repreviewResolution = true)
        } catch (_: AuthenticationRequired) {
            var shouldCloseStream = false
            actionMutex.withLock {
                if (!isCurrent(identity)) return@withLock
                auth.clear()
                accountGeneration += 1
                authStatus = AuthStatus.SignedOut
                user = null
                bootstrapSnapshot = null
                syncing = false
                retrying = false
                historyResolution = HistoryResolutionState(
                    localHistoryCount = visibleHistoryCount(projection.history),
                    remoteHistoryCount = 0,
                    corrupted = true,
                    recovery = ResolutionRecovery.Repreview,
                    error = "Session expired. Sign in again to re-check account history.",
                )
                publish()
                shouldCloseStream = true
            }
            if (shouldCloseStream) closeRevisionStream()
        } catch (error: Exception) {
            actionMutex.withLock {
                if (!isCurrent(identity)) return@withLock
                historyResolution = historyResolution?.copy(
                    submitting = false,
                    error = error.message ?: "Could not refresh account history.",
                )
                publish()
            }
        }
    }

    private suspend fun prepareBootstrapResolution(
        profile: User,
        strategy: BootstrapStrategy,
        bootstrap: SyncResponse,
    ): BootstrapResolutionAttempt? {
        val includeLocal = strategy != BootstrapStrategy.KeepRemote
        val request = BootstrapResolutionRequest(
            requestId = "bootstrap-${UUID.randomUUID()}",
            deviceId = local.deviceId,
            expectedRevision = bootstrap.revision,
            strategy = strategy,
            commands = pending.takeIf { includeLocal }.orEmpty(),
            taskOperations = pendingTaskOperations.takeIf { includeLocal }.orEmpty(),
            durationOperations = pendingDurationOperations.takeIf { includeLocal }.orEmpty(),
        )
        val validationError = runCatching { validateResolutionEnvelope(request) }.exceptionOrNull()
        if (validationError != null) {
            historyResolution = HistoryResolutionState(
                localHistoryCount = visibleHistoryCount(projection.history),
                remoteHistoryCount = visibleHistoryCount(bootstrap.history),
                corrupted = true,
                recovery = ResolutionRecovery.KeepRemote.takeIf { includeLocal },
                error = validationError.message ?: "Queued bootstrap resolution is invalid",
            )
            publish()
            return null
        }
        request.toEntity(profile).also {
            dao.upsertBootstrapResolution(it)
            pendingBootstrapResolution = it
        }
        historyResolution = HistoryResolutionState(
            localHistoryCount = visibleHistoryCount(projection.history),
            remoteHistoryCount = visibleHistoryCount(bootstrap.history),
            pendingStrategy = request.strategy,
            requestId = request.requestId,
            submitting = true,
        )
        return BootstrapResolutionAttempt(accountGeneration, request)
    }

    private suspend fun performBootstrapResolution(attempt: BootstrapResolutionAttempt) {
        val identity = RepositoryAttemptIdentity(
            attempt.accountGeneration,
            attempt.request.requestId,
        )
        try {
            val response = auth.authorized { api.resolveBootstrap(it, attempt.request) }
            var applied = false
            actionMutex.withLock {
                if (!isCurrent(identity) || authStatus != AuthStatus.SignedIn) return@withLock
                validateCanonicalResponse(response, "Bootstrap resolution")
                if (response.revision < attempt.request.expectedRevision || response.revision < local.revision) {
                    throw SyncProtocolException("Bootstrap resolution returned a regressed revision")
                }
                validateAcknowledgements(
                    attempt.request.commands.map(TimerCommand::id),
                    response.acknowledgements.map(Acknowledgement::commandId),
                    "command",
                )
                validateAcknowledgements(
                    attempt.request.taskOperations.map(TaskOperation::id),
                    response.taskAcknowledgements.map(TaskAcknowledgement::operationId),
                    "task",
                )
                validateAcknowledgements(
                    attempt.request.durationOperations.map(DurationOperation::id),
                    response.durationAcknowledgements.map(DurationAcknowledgement::operationId),
                    "duration",
                )
                applyBootstrapResolution(attempt.request, response)
                applied = true
            }
            if (applied && foreground) openRevisionStream()
        } catch (error: BootstrapConflictException) {
            actionMutex.withLock {
                if (!isCurrent(identity)) return@withLock
                dao.deleteBootstrapResolution()
                pendingBootstrapResolution = null
                bootstrapSnapshot = null
                val detail = when (error.kind) {
                    BootstrapConflictKind.Revision -> "Remote history changed before this choice was applied. Choose again to refresh it."
                    BootstrapConflictKind.RequestId -> "Server rejected the saved request identity. Choose again with a new request."
                    BootstrapConflictKind.Unknown -> error.message ?: "History resolution conflicted. Choose again."
                }
                historyResolution = historyResolution?.copy(
                    pendingStrategy = null,
                    requestId = null,
                    submitting = false,
                    error = detail,
                )
                publish()
            }
        } catch (_: AuthenticationRequired) {
            handleAuthenticationRequired(
                identity,
                "Session expired. Sign in again to retry the exact saved history choice.",
            )
        } catch (error: ApiException) {
            actionMutex.withLock {
                if (!isCurrent(identity)) return@withLock
                if (error.isRetryable()) {
                    historyResolution = historyResolution?.copy(
                        submitting = false,
                        error = error.message
                            ?: "Could not finish history resolution. Retry uses the same saved request.",
                    )
                } else {
                    dao.deleteBootstrapResolution()
                    pendingBootstrapResolution = null
                    bootstrapSnapshot = null
                    historyResolution = corruptedResolutionState().copy(
                        error = "Server permanently rejected the saved history request (${error.statusCode}). Re-check account history without deleting local queues.",
                    )
                }
                publish()
            }
        } catch (error: IOException) {
            actionMutex.withLock {
                if (!isCurrent(identity)) return@withLock
                historyResolution = historyResolution?.copy(
                    submitting = false,
                    error = error.message ?: "Could not finish history resolution. Retry uses the same saved request.",
                )
                publish()
            }
        } catch (error: Exception) {
            actionMutex.withLock {
                if (!isCurrent(identity)) return@withLock
                historyResolution = historyResolution?.copy(
                    submitting = false,
                    error = error.message ?: "History resolution failed without changing local data.",
                )
                publish()
            }
        }
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
        val identity = actionMutex.withLock { currentAttemptIdentity() }
        try {
            val profile = fetchValidatedProfile()
            val bootstrap = auth.authorized(api::bootstrap)
            completeAuthentication(profile, bootstrap, identity)
        } catch (error: IOException) {
            actionMutex.withLock {
                if (!isCurrent(identity)) return@withLock
                authStatus = AuthStatus.SignedOut
                user = null
                notice = error.message ?: "Could not verify signed-in account"
                restorePendingResolutionForSignedOut(
                    "Sign in again to retry the exact saved history choice.",
                )
                publish()
            }
        } catch (_: AuthenticationRequired) {
            handleAuthenticationRequired(identity, "Session expired while refreshing account bootstrap.")
        } catch (error: ProfileProtocolException) {
            actionMutex.withLock {
                if (!isCurrent(identity)) return@withLock
                auth.clear()
                authStatus = AuthStatus.SignedOut
                user = null
                notice = error.message
                restorePendingResolutionForSignedOut(
                    "Sign in again to retry the exact saved history choice.",
                )
                publish()
            }
        } catch (error: Exception) {
            actionMutex.withLock {
                if (!isCurrent(identity)) return@withLock
                authStatus = AuthStatus.SignedOut
                user = null
                notice = error.message ?: "Could not validate account bootstrap"
                restorePendingResolutionForSignedOut(
                    "Sign in again to retry the exact saved history choice.",
                )
                publish()
            }
        }
    }

    private suspend fun completeAuthentication(
        profile: User,
        bootstrap: SyncResponse,
        identity: RepositoryAttemptIdentity,
        repreviewResolution: Boolean = false,
    ): Boolean {
        var automaticAttempt: BootstrapResolutionAttempt? = null
        var accountMismatch = false
        actionMutex.withLock {
            if (!isCurrent(identity)) return false
            validateUser(profile)
            validateCanonicalResponse(bootstrap, "Bootstrap", requireEmptyAcknowledgements = true)
            val storedResolution = pendingBootstrapResolution
            val boundOwnerId = local.ownerUserId ?: storedResolution?.ownerUserId
            if (boundOwnerId != null && boundOwnerId != profile.id) {
                accountGeneration += 1
                pendingAccountSwitch = PendingAccountSwitch(profile, bootstrap)
                accountSwitch = AccountSwitchState(
                    localAccount = user?.email ?: boundOwnerId,
                    incomingAccount = profile.email,
                )
                user = null
                historyResolution = null
                authStatus = AuthStatus.SignedIn
                syncing = false
                retrying = false
                publish()
                accountMismatch = true
                return@withLock
            }

            bootstrapSnapshot = bootstrap
            accountGeneration += 1
            user = profile
            pendingAccountSwitch = null
            accountSwitch = null
            conflict = null
            terminalSyncError = null

            when {
                storedResolution != null -> {
                    historyResolution = try {
                        val request = storedResolution.toRequestStrict()
                        HistoryResolutionState(
                            localHistoryCount = visibleHistoryCount(projection.history),
                            remoteHistoryCount = visibleHistoryCount(bootstrap.history),
                            pendingStrategy = request.strategy,
                            requestId = request.requestId,
                            error = "Previous history choice still needs a response from the server.",
                        )
                    } catch (_: Exception) {
                        corruptedResolutionState()
                    }
                }
                !repreviewResolution && local.ownerUserId == profile.id -> {
                    installBootstrap(profile, bootstrap, clearLocal = false)
                }
                else -> {
                    val localHistoryCount = visibleHistoryCount(projection.history)
                    val remoteHistoryCount = visibleHistoryCount(bootstrap.history)
                    val automaticStrategy = when {
                        localHistoryCount > 0 && remoteHistoryCount > 0 -> null
                        localHistoryCount > 0 -> BootstrapStrategy.ReplaceRemote
                        remoteHistoryCount > 0 && hasLocalSyncState() -> BootstrapStrategy.Merge
                        remoteHistoryCount > 0 -> BootstrapStrategy.KeepRemote
                        hasLocalSyncState() -> BootstrapStrategy.Merge
                        else -> BootstrapStrategy.KeepRemote
                    }
                    if (automaticStrategy == null) {
                        historyResolution = HistoryResolutionState(
                            localHistoryCount = localHistoryCount,
                            remoteHistoryCount = remoteHistoryCount,
                        )
                    } else {
                        automaticAttempt = prepareBootstrapResolution(profile, automaticStrategy, bootstrap)
                    }
                }
            }
            authStatus = AuthStatus.SignedIn
            publish()
            scheduleAlarm()
        }
        if (accountMismatch) return true
        automaticAttempt?.let { performBootstrapResolution(it) }
        return true
    }

    private suspend fun installBootstrap(
        profile: User,
        response: SyncResponse,
        clearLocal: Boolean,
    ) {
        if (!clearLocal && response.revision < local.revision) {
            throw SyncProtocolException("Bootstrap revision regressed from ${local.revision} to ${response.revision}")
        }
        val retainedDurationOperations = if (clearLocal) emptyList() else pendingDurationOperations
        val retainedTaskOperations = if (clearLocal) emptyList() else pendingTaskOperations
        val nextKnownTasks = if (clearLocal) {
            response.tasks.associateBy(FocusTask::id)
        } else {
            (knownTasks.values + response.tasks).associateBy(FocusTask::id)
        }
        val nextTasks = TaskReducer.replay(response.tasks, retainedTaskOperations)
        val nextSettings = replayDurationOperations(
            settings.withDurations(response.durationsMs),
            retainedDurationOperations,
        )
        val (mergedWall, mergedCounter) = mergedClock(response)
        val nextLocal = local.copy(
            revision = response.revision,
            canonicalTimerJson = response.canonicalTimer?.let { json.encodeToString(it) },
            historyJson = json.encodeToString(response.history),
            tasksJson = json.encodeToString(response.tasks),
            knownTasksJson = json.encodeToString(nextKnownTasks.values.sortedBy(FocusTask::id)),
            selectedTaskId = local.selectedTaskId?.takeIf { selected ->
                nextTasks.any { it.id == selected }
            },
            settingsJson = json.encodeToString(nextSettings),
            userJson = json.encodeToString(profile),
            ownerUserId = profile.id,
            hlcWallMs = mergedWall,
            hlcCounter = mergedCounter,
        )
        if (clearLocal) dao.clearAccount(nextLocal) else dao.updateState(nextLocal)
        local = nextLocal
        settings = nextSettings
        canonicalTimer = response.canonicalTimer
        canonicalHistory = response.history
        canonicalTasks = response.tasks
        knownTasks = nextKnownTasks
        if (clearLocal) {
            pending = emptyList()
            pendingDurationOperations = emptyList()
            pendingTaskOperations = emptyList()
            pendingBootstrapResolution = null
        }
        historyResolution = null
        rebuildProjections()
    }

    private suspend fun persistSettings(next: TimerSettings) = actionMutex.withLock {
        if (mutationsBlocked()) return@withLock
        settings = next
        local = local.copy(settingsJson = json.encodeToString(settings))
        dao.updateState(local)
        publish()
    }

    private suspend fun issueCommand(type: String, startingPhase: String? = null): Boolean {
        var saved = false
        actionMutex.withLock {
            if (mutationsBlocked()) return@withLock
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
            if (mutationsBlocked() || select && projection.timer?.status in activeStatuses) return@withLock
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
                if (historyResolution != null || accountSwitch != null) {
                    syncing = false
                    retrying = false
                    publish()
                    break
                }
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
            if (historyResolution != null || accountSwitch != null) return
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
            validateCanonicalResponse(response, "Sync")
            if (response.revision < local.revision) {
                throw SyncProtocolException("Sync revision regressed from ${local.revision} to ${response.revision}")
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
            val (mergedWall, mergedCounter) = mergedClock(response)
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
            if (!foreground || !online || authStatus != AuthStatus.SignedIn ||
                historyResolution != null || accountSwitch != null || eventSource != null
            ) return
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

    private suspend fun fetchValidatedProfile(): User {
        val profile = try {
            auth.authorized(api::me).user
        } catch (error: SerializationException) {
            throw ProfileProtocolException("Account profile response is malformed: ${error.message.orEmpty()}")
        }
        validateUser(profile)
        return profile
    }

    private fun validateUser(value: User) {
        if (value.id.isBlank() || value.id != value.id.trim() || value.id.utf8Size() > 512 ||
            value.id.any(Char::isISOControl)
        ) {
            throw ProfileProtocolException("Account profile ID is invalid")
        }
        val at = value.email.indexOf('@')
        if (value.email != value.email.trim() || value.email.utf8Size() > 320 ||
            at <= 0 || at != value.email.lastIndexOf('@') || at == value.email.lastIndex ||
            value.email.any { it.isWhitespace() || it.isISOControl() }
        ) {
            throw ProfileProtocolException("Account profile email is invalid")
        }
        if (value.name.utf8Size() > 512 || value.name.any(Char::isISOControl)) {
            throw ProfileProtocolException("Account profile name is invalid")
        }
        if (value.avatarUrl.isNotBlank()) {
            val avatar = runCatching { URI(value.avatarUrl) }.getOrNull()
            if (value.avatarUrl.utf8Size() > 2_048 || avatar?.scheme != "https" || avatar.host.isNullOrBlank()) {
                throw ProfileProtocolException("Account profile avatar URL is invalid")
            }
        }
    }

    private fun String.utf8Size(): Int = toByteArray(StandardCharsets.UTF_8).size

    private fun validateResolutionEnvelope(request: BootstrapResolutionRequest) {
        require(request.requestId.isNotBlank()) { "Saved bootstrap request ID is invalid" }
        require(request.deviceId == local.deviceId) { "Saved bootstrap device does not match this device" }
        require(request.expectedRevision >= 0) { "Saved bootstrap revision is invalid" }
        require(request.commands.size <= MaxBootstrapOperations) {
            "Saved bootstrap commands exceed the 4096 item limit"
        }
        require(request.taskOperations.size <= MaxBootstrapOperations) {
            "Saved bootstrap task operations exceed the 4096 item limit"
        }
        require(request.durationOperations.size <= MaxBootstrapOperations) {
            "Saved bootstrap duration operations exceed the 4096 item limit"
        }
        require(request.commands.map(TimerCommand::id).toSet().size == request.commands.size) {
            "Saved bootstrap commands contain duplicate IDs"
        }
        require(request.commands.map(TimerCommand::deviceSequence).toSet().size == request.commands.size) {
            "Saved bootstrap commands contain duplicate device sequences"
        }
        require(request.taskOperations.map(TaskOperation::id).toSet().size == request.taskOperations.size) {
            "Saved bootstrap task operations contain duplicate IDs"
        }
        require(
            request.durationOperations.map(DurationOperation::id).toSet().size ==
                request.durationOperations.size,
        ) { "Saved bootstrap duration operations contain duplicate IDs" }
        request.commands.forEach(::validateTimerCommand)
        request.taskOperations.forEach(::validateTaskOperation)
        request.durationOperations.forEach(::validateDurationOperation)
    }

    private fun validateTimerCommand(command: TimerCommand) {
        require(command.id.isNotBlank() && command.timerId.isNotBlank()) {
            "Saved timer command identity is invalid"
        }
        require(command.deviceSequence > 0) { "Saved timer command sequence is invalid" }
        require(command.type in commandTypes) { "Saved timer command type is invalid" }
        require(command.phase in TimerPhase.all) { "Saved timer command phase is invalid" }
        require(command.plannedDurationMs in DurationLimits.MinMs..MaxTimerDurationMs) {
            "Saved timer command duration is invalid"
        }
        require(command.hlcWallMs > 0 && command.hlcCounter >= 0) {
            "Saved timer command clock is invalid"
        }
        require(command.taskId == null || command.taskId.isNotBlank()) {
            "Saved timer command task is invalid"
        }
        require(command.observedElapsedMs in 0..command.plannedDurationMs) {
            "Saved timer command elapsed time is invalid"
        }
        require(command.type != CommandType.Start || command.observedElapsedMs == 0L) {
            "Saved start command elapsed time is invalid"
        }
        require(
            command.taskId == null ||
                (isUuid(command.taskId) && command.type == CommandType.Start && command.phase == TimerPhase.Focus),
        ) { "Saved timer command task is invalid" }
        Instant.parse(command.occurredAt)
    }

    private fun validateTaskOperation(operation: TaskOperation) {
        require(operation.id.isNotBlank() && operation.taskId.isNotBlank()) {
            "Saved task operation identity is invalid"
        }
        require(isUuid(operation.taskId)) { "Saved task operation task ID is invalid" }
        require(operation.type in setOf(TaskOperationType.Upsert, TaskOperationType.Delete)) {
            "Saved task operation type is invalid"
        }
        require(operation.hlcWallMs > 0 && operation.hlcCounter >= 0) {
            "Saved task operation clock is invalid"
        }
        when (operation.type) {
            TaskOperationType.Upsert -> {
                val task = operation.title?.let(TaskReducer::taskFromTitle)
                require(task != null && task.id == operation.taskId) {
                    "Saved task upsert title or identity is invalid"
                }
            }
            TaskOperationType.Delete -> require(operation.title == null) {
                "Saved task delete must not contain a title"
            }
        }
        Instant.parse(operation.occurredAt)
    }

    private fun validateDurationOperation(operation: DurationOperation) {
        require(operation.id.isNotBlank() && operation.isValidDurationOperation()) {
            "Saved duration operation is invalid"
        }
        Instant.parse(operation.occurredAt)
    }

    private suspend fun applyBootstrapResolution(
        request: BootstrapResolutionRequest,
        response: SyncResponse,
    ) {
        val profile = user ?: throw AuthenticationRequired()
        val nextKnownTasks = if (request.strategy == BootstrapStrategy.KeepRemote) {
            response.tasks.associateBy(FocusTask::id)
        } else {
            (knownTasks.values + response.tasks).associateBy(FocusTask::id)
        }
        val nextSettings = settings.withDurations(response.durationsMs)
        val (mergedWall, mergedCounter) = mergedClock(response)
        val nextLocal = local.copy(
            revision = response.revision,
            canonicalTimerJson = response.canonicalTimer?.let { json.encodeToString(it) },
            historyJson = json.encodeToString(response.history),
            tasksJson = json.encodeToString(response.tasks),
            knownTasksJson = json.encodeToString(nextKnownTasks.values.sortedBy(FocusTask::id)),
            selectedTaskId = local.selectedTaskId?.takeIf { selected ->
                response.tasks.any { it.id == selected }
            },
            settingsJson = json.encodeToString(nextSettings),
            userJson = json.encodeToString(profile),
            ownerUserId = profile.id,
            hlcWallMs = mergedWall,
            hlcCounter = mergedCounter,
        )
        dao.applyBootstrapResolution(nextLocal)
        local = nextLocal
        pending = emptyList()
        pendingTaskOperations = emptyList()
        pendingDurationOperations = emptyList()
        pendingBootstrapResolution = null
        canonicalTimer = response.canonicalTimer
        canonicalHistory = response.history
        canonicalTasks = response.tasks
        knownTasks = nextKnownTasks
        settings = nextSettings
        historyResolution = null
        bootstrapSnapshot = response
        syncing = false
        retrying = false
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
        publish()
        scheduleAlarm()
    }

    private fun validateCanonicalResponse(
        response: SyncResponse,
        source: String,
        requireEmptyAcknowledgements: Boolean = false,
    ) {
        protocolRequire(response.revision >= 0, "$source returned an invalid revision")
        if (!response.durationsMs.isValid()) {
            throw SyncProtocolException("$source returned invalid canonical durations")
        }
        if (requireEmptyAcknowledgements && (
                response.acknowledgements.isNotEmpty() ||
                    response.taskAcknowledgements.isNotEmpty() ||
                    response.durationAcknowledgements.isNotEmpty()
                )
        ) {
            throw SyncProtocolException("$source returned acknowledgements for a read-only request")
        }
        protocolRequire(
            parseInstant(response.serverTime),
            "$source returned an invalid server timestamp",
        )
        protocolRequire(
            response.serverHlcWallMs > 0 && response.serverHlcCounter >= 0,
            "$source returned an invalid server clock",
        )
        response.canonicalTimer?.let { timer ->
            protocolRequire(timer.id.isNotBlank(), "$source returned an invalid timer identity")
            protocolRequire(timer.phase in TimerPhase.all, "$source returned an invalid timer phase")
            protocolRequire(timer.status in timerStatuses, "$source returned an invalid timer status")
            protocolRequire(
                timer.plannedDurationMs in DurationLimits.MinMs..MaxTimerDurationMs,
                "$source returned an invalid timer duration",
            )
            protocolRequire(
                timer.elapsedAtAnchorMs in 0..timer.plannedDurationMs,
                "$source returned invalid timer elapsed time",
            )
            protocolRequire(parseInstant(timer.anchorAt), "$source returned an invalid timer timestamp")
            timer.taskId?.let { validateCanonicalTaskId(it, source) }
            timer.lastIntent?.let { intent ->
                protocolRequire(
                    intent.type in commandTypes && intent.commandId.isNotBlank(),
                    "$source returned an invalid timer intent",
                )
                protocolRequire(
                    parseInstant(intent.occurredAt),
                    "$source returned an invalid timer intent timestamp",
                )
            }
        }
        protocolRequire(
            response.history.map(HistoryItem::id).toSet().size == response.history.size,
            "$source returned duplicate history identities",
        )
        protocolRequire(
            response.history.map(HistoryItem::timerId).toSet().size == response.history.size,
            "$source returned duplicate history timer identities",
        )
        val historyCommandIds = response.history.mapNotNull(HistoryItem::commandId)
        protocolRequire(
            historyCommandIds.toSet().size == historyCommandIds.size,
            "$source returned duplicate history command identities",
        )
        response.history.forEach { item ->
            protocolRequire(
                item.id.isNotBlank() && item.timerId.isNotBlank() &&
                    (item.commandId == null || item.commandId.isNotBlank()),
                "$source returned an invalid history identity",
            )
            protocolRequire(item.phase in TimerPhase.all, "$source returned an invalid history phase")
            protocolRequire(
                item.status in historyStatuses,
                "$source returned an invalid history status",
            )
            protocolRequire(
                item.plannedDurationMs in DurationLimits.MinMs..MaxTimerDurationMs,
                "$source returned an invalid history duration",
            )
            protocolRequire(!item.pending, "$source returned pending canonical history")
            if (item.status == TimerStatus.Completed) {
                protocolRequire(
                    item.completedAt != null && parseInstant(item.completedAt),
                    "$source returned an invalid history completion timestamp",
                )
                if (item.endedAt != null) {
                    protocolRequire(
                        parseInstant(item.endedAt),
                        "$source returned an invalid history end timestamp",
                    )
                }
            } else {
                protocolRequire(
                    item.endedAt != null && parseInstant(item.endedAt),
                    "$source returned an invalid history end timestamp",
                )
            }
            if (item.status != TimerStatus.Completed && item.completedAt != null) {
                protocolRequire(
                    parseInstant(item.completedAt),
                    "$source returned an invalid history completion timestamp",
                )
            }
            item.taskId?.let { validateCanonicalTaskId(it, source) }
        }
        protocolRequire(
            response.tasks.map(FocusTask::id).toSet().size == response.tasks.size,
            "$source returned duplicate task identities",
        )
        response.tasks.forEach { task ->
            validateCanonicalTaskId(task.id, source)
            protocolRequire(
                TaskReducer.taskFromTitle(task.title) == task,
                "$source returned an invalid canonical task",
            )
        }
        response.acknowledgements.forEach { acknowledgement ->
            validateAcknowledgement(
                acknowledgement.commandId,
                acknowledgement.outcome,
                source,
                "command",
            )
        }
        response.taskAcknowledgements.forEach { acknowledgement ->
            validateAcknowledgement(
                acknowledgement.operationId,
                acknowledgement.outcome,
                source,
                "task",
            )
        }
        response.durationAcknowledgements.forEach { acknowledgement ->
            validateAcknowledgement(
                acknowledgement.operationId,
                acknowledgement.outcome,
                source,
                "duration",
            )
        }
    }

    private fun validateCanonicalTaskId(taskId: String, source: String) {
        protocolRequire(
            isUuid(taskId),
            "$source returned an invalid task identity",
        )
    }

    private fun isUuid(value: String): Boolean = runCatching { UUID.fromString(value) }.isSuccess

    private fun validateAcknowledgement(id: String, outcome: String, source: String, kind: String) {
        protocolRequire(
            id.isNotBlank() && outcome in acknowledgementOutcomes,
            "$source returned an invalid $kind acknowledgement",
        )
    }

    private fun protocolRequire(condition: Boolean, message: String) {
        if (!condition) throw SyncProtocolException(message)
    }

    private fun parseInstant(value: String): Boolean = runCatching { Instant.parse(value) }.isSuccess

    private fun mergedClock(response: SyncResponse): Pair<Long, Long> {
        val wall = maxOf(System.currentTimeMillis(), local.hlcWallMs, response.serverHlcWallMs)
        val counter = when {
            wall == local.hlcWallMs && wall == response.serverHlcWallMs ->
                maxOf(local.hlcCounter, response.serverHlcCounter)
            wall == local.hlcWallMs -> local.hlcCounter
            wall == response.serverHlcWallMs -> response.serverHlcCounter
            else -> 0
        }
        return wall to counter
    }

    private fun visibleHistoryCount(history: List<HistoryItem>): Int =
        history.count { it.status == TimerStatus.Completed }

    private fun hasLocalSyncState(): Boolean =
        projection.timer != null ||
            projection.history.isNotEmpty() ||
            tasks.isNotEmpty() ||
            pending.isNotEmpty() ||
            pendingTaskOperations.isNotEmpty() ||
            pendingDurationOperations.isNotEmpty() ||
            settings.effectiveDurationsMs() != DurationsMs()

    private fun mutationsBlocked(): Boolean =
        historyResolution != null ||
            accountSwitch != null ||
            authStatus == AuthStatus.Loading ||
            authStatus == AuthStatus.SigningIn

    private fun PendingBootstrapResolutionEntity.toRequestStrict(): BootstrapResolutionRequest {
        val storedUser = strictJson.decodeFromString<User>(userJson).also(::validateUser)
        require(ownerUserId.isNotBlank() && storedUser.id == ownerUserId) {
            "Saved history resolution owner is invalid"
        }
        val request = BootstrapResolutionRequest(
            requestId = requestId,
            deviceId = deviceId,
            expectedRevision = expectedRevision,
            strategy = BootstrapStrategy.valueOf(strategy),
            commands = strictJson.decodeFromString(commandsJson),
            taskOperations = strictJson.decodeFromString(taskOperationsJson),
            durationOperations = strictJson.decodeFromString(durationOperationsJson),
        )
        validateResolutionEnvelope(request)
        validateResolutionQueues(request)
        return request
    }

    private fun validateResolutionQueues(request: BootstrapResolutionRequest) {
        if (request.strategy == BootstrapStrategy.KeepRemote) {
            require(
                request.commands.isEmpty() &&
                    request.taskOperations.isEmpty() &&
                    request.durationOperations.isEmpty(),
            ) { "Saved Keep Remote request contains local operations" }
            return
        }
        require(request.commands.sortedBy(TimerCommand::deviceSequence) ==
            pending.sortedBy(TimerCommand::deviceSequence)
        ) { "Saved bootstrap commands do not match local queues" }
        require(request.taskOperations.sortedWith(taskOperationComparator) ==
            pendingTaskOperations.sortedWith(taskOperationComparator)
        ) { "Saved bootstrap task operations do not match local queues" }
        require(request.durationOperations.sortedWith(durationOperationComparator) ==
            pendingDurationOperations.sortedWith(durationOperationComparator)
        ) { "Saved bootstrap duration operations do not match local queues" }
    }

    private fun BootstrapResolutionRequest.toEntity(profile: User) = PendingBootstrapResolutionEntity(
        requestId = requestId,
        deviceId = deviceId,
        expectedRevision = expectedRevision,
        strategy = strategy.name,
        commandsJson = json.encodeToString(commands),
        taskOperationsJson = json.encodeToString(taskOperations),
        durationOperationsJson = json.encodeToString(durationOperations),
        ownerUserId = profile.id,
        userJson = json.encodeToString(profile),
    )

    private fun BootstrapStrategy.displayName(): String = when (this) {
        BootstrapStrategy.KeepRemote -> "Keep Remote"
        BootstrapStrategy.ReplaceRemote -> "Keep Local"
        BootstrapStrategy.Merge -> "Keep Both"
    }

    private fun currentAttemptIdentity() = RepositoryAttemptIdentity(
        accountGeneration = accountGeneration,
        requestId = pendingBootstrapResolution?.requestId,
    )

    private fun isCurrent(identity: RepositoryAttemptIdentity): Boolean =
        identity.accountGeneration == accountGeneration &&
            identity.requestId == pendingBootstrapResolution?.requestId

    private suspend fun handleAuthenticationRequired(
        identity: RepositoryAttemptIdentity,
        message: String,
    ) {
        var shouldCloseStream = false
        actionMutex.withLock {
            if (!isCurrent(identity)) return@withLock
            auth.clear()
            accountGeneration += 1
            authStatus = AuthStatus.SignedOut
            user = null
            bootstrapSnapshot = null
            syncing = false
            retrying = false
            restorePendingResolutionForSignedOut(message)
            publish()
            shouldCloseStream = true
        }
        if (shouldCloseStream) closeRevisionStream()
    }

    private fun corruptedResolutionState() = HistoryResolutionState(
        localHistoryCount = visibleHistoryCount(projection.history),
        remoteHistoryCount = visibleHistoryCount(bootstrapSnapshot?.history.orEmpty()),
        corrupted = true,
        recovery = ResolutionRecovery.Repreview,
        error = "Saved history resolution is corrupted. Local data and queues were preserved.",
    )

    private fun restorePendingResolutionForSignedOut(message: String) {
        historyResolution = pendingBootstrapResolution?.let { stored ->
            runCatching { stored.toRequestStrict() }.fold(
                onSuccess = { request ->
                    HistoryResolutionState(
                        localHistoryCount = visibleHistoryCount(projection.history),
                        remoteHistoryCount = 0,
                        pendingStrategy = request.strategy,
                        requestId = request.requestId,
                        error = message,
                    )
                },
                onFailure = { corruptedResolutionState() },
            )
        }
    }

    private fun publish() {
        val syncStatus = when {
            accountSwitch != null -> SyncStatus.Conflict
            historyResolution != null -> SyncStatus.Conflict
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
            historyResolution = historyResolution,
            accountSwitch = accountSwitch,
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
        const val MaxBootstrapOperations = 4096
        const val MaxTimerDurationMs = 14_400_000L
        val commandTypes = setOf(
            CommandType.Start,
            CommandType.Pause,
            CommandType.Resume,
            CommandType.Finish,
            CommandType.Cancel,
            CommandType.Clear,
        )
        val durationOperationComparator = compareBy<DurationOperation>(
            DurationOperation::hlcWallMs,
            DurationOperation::hlcCounter,
            DurationOperation::id,
        )
        val taskOperationComparator = compareBy<TaskOperation>(
            TaskOperation::hlcWallMs,
            TaskOperation::hlcCounter,
            TaskOperation::id,
        )
        val activeStatuses = setOf(TimerStatus.Running, TimerStatus.Paused)
        val timerStatuses = activeStatuses + setOf(
            TimerStatus.Completed,
            TimerStatus.Cancelled,
            TimerStatus.Superseded,
        )
        val historyStatuses = setOf(
            TimerStatus.Completed,
            TimerStatus.Cancelled,
            TimerStatus.Superseded,
        )
        val acknowledgementOutcomes = setOf("applied", "ignored", "rejected")
    }
}
