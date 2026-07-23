package me.egigoka.pomodorough.integration

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.egigoka.pomodorough.data.Acknowledgement
import me.egigoka.pomodorough.data.AutoStartAcknowledgement
import me.egigoka.pomodorough.data.AutoStartOperation
import me.egigoka.pomodorough.data.BootstrapResolutionRequest
import me.egigoka.pomodorough.data.CanonicalTimer
import me.egigoka.pomodorough.data.CommandType
import me.egigoka.pomodorough.data.DurationAcknowledgement
import me.egigoka.pomodorough.data.DurationsMs
import me.egigoka.pomodorough.data.FocusTask
import me.egigoka.pomodorough.data.HistoryItem
import me.egigoka.pomodorough.data.MeResponse
import me.egigoka.pomodorough.data.NativeChallenge
import me.egigoka.pomodorough.data.NativeExchangeRequest
import me.egigoka.pomodorough.data.SyncRequest
import me.egigoka.pomodorough.data.SyncResponse
import me.egigoka.pomodorough.data.SyncStatus
import me.egigoka.pomodorough.data.TaskAcknowledgement
import me.egigoka.pomodorough.data.TimerPhase
import me.egigoka.pomodorough.data.TimerSettings
import me.egigoka.pomodorough.data.TimerStatus
import me.egigoka.pomodorough.data.TokenPair
import me.egigoka.pomodorough.data.User
import me.egigoka.pomodorough.data.api.ApiException
import me.egigoka.pomodorough.data.api.PomodoroughService
import me.egigoka.pomodorough.data.local.PendingAutoStartOperationEntity
import me.egigoka.pomodorough.data.local.PendingCommandEntity
import me.egigoka.pomodorough.data.local.PomodoroughDatabase
import me.egigoka.pomodorough.domain.TaskReducer
import me.egigoka.pomodorough.domain.TimerReducer
import me.egigoka.pomodorough.timer.TimerAlarmScheduler
import okhttp3.Request
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
class AutoStartRepositoryTest {
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
    fun optimisticProjectionUsesImmutableLwwClockAndIdentityTies() = runBlocking {
        val lower = testAutoStartOperation(
            id = "00000000-0000-4000-8000-000000000001",
            enabled = false,
            wallMs = 9_000_000_000_000,
            counter = 3,
        )
        val winner = lower.copy(
            id = "00000000-0000-4000-8000-000000000002",
            enabled = true,
        )
        database.timerDao().insertState(testState().copy(hlcWallMs = lower.hlcWallMs, hlcCounter = 3))
        database.timerDao().insertAutoStartOperation(PendingAutoStartOperationEntity.from(winner))
        database.timerDao().insertAutoStartOperation(PendingAutoStartOperationEntity.from(lower))
        val repository = testRepository(context, database.timerDao())

        repository.initialize()

        assertTrue(repository.state.value.settings.autoStartBreaks)
        assertEquals(
            listOf(lower.id, winner.id),
            database.timerDao().pendingAutoStartOperations().map { it.id },
        )

        repository.setAutoStart(false)

        val operations = database.timerDao().pendingAutoStartOperations().map { it.toModel() }
        val stored = requireNotNull(database.timerDao().localState())
        assertEquals(3, operations.size)
        assertTrue(!repository.state.value.settings.autoStartBreaks)
        assertEquals(stored.hlcWallMs, operations.last().hlcWallMs)
        assertEquals(stored.hlcCounter, operations.last().hlcCounter)
        assertEquals(3, operations.map { it.id }.toSet().size)
    }

    @Test
    fun untouchedLegacyFalseDoesNotOverwriteExistingAccountCanonicalTrue() = runBlocking {
        val profile = testUser("legacy-default-owner")
        database.timerDao().insertState(
            testState(
                user = profile,
                settings = TimerSettings(autoStartBreaks = false),
            ),
        )
        val service = TestRepositoryService(profile).apply {
            syncResponse = syncResponse.copy(revision = 1, autoStartBreaks = true)
            bootstrapResponse = syncResponse
        }
        val repository = testRepository(
            context,
            database.timerDao(),
            service,
            TestAuthSession(tokensAvailable = true),
        )

        repository.initialize()
        awaitState { service.syncCalls == 1 && repository.state.value.syncStatus == SyncStatus.Synced }

        assertTrue(repository.state.value.settings.autoStartBreaks)
        assertTrue(requireNotNull(database.timerDao().localState()).canonicalAutoStartBreaks)
        assertTrue(database.timerDao().pendingAutoStartOperations().isEmpty())
        assertTrue(service.syncRequests.single().autoStartOperations.isEmpty())
        assertEquals(0, service.resolveCalls)
    }

