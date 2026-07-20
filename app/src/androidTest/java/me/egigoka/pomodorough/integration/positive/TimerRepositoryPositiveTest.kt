package me.egigoka.pomodorough.integration.positive

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import me.egigoka.pomodorough.data.Acknowledgement
import me.egigoka.pomodorough.data.AuthStatus
import me.egigoka.pomodorough.data.CommandType
import me.egigoka.pomodorough.data.SyncResponse
import me.egigoka.pomodorough.data.SyncStatus
import me.egigoka.pomodorough.data.TimerPhase
import me.egigoka.pomodorough.data.TimerSettings
import me.egigoka.pomodorough.data.TimerStatus
import me.egigoka.pomodorough.data.local.PendingCommandEntity
import me.egigoka.pomodorough.data.local.PomodoroughDatabase
import me.egigoka.pomodorough.integration.TestAuthSession
import me.egigoka.pomodorough.integration.TestRepositoryService
import me.egigoka.pomodorough.integration.awaitState
import me.egigoka.pomodorough.integration.repositoryJson
import me.egigoka.pomodorough.integration.testCommand
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
}
