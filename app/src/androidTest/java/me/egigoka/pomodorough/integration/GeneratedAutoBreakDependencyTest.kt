package me.egigoka.pomodorough.integration

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import me.egigoka.pomodorough.data.Acknowledgement
import me.egigoka.pomodorough.data.BootstrapResolutionRequest
import me.egigoka.pomodorough.data.BootstrapStrategy
import me.egigoka.pomodorough.data.CanonicalTimer
import me.egigoka.pomodorough.data.CommandType
import me.egigoka.pomodorough.data.HistoryItem
import me.egigoka.pomodorough.data.SyncResponse
import me.egigoka.pomodorough.data.SyncStatus
import me.egigoka.pomodorough.data.TimerCommand
import me.egigoka.pomodorough.data.TimerPhase
import me.egigoka.pomodorough.data.TimerSettings
import me.egigoka.pomodorough.data.TimerStatus
import me.egigoka.pomodorough.data.local.PendingCommandEntity
import me.egigoka.pomodorough.data.local.PomodoroughDatabase
import me.egigoka.pomodorough.domain.TimerReducer
import me.egigoka.pomodorough.timer.TimerAlarmScheduler
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GeneratedAutoBreakDependencyTest {
    @get:Rule
    val migrationHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        PomodoroughDatabase::class.java,
    )

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
        context.deleteDatabase(LegacyResolutionDatabaseName)
    }

    @Test
    fun restartKeepsProvisionalBreakButSyncsFinishBeforeGeneratedStart() = runBlocking {
        val profile = testUser("restart-owner")
        val focus = queueOfflineGeneratedBreak(profile)
        val queued = database.timerDao().pendingCommands()
        val finish = queued.first { it.type == CommandType.Finish }
        val generated = queued.first { it.type == CommandType.Start }
        assertEquals(finish.id, generated.generatedByFinishCommandId)

        val finishSeen = CompletableDeferred<Unit>()
        val releaseFinish = CompletableDeferred<Unit>()
        var revision = 0L
        val service = TestRepositoryService(profile).apply {
            bootstrapResponse = response(timer = focus, autoStart = true)
            syncHandler = { request ->
                revision += 1
                if (revision == 1L) {
                    assertEquals(listOf(finish.id), request.commands.map { it.id })
                    finishSeen.complete(Unit)
                    releaseFinish.await()
                    acceptedFinishResponse(revision, focus, request.commands.single(), completedCount = 1)
                } else {
                    assertEquals(listOf(generated.id), request.commands.map { it.id })
                    acceptedCommandsResponse(
                        revision,
                        request.commands,
                        listOf(completedHistory(focus, finish.id)),
                    )
                }
            }
        }
        val restarted = testRepository(
            context,
            database.timerDao(),
            service,
            TestAuthSession(tokensAvailable = true),
        )

        restarted.initialize()
        finishSeen.await()

        assertEquals(TimerPhase.ShortBreak, restarted.state.value.timer?.phase)
        assertEquals(1, service.syncRequests.size)
        assertEquals(finish.id, database.timerDao().pendingCommands().first().id)

        releaseFinish.complete(Unit)
        awaitState { service.syncCalls == 2 && restarted.state.value.pendingCount == 0 }

        assertEquals(listOf(CommandType.Finish), service.syncRequests[0].commands.map { it.type })
        assertEquals(listOf(CommandType.Start), service.syncRequests[1].commands.map { it.type })
        assertEquals(generated.timerId, database.timerDao().localState()?.ownedTimerId)
    }

    @Test
    fun lostFinishResponseRetriesFinishWithoutLeakingGeneratedStart() = runBlocking {
        val profile = testUser("retry-owner")
        val focus = queueOfflineGeneratedBreak(profile)
        val queued = database.timerDao().pendingCommands()
        val finish = queued.first { it.type == CommandType.Finish }
        val generated = queued.first { it.generatedByFinishCommandId != null }
        var calls = 0
        var revision = 0L
        val service = TestRepositoryService(profile).apply {
            bootstrapResponse = response(timer = focus, autoStart = true)
            syncHandler = { request ->
                calls += 1
                if (calls == 1) throw IOException("finish response lost")
                revision += 1
                if (calls == 2) {
                    acceptedFinishResponse(revision, focus, request.commands.single(), completedCount = 1)
                } else {
                    acceptedCommandsResponse(
                        revision,
                        request.commands,
                        listOf(completedHistory(focus, finish.id)),
                    )
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
        awaitState { service.syncCalls == 3 && repository.state.value.pendingCount == 0 }

        assertEquals(listOf(finish.id), service.syncRequests[0].commands.map { it.id })
        assertEquals(listOf(finish.id), service.syncRequests[1].commands.map { it.id })
        assertEquals(listOf(generated.id), service.syncRequests[2].commands.map { it.id })
    }

    @Test
    fun ignoredFinishWithoutCanonicalCompletionDiscardsBreakAndRebasesPausedAlarm() = runBlocking {
        val profile = testUser("ignored-owner")
        val focus = queueOfflineGeneratedBreak(profile)
        val finish = database.timerDao().pendingCommands().first { it.type == CommandType.Finish }
        val paused = focus.copy(
            status = TimerStatus.Paused,
            elapsedAtAnchorMs = focus.plannedDurationMs / 2,
        )
        val service = TestRepositoryService(profile).apply {
            bootstrapResponse = response(timer = focus, autoStart = true)
            syncHandler = { request ->
                response(
                    revision = 1,
                    timer = paused,
                    acknowledgements = listOf(Acknowledgement(finish.id, "ignored", "finish lost")),
                    autoStart = true,
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
        awaitState { repository.state.value.pendingCount == 0 }

        assertEquals(1, service.syncCalls)
        assertEquals(paused, repository.state.value.timer)
        assertEquals(focus.id, database.timerDao().localState()?.ownedTimerId)
        assertTrue(database.timerDao().pendingCommands().isEmpty())
        assertFalse(repository.finishExpiredTimer())
    }

    @Test
    fun ignoredFinishWithCanonicalCompletionReleasesGeneratedStart() = runBlocking {
        val profile = testUser("canonical-owner")
        val focus = queueOfflineGeneratedBreak(profile)
        val queued = database.timerDao().pendingCommands()
        val finish = queued.first { it.type == CommandType.Finish }
        val generated = queued.first { it.generatedByFinishCommandId != null }
        var calls = 0
        val service = TestRepositoryService(profile).apply {
            bootstrapResponse = response(timer = focus, autoStart = true)
            syncHandler = { request ->
                calls += 1
                if (calls == 1) {
                    acceptedFinishResponse(
                        revision = 1,
                        focus = focus,
                        finish = finish.toModel(),
                        completedCount = 1,
                        outcome = "ignored",
                    )
                } else {
                    acceptedCommandsResponse(
                        2,
                        request.commands,
                        listOf(completedHistory(focus, finish.id)),
                    )
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
        awaitState { service.syncCalls == 2 && repository.state.value.pendingCount == 0 }

        assertEquals(listOf(finish.id), service.syncRequests[0].commands.map { it.id })
        assertEquals(listOf(generated.id), service.syncRequests[1].commands.map { it.id })
    }

    @Test
    fun canonicalFourthFocusReconcilesStaleProvisionalShortBreakToLong() = runBlocking {
        val profile = testUser("stale-history-owner")
        val localHistory = listOf(testHistory("local-1"), testHistory("local-2"))
        val focus = queueOfflineGeneratedBreak(profile, localHistory)
        val queued = database.timerDao().pendingCommands()
        val finish = queued.first { it.type == CommandType.Finish }
        assertEquals(TimerPhase.ShortBreak, queued.first { it.generatedByFinishCommandId != null }.phase)
        val globalHistory = listOf(
            completedHistory(focus, finish.id),
            testHistory("global-3"),
            testHistory("global-2"),
            testHistory("global-1"),
        )
        var calls = 0
        val service = TestRepositoryService(profile).apply {
            bootstrapResponse = response(timer = focus, history = localHistory, autoStart = true)
            syncHandler = { request ->
                calls += 1
                if (calls == 1) {
                    response(
                        revision = 1,
                        timer = completedTimer(focus),
                        history = globalHistory,
                        acknowledgements = listOf(Acknowledgement(finish.id, "applied", "")),
                        autoStart = true,
                    )
                } else {
                    assertEquals(TimerPhase.LongBreak, request.commands.single().phase)
                    acceptedCommandsResponse(2, request.commands, globalHistory)
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
        awaitState { service.syncCalls == 2 && repository.state.value.pendingCount == 0 }

        assertEquals(TimerPhase.LongBreak, service.syncRequests[1].commands.single().phase)
        assertEquals(TimerPhase.LongBreak, repository.state.value.timer?.phase)
    }

    @Test
    fun onlyPersistentOriginDeviceMayAutomaticallyFinishSharedTimer() = runBlocking {
        val profile = testUser("shared-origin-owner")
        val focus = expiredFocus()
        database.timerDao().insertState(
            testState(
                user = profile,
                timer = focus,
                settings = TimerSettings(autoStartBreaks = true),
            ),
        )
        val observerDatabase = Room.inMemoryDatabaseBuilder(
            context,
            PomodoroughDatabase::class.java,
        ).build()
        try {
            observerDatabase.timerDao().insertState(
                testState(
                    user = profile,
                    timer = focus,
                    settings = TimerSettings(autoStartBreaks = true),
                ).copy(deviceId = "observer-device", ownedTimerId = null),
            )
            val origin = testRepository(context, database.timerDao())
            val observer = testRepository(context, observerDatabase.timerDao())
            origin.initialize()
            observer.initialize()

            assertTrue(origin.finishExpiredTimer())
            assertFalse(observer.finishExpiredTimer())

            assertEquals(2, database.timerDao().pendingCommands().size)
            assertTrue(observerDatabase.timerDao().pendingCommands().isEmpty())
            assertEquals(TimerPhase.ShortBreak, origin.state.value.timer?.phase)
            assertEquals(TimerPhase.Focus, observer.state.value.timer?.phase)
        } finally {
            observerDatabase.close()
        }
    }

    @Test
    fun manualStartBypassesGeneratedChainAndSupersedesItCleanly() = runBlocking {
        val profile = testUser("manual-owner")
        val focus = queueOfflineGeneratedBreak(profile)
        val repository = testRepository(context, database.timerDao())
        repository.initialize()
        repository.finishTimer()
        repository.toggleTimer()
        val beforeSync = database.timerDao().pendingCommands()
        val sourceFinish = beforeSync.first { it.timerId == focus.id && it.type == CommandType.Finish }
        val generatedStart = beforeSync.first { it.generatedByFinishCommandId == sourceFinish.id }
        val manualStart = beforeSync.last { it.type == CommandType.Start }
        assertNull(manualStart.generatedByFinishCommandId)

        val profileService = TestRepositoryService(profile).apply {
            bootstrapResponse = response(timer = focus, autoStart = true)
            syncHandler = { request ->
                val projected = TimerReducer.replay(focus, emptyList(), request.commands)
                response(
                    revision = 1,
                    timer = projected.timer,
                    history = projected.history.map { it.copy(pending = false) },
                    acknowledgements = request.commands.map {
                        Acknowledgement(it.id, "applied", "")
                    },
                    autoStart = true,
                )
            }
        }
        val signedIn = testRepository(
            context,
            database.timerDao(),
            profileService,
            TestAuthSession(tokensAvailable = true),
        )

        signedIn.initialize()
        awaitState { profileService.syncCalls == 1 && signedIn.state.value.pendingCount == 0 }

        assertEquals(
            listOf(sourceFinish.id, manualStart.id),
            profileService.syncRequests.single().commands.map { it.id },
        )
        assertTrue(database.timerDao().pendingCommands().none { it.id == generatedStart.id })
        assertEquals(manualStart.timerId, signedIn.state.value.timer?.id)
        assertEquals(manualStart.timerId, database.timerDao().localState()?.ownedTimerId)
    }

    @Test
    fun bootstrapResolutionAlsoWithholdsGeneratedStartUntilFinishAcceptance() = runBlocking {
        val profile = testUser("bootstrap-dependency-owner")
        val focus = queueOfflineGeneratedBreak(profile)
        val detached = requireNotNull(database.timerDao().localState()).copy(
            userJson = null,
            ownerUserId = null,
        )
        database.timerDao().updateState(detached)
        val queued = database.timerDao().pendingCommands()
        val finish = queued.first { it.type == CommandType.Finish }
        val generated = queued.first { it.generatedByFinishCommandId == finish.id }
        var syncRevision = 1L
        val service = TestRepositoryService(profile).apply {
            bootstrapResponse = response(timer = focus, autoStart = true)
            resolveHandler = { request ->
                assertEquals(listOf(finish.id), request.commands.map { it.id })
                acceptedFinishResponse(1, focus, request.commands.single(), completedCount = 1)
            }
            syncHandler = { request ->
                syncRevision += 1
                if (request.commands.isEmpty()) {
                    response(
                        revision = syncRevision,
                        timer = completedTimer(focus),
                        history = listOf(completedHistory(focus, finish.id)),
                        autoStart = true,
                    )
                } else {
                    assertEquals(listOf(generated.id), request.commands.map { it.id })
                    acceptedCommandsResponse(
                        syncRevision,
                        request.commands,
                        listOf(completedHistory(focus, finish.id)),
                    )
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
        awaitState {
            service.resolveCalls == 1 &&
                service.syncRequests.any { it.commands.isNotEmpty() } &&
                repository.state.value.pendingCount == 0
        }

        assertEquals(listOf(finish.id), service.resolutionRequests.single().commands.map { it.id })
        assertEquals(
            listOf(generated.id),
            service.syncRequests.single { it.commands.isNotEmpty() }.commands.map { it.id },
        )
    }

    @Test
    fun migratedV5MergeResolutionRetriesExactGeneratedChainAfterLostResponse() = runBlocking {
        assertMigratedV5ResolutionRetriesExact(BootstrapStrategy.Merge)
    }

    @Test
    fun migratedV5ReplaceResolutionRetriesExactGeneratedChainAfterLostResponse() = runBlocking {
        assertMigratedV5ResolutionRetriesExact(BootstrapStrategy.ReplaceRemote)
    }

    private suspend fun assertMigratedV5ResolutionRetriesExact(strategy: BootstrapStrategy) {
        database.close()
        context.deleteDatabase(LegacyResolutionDatabaseName)
        val profile = testUser("legacy-resolution-owner")
        val finish = TimerCommand(
            id = "legacy-finish",
            deviceSequence = 1,
            timerId = "legacy-focus",
            type = CommandType.Finish,
            phase = TimerPhase.Focus,
            plannedDurationMs = 1_500_000,
            occurredAt = "2026-01-01T00:25:00.100Z",
            hlcWallMs = 1_767_225_600_100,
            hlcCounter = 4,
            observedElapsedMs = 1_500_000,
        )
        val generated = TimerCommand(
            id = "legacy-generated-break",
            deviceSequence = 2,
            timerId = "legacy-break",
            type = CommandType.Start,
            phase = TimerPhase.ShortBreak,
            plannedDurationMs = 300_000,
            occurredAt = "2026-01-01T00:25:00.137Z",
            hlcWallMs = 1_767_225_600_137,
            hlcCounter = 0,
            observedElapsedMs = 0,
        )
        val commands = listOf(finish, generated)
        val commandsJson = repositoryJson.encodeToString(commands)
        val userJson = repositoryJson.encodeToString(profile)
        val requestId = "legacy-${strategy.name}"
        val expectedRequest = BootstrapResolutionRequest(
            requestId = requestId,
            deviceId = "legacy-device",
            expectedRevision = 5,
            strategy = strategy,
            commands = commands,
            taskOperations = emptyList(),
            durationOperations = emptyList(),
            autoStartOperations = null,
        )

        migrationHelper.createDatabase(LegacyResolutionDatabaseName, 5).apply {
            execSQL(
                """INSERT INTO local_state (
                    id, deviceId, deviceSequence, hlcWallMs, hlcCounter, revision,
                    canonicalTimerJson, historyJson, settingsJson, userJson, ownerUserId,
                    tasksJson, knownTasksJson, selectedTaskId
                ) VALUES (0, 'legacy-device', 2, 1767225600137, 0, 5, NULL, '[]', ?, ?, ?,
                    '[]', '[]', NULL)""",
                arrayOf(
                    repositoryJson.encodeToString(TimerSettings(autoStartBreaks = true)),
                    userJson,
                    profile.id,
                ),
            )
            commands.forEach { command ->
                execSQL(
                    """INSERT INTO pending_commands (
                        id, deviceSequence, timerId, type, phase, plannedDurationMs,
                        occurredAt, hlcWallMs, hlcCounter, observedElapsedMs, taskId
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL)""",
                    arrayOf<Any>(
                        command.id,
                        command.deviceSequence,
                        command.timerId,
                        command.type,
                        command.phase,
                        command.plannedDurationMs,
                        command.occurredAt,
                        command.hlcWallMs,
                        command.hlcCounter,
                        command.observedElapsedMs,
                    ),
                )
            }
            execSQL(
                """INSERT INTO pending_bootstrap_resolution (
                    id, requestId, deviceId, expectedRevision, strategy, commandsJson,
                    taskOperationsJson, durationOperationsJson, ownerUserId, userJson
                ) VALUES (0, ?, 'legacy-device', 5, ?, ?, '[]', '[]', ?, ?)""",
                arrayOf(requestId, strategy.name, commandsJson, profile.id, userJson),
            )
            close()
        }
        migrationHelper.runMigrationsAndValidate(
            LegacyResolutionDatabaseName,
            7,
            true,
            PomodoroughDatabase.Migration5To6,
            PomodoroughDatabase.Migration6To7,
        ).use { migrated ->
            migrated.query("SELECT commandsJson FROM pending_bootstrap_resolution").use {
                assertTrue(it.moveToFirst())
                assertEquals(commandsJson, it.getString(0))
            }
            migrated.query(
                """SELECT generatedByFinishCommandId FROM pending_commands
                    WHERE id = 'legacy-generated-break'""",
            ).use {
                assertTrue(it.moveToFirst())
                assertNull(it.getString(0))
            }
        }
        database = Room.databaseBuilder(
            context,
            PomodoroughDatabase::class.java,
            LegacyResolutionDatabaseName,
        ).addMigrations(
            PomodoroughDatabase.Migration5To6,
            PomodoroughDatabase.Migration6To7,
        ).build()
        val service = TestRepositoryService(profile).apply {
            bootstrapResponse = response(revision = 5)
            resolveFailure = IOException("lost resolution response")
        }
        val first = testRepository(
            context,
            database.timerDao(),
            service,
            TestAuthSession(tokensAvailable = true),
        )

        first.initialize()
        first.resolveHistory(strategy)

        assertEquals(expectedRequest, service.resolutionRequests.single())
        assertEquals(commandsJson, database.timerDao().pendingBootstrapResolution()?.commandsJson)
        assertNull(database.timerDao().pendingCommands().last().generatedByFinishCommandId)

        service.resolveFailure = null
        service.resolveHandler = { request ->
            response(
                revision = 6,
                acknowledgements = request.commands.map {
                    Acknowledgement(it.id, "applied", "")
                },
            )
        }
        val restarted = testRepository(
            context,
            database.timerDao(),
            service,
            TestAuthSession(tokensAvailable = true),
        )

        restarted.initialize()
        restarted.resolveHistory(strategy)

        assertEquals(listOf(expectedRequest, expectedRequest), service.resolutionRequests)
        assertNull(database.timerDao().pendingBootstrapResolution())
        assertTrue(database.timerDao().pendingCommands().isEmpty())
    }

    private suspend fun queueOfflineGeneratedBreak(
        profile: me.egigoka.pomodorough.data.User,
        history: List<HistoryItem> = emptyList(),
    ): CanonicalTimer {
        val focus = expiredFocus()
        database.timerDao().insertState(
            testState(
                user = profile,
                timer = focus,
                history = history,
                settings = TimerSettings(autoStartBreaks = true),
            ),
        )
        val repository = testRepository(context, database.timerDao())
        repository.initialize()
        assertTrue(repository.finishExpiredTimer())
        assertEquals(TimerPhase.ShortBreak, repository.state.value.timer?.phase)
        return focus
    }

    private fun expiredFocus() = testTimer(
        id = "focus-source",
        elapsedMs = 1_500_000,
        anchorAt = "2000-01-01T00:00:00Z",
    )

    private fun completedHistory(focus: CanonicalTimer, commandId: String) = HistoryItem(
        id = "${focus.id}:$commandId",
        timerId = focus.id,
        commandId = commandId,
        phase = TimerPhase.Focus,
        status = TimerStatus.Completed,
        plannedDurationMs = focus.plannedDurationMs,
        completedAt = "2026-01-01T00:25:00Z",
        endedAt = "2026-01-01T00:25:00Z",
    )

    private fun completedTimer(focus: CanonicalTimer) = focus.copy(
        status = TimerStatus.Completed,
        elapsedAtAnchorMs = focus.plannedDurationMs,
        anchorAt = "2026-01-01T00:25:00Z",
    )

    private fun acceptedFinishResponse(
        revision: Long,
        focus: CanonicalTimer,
        finish: TimerCommand,
        completedCount: Int,
        outcome: String = "applied",
    ): SyncResponse {
        val source = completedHistory(focus, finish.id)
        val preceding = (1 until completedCount).map { testHistory("global-$it") }
        return response(
            revision = revision,
            timer = completedTimer(focus),
            history = listOf(source) + preceding,
            acknowledgements = listOf(Acknowledgement(finish.id, outcome, "")),
            autoStart = true,
        )
    }

    private fun acceptedCommandsResponse(
        revision: Long,
        commands: List<TimerCommand>,
        history: List<HistoryItem>,
    ): SyncResponse {
        val projected = TimerReducer.replay(null, history, commands)
        return response(
            revision = revision,
            timer = projected.timer,
            history = projected.history.map { it.copy(pending = false) },
            acknowledgements = commands.map { Acknowledgement(it.id, "applied", "") },
            autoStart = true,
        )
    }

    private fun response(
        revision: Long = 0,
        timer: CanonicalTimer? = null,
        history: List<HistoryItem> = emptyList(),
        acknowledgements: List<Acknowledgement> = emptyList(),
        autoStart: Boolean = false,
    ) = SyncResponse(
        acknowledgements = acknowledgements,
        revision = revision,
        canonicalTimer = timer,
        history = history,
        serverTime = "2026-01-01T00:00:00Z",
        serverHlcWallMs = 1_767_225_600_000 + revision,
        serverHlcCounter = 0,
        durationAcknowledgements = emptyList(),
        durationsMs = me.egigoka.pomodorough.data.DurationsMs(),
        taskAcknowledgements = emptyList(),
        tasks = emptyList(),
        autoStartAcknowledgements = emptyList(),
        autoStartBreaks = autoStart,
    )

    private companion object {
        const val LegacyResolutionDatabaseName = "legacy-resolution-migration-test"
    }
}