    @Test
    fun syncBatchesTrueAndFalseOperationsAndRequiresExactAcknowledgements() = runBlocking {
        val profile = testUser()
        val operations = listOf(
            testAutoStartOperation("00000000-0000-4000-8000-000000000001", enabled = true),
            testAutoStartOperation(
                "00000000-0000-4000-8000-000000000002",
                enabled = false,
                wallMs = 1_767_225_600_001,
            ),
        )
        database.timerDao().insertState(testState(user = profile))
        operations.forEach {
            database.timerDao().insertAutoStartOperation(PendingAutoStartOperationEntity.from(it))
        }
        val service = TestRepositoryService(profile).apply {
            syncHandler = { request ->
                syncResponse.copy(
                    revision = 1,
                    autoStartAcknowledgements = request.autoStartOperations.map {
                        AutoStartAcknowledgement(it.id, "applied", "")
                    },
                    autoStartBreaks = request.autoStartOperations.last().enabled,
                )
            }
        }
        val repository = testRepository(
            context,
            database.timerDao(),
            service,
            TestAuthSession(tokensAvailable = true),
        )

        repository.initialize()
        awaitState { service.syncCalls == 1 && repository.state.value.pendingCount == 0 }

        assertEquals(operations, service.syncRequests.single().autoStartOperations)
        assertTrue(!repository.state.value.settings.autoStartBreaks)
        assertTrue(!requireNotNull(database.timerDao().localState()).canonicalAutoStartBreaks)
        assertTrue(database.timerDao().pendingAutoStartOperations().isEmpty())
    }

    @Test
    fun autoStartAcknowledgementMismatchKeepsQueueAndCanonicalSnapshot() = runBlocking {
        val profile = testUser()
        val operation = testAutoStartOperation(
            "00000000-0000-4000-8000-000000000001",
            enabled = true,
        )
        database.timerDao().insertState(testState(user = profile))
        database.timerDao().insertAutoStartOperation(PendingAutoStartOperationEntity.from(operation))
        val service = TestRepositoryService(profile).apply {
            bootstrapResponse = syncResponse
            syncResponse = syncResponse.copy(revision = 1, autoStartBreaks = false)
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
            "Sync returned an invalid auto-start acknowledgement set",
            repository.state.value.conflict,
        )
        assertEquals(operation.id, database.timerDao().pendingAutoStartOperations().single().id)
        assertEquals(0L, database.timerDao().localState()?.revision)
        assertTrue(repository.state.value.settings.autoStartBreaks)
    }

    @Test
    fun newerToggleDuringInFlightSyncRebasesOverCanonicalResponse() = runBlocking {
        val profile = testUser()
        val sent = testAutoStartOperation(
            "00000000-0000-4000-8000-000000000001",
            enabled = true,
        )
        database.timerDao().insertState(testState(user = profile))
        database.timerDao().insertAutoStartOperation(PendingAutoStartOperationEntity.from(sent))
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var calls = 0
        val service = TestRepositoryService(profile).apply {
            syncHandler = { request ->
                calls += 1
                if (calls == 1) {
                    started.complete(Unit)
                    release.await()
                    syncResponse.copy(
                        revision = 1,
                        autoStartAcknowledgements = listOf(
                            AutoStartAcknowledgement(sent.id, "applied", ""),
                        ),
                        autoStartBreaks = true,
                    )
                } else {
                    throw ApiException(409, "stop after auto-start rebase verification")
                }
            }
        }
        val repository = testRepository(
            context,
            database.timerDao(),
            service,
            TestAuthSession(tokensAvailable = true),
        )

        repository.initialize()
        started.await()
        repository.setAutoStart(false)
        val replacementId = database.timerDao().pendingAutoStartOperations().last().id
        release.complete(Unit)
        awaitState { repository.state.value.conflict == "stop after auto-start rebase verification" }

        val remaining = database.timerDao().pendingAutoStartOperations().single().toModel()
        assertEquals(replacementId, remaining.id)
        assertTrue(!remaining.enabled)
        assertTrue(!repository.state.value.settings.autoStartBreaks)
        assertEquals(replacementId, service.syncRequests[1].autoStartOperations.single().id)
    }

