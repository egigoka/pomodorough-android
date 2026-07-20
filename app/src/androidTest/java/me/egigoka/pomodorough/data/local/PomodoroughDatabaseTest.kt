package me.egigoka.pomodorough.data.local

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import me.egigoka.pomodorough.data.CommandType
import me.egigoka.pomodorough.data.TimerCommand
import me.egigoka.pomodorough.data.TimerPhase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PomodoroughDatabaseTest {
    @get:Rule
    val migrationHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        PomodoroughDatabase::class.java,
    )

    private lateinit var context: Context
    private lateinit var database: PomodoroughDatabase
    private lateinit var dao: TimerDao

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, PomodoroughDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.timerDao()
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(MigrationDatabaseName)
    }

    @Test
    fun persistCommandStoresCommandAndClockStateTogether() = runBlocking {
        val initial = state()
        dao.insertState(initial)
        val command = command(1)
        val next = initial.copy(deviceSequence = 1, hlcWallMs = 100, hlcCounter = 2)

        dao.persistCommand(PendingCommandEntity.from(command), next)

        assertEquals(listOf(command), dao.pendingCommands().map(PendingCommandEntity::toModel))
        assertEquals(next, dao.localState())
    }

    @Test
    fun applySyncDeletesOnlyAcknowledgedCommandsAndUpdatesSnapshot() = runBlocking {
        val initial = state()
        dao.insertState(initial)
        val first = PendingCommandEntity.from(command(1))
        val second = PendingCommandEntity.from(command(2))
        dao.insertCommand(first)
        dao.insertCommand(second)
        val synced = initial.copy(revision = 9, historyJson = "[{\"id\":\"history\"}]")

        dao.applySync(listOf(first), synced)

        assertEquals(listOf(second), dao.pendingCommands())
        assertEquals(synced, dao.localState())
    }

    @Test
    fun clearAccountRemovesQueueAndPersistsClearedOwner() = runBlocking {
        val initial = state().copy(ownerUserId = "old-user", userJson = "{\"id\":\"old-user\"}")
        dao.insertState(initial)
        dao.insertCommand(PendingCommandEntity.from(command(1)))
        val cleared = initial.copy(
            revision = 0,
            canonicalTimerJson = null,
            historyJson = "[]",
            userJson = null,
            ownerUserId = null,
        )

        dao.clearAccount(cleared)

        assertTrue(dao.pendingCommands().isEmpty())
        assertEquals(cleared, dao.localState())
    }

    @Test
    fun migrationOneToTwoPreservesStateAndAddsNullableOwner() {
        context.deleteDatabase(MigrationDatabaseName)
        migrationHelper.createDatabase(MigrationDatabaseName, 1).apply {
            execSQL(
                """INSERT INTO local_state (
                    id, deviceId, deviceSequence, hlcWallMs, hlcCounter, revision,
                    canonicalTimerJson, historyJson, settingsJson, userJson
                ) VALUES (0, 'device-1', 7, 100, 2, 4, NULL, '[]', '{}', NULL)""",
            )
            close()
        }

        val migrated = migrationHelper.runMigrationsAndValidate(
            MigrationDatabaseName,
            2,
            true,
            PomodoroughDatabase.Migration1To2,
        )

        migrated.query("SELECT deviceId, deviceSequence, revision, ownerUserId FROM local_state").use {
            assertTrue(it.moveToFirst())
            assertEquals("device-1", it.getString(0))
            assertEquals(7, it.getLong(1))
            assertEquals(4, it.getLong(2))
            assertNull(it.getString(3))
        }
        migrated.close()
    }

    private fun state() = LocalStateEntity(
        deviceId = "device-1",
        settingsJson = "{}",
    )

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
        const val MigrationDatabaseName = "migration-test"
    }
}
