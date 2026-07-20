package me.egigoka.pomodorough.integration

import android.app.Activity
import android.content.Context
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.egigoka.pomodorough.data.CanonicalTimer
import me.egigoka.pomodorough.data.CommandType
import me.egigoka.pomodorough.data.HistoryItem
import me.egigoka.pomodorough.data.MeResponse
import me.egigoka.pomodorough.data.NativeChallenge
import me.egigoka.pomodorough.data.NativeExchangeRequest
import me.egigoka.pomodorough.data.SyncRequest
import me.egigoka.pomodorough.data.SyncResponse
import me.egigoka.pomodorough.data.TimerCommand
import me.egigoka.pomodorough.data.TimerPhase
import me.egigoka.pomodorough.data.TimerRepository
import me.egigoka.pomodorough.data.TimerSettings
import me.egigoka.pomodorough.data.TimerStatus
import me.egigoka.pomodorough.data.TokenPair
import me.egigoka.pomodorough.data.User
import me.egigoka.pomodorough.data.api.PomodoroughService
import me.egigoka.pomodorough.data.auth.AuthSession
import me.egigoka.pomodorough.data.local.LocalStateEntity
import me.egigoka.pomodorough.data.local.TimerDao
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener

internal val repositoryJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}

internal class TestAuthSession(
    var tokensAvailable: Boolean = false,
) : AuthSession {
    var logoutCalls = 0
    var logoutFailure: Throwable? = null

    override suspend fun signIn(activity: Activity, deviceId: String): TokenPair = error("Unused")
    override fun hasTokens(): Boolean = tokensAvailable
    override suspend fun <T> authorized(block: suspend (String) -> T): T = block("access-token")

    override suspend fun logout() {
        logoutCalls += 1
        logoutFailure?.let { throw it }
        tokensAvailable = false
    }

    override fun clear() {
        tokensAvailable = false
    }
}

internal class TestRepositoryService(
    var profile: User = testUser(),
) : PomodoroughService {
    var syncCalls = 0
    val syncRequests = mutableListOf<SyncRequest>()
    var syncFailure: Throwable? = null
    var syncResponse = SyncResponse(
        acknowledgements = emptyList(),
        revision = 0,
        canonicalTimer = null,
        history = emptyList(),
        serverTime = "2026-01-01T00:00:00Z",
        serverHlcWallMs = 1_767_225_600_000,
    )

    override suspend fun me(accessToken: String) = MeResponse(profile, "csrf-token")

    override suspend fun sync(accessToken: String, request: SyncRequest): SyncResponse {
        syncCalls += 1
        syncRequests += request
        syncFailure?.let { throw it }
        return syncResponse
    }

    override suspend fun createChallenge(): NativeChallenge = error("Unused")
    override suspend fun exchange(request: NativeExchangeRequest): TokenPair = error("Unused")
    override suspend fun refresh(refreshToken: String): TokenPair = error("Unused")
    override suspend fun logout(accessToken: String) = Unit
    override fun revisionStream(accessToken: String, listener: EventSourceListener): EventSource =
        error("Unused")
}

internal fun testRepository(
    context: Context,
    dao: TimerDao,
    service: TestRepositoryService = TestRepositoryService(),
    auth: TestAuthSession = TestAuthSession(),
    online: Boolean = true,
) = TimerRepository(
    context = context,
    dao = dao,
    api = service,
    auth = auth,
    json = repositoryJson,
    networkAvailable = { online },
)

internal fun testState(
    user: User? = null,
    timer: CanonicalTimer? = null,
    history: List<HistoryItem> = emptyList(),
    settings: TimerSettings = TimerSettings(),
    deviceSequence: Long = 0,
    revision: Long = 0,
) = LocalStateEntity(
    deviceId = "device-1",
    deviceSequence = deviceSequence,
    revision = revision,
    canonicalTimerJson = timer?.let(repositoryJson::encodeToString),
    historyJson = repositoryJson.encodeToString(history),
    settingsJson = repositoryJson.encodeToString(settings),
    userJson = user?.let(repositoryJson::encodeToString),
    ownerUserId = user?.id,
)

internal fun testUser(id: String = "user-1") = User(
    id = id,
    email = "$id@example.com",
    name = id,
    avatarUrl = "",
)

internal fun testTimer(
    id: String = "timer-1",
    phase: String = TimerPhase.Focus,
    status: String = TimerStatus.Running,
    durationMs: Long = 1_500_000,
    elapsedMs: Long = 0,
    anchorAt: String = "2026-01-01T00:00:00Z",
) = CanonicalTimer(
    id = id,
    phase = phase,
    status = status,
    plannedDurationMs = durationMs,
    elapsedAtAnchorMs = elapsedMs,
    anchorAt = anchorAt,
)

internal fun testHistory(id: String, phase: String = TimerPhase.Focus) = HistoryItem(
    id = id,
    timerId = "timer-$id",
    commandId = "command-$id",
    phase = phase,
    status = TimerStatus.Completed,
    plannedDurationMs = 1_500_000,
    completedAt = "2026-01-01T00:25:00Z",
    endedAt = "2026-01-01T00:25:00Z",
)

internal fun testCommand(
    id: String,
    sequence: Long,
    timerId: String = "timer-1",
    type: String = CommandType.Start,
) = TimerCommand(
    id = id,
    deviceSequence = sequence,
    timerId = timerId,
    type = type,
    phase = TimerPhase.Focus,
    plannedDurationMs = 1_500_000,
    occurredAt = "2026-01-01T00:00:00Z",
    hlcWallMs = 1_767_225_600_000 + sequence,
    hlcCounter = 0,
    observedElapsedMs = 0,
)

internal suspend fun awaitState(condition: () -> Boolean) {
    withTimeout(5_000) {
        while (!condition()) delay(10)
    }
}