    @Test
    fun autoStartQueueSyncsInProtocolSizedBatches() = runBlocking {
        val profile = testUser()
        val operations = (1..257).map { index ->
            testAutoStartOperation(
                id = "00000000-0000-4000-8000-${index.toString().padStart(12, '0')}",
                enabled = index % 2 == 1,
                wallMs = 1_767_225_600_000 + index,
            )
        }
        database.timerDao().insertState(testState(user = profile))
        operations.forEach {
            database.timerDao().insertAutoStartOperation(PendingAutoStartOperationEntity.from(it))
        }
        var revision = 0L
        var canonical = false
        val service = TestRepositoryService(profile).apply {
            syncHandler = { request ->
                revision += 1
                canonical = request.autoStartOperations.lastOrNull()?.enabled ?: canonical
                syncResponse.copy(
                    revision = revision,
                    autoStartAcknowledgements = request.autoStartOperations.map {
                        AutoStartAcknowledgement(it.id, "applied", "")
                    },
                    autoStartBreaks = canonical,
                )
            }
        }
        val repository = testRepository(
            context,
            database.timerDao(),
            service,
            TestAuthSession(tokensAvailable = true),
        )

        repository.initialize()
        awaitState { service.syncCalls == 2 && repository.state.value.pendingCount == 0 }

        assertEquals(listOf(256, 1), service.syncRequests.map { it.autoStartOperations.size })
        assertTrue(repository.state.value.settings.autoStartBreaks)
    }

    @Test
    fun fullCustomDurationFocusBreakPathKeepsFocusTaskAndUsesFourthLongBreak() = runBlocking {
        val repository = testRepository(context, database.timerDao())
        repository.initialize()
        repository.changeDuration(TimerPhase.Focus, -24)
        repository.changeDuration(TimerPhase.ShortBreak, -4)
        repository.changeDuration(TimerPhase.LongBreak, -14)
        repository.setAutoStart(true)
        repository.addTask("Account-synced launch")
        val task = repository.state.value.tasks.single()

        repository.toggleTimer()
        repeat(4) { index ->
            assertEquals(TimerPhase.Focus, repository.state.value.timer?.phase)
            assertEquals(60_000L, repository.state.value.timer?.plannedDurationMs)
            assertEquals(task.id, repository.state.value.timer?.taskId)

            repository.finishTimer()

            val expectedBreak = if (index == 3) TimerPhase.LongBreak else TimerPhase.ShortBreak
            assertEquals(expectedBreak, repository.state.value.timer?.phase)
            assertEquals(TimerStatus.Running, repository.state.value.timer?.status)
            assertEquals(60_000L, repository.state.value.timer?.plannedDurationMs)
            assertNull(repository.state.value.timer?.taskId)
            if (index < 3) {
                repository.finishTimer()
                repository.toggleTimer()
            }
        }

        val focusHistory = repository.state.value.history.filter { it.phase == TimerPhase.Focus }
        val commands = database.timerDao().pendingCommands().map(PendingCommandEntity::toModel)
        assertEquals(4, focusHistory.size)
        assertTrue(focusHistory.all { it.taskId == task.id })
        assertEquals(4 * 60_000L, repository.state.value.taskSummaries.single().timeSpentMs)
        commands.filter { it.type == CommandType.Finish && it.phase == TimerPhase.Focus }.forEach { finish ->
            val next = commands.single { it.deviceSequence == finish.deviceSequence + 1 }
            assertEquals(CommandType.Start, next.type)
            assertTrue(next.phase == TimerPhase.ShortBreak || next.phase == TimerPhase.LongBreak)
        }
    }

