package me.egigoka.pomodorough.integration.positive

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import me.egigoka.pomodorough.data.Acknowledgement
import me.egigoka.pomodorough.data.AuthStatus
import me.egigoka.pomodorough.data.CommandType
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
import me.egigoka.pomodorough.data.TimerStatus
import me.egigoka.pomodorough.data.api.ApiException
import me.egigoka.pomodorough.data.local.PendingCommandEntity
import me.egigoka.pomodorough.data.local.PendingDurationOperationEntity
import me.egigoka.pomodorough.data.local.PendingTaskOperationEntity
import me.egigoka.pomodorough.data.local.PomodoroughDatabase
import me.egigoka.pomodorough.domain.TaskReducer
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TimerRepositoryPositiveTest {
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
    fun initializeCreatesStableLocalIdentityAndDefaultState() = runBlocking {
        val repository = testRepository(context, database.timerDao())

        repository.initialize()

        val state = repository.state.value
        val stored = database.timerDao().localState()
        assertTrue(state.ready)
        assertEquals(AuthStatus.SignedOut, state.authStatus)
        assertEquals(SyncStatus.Synced, state.syncStatus)
        assertTrue(state.deviceId.isNotBlank())
        assertEquals(state.deviceId, stored?.deviceId)
        assertEquals(TimerSettings(), state.settings)
        assertNull(state.timer)
    }

    @Test
    fun offlineCommandLifecyclePersistsInMonotonicOrder() = runBlocking {
        val repository = testRepository(context, database.timerDao())
        repository.initialize()

        repository.toggleTimer()
        val timerId = repository.state.value.timer?.id
        assertNotNull(timerId)
        assertEquals(TimerStatus.Running, repository.state.value.timer?.status)

        repository.toggleTimer()
        assertEquals(TimerStatus.Paused, repository.state.value.timer?.status)
        repository.toggleTimer()
        assertEquals(TimerStatus.Running, repository.state.value.timer?.status)
        repository.cancelTimer()
        assertEquals(TimerStatus.Cancelled, repository.state.value.timer?.status)
        repository.clearTimer()

        val commands = database.timerDao().pendingCommands().map(PendingCommandEntity::toModel)
        assertNull(repository.state.value.timer)
        assertEquals(
            listOf(CommandType.Start, CommandType.Pause, CommandType.Resume, CommandType.Cancel, CommandType.Clear),
            commands.map { it.type },
        )
        assertEquals((1L..5L).toList(), commands.map { it.deviceSequence })
        assertTrue(commands.all { it.timerId == timerId })
        assertEquals(5L, database.timerDao().localState()?.deviceSequence)
    }

    @Test
    fun fourthExpiredFocusTimerAutoStartsLongBreak() = runBlocking {
        val completedFocus = (1..3).map { testHistory("history-$it") }
        val expired = testTimer(elapsedMs = 1_500_000, anchorAt = "2000-01-01T00:00:00Z")
        database.timerDao().insertState(
            testState(
                timer = expired,
                history = completedFocus,
                settings = TimerSettings(autoStartBreaks = true),
                deviceSequence = 10,
            ),
        )
        val repository = testRepository(context, database.timerDao())
        repository.initialize()

        assertTrue(repository.finishExpiredTimer())

        val state = repository.state.value
        val commands = database.timerDao().pendingCommands().map(PendingCommandEntity::toModel)
        assertEquals(TimerStatus.Running, state.timer?.status)
        assertEquals(TimerPhase.LongBreak, state.timer?.phase)
        assertEquals(4, state.history.size)
        assertTrue(state.history.first().pending)
        assertEquals(listOf(CommandType.Finish, CommandType.Start), commands.map { it.type })
        assertEquals(listOf(11L, 12L), commands.map { it.deviceSequence })
        assertEquals(TimerPhase.LongBreak, commands.last().phase)
    }

    @Test
    fun acknowledgedSyncReplacesProjectionAndClearsOnlySentCommand() = runBlocking {
        val profile = testUser()
        val command = testCommand("command-1", sequence = 1)
        database.timerDao().insertState(testState(user = profile, deviceSequence = 1, revision = 3))
        database.timerDao().insertCommand(PendingCommandEntity.from(command))
        val serverTimer = testTimer(id = "server-timer", status = TimerStatus.Paused, elapsedMs = 120_000)
        val service = TestRepositoryService(profile).apply {
            syncResponse = SyncResponse(
                acknowledgements = listOf(Acknowledgement(command.id, "applied", "")),
                revision = 4,
                canonicalTimer = serverTimer,
                history = listOf(testHistory("server-history")),
                serverTime = "2026-01-01T00:00:00Z",
                serverHlcWallMs = 1_767_225_600_100,
                serverHlcCounter = 0,
                durationAcknowledgements = emptyList(),
                durationsMs = DurationsMs(),
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
        awaitState { service.syncCalls == 1 && repository.state.value.pendingCount == 0 }

        val stored = database.timerDao().localState()
        assertEquals(1, service.syncRequests.single().commands.size)
        assertEquals(command.id, service.syncRequests.single().commands.single().id)
        assertEquals(4L, stored?.revision)
        assertEquals(serverTimer, repository.state.value.timer)
        assertEquals(SyncStatus.Synced, repository.state.value.syncStatus)
        assertTrue(database.timerDao().pendingCommands().isEmpty())
    }

    @Test
    fun restartRepairsSequenceFromQueueBeforeIssuingNextCommand() = runBlocking {
        val queued = testCommand("command-7", sequence = 7)
        database.timerDao().insertState(testState(deviceSequence = 2))
        database.timerDao().insertCommand(PendingCommandEntity.from(queued))
        val repository = testRepository(context, database.timerDao())

        repository.initialize()
        repository.cancelTimer()

        val commands = database.timerDao().pendingCommands().map(PendingCommandEntity::toModel)
        assertEquals(listOf(7L, 8L), commands.map { it.deviceSequence })
        assertEquals(CommandType.Cancel, commands.last().type)
        assertEquals(8L, database.timerDao().localState()?.deviceSequence)
    }

    @Test
    fun settingsPersistAndDriveNextTimer() = runBlocking {
        val repository = testRepository(context, database.timerDao())
        repository.initialize()

        repository.selectPhase(TimerPhase.ShortBreak)
        repository.changeDuration(TimerPhase.ShortBreak, 2)
        repository.setAutoStart(true)
        repository.toggleTimer()

        val state = repository.state.value
        val storedSettings = repositoryJson.decodeFromString<TimerSettings>(
            requireNotNull(database.timerDao().localState()).settingsJson,
        )
        assertEquals(TimerPhase.ShortBreak, state.timer?.phase)
        assertEquals(7 * 60_000L, state.timer?.plannedDurationMs)
        assertTrue(state.settings.autoStartBreaks)
        assertEquals(state.settings, storedSettings)
        assertFalse(database.timerDao().pendingCommands().isEmpty())
    }

    @Test
    fun durationEditsClampNoOpAndCompactOnePendingOperationPerPhase() = runBlocking {
        val existingWall = System.currentTimeMillis() + 60_000
        database.timerDao().insertState(
            testState().copy(hlcWallMs = existingWall, hlcCounter = 4),
        )
        val repository = testRepository(context, database.timerDao())
        repository.initialize()

        repository.changeDuration(TimerPhase.Focus, 1)
        repository.changeDuration(TimerPhase.Focus, 1)
        repository.changeDuration(TimerPhase.ShortBreak, -100)
        repository.changeDuration(TimerPhase.ShortBreak, -1)
        repository.changeDuration(TimerPhase.LongBreak, 1)
        repository.changeDuration("unknown", 1)
        repository.changeDuration(TimerPhase.LongBreak, 0)

        val operations = database.timerDao().pendingDurationOperations()
            .map(PendingDurationOperationEntity::toModel)
            .associateBy { it.phase }
        assertEquals(3, repository.state.value.pendingCount)
        assertEquals(3, operations.size)
        assertEquals(27 * 60_000L, operations.getValue(TimerPhase.Focus).durationMs)
        assertEquals(60_000L, operations.getValue(TimerPhase.ShortBreak).durationMs)
        assertEquals(16 * 60_000L, operations.getValue(TimerPhase.LongBreak).durationMs)
        assertEquals(27 * 60_000L, repository.state.value.settings.durationMsFor(TimerPhase.Focus))
        assertEquals(60_000L, repository.state.value.settings.durationMsFor(TimerPhase.ShortBreak))
        assertEquals(16 * 60_000L, repository.state.value.settings.durationMsFor(TimerPhase.LongBreak))
        assertTrue(operations.values.all { it.hlcWallMs == existingWall })
        assertTrue(operations.values.all { it.hlcWallMs > 0L && it.hlcCounter > 0L })
        assertEquals(8L, database.timerDao().localState()?.hlcCounter)
    }

    @Test
    fun durationSyncSendsAndAcknowledgesAllPhases() = runBlocking {
        val profile = testUser()
        val durations = DurationsMs(
            focus = 30 * 60_000L,
            shortBreak = 6 * 60_000L,
            longBreak = 20 * 60_000L,
        )
        val operations = TimerPhase.all.mapIndexed { index, phase ->
            testDurationOperation(
                id = "duration-$index",
                phase = phase,
                durationMs = durations.forPhase(phase),
                wallMs = 1_767_225_600_000 + index,
            )
        }
        database.timerDao().insertState(
            testState(user = profile, settings = TimerSettings().withDurations(durations)),
        )
        operations.forEach {
            database.timerDao().upsertDurationOperation(PendingDurationOperationEntity.from(it))
        }
        val service = TestRepositoryService(profile).apply {
            syncHandler = { request ->
                SyncResponse(
                    acknowledgements = emptyList(),
                    durationAcknowledgements = request.durationOperations.map {
                        DurationAcknowledgement(it.id, "applied", "")
                    },
                    revision = 1,
                    canonicalTimer = null,
                    history = emptyList(),
                    durationsMs = durations,
                    taskAcknowledgements = emptyList(),
                    tasks = emptyList(),
                    serverTime = "2026-01-01T00:00:00Z",
                    serverHlcWallMs = 1_767_225_600_100,
                    serverHlcCounter = 0,
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

        assertEquals(operations, service.syncRequests.single().durationOperations)
        assertTrue(database.timerDao().pendingDurationOperations().isEmpty())
        assertEquals(durations, repository.state.value.settings.effectiveDurationsMs())
    }

    @Test
    fun canonicalDurationPullKeepsLocalControlsAndActiveTimerPlan() = runBlocking {
        val profile = testUser()
        val activeTimer = testTimer(durationMs = 25 * 60_000L)
        val serverHlcWallMs = System.currentTimeMillis() + 60_000
        val localSettings = TimerSettings(
            selectedPhase = TimerPhase.LongBreak,
            autoStartBreaks = true,
        )
        val canonicalDurations = DurationsMs(
            focus = 45 * 60_000L,
            shortBreak = 8 * 60_000L,
            longBreak = 25 * 60_000L,
        )
        database.timerDao().insertState(
            testState(user = profile, timer = activeTimer, settings = localSettings).copy(
                hlcWallMs = serverHlcWallMs,
                hlcCounter = 3,
            ),
        )
        val service = TestRepositoryService(profile).apply {
            syncResponse = SyncResponse(
                acknowledgements = emptyList(),
                revision = 1,
                canonicalTimer = activeTimer,
                history = emptyList(),
                durationAcknowledgements = emptyList(),
                durationsMs = canonicalDurations,
                taskAcknowledgements = emptyList(),
                tasks = emptyList(),
                serverTime = "2026-01-01T00:00:00Z",
                serverHlcWallMs = serverHlcWallMs,
                serverHlcCounter = 7,
            )
        }
        val repository = testRepository(
            context,
            database.timerDao(),
            service,
            TestAuthSession(tokensAvailable = true),
        )

        repository.initialize()
        awaitState { service.syncCalls == 1 && repository.state.value.syncStatus == SyncStatus.Synced }

        assertEquals(canonicalDurations, repository.state.value.settings.effectiveDurationsMs())
        assertEquals(TimerPhase.LongBreak, repository.state.value.settings.selectedPhase)
        assertTrue(repository.state.value.settings.autoStartBreaks)
        assertEquals(25 * 60_000L, repository.state.value.timer?.plannedDurationMs)
        assertTrue(service.syncRequests.single().durationOperations.isEmpty())
        assertEquals(serverHlcWallMs, database.timerDao().localState()?.hlcWallMs)
        assertEquals(7L, database.timerDao().localState()?.hlcCounter)
    }

    @Test
    fun newerSamePhaseEditSurvivesAcknowledgementOfInFlightOperation() = runBlocking {
        val profile = testUser()
        val sent = testDurationOperation(
            id = "duration-sent",
            phase = TimerPhase.Focus,
            durationMs = 26 * 60_000L,
        )
        val initialDurations = DurationsMs(focus = sent.durationMs)
        database.timerDao().insertState(
            testState(user = profile, settings = TimerSettings().withDurations(initialDurations)),
        )
        database.timerDao().upsertDurationOperation(PendingDurationOperationEntity.from(sent))
        val firstSyncStarted = CompletableDeferred<Unit>()
        val releaseFirstSync = CompletableDeferred<Unit>()
        var calls = 0
        val service = TestRepositoryService(profile).apply {
            syncHandler = { request ->
                calls += 1
                if (calls == 1) {
                    firstSyncStarted.complete(Unit)
                    releaseFirstSync.await()
                    SyncResponse(
                        acknowledgements = emptyList(),
                        durationAcknowledgements = listOf(
                            DurationAcknowledgement(sent.id, "applied", ""),
                        ),
                        revision = 1,
                        canonicalTimer = null,
                        history = emptyList(),
                        durationsMs = DurationsMs(focus = 30 * 60_000L),
                        taskAcknowledgements = emptyList(),
                        tasks = emptyList(),
                        serverTime = "2026-01-01T00:00:00Z",
                        serverHlcWallMs = 1_767_225_600_100,
                        serverHlcCounter = 0,
                    )
                } else {
                    throw ApiException(409, "stop after race verification")
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
        firstSyncStarted.await()
        repository.changeDuration(TimerPhase.Focus, 1)
        val replacementId = database.timerDao().pendingDurationOperations().single().id
        releaseFirstSync.complete(Unit)
        awaitState { repository.state.value.conflict == "stop after race verification" }

        val remaining = database.timerDao().pendingDurationOperations().single().toModel()
        assertTrue(replacementId != sent.id)
        assertEquals(replacementId, remaining.id)
        assertEquals(27 * 60_000L, remaining.durationMs)
        assertEquals(27 * 60_000L, repository.state.value.settings.durationMsFor(TimerPhase.Focus))
        assertEquals(replacementId, service.syncRequests[1].durationOperations.single().id)
    }

    @Test
    fun offlineTaskSelectionIsDurableAndOnlyFocusStartCarriesTask() = runBlocking {
        val repository = testRepository(context, database.timerDao())
        repository.initialize()

        repository.addTask("Café")
        val task = repository.state.value.tasks.single()
        repository.toggleTimer()

        assertEquals("aaf83054-24b2-8c0e-901f-a974147bfe82", task.id)
        assertEquals(task.id, repository.state.value.selectedTaskId)
        assertEquals(task.id, database.timerDao().localState()?.selectedTaskId)
        assertEquals(task.id, repository.state.value.timer?.taskId)
        assertEquals(task.id, database.timerDao().pendingCommands().single().taskId)
        assertEquals(1, database.timerDao().pendingTaskOperations().size)

        repository.cancelTimer()
        repository.clearTimer()
        repository.selectPhase(TimerPhase.ShortBreak)
        repository.toggleTimer()

        assertNull(repository.state.value.timer?.taskId)
        assertNull(database.timerDao().pendingCommands().last().taskId)
    }

    @Test
    fun taskSyncAcknowledgesOperationAndAcceptsAuthoritativeTasks() = runBlocking {
        val profile = testUser()
        val task = requireNotNull(TaskReducer.taskFromTitle("Ship Android"))
        val operation = TaskOperation(
            id = "task-operation-1",
            taskId = task.id,
            type = TaskOperationType.Upsert,
            title = task.title,
            occurredAt = "2026-01-01T00:00:00Z",
            hlcWallMs = 1_767_225_600_001,
            hlcCounter = 0,
        )
        database.timerDao().insertState(
            testState(user = profile).copy(
                knownTasksJson = repositoryJson.encodeToString(listOf(task)),
            ),
        )
        database.timerDao().insertTaskOperation(PendingTaskOperationEntity.from(operation))
        val service = TestRepositoryService(profile).apply {
            syncResponse = SyncResponse(
                acknowledgements = emptyList(),
                taskAcknowledgements = listOf(
                    TaskAcknowledgement(operation.id, "applied", ""),
                ),
                revision = 1,
                canonicalTimer = null,
                history = emptyList(),
                tasks = listOf(task),
                serverTime = "2026-01-01T00:00:00Z",
                serverHlcWallMs = 1_767_225_600_100,
                serverHlcCounter = 0,
                durationAcknowledgements = emptyList(),
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
        awaitState { service.syncCalls == 1 && repository.state.value.pendingCount == 0 }

        assertEquals(listOf(operation), service.syncRequests.single().taskOperations)
        assertEquals(listOf(task), repository.state.value.tasks)
        assertTrue(database.timerDao().pendingTaskOperations().isEmpty())
        assertEquals(repositoryJson.encodeToString(listOf(task)), database.timerDao().localState()?.tasksJson)
    }

    @Test
    fun taskDeleteSyncSendsAcknowledgesAndAcceptsRemoteDeletion() = runBlocking {
        val profile = testUser()
        val task = requireNotNull(TaskReducer.taskFromTitle("Delete Android task"))
        val operation = TaskOperation(
            id = "task-operation-delete",
            taskId = task.id,
            type = TaskOperationType.Delete,
            title = null,
            occurredAt = "2026-01-01T00:00:00Z",
            hlcWallMs = 1_767_225_600_001,
            hlcCounter = 0,
        )
        database.timerDao().insertState(
            testState(user = profile).copy(
                tasksJson = repositoryJson.encodeToString(listOf(task)),
                knownTasksJson = repositoryJson.encodeToString(listOf(task)),
            ),
        )
        database.timerDao().insertTaskOperation(PendingTaskOperationEntity.from(operation))
        val service = TestRepositoryService(profile).apply {
            syncResponse = SyncResponse(
                acknowledgements = emptyList(),
                revision = 1,
                canonicalTimer = null,
                history = emptyList(),
                durationAcknowledgements = emptyList(),
                durationsMs = DurationsMs(),
                taskAcknowledgements = listOf(TaskAcknowledgement(operation.id, "applied", "")),
                tasks = emptyList(),
                serverTime = "2026-01-01T00:00:00Z",
                serverHlcWallMs = 1_767_225_600_100,
                serverHlcCounter = 0,
            )
        }
        val repository = testRepository(
            context,
            database.timerDao(),
            service,
            TestAuthSession(tokensAvailable = true),
        )

        repository.initialize()
        awaitState { service.syncCalls == 1 && repository.state.value.pendingCount == 0 }

        assertEquals(listOf(operation), service.syncRequests.single().taskOperations)
        assertTrue(database.timerDao().pendingTaskOperations().isEmpty())
        assertTrue(repository.state.value.tasks.isEmpty())
        assertEquals("[]", database.timerDao().localState()?.tasksJson)
    }

    @Test
    fun remoteBootstrapDeletesCanonicalTaskWithoutLocalOperation() = runBlocking {
        val profile = testUser()
        val task = requireNotNull(TaskReducer.taskFromTitle("Remote deletion"))
        database.timerDao().insertState(
            testState(user = profile).copy(
                tasksJson = repositoryJson.encodeToString(listOf(task)),
                knownTasksJson = repositoryJson.encodeToString(listOf(task)),
            ),
        )
        val service = TestRepositoryService(profile).apply {
            bootstrapResponse = syncResponse.copy(revision = 1, tasks = emptyList())
        }
        val repository = testRepository(
            context,
            database.timerDao(),
            service,
            TestAuthSession(tokensAvailable = true),
            online = false,
        )

        repository.initialize()

        assertTrue(repository.state.value.tasks.isEmpty())
        assertTrue(database.timerDao().pendingTaskOperations().isEmpty())
        assertEquals("[]", database.timerDao().localState()?.tasksJson)
        assertEquals(0, service.resolveCalls)
    }

    @Test
    fun taskOperationQueueSyncsInProtocolSizedBatches() = runBlocking {
        val profile = testUser()
        val operations = (1..257).map { index ->
            val task = requireNotNull(TaskReducer.taskFromTitle("Batched task $index"))
            TaskOperation(
                id = "task-operation-batch-$index",
                taskId = task.id,
                type = TaskOperationType.Upsert,
                title = task.title,
                occurredAt = "2026-01-01T00:00:00Z",
                hlcWallMs = 1_767_225_600_000 + index,
                hlcCounter = 0,
            )
        }
        database.timerDao().insertState(testState(user = profile))
        operations.forEach {
            database.timerDao().insertTaskOperation(PendingTaskOperationEntity.from(it))
        }
        val canonicalTasks = linkedMapOf<String, FocusTask>()
        var revision = 0L
        val service = TestRepositoryService(profile).apply {
            syncHandler = { request ->
                request.taskOperations.forEach { operation ->
                    val task = requireNotNull(operation.title?.let(TaskReducer::taskFromTitle))
                    canonicalTasks[task.id] = task
                }
                revision += 1
                SyncResponse(
                    acknowledgements = emptyList(),
                    revision = revision,
                    canonicalTimer = null,
                    history = emptyList(),
                    durationAcknowledgements = emptyList(),
                    durationsMs = DurationsMs(),
                    taskAcknowledgements = request.taskOperations.map {
                        TaskAcknowledgement(it.id, "applied", "")
                    },
                    tasks = canonicalTasks.values.toList(),
                    serverTime = "2026-01-01T00:00:00Z",
                    serverHlcWallMs = 1_767_225_600_000 + revision,
                    serverHlcCounter = 0,
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

        assertEquals(listOf(256, 1), service.syncRequests.map { it.taskOperations.size })
        assertTrue(database.timerDao().pendingTaskOperations().isEmpty())
        assertEquals(257, repository.state.value.tasks.size)
    }

    @Test
    fun commandQueueSyncsInProtocolSizedBatches() = runBlocking {
        val profile = testUser()
        val commands = (1L..257L).map { sequence ->
            testCommand(
                "command-$sequence",
                sequence,
                timerId = "timer-$sequence",
                type = CommandType.Cancel,
            )
        }
        database.timerDao().insertState(
            testState(user = profile, deviceSequence = commands.last().deviceSequence),
        )
        commands.forEach { database.timerDao().insertCommand(PendingCommandEntity.from(it)) }
        var revision = 0L
        val service = TestRepositoryService(profile).apply {
            syncHandler = { request ->
                revision += 1
                SyncResponse(
                    acknowledgements = request.commands.map {
                        Acknowledgement(it.id, "applied", "")
                    },
                    revision = revision,
                    canonicalTimer = null,
                    history = emptyList(),
                    durationAcknowledgements = emptyList(),
                    durationsMs = DurationsMs(),
                    taskAcknowledgements = emptyList(),
                    tasks = emptyList(),
                    serverTime = "2026-01-01T00:00:00Z",
                    serverHlcWallMs = 1_767_225_600_000 + revision,
                    serverHlcCounter = 0,
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

        assertEquals(listOf(256, 1), service.syncRequests.map { it.commands.size })
        assertTrue(database.timerDao().pendingCommands().isEmpty())
        assertEquals(2L, database.timerDao().localState()?.revision)
    }

    @Test
    fun taskOperationCreatedDuringSyncRebasesAndSurvivesOldAcknowledgement() = runBlocking {
        val profile = testUser()
        val task = requireNotNull(TaskReducer.taskFromTitle("Ship Android"))
        val sent = TaskOperation(
            id = "task-operation-sent",
            taskId = task.id,
            type = TaskOperationType.Upsert,
            title = task.title,
            occurredAt = "2026-01-01T00:00:00Z",
            hlcWallMs = 1_767_225_600_001,
            hlcCounter = 0,
        )
        database.timerDao().insertState(
            testState(user = profile).copy(
                knownTasksJson = repositoryJson.encodeToString(listOf(task)),
            ),
        )
        database.timerDao().insertTaskOperation(PendingTaskOperationEntity.from(sent))
        val firstSyncStarted = CompletableDeferred<Unit>()
        val releaseFirstSync = CompletableDeferred<Unit>()
        var calls = 0
        val service = TestRepositoryService(profile).apply {
            syncHandler = { request ->
                calls += 1
                if (calls == 1) {
                    firstSyncStarted.complete(Unit)
                    releaseFirstSync.await()
                    SyncResponse(
                        acknowledgements = emptyList(),
                        revision = 1,
                        canonicalTimer = null,
                        history = emptyList(),
                        durationAcknowledgements = emptyList(),
                        durationsMs = DurationsMs(),
                        taskAcknowledgements = listOf(
                            TaskAcknowledgement(sent.id, "applied", ""),
                        ),
                        tasks = listOf(task),
                        serverTime = "2026-01-01T00:00:00Z",
                        serverHlcWallMs = 1_767_225_600_100,
                        serverHlcCounter = 0,
                    )
                } else {
                    throw ApiException(409, "stop after task rebase verification")
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
        firstSyncStarted.await()
        repository.deleteTask(task.id)
        val replacement = database.timerDao().pendingTaskOperations().last().toModel()
        releaseFirstSync.complete(Unit)
        awaitState { repository.state.value.conflict == "stop after task rebase verification" }

        val remaining = database.timerDao().pendingTaskOperations().single().toModel()
        assertEquals(TaskOperationType.Delete, replacement.type)
        assertEquals(replacement.id, remaining.id)
        assertEquals(replacement.id, service.syncRequests[1].taskOperations.single().id)
        assertTrue(repository.state.value.tasks.isEmpty())
    }
}
