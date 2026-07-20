package me.egigoka.pomodorough.integration.negative

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import me.egigoka.pomodorough.data.CommandType
import me.egigoka.pomodorough.data.TimerCommand
import me.egigoka.pomodorough.data.TimerPhase
import me.egigoka.pomodorough.data.local.LocalStateEntity
import me.egigoka.pomodorough.data.local.PendingCommandEntity
import me.egigoka.pomodorough.data.local.PomodoroughDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PomodoroughDatabaseNegativeTest {
    private lateinit var database: PomodoroughDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, PomodoroughDatabase::class.java).build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun duplicateCommandRollsBackClockStateUpdate() = runBlocking {
        val dao = database.timerDao()
        val initialState = LocalStateEntity(
            deviceId = "device-1",
            deviceSequence = 1,
            hlcWallMs = 100,
            settingsJson = "{}",
        )
        val existing = PendingCommandEntity.from(command(sequence = 1))
        dao.insertState(initialState)
        dao.insertCommand(existing)
        val duplicate = PendingCommandEntity.from(command(sequence = 2)).copy(id = existing.id)
        val changedState = initialState.copy(deviceSequence = 2, hlcWallMs = 102)

        assertThrows(SQLiteConstraintException::class.java) {
            runBlocking { dao.persistCommand(duplicate, changedState) }
        }

        assertEquals(initialState, dao.localState())
        assertEquals(listOf(existing), dao.pendingCommands())
    }

    private fun command(sequence: Long) = TimerCommand(
        id = "command-$sequence",
        deviceSequence = sequence,
        timerId = "timer-1",
        type = CommandType.Pause,
        phase = TimerPhase.Focus,
        plannedDurationMs = 1_500_000,
        occurredAt = "2026-01-01T00:05:00Z",
        hlcWallMs = 100 + sequence,
        hlcCounter = 0,
        observedElapsedMs = 300_000,
    )
}
