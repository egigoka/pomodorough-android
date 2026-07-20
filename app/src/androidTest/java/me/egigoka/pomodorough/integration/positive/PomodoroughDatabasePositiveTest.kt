package me.egigoka.pomodorough.integration.positive

import android.content.Context
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
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PomodoroughDatabasePositiveTest {
    private lateinit var context: Context
    private lateinit var database: PomodoroughDatabase

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(DatabaseName)
        database = openDatabase()
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(DatabaseName)
    }

    @Test
    fun stateAndOrderedCommandQueueSurviveDatabaseReopen() = runBlocking {
        val state = LocalStateEntity(
            deviceId = "device-1",
            deviceSequence = 2,
            revision = 7,
            settingsJson = "{}",
        )
        val first = PendingCommandEntity.from(command(sequence = 1))
        val second = PendingCommandEntity.from(command(sequence = 2))
        database.timerDao().insertState(state)
        database.timerDao().insertCommand(second)
        database.timerDao().insertCommand(first)

        database.close()
        database = openDatabase()

        assertEquals(state, database.timerDao().localState())
        assertEquals(listOf(first, second), database.timerDao().pendingCommands())
    }

    private fun openDatabase() = Room.databaseBuilder(
        context,
        PomodoroughDatabase::class.java,
        DatabaseName,
    ).addMigrations(PomodoroughDatabase.Migration1To2).build()

    private fun command(sequence: Long) = TimerCommand(
        id = "command-$sequence",
        deviceSequence = sequence,
        timerId = "timer-1",
        type = CommandType.Start,
        phase = TimerPhase.Focus,
        plannedDurationMs = 1_500_000,
        occurredAt = "2026-01-01T00:00:00Z",
        hlcWallMs = 100 + sequence,
        hlcCounter = 0,
        observedElapsedMs = 0,
    )

    private companion object {
        const val DatabaseName = "positive-integration-test"
    }
}