    @Test
    fun autoStartOffAndRemoteCompletionNeverCreateBreakCommands() = runBlocking {
        val localRepository = testRepository(context, database.timerDao())
        localRepository.initialize()
        localRepository.toggleTimer()
        localRepository.finishTimer()

        assertEquals(TimerStatus.Completed, localRepository.state.value.timer?.status)
        assertEquals(
            listOf(CommandType.Start, CommandType.Finish),
            database.timerDao().pendingCommands().map { it.type },
        )

        database.close()
        database = Room.inMemoryDatabaseBuilder(context, PomodoroughDatabase::class.java).build()
        val profile = testUser()
        val running = testTimer()
        val completed = running.copy(
            status = TimerStatus.Completed,
            elapsedAtAnchorMs = running.plannedDurationMs,
        )
        val history = testHistory("remote-completion").copy(timerId = running.id)
        database.timerDao().insertState(
            testState(
                user = profile,
                timer = running,
                settings = TimerSettings(autoStartBreaks = true),
            ),
        )
        val service = TestRepositoryService(profile).apply {
            bootstrapResponse = syncResponse.copy(canonicalTimer = running, autoStartBreaks = true)
            syncResponse = syncResponse.copy(
                revision = 1,
                canonicalTimer = completed,
                history = listOf(history),
                autoStartBreaks = true,
            )
        }
        val remoteRepository = testRepository(
            context,
            database.timerDao(),
            service,
            TestAuthSession(tokensAvailable = true),
        )

        remoteRepository.initialize()
        awaitState { service.syncCalls == 1 && remoteRepository.state.value.timer?.status == TimerStatus.Completed }

        assertTrue(database.timerDao().pendingCommands().isEmpty())
        assertEquals(TimerStatus.Completed, remoteRepository.state.value.timer?.status)
    }

    @Test
    fun coldSignedInExpiredAlarmPersistsFinishAndBreakBeforeProfileRestore() = runBlocking {
        val profile = testUser()
        val expired = testTimer(elapsedMs = 1_500_000, anchorAt = "2000-01-01T00:00:00Z")
        database.timerDao().insertState(
            testState(
                user = profile,
                timer = expired,
                settings = TimerSettings(autoStartBreaks = true),
                deviceSequence = 9,
            ),
        )
        val service = TestRepositoryService(profile)
        val repository = testRepository(
            context,
            database.timerDao(),
            service,
            TestAuthSession(tokensAvailable = true),
        )

        assertTrue(repository.finishExpiredTimer())

        val commands = database.timerDao().pendingCommands().map { it.toModel() }
        assertEquals(listOf(CommandType.Finish, CommandType.Start), commands.map { it.type })
        assertEquals(listOf(10L, 11L), commands.map { it.deviceSequence })
        assertEquals(TimerPhase.ShortBreak, repository.state.value.timer?.phase)
        assertEquals(0, service.bootstrapCalls)
    }

    @Test
    fun lostResponseThenRestartRetriesSameOperationAndAcceptsIgnoredAck() = runBlocking {
        val profile = testUser()
        val operation = testAutoStartOperation(
            "00000000-0000-4000-8000-000000000001",
            enabled = true,
        )
        database.timerDao().insertState(testState(user = profile))
        database.timerDao().insertAutoStartOperation(PendingAutoStartOperationEntity.from(operation))
        var calls = 0
        val failedService = TestRepositoryService(profile).apply {
            syncHandler = {
                calls += 1
                if (calls == 1) throw IOException("response lost after apply")
                throw ApiException(409, "stop retries for restart")
            }
        }
        val first = testRepository(
            context,
            database.timerDao(),
            failedService,
            TestAuthSession(tokensAvailable = true),
        )
        first.initialize()
        awaitState { first.state.value.conflict == "stop retries for restart" }
        assertEquals(operation.id, database.timerDao().pendingAutoStartOperations().single().id)

        val retryService = TestRepositoryService(profile).apply {
            bootstrapResponse = syncResponse.copy(autoStartBreaks = true)
            syncHandler = { request ->
                syncResponse.copy(
                    revision = 1,
                    autoStartAcknowledgements = request.autoStartOperations.map {
                        AutoStartAcknowledgement(it.id, "ignored", "already applied")
                    },
                    autoStartBreaks = true,
                )
            }
        }
        val restarted = testRepository(
            context,
            database.timerDao(),
            retryService,
            TestAuthSession(tokensAvailable = true),
        )
        restarted.initialize()
        awaitState { retryService.syncCalls == 1 && restarted.state.value.pendingCount == 0 }

        assertEquals(listOf(operation), retryService.syncRequests.single().autoStartOperations)
        assertTrue(restarted.state.value.settings.autoStartBreaks)
        assertTrue(database.timerDao().pendingAutoStartOperations().isEmpty())
    }

