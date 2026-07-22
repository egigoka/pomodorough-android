package me.egigoka.pomodorough.integration.negative

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import me.egigoka.pomodorough.data.Acknowledgement
import me.egigoka.pomodorough.data.AuthStatus
import me.egigoka.pomodorough.data.DurationAcknowledgement
import me.egigoka.pomodorough.data.DurationsMs
import me.egigoka.pomodorough.data.FocusTask
import me.egigoka.pomodorough.data.SyncResponse
import me.egigoka.pomodorough.data.SyncStatus
import me.egigoka.pomodorough.data.TaskAcknowledgement
import me.egigoka.pomodorough.data.TaskOperation
import me.egigoka.pomodorough.data.TaskOperationType
import me.egigoka.pomodorough.data.TimerPhase
import me.egigoka.pomodorough.data.TimerSettings
import me.egigoka.pomodorough.data.api.ApiException
import me.egigoka.pomodorough.data.auth.AuthenticationRequired
import me.egigoka.pomodorough.data.local.LocalStateEntity
import me.egigoka.pomodorough.data.local.PendingCommandEntity
import me.egigoka.pomodorough.data.local.PendingDurationOperationEntity
import me.egigoka.pomodorough.data.local.PendingTaskOperationEntity
import me.egigoka.pomodorough.data.local.PomodoroughDatabase
import me.egigoka.pomodorough.integration.TestAuthSession
import me.egigoka.pomodorough.integration.TestRepositoryService
import me.egigoka.pomodorough.integration.awaitState
import me.egigoka.pomodorough.integration.repositoryJson
import me.egigoka.pomodorough.integration.testCommand
import me.egigoka.pomodorough.integration.testDurationOperation
import me.egigoka.pomodorough.integration.testHistory
import me.egigoka.pomodorough.integration.testRepository
import me.egigoka.pomodorough.integration.testState
import me.egigoka.pomodorough.integration.testTimer
import me.egigoka.pomodorough.integration.testUser
import me.egigoka.pomodorough.timer.TimerAlarmScheduler
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TimerRepositoryNegativeTest {
    private lateinit var context: Context
    private lateinit var database: PomodoroughDatabase

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, PomodoroughDatabase::class.java).build()
    }

    @After
    fun tearDown() {
        TimerAlarmScheduler(context).cancel()
        database.close()
    }

    @Test
    fun terminalCommandsWithoutTimerDoNotMutateQueue() = runBlocking {
        val repository = testRepository(context, database.timerDao())
        repository.initialize()

        repository.finishTimer()
        repository.cancelTimer()
        repository.clearTimer()

        assertNull(repository.state.value.timer)
        assertEquals(0, repository.state.value.pendingCount)
        assertTrue(database.timerDao().pendingCommands().isEmpty())
        assertEquals(0L, database.timerDao().localState()?.deviceSequence)
    }

    @Test
    fun activeTimerRejectsPhaseAndDurationChanges() = runBlocking {
        val repository = testRepository(context, database.timerDao())
        repository.initialize()
        repository.toggleTimer()
        val original = repository.state.value.settings

        repository.selectPhase(TimerPhase.LongBreak)
        repository.changeDuration(TimerPhase.Focus, 20)

        assertEquals(original, repository.state.value.settings)
        assertEquals(1, repository.state.value.pendingCount)
        assertEquals(
            original,
            repositoryJson.decodeFromString<TimerSettings>(
                requireNotNull(database.timerDao().localState()).settingsJson,
            ),
        )
    }

    @Test
    fun malformedPersistedJsonFallsBackWithoutBlockingStartup() = runBlocking {
        database.timerDao().insertState(
            LocalStateEntity(
                deviceId = "device-1",
                canonicalTimerJson = "not-json",
                historyJson = "not-json",
                settingsJson = "not-json",
                userJson = "not-json",
            ),
        )
        val repository = testRepository(context, database.timerDao())

        repository.initialize()

        val state = repository.state.value
        assertTrue(state.ready)
        assertEquals(TimerSettings(), state.settings)
        assertNull(state.timer)
        assertNull(state.user)
        assertTrue(state.history.isEmpty())
    }

    @Test
    fun rejectedSyncKeepsQueueAndSurfacesConflict() = runBlocking {
        val profile = testUser()
        val command = testCommand("command-1", sequence = 1)
        database.timerDao().insertState(testState(user = profile, deviceSequence = 1))
        database.timerDao().insertCommand(PendingCommandEntity.from(command))
        val service = TestRepositoryService(profile).apply {
            syncFailure = ApiException(409, "revision conflict")
        }
        val repository = testRepository(
            context,
            database.timerDao(),
            service,
            TestAuthSession(tokensAvailable = true),
        )

        repository.initialize()
        awaitState { repository.state.value.syncStatus == SyncStatus.Conflict }

        assertEquals("revision conflict", repository.state.value.conflict)
        assertEquals(1, repository.state.value.pendingCount)
        assertEquals(listOf(command.id), database.timerDao().pendingCommands().map { it.id })
        assertEquals(1, service.syncCalls)
    }

    @Test
    fun incompleteDurationAcknowledgementSetKeepsPendingOperationAndCanonicalState() = runBlocking {
        val profile = testUser()
        val operation = testDurationOperation(
            id = "duration-1",
            phase = TimerPhase.Focus,
            durationMs = 26 * 60_000L,
        )
        val localDurations = DurationsMs(focus = operation.durationMs)
        database.timerDao().insertState(
            testState(user = profile, settings = TimerSettings().withDurations(localDurations)),
        )
        database.timerDao().upsertDurationOperation(PendingDurationOperationEntity.from(operation))
        val service = TestRepositoryService(profile).apply {
            syncResponse = SyncResponse(
                acknowledgements = emptyList(),
                revision = 1,
                canonicalTimer = null,
                history = emptyList(),
                durationAcknowledgements = emptyList(),
                durationsMs = DurationsMs(focus = 30 * 60_000L),
                taskAcknowledgements = emptyList(),
                tasks = emptyList(),
                serverTime = "2026-01-01T00:00:00Z",
                serverHlcWallMs = 1_767_225_600_100,
                serverHlcCounter = 0,
            )
            bootstrapResponse = syncResponse.copy(
                revision = 0,
                durationAcknowledgements = emptyList(),
                durationsMs = localDurations,
            )
        }
        val repository = testRepository(
            context,
            database.timerDao(),
            service,
            TestAuthSession(tokensAvailable = true),
        )

        repository.initialize()
        awaitState { repository.state.value.syncStatus == SyncStatus.Conflict }

        assertEquals(
            "Sync returned an invalid duration acknowledgement set",
            repository.state.value.conflict,
        )
        assertEquals(1, repository.state.value.pendingCount)
        assertEquals(operation.id, database.timerDao().pendingDurationOperations().single().id)
        assertEquals(operation.durationMs, repository.state.value.settings.durationMsFor(TimerPhase.Focus))
        assertEquals(0L, database.timerDao().localState()?.revision)
    }

    @Test
    fun invalidCanonicalDurationsDoNotReplaceLocalState() = runBlocking {
        val profile = testUser()
        database.timerDao().insertState(testState(user = profile))
        val service = TestRepositoryService(profile).apply {
            syncResponse = SyncResponse(
                acknowledgements = emptyList(),
                revision = 1,
                canonicalTimer = null,
                history = emptyList(),
                durationAcknowledgements = emptyList(),
                durationsMs = DurationsMs(focus = 1_500_001),
                taskAcknowledgements = emptyList(),
                tasks = emptyList(),
                serverTime = "2026-01-01T00:00:00Z",
                serverHlcWallMs = 1_767_225_600_100,
                serverHlcCounter = 0,
            )
            bootstrapResponse = syncResponse.copy(
                revision = 0,
                durationsMs = DurationsMs(),
            )
        }
        val repository = testRepository(
            context,
            database.timerDao(),
            service,
            TestAuthSession(tokensAvailable = true),
        )

        repository.initialize()
        awaitState { repository.state.value.syncStatus == SyncStatus.Conflict }

        assertEquals("Sync returned invalid canonical durations", repository.state.value.conflict)
        assertEquals(DurationsMs(), repository.state.value.settings.effectiveDurationsMs())
        assertEquals(0L, database.timerDao().localState()?.revision)
    }

    @Test
    fun foreignCommandAcknowledgementCannotDeleteInFlightNewCommand() = runBlocking {
        val profile = testUser()
        val sent = testCommand("command-sent", sequence = 1)
        database.timerDao().insertState(testState(user = profile, deviceSequence = 1))
        database.timerDao().insertCommand(PendingCommandEntity.from(sent))
        val syncStarted = CompletableDeferred<Unit>()
        val releaseSync = CompletableDeferred<Unit>()
        var inFlightId: String? = null
        val service = TestRepositoryService(profile).apply {
            syncHandler = {
                syncStarted.complete(Unit)
                releaseSync.await()
                SyncResponse(
                    acknowledgements = listOf(
                        Acknowledgement(sent.id, "applied", ""),
                        Acknowledgement(requireNotNull(inFlightId), "applied", ""),
                    ),
                    revision = 1,
                    canonicalTimer = null,
                    history = emptyList(),
                    durationAcknowledgements = emptyList(),
                    durationsMs = DurationsMs(),
                    taskAcknowledgements = emptyList(),
                    tasks = emptyList(),
                    serverTime = "2026-01-01T00:00:00Z",
                    serverHlcWallMs = 1_767_225_600_100,
                    serverHlcCounter = 0,
                )
            }
            bootstrapResponse = syncResponse
        }
        val repository = testRepository(
            context,
            database.timerDao(),
            service,
            TestAuthSession(tokensAvailable = true),
        )

        repository.initialize()
        syncStarted.await()
        repository.toggleTimer()
        val inFlight = database.timerDao().pendingCommands().last()
        inFlightId = inFlight.id
        releaseSync.complete(Unit)
        awaitState { repository.state.value.syncStatus == SyncStatus.Conflict }
        delay(100)

        assertEquals(
            "Sync returned an invalid command acknowledgement set",
            repository.state.value.conflict,
        )
        assertEquals(listOf(sent.id, inFlight.id), database.timerDao().pendingCommands().map { it.id })
        assertEquals(0L, database.timerDao().localState()?.revision)
        assertEquals(1, service.syncCalls)
    }

    @Test
    fun duplicateTaskAcknowledgementsCannotMutateQueueOrSnapshot() = runBlocking {
        val profile = testUser()
        val task = FocusTask("task-1", "Ship")
        val operation = TaskOperation(
            id = "task-operation-1",
            taskId = task.id,
            type = TaskOperationType.Upsert,
            title = task.title,
            occurredAt = "2026-01-01T00:00:00Z",
            hlcWallMs = 1_767_225_600_001,
            hlcCounter = 0,
        )
        database.timerDao().insertState(testState(user = profile))
        database.timerDao().insertTaskOperation(PendingTaskOperationEntity.from(operation))
        val service = TestRepositoryService(profile).apply {
            syncResponse = SyncResponse(
                acknowledgements = emptyList(),
                revision = 1,
                canonicalTimer = null,
                history = emptyList(),
                durationAcknowledgements = emptyList(),
                durationsMs = DurationsMs(),
                taskAcknowledgements = listOf(
                    TaskAcknowledgement(operation.id, "applied", ""),
                    TaskAcknowledgement(operation.id, "applied", ""),
                ),
                tasks = listOf(task),
                serverTime = "2026-01-01T00:00:00Z",
                serverHlcWallMs = 1_767_225_600_100,
                serverHlcCounter = 0,
            )
            bootstrapResponse = syncResponse.copy(
                revision = 0,
                taskAcknowledgements = emptyList(),
                tasks = emptyList(),
            )
        }
        val repository = testRepository(
            context,
            database.timerDao(),
            service,
            TestAuthSession(tokensAvailable = true),
        )

        repository.initialize()
        awaitState { repository.state.value.syncStatus == SyncStatus.Conflict }

        assertEquals("Sync returned an invalid task acknowledgement set", repository.state.value.conflict)
        assertEquals(operation.id, database.timerDao().pendingTaskOperations().single().id)
        assertEquals(0L, database.timerDao().localState()?.revision)
    }

    @Test
    fun nonMinutePendingDurationIsTerminalWithoutNetworkMutation() = runBlocking {
        val profile = testUser()
        val operation = testDurationOperation(
            id = "duration-invalid-minute",
            phase = TimerPhase.Focus,
            durationMs = 1_500_001,
        )
        database.timerDao().insertState(testState(user = profile))
        database.timerDao().upsertDurationOperation(PendingDurationOperationEntity.from(operation))
        val service = TestRepositoryService(profile)
        val repository = testRepository(
            context,
            database.timerDao(),
            service,
            TestAuthSession(tokensAvailable = true),
        )

        repository.initialize()
        awaitState { repository.state.value.syncStatus == SyncStatus.Conflict }

        assertEquals("Queued duration operation is invalid", repository.state.value.conflict)
        assertEquals(operation.id, database.timerDao().pendingDurationOperations().single().id)
        assertEquals(0, service.syncCalls)
    }

    @Test
    fun cachedOwnerIsNotTrustedWhenProfileVerificationFails() = runBlocking {
        val profile = testUser("cached-user")
        val command = testCommand("command-1", sequence = 1)
        database.timerDao().insertState(testState(user = profile, deviceSequence = 1))
        database.timerDao().insertCommand(PendingCommandEntity.from(command))
        val service = TestRepositoryService(profile).apply {
            meFailure = IOException("profile unavailable")
        }
        val repository = testRepository(
            context,
            database.timerDao(),
            service,
            TestAuthSession(tokensAvailable = true),
        )

        repository.initialize()

        assertEquals(AuthStatus.SignedOut, repository.state.value.authStatus)
        assertNull(repository.state.value.user)
        assertEquals("profile unavailable", repository.state.value.notice)
        assertEquals(0, service.syncCalls)
        assertEquals(command.id, database.timerDao().pendingCommands().single().id)
    }

    @Test
    fun invalidProfileIsRejectedBeforeBootstrapOrOwnerMutation() = runBlocking {
        val owner = testUser("owner-user")
        val command = testCommand("owner-command", sequence = 1)
        database.timerDao().insertState(testState(user = owner, deviceSequence = 1))
        database.timerDao().insertCommand(PendingCommandEntity.from(command))
        val service = TestRepositoryService(owner.copy(email = "invalid-email"))
        val auth = TestAuthSession(tokensAvailable = true)
        val repository = testRepository(context, database.timerDao(), service, auth)

        repository.initialize()

        assertEquals(AuthStatus.SignedOut, repository.state.value.authStatus)
        assertTrue(!auth.tokensAvailable)
        assertEquals(0, service.bootstrapCalls)
        assertNull(repository.state.value.accountSwitch)
        assertEquals(owner.id, database.timerDao().localState()?.ownerUserId)
        assertEquals(listOf(command.id), database.timerDao().pendingCommands().map { it.id })
    }

    @Test
    fun failedLogoutPreservesAccountDataAndReportsNotice() = runBlocking {
        val profile = testUser()
        val timer = testTimer()
        val persisted = testState(user = profile, timer = timer, history = listOf(testHistory("history-1")))
        database.timerDao().insertState(persisted)
        val auth = TestAuthSession(tokensAvailable = true).apply {
            logoutFailure = IOException("logout unavailable")
        }
        val service = TestRepositoryService(profile).apply {
            bootstrapResponse = syncResponse.copy(
                canonicalTimer = timer,
                history = listOf(testHistory("history-1")),
            )
        }
        val repository = testRepository(
            context,
            database.timerDao(),
            service,
            auth,
            online = false,
        )
        repository.initialize()
        val beforeLogout = database.timerDao().localState()

        repository.logout()

        assertEquals(1, auth.logoutCalls)
        assertEquals(AuthStatus.SignedIn, repository.state.value.authStatus)
        assertEquals(profile, repository.state.value.user)
        assertEquals(timer, repository.state.value.timer)
        assertEquals("logout unavailable", repository.state.value.notice)
        assertEquals(beforeLogout, database.timerDao().localState())
    }

    @Test
    fun authenticationFailureDuringSyncSignsOutWithoutDeletingQueue() = runBlocking {
        val profile = testUser()
        val command = testCommand("command-1", sequence = 1)
        database.timerDao().insertState(testState(user = profile, deviceSequence = 1))
        database.timerDao().insertCommand(PendingCommandEntity.from(command))
        val service = TestRepositoryService(profile).apply {
            syncFailure = AuthenticationRequired("Session expired")
        }
        val repository = testRepository(
            context,
            database.timerDao(),
            service,
            TestAuthSession(tokensAvailable = true),
        )

        repository.initialize()
        awaitState { repository.state.value.authStatus == AuthStatus.SignedOut }

        assertNull(repository.state.value.user)
        assertEquals(1, repository.state.value.pendingCount)
        assertEquals(listOf(command.id), database.timerDao().pendingCommands().map { it.id })
        assertNotNull(database.timerDao().localState()?.ownerUserId)
    }
}
