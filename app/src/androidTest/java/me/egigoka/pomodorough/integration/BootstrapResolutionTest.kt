package me.egigoka.pomodorough.integration

import android.app.Activity
import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import me.egigoka.pomodorough.data.Acknowledgement
import me.egigoka.pomodorough.data.AuthStatus
import me.egigoka.pomodorough.data.BootstrapResolutionRequest
import me.egigoka.pomodorough.data.BootstrapStrategy
import me.egigoka.pomodorough.data.CommandType
import me.egigoka.pomodorough.data.DurationAcknowledgement
import me.egigoka.pomodorough.data.DurationsMs
import me.egigoka.pomodorough.data.FocusTask
import me.egigoka.pomodorough.data.ResolutionRecovery
import me.egigoka.pomodorough.data.SyncResponse
import me.egigoka.pomodorough.data.TaskAcknowledgement
import me.egigoka.pomodorough.data.TaskOperation
import me.egigoka.pomodorough.data.TaskOperationType
import me.egigoka.pomodorough.data.TimerPhase
import me.egigoka.pomodorough.data.TimerStatus
import me.egigoka.pomodorough.data.TokenPair
import me.egigoka.pomodorough.data.auth.AuthenticationRequired
import me.egigoka.pomodorough.data.api.ApiException
import me.egigoka.pomodorough.data.local.PendingBootstrapResolutionEntity
import me.egigoka.pomodorough.data.local.PendingCommandEntity
import me.egigoka.pomodorough.data.local.PendingDurationOperationEntity
import me.egigoka.pomodorough.data.local.PendingTaskOperationEntity
import me.egigoka.pomodorough.data.local.PomodoroughDatabase
import me.egigoka.pomodorough.domain.TaskReducer
import me.egigoka.pomodorough.timer.TimerAlarmScheduler
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BootstrapResolutionTest {
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
    fun bothHistoriesRequireChoiceAndBlockMutationsAndSync() = runBlocking {
        val localHistory = testHistory("local-history")
        val remoteHistory = testHistory("remote-history")
        database.timerDao().insertState(testState(history = listOf(localHistory)))
        val service = TestRepositoryService().apply {
            bootstrapResponse = response(revision = 4, history = listOf(remoteHistory))
        }
        val repository = testRepository(
            context,
            database.timerDao(),
            service,
            TestAuthSession(tokensAvailable = true),
        )

        repository.initialize()

        val unresolved = repository.state.value.historyResolution
        assertEquals(AuthStatus.SignedIn, repository.state.value.authStatus)
        assertNotNull(unresolved)
        assertEquals(1, unresolved?.localHistoryCount)
        assertEquals(1, unresolved?.remoteHistoryCount)
        assertEquals(listOf("me", "bootstrap"), service.callOrder)
        assertEquals(0, service.syncCalls)
        assertNull(database.timerDao().localState()?.ownerUserId)

        repository.toggleTimer()
        repository.addTask("Blocked task")
        repository.changeDuration(TimerPhase.Focus, 1)

        assertEquals(0, repository.state.value.pendingCount)
        assertEquals(listOf(localHistory), repository.state.value.history)
        assertTrue(database.timerDao().pendingCommands().isEmpty())
        assertTrue(database.timerDao().pendingTaskOperations().isEmpty())
        assertTrue(database.timerDao().pendingDurationOperations().isEmpty())
    }

    @Test
    fun bothHistoriesKeepLocalReplacesRemoteAtomically() = runBlocking {
        assertBothHistoryChoice(BootstrapStrategy.ReplaceRemote)
    }

    @Test
    fun bothHistoriesKeepRemoteReplacesLocalAtomically() = runBlocking {
        assertBothHistoryChoice(BootstrapStrategy.KeepRemote)
    }

    @Test
    fun bothHistoriesKeepBothMergesCanonicalHistoryAtomically() = runBlocking {
        assertBothHistoryChoice(BootstrapStrategy.Merge)
    }

    @Test
    fun localOnlyAutomaticallyReplacesRemoteWithoutLosingUnownedQueue() = runBlocking {
        val commands = completedLocalCommands()
        database.timerDao().insertState(testState(deviceSequence = 2))
        commands.forEach { database.timerDao().insertCommand(PendingCommandEntity.from(it)) }
        val canonicalHistory = testHistory("resolved-local")
        val service = TestRepositoryService().apply {
            bootstrapResponse = response(revision = 2)
            syncResponse = response(revision = 3, history = listOf(canonicalHistory))
            resolveHandler = { request ->
                syncResponse.copy(
                    acknowledgements = request.commands.map { Acknowledgement(it.id, "applied", "") },
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

        val request = service.resolutionRequests.single()
        assertEquals(BootstrapStrategy.ReplaceRemote, request.strategy)
        assertEquals(commands, request.commands)
        assertEquals(listOf("me", "bootstrap", "resolve"), service.callOrder.take(3))
        assertEquals("user-1", database.timerDao().localState()?.ownerUserId)
        assertEquals(listOf(canonicalHistory), repository.state.value.history)
        assertTrue(database.timerDao().pendingCommands().isEmpty())
        assertNull(database.timerDao().pendingBootstrapResolution())
        assertNull(repository.state.value.historyResolution)
    }

    @Test
    fun remoteOnlyAutomaticallyKeepsRemoteWithAllArraysPresentAndEmpty() = runBlocking {
        val remoteHistory = testHistory("remote-history")
        database.timerDao().insertState(testState())
        val service = TestRepositoryService().apply {
            bootstrapResponse = response(revision = 7, history = listOf(remoteHistory))
            syncResponse = response(revision = 7, history = listOf(remoteHistory))
            resolveHandler = { syncResponse }
        }
        val repository = testRepository(
            context,
            database.timerDao(),
            service,
            TestAuthSession(tokensAvailable = true),
        )

        repository.initialize()

        val request = service.resolutionRequests.single()
        assertEquals(BootstrapStrategy.KeepRemote, request.strategy)
        assertTrue(request.commands.isEmpty())
        assertTrue(request.taskOperations.isEmpty())
        assertTrue(request.durationOperations.isEmpty())
        assertEquals(listOf(remoteHistory), repository.state.value.history)
    }

    @Test
    fun nonVisibleRemoteHistoryDoesNotBroadenChooserCriterion() = runBlocking {
        val commands = completedLocalCommands()
        val cancelledRemote = testHistory("remote-cancelled").copy(
            status = TimerStatus.Cancelled,
            completedAt = null,
        )
        database.timerDao().insertState(testState(deviceSequence = 2))
        commands.forEach { database.timerDao().insertCommand(PendingCommandEntity.from(it)) }
        val service = TestRepositoryService().apply {
            bootstrapResponse = response(revision = 1, history = listOf(cancelledRemote))
            syncResponse = response(revision = 2, history = listOf(testHistory("local-visible")))
            resolveHandler = { request -> acknowledgedResolution(request, syncResponse) }
        }
        val repository = testRepository(
            context,
            database.timerDao(),
            service,
            TestAuthSession(tokensAvailable = true),
        )

        repository.initialize()

        assertEquals(BootstrapStrategy.ReplaceRemote, service.resolutionRequests.single().strategy)
        assertNull(repository.state.value.historyResolution)
    }

    @Test
    fun remoteHistoryAndLocalNonHistoryOperationsAutomaticallyMerge() = runBlocking {
        val now = System.currentTimeMillis()
        val command = testCommand("command-local", sequence = 1).copy(
            occurredAt = Instant.ofEpochMilli(now).toString(),
            hlcWallMs = now,
        )
        val task = requireNotNull(TaskReducer.taskFromTitle("Local task"))
        val taskOperation = taskOperation(task)
        val durationOperation = testDurationOperation(
            id = "duration-local",
            phase = TimerPhase.Focus,
            durationMs = 30 * 60_000L,
        )
        val remoteHistory = testHistory("remote-history")
        database.timerDao().insertState(testState(deviceSequence = 1))
        database.timerDao().insertCommand(PendingCommandEntity.from(command))
        database.timerDao().insertTaskOperation(PendingTaskOperationEntity.from(taskOperation))
        database.timerDao().upsertDurationOperation(PendingDurationOperationEntity.from(durationOperation))
        val service = TestRepositoryService().apply {
            bootstrapResponse = response(revision = 7, history = listOf(remoteHistory))
            syncResponse = response(
                revision = 8,
                timer = testTimer(),
                history = listOf(remoteHistory),
                tasks = listOf(task),
                durations = DurationsMs(focus = durationOperation.durationMs),
            )
            resolveHandler = { request -> acknowledgedResolution(request, syncResponse) }
        }
        val repository = testRepository(
            context,
            database.timerDao(),
            service,
            TestAuthSession(tokensAvailable = true),
        )

        repository.initialize()

        val request = service.resolutionRequests.single()
        assertEquals(BootstrapStrategy.Merge, request.strategy)
        assertEquals(listOf(command), request.commands)
        assertEquals(listOf(taskOperation), request.taskOperations)
        assertEquals(listOf(durationOperation), request.durationOperations)
        assertEquals(listOf(remoteHistory), repository.state.value.history)
        assertEquals(listOf(task), repository.state.value.tasks)
        assertEquals(durationOperation.durationMs, repository.state.value.settings.durationMsFor(TimerPhase.Focus))
    }

    @Test
    fun migratedMatchingUserBackfillsOwnerAndRetainsQueuedData() = runBlocking {
        val profile = testUser("migrated-user")
        val command = testCommand("command-retained", sequence = 1)
        database.timerDao().insertState(testState(user = profile, deviceSequence = 1).copy(ownerUserId = null))
        database.timerDao().insertCommand(PendingCommandEntity.from(command))
        val service = TestRepositoryService(profile).apply {
            bootstrapResponse = response(revision = 0)
        }
        val repository = testRepository(
            context,
            database.timerDao(),
            service,
            TestAuthSession(tokensAvailable = true),
            online = false,
        )

        repository.initialize()

        assertEquals(profile.id, database.timerDao().localState()?.ownerUserId)
        assertEquals(listOf(command.id), database.timerDao().pendingCommands().map { it.id })
        assertEquals(0, service.resolveCalls)
        assertNull(repository.state.value.historyResolution)
    }

    @Test
    fun migratedDifferentUserRequiresConfirmationBeforeClearingOldData() = runBlocking {
        val oldUser = testUser("old-user")
        val newUser = testUser("new-user")
        val oldHistory = testHistory("old-history")
        val remoteHistory = testHistory("new-history")
        val command = testCommand("old-command", sequence = 1)
        database.timerDao().insertState(
            testState(user = oldUser, history = listOf(oldHistory), deviceSequence = 1).copy(ownerUserId = null),
        )
        database.timerDao().insertCommand(PendingCommandEntity.from(command))
        val service = TestRepositoryService(newUser).apply {
            bootstrapResponse = response(revision = 2, history = listOf(remoteHistory))
        }
        val repository = testRepository(
            context,
            database.timerDao(),
            service,
            TestAuthSession(tokensAvailable = true),
            online = false,
        )

        repository.initialize()

        assertNotNull(repository.state.value.accountSwitch)
        assertEquals(oldUser.id, database.timerDao().localState()?.ownerUserId)
        assertEquals(listOf(command.id), database.timerDao().pendingCommands().map { it.id })
        assertEquals(0, service.resolveCalls)
        assertEquals(0, service.syncCalls)

        repository.confirmAccountSwitch()

        assertEquals(newUser.id, database.timerDao().localState()?.ownerUserId)
        assertEquals(listOf(remoteHistory), repository.state.value.history)
        assertTrue(database.timerDao().pendingCommands().isEmpty())
        assertEquals(0, service.resolveCalls)
        assertTrue(service.resolutionRequests.isEmpty())
        assertEquals(0, service.syncCalls)
    }

    @Test
    fun profileFailureAllowsOfflineWorkButDifferentAccountNeedsExplicitChoice() = runBlocking {
        val oldUser = testUser("offline-owner")
        val newUser = testUser("different-user")
        database.timerDao().insertState(testState(user = oldUser))
        val service = TestRepositoryService(oldUser).apply {
            meFailure = IOException("profile unavailable")
            bootstrapResponse = response(revision = 0)
        }
        val auth = TestAuthSession(tokensAvailable = true).apply {
            signInHandler = { _, _ ->
                tokensAvailable = true
                testTokens()
            }
        }
        val repository = testRepository(
            context,
            database.timerDao(),
            service,
            auth,
            online = false,
        )

        repository.initialize()
        repository.toggleTimer()

        assertEquals(AuthStatus.SignedOut, repository.state.value.authStatus)
        assertEquals(1, database.timerDao().pendingCommands().size)

        service.meFailure = null
        service.profile = newUser
        repository.signIn(testActivity())

        assertNotNull(repository.state.value.accountSwitch)
        assertEquals(oldUser.id, database.timerDao().localState()?.ownerUserId)
        assertEquals(1, database.timerDao().pendingCommands().size)
        repository.toggleTimer()
        assertEquals(1, database.timerDao().pendingCommands().size)

        repository.cancelAccountSwitch()

        assertEquals(AuthStatus.SignedOut, repository.state.value.authStatus)
        assertNull(repository.state.value.accountSwitch)
        assertEquals(1, database.timerDao().pendingCommands().size)

        service.profile = oldUser
        repository.signIn(testActivity())

        assertEquals(AuthStatus.SignedIn, repository.state.value.authStatus)
        assertNull(repository.state.value.accountSwitch)
        assertEquals(oldUser.id, database.timerDao().localState()?.ownerUserId)
        assertEquals(1, database.timerDao().pendingCommands().size)
    }

    @Test
    fun malformedMigratedOwnerQuarantinesWithoutBootstrapOrDataLoss() = runBlocking {
        val rawUser = """{
            "id":"legacy-user",
            "email":"legacy@example.com",
            "name":"Legacy",
            "avatarUrl":"",
            "unexpected":true
        }""".trimIndent()
        val command = testCommand("legacy-command", sequence = 1)
        val task = requireNotNull(TaskReducer.taskFromTitle("Legacy task"))
        val taskOperation = taskOperation(task)
        val durationOperation = testDurationOperation(
            id = "legacy-duration",
            phase = TimerPhase.Focus,
            durationMs = 30 * 60_000L,
        )
        database.timerDao().insertState(
            testState(history = listOf(testHistory("legacy-history")), deviceSequence = 1).copy(
                userJson = rawUser,
                ownerUserId = null,
            ),
        )
        database.timerDao().insertCommand(PendingCommandEntity.from(command))
        database.timerDao().insertTaskOperation(PendingTaskOperationEntity.from(taskOperation))
        database.timerDao().upsertDurationOperation(PendingDurationOperationEntity.from(durationOperation))
        val service = TestRepositoryService()
        val repository = testRepository(
            context,
            database.timerDao(),
            service,
            TestAuthSession(tokensAvailable = true),
        )

        repository.initialize()

        assertEquals(AuthStatus.SignedOut, repository.state.value.authStatus)
        assertTrue(repository.state.value.historyResolution?.corrupted == true)
        assertTrue(service.callOrder.isEmpty())
        assertEquals(rawUser, database.timerDao().localState()?.userJson)
        assertNull(database.timerDao().localState()?.ownerUserId)
        assertEquals(listOf(command.id), database.timerDao().pendingCommands().map { it.id })
        assertEquals(listOf(taskOperation.id), database.timerDao().pendingTaskOperations().map { it.id })
        assertEquals(listOf(durationOperation.id), database.timerDao().pendingDurationOperations().map { it.id })
    }

    @Test
    fun automaticResolutionPublishesDurableGateBeforeNetworkCall() = runBlocking {
        val commands = completedLocalCommands()
        database.timerDao().insertState(testState(deviceSequence = 2))
        commands.forEach { database.timerDao().insertCommand(PendingCommandEntity.from(it)) }
        val resolveStarted = CompletableDeferred<Unit>()
        val releaseResolve = CompletableDeferred<Unit>()
        val service = TestRepositoryService().apply {
            bootstrapResponse = response(revision = 2)
            syncResponse = response(revision = 3, history = listOf(testHistory("resolved")))
            resolveHandler = { request ->
                resolveStarted.complete(Unit)
                releaseResolve.await()
                acknowledgedResolution(request, syncResponse)
            }
        }
        val repository = testRepository(
            context,
            database.timerDao(),
            service,
            TestAuthSession(tokensAvailable = true),
        )

        val initialization = async { repository.initialize() }
        resolveStarted.await()

        assertEquals(AuthStatus.SignedIn, repository.state.value.authStatus)
        assertTrue(repository.state.value.historyResolution?.submitting == true)
        assertNotNull(database.timerDao().pendingBootstrapResolution())
        repository.toggleTimer()
        repository.addTask("Racing task")
        repository.changeDuration(TimerPhase.Focus, 1)
        repository.onForeground()
        delay(100)

        assertEquals(2, database.timerDao().pendingCommands().size)
        assertTrue(database.timerDao().pendingTaskOperations().isEmpty())
        assertTrue(database.timerDao().pendingDurationOperations().isEmpty())
        assertEquals(0, service.syncCalls)

        releaseResolve.complete(Unit)
        initialization.await()
    }

    @Test
    fun loadingAndSigningInBlockMutationsAndSignInIsSingleFlight() = runBlocking {
        val bootstrapStarted = CompletableDeferred<Unit>()
        val releaseBootstrap = CompletableDeferred<Unit>()
        val service = TestRepositoryService().apply {
            bootstrapHandler = {
                bootstrapStarted.complete(Unit)
                releaseBootstrap.await()
                response(revision = 0)
            }
        }
        val loadingRepository = testRepository(
            context,
            database.timerDao(),
            service,
            TestAuthSession(tokensAvailable = true),
            online = false,
        )
        val loadingInitialization = async { loadingRepository.initialize() }
        bootstrapStarted.await()

        assertEquals(AuthStatus.Loading, loadingRepository.state.value.authStatus)
        loadingRepository.toggleTimer()
        loadingRepository.addTask("Loading task")
        loadingRepository.changeDuration(TimerPhase.Focus, 1)
        assertEquals(0, loadingRepository.state.value.pendingCount)

        releaseBootstrap.complete(Unit)
        loadingInitialization.await()

        database.close()
        database = Room.inMemoryDatabaseBuilder(context, PomodoroughDatabase::class.java).build()
        val signInStarted = CompletableDeferred<Unit>()
        val releaseSignIn = CompletableDeferred<Unit>()
        val auth = TestAuthSession(tokensAvailable = false).apply {
            signInHandler = { _, _ ->
                signInStarted.complete(Unit)
                releaseSignIn.await()
                throw IOException("sign-in cancelled")
            }
        }
        val signingRepository = testRepository(context, database.timerDao(), TestRepositoryService(), auth)
        signingRepository.initialize()
        val activity = testActivity()
        val first = async { signingRepository.signIn(activity) }
        signInStarted.await()
        val second = async { signingRepository.signIn(activity) }
        delay(100)

        assertEquals(AuthStatus.SigningIn, signingRepository.state.value.authStatus)
        assertEquals(1, auth.signInCalls)
        signingRepository.toggleTimer()
        signingRepository.addTask("Signing task")
        signingRepository.changeDuration(TimerPhase.Focus, 1)
        assertEquals(0, signingRepository.state.value.pendingCount)

        releaseSignIn.complete(Unit)
        first.await()
        second.await()
        assertEquals(AuthStatus.SignedOut, signingRepository.state.value.authStatus)
    }

    @Test
    fun authenticationRequiredDuringResolutionPreservesExactPendingChoice() = runBlocking {
        val commands = completedLocalCommands()
        database.timerDao().insertState(testState(deviceSequence = 2))
        commands.forEach { database.timerDao().insertCommand(PendingCommandEntity.from(it)) }
        val service = TestRepositoryService().apply {
            bootstrapResponse = response(revision = 4)
            resolveFailure = AuthenticationRequired("expired")
        }
        val auth = TestAuthSession(tokensAvailable = true)
        val repository = testRepository(context, database.timerDao(), service, auth)

        repository.initialize()

        val first = service.resolutionRequests.single()
        assertEquals(AuthStatus.SignedOut, repository.state.value.authStatus)
        assertTrue(!auth.tokensAvailable)
        assertEquals(first.requestId, database.timerDao().pendingBootstrapResolution()?.requestId)
        assertEquals(testUser().id, database.timerDao().pendingBootstrapResolution()?.ownerUserId)
        assertEquals(first.requestId, repository.state.value.historyResolution?.requestId)
        assertEquals(2, database.timerDao().pendingCommands().size)

        service.resolveFailure = null
        service.syncResponse = response(revision = 5, history = listOf(testHistory("resolved")))
        service.resolveHandler = { request -> acknowledgedResolution(request, service.syncResponse) }
        val restarted = testRepository(
            context,
            database.timerDao(),
            service,
            TestAuthSession(tokensAvailable = true),
        )
        restarted.initialize()
        restarted.resolveHistory(BootstrapStrategy.ReplaceRemote)

        assertEquals(first, service.resolutionRequests.last())
        assertTrue(database.timerDao().pendingCommands().isEmpty())
    }

    @Test
    fun authenticationRequiredWhileRefreshingBootstrapSignsOutCurrentSession() = runBlocking {
        val localHistory = testHistory("local-history")
        val remoteHistory = testHistory("remote-history")
        database.timerDao().insertState(testState(history = listOf(localHistory)))
        val service = TestRepositoryService().apply {
            bootstrapResponse = response(revision = 2, history = listOf(remoteHistory))
            resolveFailure = me.egigoka.pomodorough.data.api.BootstrapConflictException(
                me.egigoka.pomodorough.data.api.BootstrapConflictKind.Revision,
                "revision_conflict",
            )
        }
        val auth = TestAuthSession(tokensAvailable = true)
        val repository = testRepository(context, database.timerDao(), service, auth)
        repository.initialize()
        repository.resolveHistory(BootstrapStrategy.ReplaceRemote)
        assertNull(database.timerDao().pendingBootstrapResolution())

        service.resolveFailure = null
        service.bootstrapFailure = AuthenticationRequired("expired refresh")
        repository.resolveHistory(BootstrapStrategy.ReplaceRemote)

        assertEquals(AuthStatus.SignedOut, repository.state.value.authStatus)
        assertNull(repository.state.value.user)
        assertTrue(!auth.tokensAvailable)
        assertEquals(listOf(localHistory), repository.state.value.history)
    }

    @Test
    fun staleResolutionSuccessCannotOverwriteNewAccount() = runBlocking {
        assertStaleResolutionCannotMutateNewAccount(failWithAuthentication = false)
    }

    @Test
    fun staleResolutionAuthenticationErrorCannotClearNewAccountSession() = runBlocking {
        assertStaleResolutionCannotMutateNewAccount(failWithAuthentication = true)
    }

    @Test
    fun corruptPersistedCommandArrayBlocksAndPreservesQueues() = runBlocking {
        assertCorruptResolutionPreservesQueues(commandsJson = "not-json")
    }

    @Test
    fun corruptPersistedTaskArrayBlocksAndPreservesQueues() = runBlocking {
        assertCorruptResolutionPreservesQueues(taskOperationsJson = "not-json")
    }

    @Test
    fun corruptPersistedDurationArrayBlocksAndPreservesQueues() = runBlocking {
        assertCorruptResolutionPreservesQueues(durationOperationsJson = "not-json")
    }

    @Test
    fun persistedRequestUnknownOperationKeyBlocksAndPreservesQueues() = runBlocking {
        val command = testCommand("unknown-key-command", sequence = 1)
        val encoded = repositoryJson.encodeToString(listOf(command))
            .replace("}]", ",\"unknown\":true}]")
        assertCorruptResolutionPreservesQueues(commandsJson = encoded)
    }

    @Test
    fun persistedRequestInvalidOperationSemanticsBlockAndPreserveQueues() = runBlocking {
        val invalidCommand = testCommand("invalid-command", sequence = 1).copy(phase = "invalid")
        val invalidTask = taskOperation(requireNotNull(TaskReducer.taskFromTitle("Invalid task"))).copy(
            taskId = "mismatched-task",
        )
        val invalidDuration = testDurationOperation(
            id = "invalid-duration",
            phase = TimerPhase.Focus,
            durationMs = 1_500_001,
        )

        assertCorruptResolutionPreservesQueues(
            commandsJson = repositoryJson.encodeToString(listOf(invalidCommand)),
        )
        resetDatabase()
        assertCorruptResolutionPreservesQueues(
            taskOperationsJson = repositoryJson.encodeToString(listOf(invalidTask)),
        )
        resetDatabase()
        assertCorruptResolutionPreservesQueues(
            durationOperationsJson = repositoryJson.encodeToString(listOf(invalidDuration)),
        )
    }

    @Test
    fun persistedRequestInvalidEnvelopeBlocksAndPreservesQueues() = runBlocking {
        assertCorruptResolutionPreservesQueues(deviceId = "other-device")
    }

    @Test
    fun malformedSuccessfulResolutionResponsesPreserveQueuesAndSavedRequest() = runBlocking {
        val invalidResponses = listOf<(BootstrapResolutionRequest, SyncResponse) -> SyncResponse>(
            { _, value -> value.copy(canonicalTimer = testTimer().copy(phase = "invalid")) },
            { _, value -> value.copy(canonicalTimer = testTimer().copy(elapsedAtAnchorMs = 1_500_001)) },
            { _, value -> value.copy(history = listOf(testHistory("duplicate"), testHistory("duplicate"))) },
            { _, value ->
                value.copy(
                    history = listOf(
                        testHistory("timer-identity-a"),
                        testHistory("timer-identity-b").copy(timerId = "timer-timer-identity-a"),
                    ),
                )
            },
            { _, value ->
                value.copy(
                    history = listOf(testHistory("bad-time").copy(completedAt = "not-an-instant")),
                )
            },
            { _, value ->
                value.copy(tasks = listOf(FocusTask("00000000-0000-4000-8000-000000000000", "Ship")))
            },
            { _, value -> value.copy(serverTime = "not-an-instant") },
            { _, value -> value.copy(serverHlcCounter = -1) },
            { request, value ->
                value.copy(
                    acknowledgements = request.commands.map {
                        Acknowledgement(it.id, "unknown", "")
                    },
                )
            },
        )

        invalidResponses.forEachIndexed { index, invalidate ->
            if (index > 0) resetDatabase()
            val localHistory = testHistory("local-$index")
            val remoteHistory = testHistory("remote-$index")
            val command = testCommand("queued-$index", sequence = 1)
            database.timerDao().insertState(
                testState(history = listOf(localHistory), deviceSequence = 1),
            )
            database.timerDao().insertCommand(PendingCommandEntity.from(command))
            val service = TestRepositoryService().apply {
                bootstrapResponse = response(revision = 1, history = listOf(remoteHistory))
                resolveHandler = { request ->
                    invalidate(
                        request,
                        acknowledgedResolution(
                            request,
                            response(revision = 2, history = listOf(remoteHistory)),
                        ),
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

            repository.resolveHistory(BootstrapStrategy.Merge)

            assertEquals("variant $index", 0L, database.timerDao().localState()?.revision)
            assertEquals("variant $index", listOf(command.id), database.timerDao().pendingCommands().map { it.id })
            assertNotNull("variant $index", database.timerDao().pendingBootstrapResolution())
            assertNotNull("variant $index", repository.state.value.historyResolution?.error)
            assertEquals("variant $index", listOf(localHistory), repository.state.value.history)
        }
    }

    @Test
    fun bootstrapResolutionCollectionLimitAllows4096AndBlocks4097Recoverably() = runBlocking {
        val localHistory = testHistory("local-limit")
        val remoteHistory = testHistory("remote-limit")
        val commands = List(4_097) { index ->
            testCommand("limit-command-$index", sequence = (index + 1).toLong())
        }
        val taskOperations = List(4_096) { index ->
            TaskOperation(
                id = "limit-task-operation-$index",
                taskId = "00000000-0000-4000-8000-${index.toString().padStart(12, '0')}",
                type = TaskOperationType.Delete,
                occurredAt = "2026-01-01T00:00:00Z",
                hlcWallMs = 1_767_225_600_000 + index,
                hlcCounter = 0,
            )
        }
        val durationOperations = List(4_096) { index ->
            testDurationOperation(
                id = "limit-duration-operation-$index",
                phase = TimerPhase.all[index % TimerPhase.all.size],
                durationMs = 30 * 60_000L,
                wallMs = 1_767_225_600_000 + index,
            )
        }

        database.timerDao().insertState(
            testState(history = listOf(localHistory), deviceSequence = 4_096),
        )
        database.withTransaction {
            commands.take(4_096).forEach {
                database.timerDao().insertCommand(PendingCommandEntity.from(it))
            }
            taskOperations.forEach {
                database.timerDao().insertTaskOperation(PendingTaskOperationEntity.from(it))
            }
            durationOperations.forEach {
                database.timerDao().upsertDurationOperation(PendingDurationOperationEntity.from(it))
            }
        }
        val acceptedService = TestRepositoryService().apply {
            bootstrapResponse = response(revision = 1, history = listOf(remoteHistory))
            resolveHandler = { request ->
                acknowledgedResolution(
                    request,
                    response(revision = 2, history = listOf(localHistory, remoteHistory)),
                )
            }
        }
        val acceptedRepository = testRepository(
            context,
            database.timerDao(),
            acceptedService,
            TestAuthSession(tokensAvailable = true),
        )
        acceptedRepository.initialize()
        acceptedRepository.resolveHistory(BootstrapStrategy.Merge)

        assertEquals(4_096, acceptedService.resolutionRequests.single().commands.size)
        assertEquals(4_096, acceptedService.resolutionRequests.single().taskOperations.size)
        assertEquals(TimerPhase.all.size, acceptedService.resolutionRequests.single().durationOperations.size)
        assertTrue(database.timerDao().pendingCommands().isEmpty())
        assertTrue(database.timerDao().pendingTaskOperations().isEmpty())
        assertTrue(database.timerDao().pendingDurationOperations().isEmpty())

        resetDatabase()
        database.timerDao().insertState(
            testState(history = listOf(localHistory), deviceSequence = 4_097),
        )
        database.withTransaction {
            commands.forEach { database.timerDao().insertCommand(PendingCommandEntity.from(it)) }
        }
        val blockedService = TestRepositoryService().apply {
            bootstrapResponse = response(revision = 1, history = listOf(remoteHistory))
            resolveHandler = { request ->
                acknowledgedResolution(request, response(revision = 2, history = listOf(remoteHistory)))
            }
        }
        val blockedRepository = testRepository(
            context,
            database.timerDao(),
            blockedService,
            TestAuthSession(tokensAvailable = true),
        )
        blockedRepository.initialize()
        blockedRepository.resolveHistory(BootstrapStrategy.Merge)

        assertTrue(blockedService.resolutionRequests.isEmpty())
        assertNull(database.timerDao().pendingBootstrapResolution())
        assertEquals(4_097, database.timerDao().pendingCommands().size)
        assertEquals(
            ResolutionRecovery.KeepRemote,
            blockedRepository.state.value.historyResolution?.recovery,
        )

        blockedRepository.resolveHistory(BootstrapStrategy.KeepRemote)

        assertTrue(blockedService.resolutionRequests.single().commands.isEmpty())
        assertTrue(database.timerDao().pendingCommands().isEmpty())
    }

    @Test
    fun rehydrationRejectsDurationEnvelopeThatCannotMatchCoalescedQueue() = runBlocking {
        val profile = testUser("limit-owner")
        val durationOperations = List(4_096) { index ->
            testDurationOperation(
                id = "accepted-stored-duration-$index",
                phase = TimerPhase.all[index % TimerPhase.all.size],
                durationMs = 30 * 60_000L,
                wallMs = 1_767_225_600_000 + index,
            )
        }
        database.timerDao().insertState(testState())
        database.timerDao().upsertBootstrapResolution(
            PendingBootstrapResolutionEntity(
                requestId = "bootstrap-limit-accepted",
                deviceId = "device-1",
                expectedRevision = 1,
                strategy = BootstrapStrategy.Merge.name,
                commandsJson = "[]",
                taskOperationsJson = "[]",
                durationOperationsJson = repositoryJson.encodeToString(durationOperations),
                ownerUserId = profile.id,
                userJson = repositoryJson.encodeToString(profile),
            ),
        )
        val repository = testRepository(
            context,
            database.timerDao(),
            TestRepositoryService(profile),
            TestAuthSession(tokensAvailable = false),
        )

        repository.initialize()

        assertTrue(repository.state.value.historyResolution?.corrupted == true)
        assertEquals(ResolutionRecovery.Repreview, repository.state.value.historyResolution?.recovery)
        assertNotNull(database.timerDao().pendingBootstrapResolution())
    }

    @Test
    fun rehydrationRejectsOversizedCommandTaskAndDurationArrays() = runBlocking {
        val commands = List(4_097) { index ->
            testCommand("stored-limit-command-$index", sequence = (index + 1).toLong())
        }
        assertCorruptResolutionPreservesQueues(
            commandsJson = repositoryJson.encodeToString(commands),
        )

        resetDatabase()
        val taskOperations = List(4_097) { index ->
            TaskOperation(
                id = "stored-limit-task-operation-$index",
                taskId = "stored-limit-task-$index",
                type = TaskOperationType.Delete,
                occurredAt = "2026-01-01T00:00:00Z",
                hlcWallMs = 1_767_225_600_000 + index,
                hlcCounter = 0,
            )
        }
        assertCorruptResolutionPreservesQueues(
            taskOperationsJson = repositoryJson.encodeToString(taskOperations),
        )

        resetDatabase()
        val durationOperations = List(4_097) { index ->
            testDurationOperation(
                id = "stored-limit-duration-operation-$index",
                phase = TimerPhase.all[index % TimerPhase.all.size],
                durationMs = 30 * 60_000L,
                wallMs = 1_767_225_600_000 + index,
            )
        }
        assertCorruptResolutionPreservesQueues(
            durationOperationsJson = repositoryJson.encodeToString(durationOperations),
        )
    }

    @Test
    fun syntacticallyValidSavedRequestThatDiffersFromQueuesIsQuarantined() = runBlocking {
        assertCorruptResolutionPreservesQueues(
            commandsJson = repositoryJson.encodeToString(
                listOf(testCommand("different-command", sequence = 1)),
            ),
        )
    }

    @Test
    fun corruptSavedResolutionCanRepreviewWithoutDeletingQueues() = runBlocking {
        val localHistory = testHistory("local-recovery")
        val remoteHistory = testHistory("remote-recovery")
        val command = testCommand("command-recovery", sequence = 1)
        val task = requireNotNull(TaskReducer.taskFromTitle("Recovery task"))
        val taskOperation = taskOperation(task)
        val durationOperation = testDurationOperation(
            id = "duration-recovery",
            phase = TimerPhase.Focus,
            durationMs = 30 * 60_000L,
        )
        val profile = testUser("recovery-user")
        database.timerDao().insertState(
            testState(history = listOf(localHistory), deviceSequence = 1),
        )
        database.timerDao().insertCommand(PendingCommandEntity.from(command))
        database.timerDao().insertTaskOperation(PendingTaskOperationEntity.from(taskOperation))
        database.timerDao().upsertDurationOperation(PendingDurationOperationEntity.from(durationOperation))
        database.timerDao().upsertBootstrapResolution(
            PendingBootstrapResolutionEntity(
                requestId = "bootstrap-recovery",
                deviceId = "device-1",
                expectedRevision = 1,
                strategy = BootstrapStrategy.Merge.name,
                commandsJson = "not-json",
                taskOperationsJson = repositoryJson.encodeToString(listOf(taskOperation)),
                durationOperationsJson = repositoryJson.encodeToString(listOf(durationOperation)),
                ownerUserId = profile.id,
                userJson = repositoryJson.encodeToString(profile),
            ),
        )
        val service = TestRepositoryService(profile).apply {
            bootstrapResponse = response(revision = 1, history = listOf(remoteHistory))
        }
        val repository = testRepository(
            context,
            database.timerDao(),
            service,
            TestAuthSession(tokensAvailable = true),
        )
        repository.initialize()
        assertEquals(ResolutionRecovery.Repreview, repository.state.value.historyResolution?.recovery)

        service.bootstrapFailure = IOException("refresh failed")
        repository.recoverCorruptedResolution()

        assertEquals(2, service.bootstrapCalls)
        assertNull(database.timerDao().pendingBootstrapResolution())
        assertEquals(listOf(command.id), database.timerDao().pendingCommands().map { it.id })
        assertEquals(listOf(taskOperation.id), database.timerDao().pendingTaskOperations().map { it.id })
        assertEquals(listOf(durationOperation.id), database.timerDao().pendingDurationOperations().map { it.id })
        assertEquals(ResolutionRecovery.Repreview, repository.state.value.historyResolution?.recovery)

        service.bootstrapFailure = null
        repository.recoverCorruptedResolution()

        assertEquals(3, service.bootstrapCalls)
        assertTrue(repository.state.value.historyResolution?.corrupted == false)
        assertEquals(1, repository.state.value.historyResolution?.localHistoryCount)
        assertEquals(1, repository.state.value.historyResolution?.remoteHistoryCount)
    }

    @Test
    fun emptyHistoriesMergeQueuedTimerTaskAndDurationOperationsAtomically() = runBlocking {
        val now = System.currentTimeMillis()
        val command = testCommand("command-1", sequence = 1).copy(
            occurredAt = Instant.ofEpochMilli(now).toString(),
            hlcWallMs = now,
        )
        val task = requireNotNull(TaskReducer.taskFromTitle("Ship Android"))
        val taskOperation = TaskOperation(
            id = "task-operation-1",
            taskId = task.id,
            type = TaskOperationType.Upsert,
            title = task.title,
            occurredAt = "2026-01-01T00:00:00Z",
            hlcWallMs = 1_767_225_600_001,
            hlcCounter = 0,
        )
        val durationOperation = testDurationOperation(
            id = "duration-operation-1",
            phase = TimerPhase.Focus,
            durationMs = 30 * 60_000L,
        )
        database.timerDao().insertState(
            testState(deviceSequence = 1).copy(
                knownTasksJson = repositoryJson.encodeToString(listOf(task)),
            ),
        )
        database.timerDao().insertCommand(PendingCommandEntity.from(command))
        database.timerDao().insertTaskOperation(PendingTaskOperationEntity.from(taskOperation))
        database.timerDao().upsertDurationOperation(
            PendingDurationOperationEntity.from(durationOperation),
        )
        val service = TestRepositoryService().apply {
            bootstrapResponse = response(revision = 0)
            syncResponse = response(
                revision = 1,
                timer = testTimer(),
                tasks = listOf(task),
                durations = DurationsMs(focus = durationOperation.durationMs),
            )
            resolveHandler = { request ->
                syncResponse.copy(
                    acknowledgements = request.commands.map { Acknowledgement(it.id, "applied", "") },
                    taskAcknowledgements = request.taskOperations.map {
                        TaskAcknowledgement(it.id, "applied", "")
                    },
                    durationAcknowledgements = request.durationOperations.map {
                        DurationAcknowledgement(it.id, "applied", "")
                    },
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

        val request = service.resolutionRequests.single()
        assertEquals(BootstrapStrategy.Merge, request.strategy)
        assertEquals(listOf(command), request.commands)
        assertEquals(listOf(taskOperation), request.taskOperations)
        assertEquals(listOf(durationOperation), request.durationOperations)
        assertEquals(0, repository.state.value.pendingCount)
        assertEquals(listOf(task), repository.state.value.tasks)
        assertEquals(durationOperation.durationMs, repository.state.value.settings.durationMsFor(TimerPhase.Focus))
        assertNull(database.timerDao().pendingBootstrapResolution())
    }

    @Test
    fun permanentResolution4xxDropsOnlyMetadataAndCanRepreview() = runBlocking {
        val commands = completedLocalCommands()
        database.timerDao().insertState(testState(deviceSequence = 2))
        commands.forEach { database.timerDao().insertCommand(PendingCommandEntity.from(it)) }
        val service = TestRepositoryService().apply {
            bootstrapResponse = response(revision = 5)
            resolveFailure = ApiException(422, "invalid resolution payload")
        }
        val repository = testRepository(
            context,
            database.timerDao(),
            service,
            TestAuthSession(tokensAvailable = true),
        )

        repository.initialize()

        val rejected = service.resolutionRequests.single()
        assertNull(database.timerDao().pendingBootstrapResolution())
        assertEquals(commands.map { it.id }, database.timerDao().pendingCommands().map { it.id })
        assertEquals(ResolutionRecovery.Repreview, repository.state.value.historyResolution?.recovery)

        service.resolveFailure = null
        service.resolveHandler = { request ->
            acknowledgedResolution(
                request,
                response(revision = 6, history = listOf(testHistory("resolved-after-422"))),
            )
        }
        repository.recoverCorruptedResolution()

        val retried = service.resolutionRequests.last()
        assertNotEquals(rejected.requestId, retried.requestId)
        assertTrue(database.timerDao().pendingCommands().isEmpty())
        assertNull(database.timerDao().pendingBootstrapResolution())
    }

    @Test
    fun networkRetryUsesExactPersistedRequestIdentityAndPayload() = runBlocking {
        val commands = completedLocalCommands()
        val task = requireNotNull(TaskReducer.taskFromTitle("Retry task"))
        val taskOperation = taskOperation(task).copy(id = "task-operation-retry")
        val durationOperation = testDurationOperation(
            id = "duration-operation-retry",
            phase = TimerPhase.Focus,
            durationMs = 30 * 60_000L,
        )
        database.timerDao().insertState(
            testState(deviceSequence = 2).copy(
                knownTasksJson = repositoryJson.encodeToString(listOf(task)),
            ),
        )
        commands.forEach { database.timerDao().insertCommand(PendingCommandEntity.from(it)) }
        database.timerDao().insertTaskOperation(PendingTaskOperationEntity.from(taskOperation))
        database.timerDao().upsertDurationOperation(PendingDurationOperationEntity.from(durationOperation))
        val service = TestRepositoryService().apply {
            bootstrapResponse = response(revision = 5)
            resolveFailure = IOException("response lost")
        }
        val firstAuth = TestAuthSession(tokensAvailable = true)
        val repository = testRepository(
            context,
            database.timerDao(),
            service,
            firstAuth,
        )

        repository.initialize()

        val first = service.resolutionRequests.single()
        val persisted = database.timerDao().pendingBootstrapResolution()
        assertNotNull(persisted)
        assertEquals(first.requestId, persisted?.requestId)
        assertEquals(BootstrapStrategy.ReplaceRemote, repository.state.value.historyResolution?.pendingStrategy)
        assertEquals(commands, first.commands)
        assertEquals(listOf(taskOperation), first.taskOperations)
        assertEquals(listOf(durationOperation), first.durationOperations)
        assertEquals(2, database.timerDao().pendingCommands().size)
        assertEquals(1, database.timerDao().pendingTaskOperations().size)
        assertEquals(1, database.timerDao().pendingDurationOperations().size)

        firstAuth.tokensAvailable = false
        val signedOutRestart = testRepository(
            context,
            database.timerDao(),
            service,
            TestAuthSession(tokensAvailable = false),
        )
        signedOutRestart.initialize()
        assertEquals(AuthStatus.SignedOut, signedOutRestart.state.value.authStatus)
        assertEquals(first.requestId, signedOutRestart.state.value.historyResolution?.requestId)
        signedOutRestart.toggleTimer()
        assertEquals(2, database.timerDao().pendingCommands().size)
        assertEquals(1, database.timerDao().pendingTaskOperations().size)
        assertEquals(1, database.timerDao().pendingDurationOperations().size)

        val canonicalHistory = testHistory("resolved-history")
        service.resolveFailure = null
        service.syncResponse = response(
            revision = 6,
            history = listOf(canonicalHistory),
            tasks = listOf(task),
            durations = DurationsMs(focus = durationOperation.durationMs),
        )
        service.resolveHandler = { request ->
            acknowledgedResolution(request, service.syncResponse)
        }
        val restarted = testRepository(
            context,
            database.timerDao(),
            service,
            TestAuthSession(tokensAvailable = true),
        )
        restarted.initialize()
        assertEquals(first.requestId, restarted.state.value.historyResolution?.requestId)
        restarted.resolveHistory(BootstrapStrategy.ReplaceRemote)

        val retry = service.resolutionRequests.last()
        assertEquals(first, retry)
        assertEquals(first.requestId, retry.requestId)
        assertEquals(listOf(canonicalHistory), restarted.state.value.history)
        assertNull(database.timerDao().pendingBootstrapResolution())
        assertTrue(database.timerDao().pendingCommands().isEmpty())
        assertTrue(database.timerDao().pendingTaskOperations().isEmpty())
        assertTrue(database.timerDao().pendingDurationOperations().isEmpty())
    }

    @Test
    fun revisionConflictKeepsLocalDataAndNextChoiceUsesFreshCasIdentity() = runBlocking {
        val commands = completedLocalCommands()
        database.timerDao().insertState(testState(deviceSequence = 2))
        commands.forEach { database.timerDao().insertCommand(PendingCommandEntity.from(it)) }
        val service = TestRepositoryService().apply {
            bootstrapResponse = response(revision = 3)
            resolveFailure = me.egigoka.pomodorough.data.api.BootstrapConflictException(
                me.egigoka.pomodorough.data.api.BootstrapConflictKind.Revision,
                "revision_conflict",
            )
        }
        val repository = testRepository(
            context,
            database.timerDao(),
            service,
            TestAuthSession(tokensAvailable = true),
        )

        repository.initialize()

        val conflicted = service.resolutionRequests.single()
        assertNull(database.timerDao().pendingBootstrapResolution())
        assertEquals(2, database.timerDao().pendingCommands().size)
        assertNull(repository.state.value.historyResolution?.pendingStrategy)

        service.resolveFailure = null
        service.bootstrapResponse = response(revision = 4)
        service.syncResponse = response(
            revision = 5,
            history = listOf(testHistory("resolved-after-conflict")),
        )
        service.resolveHandler = { request ->
            service.syncResponse.copy(
                acknowledgements = request.commands.map { Acknowledgement(it.id, "applied", "") },
            )
        }
        repository.resolveHistory(BootstrapStrategy.ReplaceRemote)

        val retried = service.resolutionRequests.last()
        assertNotEquals(conflicted.requestId, retried.requestId)
        assertEquals(4L, retried.expectedRevision)
        assertEquals(commands, retried.commands)
        assertTrue(database.timerDao().pendingCommands().isEmpty())
    }

    private fun completedLocalCommands() = listOf(
        testCommand("command-start", sequence = 1, timerId = "timer-local", type = CommandType.Start),
        testCommand("command-finish", sequence = 2, timerId = "timer-local", type = CommandType.Finish),
    )

    private suspend fun assertBothHistoryChoice(strategy: BootstrapStrategy) {
        val commands = completedLocalCommands()
        val localHistory = testHistory("local-choice")
        val remoteHistory = testHistory("remote-choice")
        val canonicalHistory = when (strategy) {
            BootstrapStrategy.ReplaceRemote -> listOf(localHistory)
            BootstrapStrategy.KeepRemote -> listOf(remoteHistory)
            BootstrapStrategy.Merge -> listOf(localHistory, remoteHistory)
        }
        database.timerDao().insertState(testState(deviceSequence = 2))
        commands.forEach { database.timerDao().insertCommand(PendingCommandEntity.from(it)) }
        val service = TestRepositoryService().apply {
            bootstrapResponse = response(revision = 5, history = listOf(remoteHistory))
            syncResponse = response(revision = 6, history = canonicalHistory)
            resolveHandler = { request -> acknowledgedResolution(request, syncResponse) }
        }
        val repository = testRepository(
            context,
            database.timerDao(),
            service,
            TestAuthSession(tokensAvailable = true),
        )
        repository.initialize()
        assertNotNull(repository.state.value.historyResolution)

        repository.resolveHistory(strategy)

        val request = service.resolutionRequests.single()
        assertEquals(strategy, request.strategy)
        if (strategy == BootstrapStrategy.KeepRemote) {
            assertTrue(request.commands.isEmpty())
        } else {
            assertEquals(commands, request.commands)
        }
        assertEquals(canonicalHistory, repository.state.value.history)
        assertTrue(database.timerDao().pendingCommands().isEmpty())
        assertNull(database.timerDao().pendingBootstrapResolution())
    }

    private fun taskOperation(task: FocusTask) = TaskOperation(
        id = "task-operation-${task.id}",
        taskId = task.id,
        type = TaskOperationType.Upsert,
        title = task.title,
        occurredAt = "2026-01-01T00:00:00Z",
        hlcWallMs = 1_767_225_600_001,
        hlcCounter = 0,
    )

    private fun acknowledgedResolution(
        request: BootstrapResolutionRequest,
        canonical: SyncResponse,
    ) = canonical.copy(
        acknowledgements = request.commands.map { Acknowledgement(it.id, "applied", "") },
        taskAcknowledgements = request.taskOperations.map {
            TaskAcknowledgement(it.id, "applied", "")
        },
        durationAcknowledgements = request.durationOperations.map {
            DurationAcknowledgement(it.id, "applied", "")
        },
    )

    private suspend fun assertCorruptResolutionPreservesQueues(
        commandsJson: String? = null,
        taskOperationsJson: String? = null,
        durationOperationsJson: String? = null,
        deviceId: String = "device-1",
    ) {
        val now = System.currentTimeMillis()
        val command = testCommand("command-corrupt", sequence = 1).copy(
            occurredAt = Instant.ofEpochMilli(now).toString(),
            hlcWallMs = now,
        )
        val task = requireNotNull(TaskReducer.taskFromTitle("Corrupt task"))
        val taskOperation = taskOperation(task)
        val durationOperation = testDurationOperation(
            id = "duration-corrupt",
            phase = TimerPhase.Focus,
            durationMs = 30 * 60_000L,
        )
        val profile = testUser("resolution-user")
        database.timerDao().insertState(testState(deviceSequence = 1))
        database.timerDao().insertCommand(PendingCommandEntity.from(command))
        database.timerDao().insertTaskOperation(PendingTaskOperationEntity.from(taskOperation))
        database.timerDao().upsertDurationOperation(PendingDurationOperationEntity.from(durationOperation))
        database.timerDao().upsertBootstrapResolution(
            PendingBootstrapResolutionEntity(
                requestId = "bootstrap-corrupt",
                deviceId = deviceId,
                expectedRevision = 3,
                strategy = BootstrapStrategy.Merge.name,
                commandsJson = commandsJson ?: repositoryJson.encodeToString(listOf(command)),
                taskOperationsJson = taskOperationsJson ?: repositoryJson.encodeToString(listOf(taskOperation)),
                durationOperationsJson = durationOperationsJson
                    ?: repositoryJson.encodeToString(listOf(durationOperation)),
                ownerUserId = profile.id,
                userJson = repositoryJson.encodeToString(profile),
            ),
        )
        val repository = testRepository(
            context,
            database.timerDao(),
            TestRepositoryService(profile),
            TestAuthSession(tokensAvailable = false),
        )

        repository.initialize()

        assertTrue(repository.state.value.historyResolution?.corrupted == true)
        repository.toggleTimer()
        repository.addTask("Must stay blocked")
        repository.changeDuration(TimerPhase.Focus, 1)
        assertEquals(listOf(command.id), database.timerDao().pendingCommands().map { it.id })
        assertEquals(listOf(taskOperation.id), database.timerDao().pendingTaskOperations().map { it.id })
        assertEquals(listOf(durationOperation.id), database.timerDao().pendingDurationOperations().map { it.id })
        assertEquals("bootstrap-corrupt", database.timerDao().pendingBootstrapResolution()?.requestId)
    }

    private suspend fun assertStaleResolutionCannotMutateNewAccount(
        failWithAuthentication: Boolean,
    ) = coroutineScope {
        val oldUser = testUser("old-resolution-user")
        val newUser = testUser("new-current-user")
        val commands = completedLocalCommands()
        val newHistory = testHistory("new-account-history")
        database.timerDao().insertState(testState(deviceSequence = 2))
        commands.forEach { database.timerDao().insertCommand(PendingCommandEntity.from(it)) }
        val resolveStarted = CompletableDeferred<Unit>()
        val releaseResolve = CompletableDeferred<Unit>()
        val oldCanonical = response(revision = 2, history = listOf(testHistory("old-result")))
        val service = TestRepositoryService(oldUser).apply {
            bootstrapResponse = response(revision = 1)
            resolveHandler = { request ->
                resolveStarted.complete(Unit)
                releaseResolve.await()
                if (failWithAuthentication) throw AuthenticationRequired("stale expired")
                acknowledgedResolution(request, oldCanonical)
            }
        }
        val auth = TestAuthSession(tokensAvailable = true)
        val repository = testRepository(context, database.timerDao(), service, auth, online = false)
        val oldInitialization = async { repository.initialize() }
        resolveStarted.await()

        repository.logout()
        service.profile = newUser
        service.bootstrapResponse = response(revision = 7, history = listOf(newHistory))
        auth.signInHandler = { _, _ ->
            auth.tokensAvailable = true
            testTokens()
        }
        repository.signIn(testActivity())

        assertNotNull(repository.state.value.accountSwitch)
        repository.confirmAccountSwitch()

        assertEquals(AuthStatus.SignedIn, repository.state.value.authStatus)
        assertEquals(newUser, repository.state.value.user)
        assertEquals(newUser.id, database.timerDao().localState()?.ownerUserId)
        assertEquals(listOf(newHistory), repository.state.value.history)
        assertTrue(auth.tokensAvailable)

        releaseResolve.complete(Unit)
        oldInitialization.await()

        assertEquals(AuthStatus.SignedIn, repository.state.value.authStatus)
        assertEquals(newUser, repository.state.value.user)
        assertEquals(newUser.id, database.timerDao().localState()?.ownerUserId)
        assertEquals(listOf(newHistory), repository.state.value.history)
        assertNull(database.timerDao().pendingBootstrapResolution())
        assertTrue(auth.tokensAvailable)
    }

    private fun resetDatabase() {
        database.close()
        database = Room.inMemoryDatabaseBuilder(context, PomodoroughDatabase::class.java).build()
    }

    private fun testTokens() = TokenPair(
        accessToken = "access-token",
        accessTokenExpiresAt = "2999-01-01T00:00:00Z",
        refreshToken = "refresh-token",
        refreshTokenExpiresAt = "2999-02-01T00:00:00Z",
    )

    private fun testActivity(): Activity {
        var activity: Activity? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            activity = Activity()
        }
        return requireNotNull(activity)
    }

    private fun response(
        revision: Long,
        timer: me.egigoka.pomodorough.data.CanonicalTimer? = null,
        history: List<me.egigoka.pomodorough.data.HistoryItem> = emptyList(),
        tasks: List<FocusTask> = emptyList(),
        durations: DurationsMs = DurationsMs(),
    ) = SyncResponse(
        acknowledgements = emptyList(),
        revision = revision,
        canonicalTimer = timer,
        history = history,
        serverTime = "2026-01-01T00:00:00Z",
        serverHlcWallMs = 1_767_225_600_000 + revision,
        serverHlcCounter = 0,
        durationAcknowledgements = emptyList(),
        durationsMs = durations,
        taskAcknowledgements = emptyList(),
        tasks = tasks,
    )
}