    @Test
    fun sseRevisionPullsRemoteCanonicalAutoStartWithoutLocalOperation() = runBlocking {
        val profile = testUser()
        database.timerDao().insertState(testState(user = profile))
        val service = TestRepositoryService(profile)
        val repository = testRepository(
            context,
            database.timerDao(),
            service,
            TestAuthSession(tokensAvailable = true),
        )
        repository.initialize()
        awaitState { service.syncCalls == 1 }
        repository.onForeground()
        awaitState { service.revisionListener != null }
        service.syncResponse = service.syncResponse.copy(revision = 2, autoStartBreaks = true)

        service.revisionListener?.onEvent(dummyEventSource(), null, null, "{\"revision\":2}")
        awaitState { service.syncCalls >= 2 && repository.state.value.settings.autoStartBreaks }

        assertTrue(database.timerDao().pendingAutoStartOperations().isEmpty())
        assertTrue(requireNotNull(database.timerDao().localState()).canonicalAutoStartBreaks)
    }

    @Test
    fun twoLogicalClientsConvergeWhileSelectedNextTaskStaysDeviceLocal() = runBlocking {
        val profile = testUser("shared-user")
        val secondDatabase = Room.inMemoryDatabaseBuilder(context, PomodoroughDatabase::class.java).build()
        try {
            database.timerDao().insertState(testState(user = profile).copy(deviceId = "device-a"))
            secondDatabase.timerDao().insertState(testState(user = profile).copy(deviceId = "device-b"))
            val service = ConvergingService(profile)
            val first = testRepository(
                context,
                database.timerDao(),
                service,
                TestAuthSession(tokensAvailable = true),
            )
            val second = testRepository(
                context,
                secondDatabase.timerDao(),
                service,
                TestAuthSession(tokensAvailable = true),
            )
            first.initialize()
            second.initialize()
            awaitState { first.state.value.syncStatus == SyncStatus.Synced }
            awaitState { second.state.value.syncStatus == SyncStatus.Synced }

            first.addTask("Shared focus")
            first.changeDuration(TimerPhase.Focus, 5)
            first.setAutoStart(true)
            first.toggleTimer()
            awaitState { first.state.value.pendingCount == 0 }

            second.onForeground()
            awaitState {
                second.state.value.timer?.taskId == first.state.value.selectedTaskId &&
                    second.state.value.settings.autoStartBreaks
            }
            assertNull(second.state.value.selectedTaskId)

            first.finishTimer()
            awaitState { first.state.value.pendingCount == 0 }
            second.onForeground()
            awaitState {
                second.state.value.timer?.phase == TimerPhase.ShortBreak &&
                    second.state.value.history.size == 1
            }

            assertEquals(first.state.value.timer, second.state.value.timer)
            assertEquals(first.state.value.history, second.state.value.history)
            assertEquals(first.state.value.tasks, second.state.value.tasks)
            assertEquals(
                first.state.value.settings.effectiveDurationsMs(),
                second.state.value.settings.effectiveDurationsMs(),
            )
            assertEquals(
                first.state.value.settings.autoStartBreaks,
                second.state.value.settings.autoStartBreaks,
            )
            assertEquals(30 * 60_000L, second.state.value.history.single().plannedDurationMs)
            assertEquals(first.state.value.selectedTaskId, second.state.value.history.single().taskId)
        } finally {
            secondDatabase.close()
        }
    }

    private fun dummyEventSource() = object : EventSource {
        override fun request(): Request = Request.Builder().url("https://example.test/stream").build()
        override fun cancel() = Unit
    }

