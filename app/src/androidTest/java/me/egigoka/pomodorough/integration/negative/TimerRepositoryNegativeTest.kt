package me.egigoka.pomodorough.integration.negative

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.IOException
import kotlinx.coroutines.runBlocking
import me.egigoka.pomodorough.data.AuthStatus
import me.egigoka.pomodorough.data.SyncStatus
import me.egigoka.pomodorough.data.TimerPhase
import me.egigoka.pomodorough.data.TimerSettings
import me.egigoka.pomodorough.data.api.ApiException
import me.egigoka.pomodorough.data.auth.AuthenticationRequired
import me.egigoka.pomodorough.data.local.LocalStateEntity
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
    fun failedLogoutPreservesAccountDataAndReportsNotice() = runBlocking {
        val profile = testUser()
        val timer = testTimer()
        val persisted = testState(user = profile, timer = timer, history = listOf(testHistory("history-1")))
        database.timerDao().insertState(persisted)
        val auth = TestAuthSession(tokensAvailable = true).apply {
            logoutFailure = IOException("logout unavailable")
        }
        val repository = testRepository(
            context,
            database.timerDao(),
            TestRepositoryService(profile),
            auth,
            online = false,
        )
        repository.initialize()

        repository.logout()

        assertEquals(1, auth.logoutCalls)
        assertEquals(AuthStatus.SignedIn, repository.state.value.authStatus)
        assertEquals(profile, repository.state.value.user)
        assertEquals(timer, repository.state.value.timer)
        assertEquals("logout unavailable", repository.state.value.notice)
        assertEquals(persisted, database.timerDao().localState())
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
