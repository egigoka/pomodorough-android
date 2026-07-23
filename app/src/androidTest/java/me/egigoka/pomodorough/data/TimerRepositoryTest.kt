package me.egigoka.pomodorough.data

import android.app.Activity
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.egigoka.pomodorough.data.api.PomodoroughService
import me.egigoka.pomodorough.data.auth.AuthSession
import me.egigoka.pomodorough.data.local.LocalStateEntity
import me.egigoka.pomodorough.data.local.PendingCommandEntity
import me.egigoka.pomodorough.data.local.PendingDurationOperationEntity
import me.egigoka.pomodorough.data.local.PendingTaskOperationEntity
import me.egigoka.pomodorough.data.local.PomodoroughDatabase
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TimerRepositoryTest {
    private lateinit var context: Context
    private lateinit var database: PomodoroughDatabase
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, PomodoroughDatabase::class.java).build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun differentSignedInAccountRequiresExplicitDestructiveConfirmation() = runBlocking {
        val oldUser = user("old-user")
        val oldTimer = timer("old-timer")
        val oldSettings = TimerSettings(
            selectedPhase = TimerPhase.LongBreak,
            autoStartBreaks = true,
        ).withDurations(DurationsMs(focus = 45 * 60_000L))
        val state = state(oldUser, oldTimer).copy(settingsJson = json.encodeToString(oldSettings))
        database.timerDao().insertState(state)
        database.timerDao().insertCommand(PendingCommandEntity.from(command("old-command", "old-timer")))
        database.timerDao().insertTaskOperation(PendingTaskOperationEntity.from(taskOperation()))
        database.timerDao().upsertDurationOperation(
            PendingDurationOperationEntity.from(durationOperation()),
        )
        val service = FakeService(user("new-user"))
        val repository = repository(service)

        repository.initialize()
        await { repository.state.value.authStatus == AuthStatus.SignedIn }

        assertEquals("old-user@example.com", repository.state.value.accountSwitch?.localAccount)
        assertEquals("new-user@example.com", repository.state.value.accountSwitch?.incomingAccount)
        assertEquals("old-user", database.timerDao().localState()?.ownerUserId)
        assertEquals(3, repository.state.value.pendingCount)
        assertEquals("old-timer", repository.state.value.timer?.id)

        repository.confirmAccountSwitch()

        val stored = database.timerDao().localState()
        assertNull(repository.state.value.accountSwitch)
        assertEquals("new-user", repository.state.value.user?.id)
        assertNull(repository.state.value.timer)
        assertTrue(repository.state.value.history.isEmpty())
        assertEquals(0, repository.state.value.pendingCount)
        assertEquals("new-user", stored?.ownerUserId)
        assertNull(stored?.canonicalTimerJson)
        assertEquals("[]", stored?.historyJson)
        assertTrue(database.timerDao().pendingCommands().isEmpty())
        assertTrue(database.timerDao().pendingTaskOperations().isEmpty())
        assertTrue(database.timerDao().pendingDurationOperations().isEmpty())
        assertTrue(repository.state.value.tasks.isEmpty())
        assertEquals(DurationsMs(), repository.state.value.settings.effectiveDurationsMs())
        assertEquals(TimerPhase.LongBreak, repository.state.value.settings.selectedPhase)
        assertTrue(!repository.state.value.settings.autoStartBreaks)
    }

    @Test
    fun lateSyncResponseCannotRestoreLoggedOutAccount() = runBlocking {
        val account = user("account-1")
        val running = timer("timer-1")
        database.timerDao().insertState(state(account, running))
        database.timerDao().insertCommand(PendingCommandEntity.from(command("command-1", "timer-1")))
        val service = FakeService(account).apply {
            syncResponse = SyncResponse(
                acknowledgements = listOf(Acknowledgement("command-1", "applied", "")),
                revision = 99,
                canonicalTimer = timer("server-timer"),
                history = listOf(history("server-history")),
                serverTime = "2026-01-01T00:00:00Z",
                serverHlcWallMs = 1_767_225_600_000,
                serverHlcCounter = 0,
                durationAcknowledgements = emptyList(),
                durationsMs = DurationsMs(),
                taskAcknowledgements = emptyList(),
                tasks = emptyList(),
            )
            blockSync = true
        }
        val auth = FakeAuthSession()
        val repository = repository(service, auth)

        repository.initialize()
        withTimeout(5_000) { service.syncStarted.await() }
        repository.logout()
        service.releaseSync.complete(Unit)
        delay(250)

        val stored = database.timerDao().localState()
        assertEquals(AuthStatus.SignedOut, repository.state.value.authStatus)
        assertNull(repository.state.value.user)
        assertNull(repository.state.value.timer)
        assertEquals(0, repository.state.value.pendingCount)
        assertEquals(0L, stored?.revision)
        assertNull(stored?.ownerUserId)
        assertNull(stored?.canonicalTimerJson)
        assertEquals("[]", stored?.historyJson)
        assertTrue(database.timerDao().pendingCommands().isEmpty())
        assertEquals(1, auth.logoutCalls)
    }

    private fun repository(
        service: FakeService,
        auth: FakeAuthSession = FakeAuthSession(),
    ) = TimerRepository(
        context = context,
        dao = database.timerDao(),
        api = service,
        auth = auth,
        json = json,
        networkAvailable = { true },
    )

    private suspend fun await(condition: () -> Boolean) {
        withTimeout(5_000) {
            while (!condition()) delay(10)
        }
    }

    private fun state(user: User, timer: CanonicalTimer) = LocalStateEntity(
        deviceId = "device-1",
        revision = 4,
        canonicalTimerJson = json.encodeToString(timer),
        historyJson = json.encodeToString(listOf(history("old-history"))),
        settingsJson = json.encodeToString(TimerSettings()),
        userJson = json.encodeToString(user),
        ownerUserId = user.id,
    )

    private fun user(id: String) = User(
        id = id,
        email = "$id@example.com",
        name = id,
        avatarUrl = "",
    )

    private fun timer(id: String) = CanonicalTimer(
        id = id,
        phase = TimerPhase.Focus,
        status = TimerStatus.Running,
        plannedDurationMs = 1_500_000,
        elapsedAtAnchorMs = 0,
        anchorAt = Instant.now().toString(),
    )

    private fun history(id: String) = HistoryItem(
        id = id,
        timerId = id,
        phase = TimerPhase.Focus,
        status = TimerStatus.Completed,
        plannedDurationMs = 1_500_000,
        completedAt = "2026-01-01T00:25:00Z",
    )

    private fun command(id: String, timerId: String) = TimerCommand(
        id = id,
        deviceSequence = 1,
        timerId = timerId,
        type = CommandType.Pause,
        phase = TimerPhase.Focus,
        plannedDurationMs = 1_500_000,
        occurredAt = "2026-01-01T00:05:00Z",
        hlcWallMs = 1_767_225_900_000,
        hlcCounter = 0,
        observedElapsedMs = 300_000,
    )

    private fun taskOperation() = TaskOperation(
        id = "task-operation-1",
        taskId = "aaf83054-24b2-8c0e-901f-a974147bfe82",
        type = TaskOperationType.Upsert,
        title = "Café",
        occurredAt = "2026-01-01T00:00:00Z",
        hlcWallMs = 1_767_225_600_000,
        hlcCounter = 0,
    )

    private fun durationOperation() = DurationOperation(
        id = "duration-operation-1",
        phase = TimerPhase.Focus,
        durationMs = 1_800_000,
        occurredAt = "2026-01-01T00:00:00Z",
        hlcWallMs = 1_767_225_600_001,
        hlcCounter = 0,
    )

    private class FakeAuthSession : AuthSession {
        var logoutCalls = 0

        override suspend fun signIn(activity: Activity, deviceId: String): TokenPair = error("Unused")
        override fun hasTokens(): Boolean = true
        override suspend fun <T> authorized(block: suspend (String) -> T): T = block("access-token")
        override suspend fun logout() {
            logoutCalls += 1
        }
        override fun clear() = Unit
    }

    private class FakeService(private val profile: User) : PomodoroughService {
        var blockSync = false
        val syncStarted = CompletableDeferred<Unit>()
        val releaseSync = CompletableDeferred<Unit>()
        var syncResponse = SyncResponse(
            acknowledgements = emptyList(),
            revision = 0,
            canonicalTimer = null,
            history = emptyList(),
            serverTime = "2026-01-01T00:00:00Z",
            serverHlcWallMs = 1_767_225_600_000,
            serverHlcCounter = 0,
            durationAcknowledgements = emptyList(),
            durationsMs = DurationsMs(),
            taskAcknowledgements = emptyList(),
            tasks = emptyList(),
        )

        override suspend fun me(accessToken: String) = MeResponse(profile, "")

        override suspend fun bootstrap(accessToken: String): SyncResponse = syncResponse.copy(
            acknowledgements = emptyList(),
            durationAcknowledgements = emptyList(),
            taskAcknowledgements = emptyList(),
        )

        override suspend fun resolveBootstrap(
            accessToken: String,
            request: BootstrapResolutionRequest,
        ): SyncResponse = syncResponse

        override suspend fun sync(accessToken: String, request: SyncRequest): SyncResponse {
            syncStarted.complete(Unit)
            if (blockSync) releaseSync.await()
            return syncResponse
        }

        override suspend fun createChallenge(): NativeChallenge = error("Unused")
        override suspend fun exchange(request: NativeExchangeRequest): TokenPair = error("Unused")
        override suspend fun refresh(refreshToken: String): TokenPair = error("Unused")
        override suspend fun logout(accessToken: String) = Unit
        override fun revisionStream(accessToken: String, listener: EventSourceListener): EventSource =
            error("Unused")
    }
}