    private class ConvergingService(private val profile: User) : PomodoroughService {
        private val mutex = Mutex()
        private var revision = 0L
        private var timer: CanonicalTimer? = null
        private var history = emptyList<HistoryItem>()
        private var tasks = emptyList<FocusTask>()
        private var durations = DurationsMs()
        private var autoStartBreaks = false
        private var autoWinner: AutoStartOperation? = null

        override suspend fun me(accessToken: String) = MeResponse(profile, "csrf")

        override suspend fun bootstrap(accessToken: String): SyncResponse = mutex.withLock {
            response()
        }

        override suspend fun sync(accessToken: String, request: SyncRequest): SyncResponse = mutex.withLock {
            if (request.commands.isNotEmpty()) {
                val projected = TimerReducer.replay(timer, history, request.commands)
                timer = projected.timer
                history = projected.history.map { it.copy(pending = false) }
            }
            if (request.taskOperations.isNotEmpty()) {
                tasks = TaskReducer.replay(tasks, request.taskOperations)
            }
            request.durationOperations.sortedWith(
                compareBy({ it.hlcWallMs }, { it.hlcCounter }, { it.id }),
            ).forEach { operation ->
                durations = durations.withDuration(operation.phase, operation.durationMs)
            }
            request.autoStartOperations.forEach { operation ->
                val current = autoWinner
                if (current == null || autoKey(operation) > autoKey(current)) {
                    autoWinner = operation
                    autoStartBreaks = operation.enabled
                }
            }
            if (request.commands.isNotEmpty() || request.taskOperations.isNotEmpty() ||
                request.durationOperations.isNotEmpty() || request.autoStartOperations.isNotEmpty()
            ) revision += 1
            response(
                acknowledgements = request.commands.map { Acknowledgement(it.id, "applied", "") },
                taskAcknowledgements = request.taskOperations.map {
                    TaskAcknowledgement(it.id, "applied", "")
                },
                durationAcknowledgements = request.durationOperations.map {
                    DurationAcknowledgement(it.id, "applied", "")
                },
                autoStartAcknowledgements = request.autoStartOperations.map {
                    AutoStartAcknowledgement(it.id, "applied", "")
                },
            )
        }

        private fun response(
            acknowledgements: List<Acknowledgement> = emptyList(),
            taskAcknowledgements: List<TaskAcknowledgement> = emptyList(),
            durationAcknowledgements: List<DurationAcknowledgement> = emptyList(),
            autoStartAcknowledgements: List<AutoStartAcknowledgement> = emptyList(),
        ) = SyncResponse(
            acknowledgements = acknowledgements,
            revision = revision,
            canonicalTimer = timer,
            history = history,
            serverTime = "2026-01-01T00:00:00Z",
            serverHlcWallMs = 1_767_225_600_000 + revision,
            serverHlcCounter = 0,
            durationAcknowledgements = durationAcknowledgements,
            durationsMs = durations,
            taskAcknowledgements = taskAcknowledgements,
            tasks = tasks,
            autoStartAcknowledgements = autoStartAcknowledgements,
            autoStartBreaks = autoStartBreaks,
        )

        private fun autoKey(operation: AutoStartOperation) = listOf(
            operation.hlcWallMs.toString().padStart(20, '0'),
            operation.hlcCounter.toString().padStart(20, '0'),
            operation.deviceId,
            operation.id,
        ).joinToString(":")

        override suspend fun resolveBootstrap(
            accessToken: String,
            request: BootstrapResolutionRequest,
        ): SyncResponse = error("Unused")
        override suspend fun createChallenge(): NativeChallenge = error("Unused")
        override suspend fun exchange(request: NativeExchangeRequest): TokenPair = error("Unused")
        override suspend fun refresh(refreshToken: String): TokenPair = error("Unused")
        override suspend fun logout(accessToken: String) = Unit
        override fun revisionStream(accessToken: String, listener: EventSourceListener): EventSource =
            object : EventSource {
                override fun request(): Request = Request.Builder().url("https://example.test/stream").build()
                override fun cancel() = Unit
            }
    }
}
