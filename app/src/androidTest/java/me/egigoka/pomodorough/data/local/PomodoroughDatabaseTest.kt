package me.egigoka.pomodorough.data.local

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import me.egigoka.pomodorough.data.CommandType
import me.egigoka.pomodorough.data.DurationOperation
import me.egigoka.pomodorough.data.TaskOperation
import me.egigoka.pomodorough.data.TaskOperationType
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
    fun fullSyncDeletesAcknowledgedTaskOperationsAndUpdatesSnapshot() = runBlocking {
        val initial = state()
        dao.insertState(initial)
        val first = PendingTaskOperationEntity.from(taskOperation("operation-1", 1))
        val second = PendingTaskOperationEntity.from(taskOperation("operation-2", 2))
        dao.insertTaskOperation(first)
        dao.insertTaskOperation(second)
        val synced = initial.copy(revision = 9, tasksJson = "[{\"id\":\"task-1\",\"title\":\"Ship\"}]")

        dao.applyFullSync(emptyList(), listOf(first), emptyList(), synced)

        assertEquals(listOf(second), dao.pendingTaskOperations())
        assertEquals(synced, dao.localState())
    }

    @Test
    fun durationCompactionAndAcknowledgementDeleteAreOperationIdGuarded() = runBlocking {
        val initial = state()
        dao.insertState(initial)
        val sent = durationOperation("duration-1", TimerPhase.Focus, 1_560_000, 100)
        val replacement = durationOperation("duration-2", TimerPhase.Focus, 1_620_000, 101)
        dao.persistDurationOperation(
            PendingDurationOperationEntity.from(sent),
            initial.copy(hlcWallMs = 100),
        )
        val replacementState = initial.copy(hlcWallMs = 101, settingsJson = "replacement")
        dao.persistDurationOperation(PendingDurationOperationEntity.from(replacement), replacementState)

        dao.applyFullSync(emptyList(), emptyList(), listOf(sent.id), replacementState)

        assertEquals(
            listOf(replacement),
            dao.pendingDurationOperations().map(PendingDurationOperationEntity::toModel),
        )
        assertEquals(replacementState, dao.localState())
    }

    @Test
    fun clearAccountRemovesQueueAndPersistsClearedOwner() = runBlocking {
        val initial = state().copy(ownerUserId = "old-user", userJson = "{\"id\":\"old-user\"}")
        dao.insertState(initial)
        dao.insertCommand(PendingCommandEntity.from(command(1)))
        dao.insertTaskOperation(PendingTaskOperationEntity.from(taskOperation("operation-1", 1)))
        val cleared = initial.copy(
            revision = 0,
            canonicalTimerJson = null,
            historyJson = "[]",
            userJson = null,
            ownerUserId = null,
        )

        dao.clearAccount(cleared)

        assertTrue(dao.pendingCommands().isEmpty())
        assertTrue(dao.pendingTaskOperations().isEmpty())
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

    @Test
    fun migrationTwoToThreePreservesDataAndAddsTaskState() {
        context.deleteDatabase(MigrationDatabaseName)
        migrationHelper.createDatabase(MigrationDatabaseName, 2).apply {
            execSQL(
                """INSERT INTO local_state (
                    id, deviceId, deviceSequence, hlcWallMs, hlcCounter, revision,
                    canonicalTimerJson, historyJson, settingsJson, userJson, ownerUserId
                ) VALUES (0, 'device-1', 7, 100, 2, 4, NULL, '[]', '{}', NULL, 'user-1')""",
            )
            execSQL(
                """INSERT INTO pending_commands (
                    id, deviceSequence, timerId, type, phase, plannedDurationMs,
                    occurredAt, hlcWallMs, hlcCounter, observedElapsedMs
                ) VALUES ('command-1', 7, 'timer-1', 'start', 'focus', 1500000,
                    '2026-01-01T00:00:00Z', 100, 0, 0)""",
            )
            close()
        }

        val migrated = migrationHelper.runMigrationsAndValidate(
            MigrationDatabaseName,
            3,
            true,
            PomodoroughDatabase.Migration2To3,
        )

        migrated.query(
            "SELECT deviceId, tasksJson, knownTasksJson, selectedTaskId FROM local_state",
        ).use {
            assertTrue(it.moveToFirst())
            assertEquals("device-1", it.getString(0))
            assertEquals("[]", it.getString(1))
            assertEquals("[]", it.getString(2))
            assertNull(it.getString(3))
        }
        migrated.query("SELECT id, taskId FROM pending_commands").use {
            assertTrue(it.moveToFirst())
            assertEquals("command-1", it.getString(0))
            assertNull(it.getString(1))
        }
        migrated.query("SELECT COUNT(*) FROM pending_task_operations").use {
            assertTrue(it.moveToFirst())
            assertEquals(0, it.getInt(0))
        }
        migrated.close()
    }

    @Test
    fun migrationThreeToFourQueuesOnlyNonDefaultLegacyDurations() {
        context.deleteDatabase(MigrationDatabaseName)
        migrationHelper.createDatabase(MigrationDatabaseName, 3).apply {
            execSQL(
                """INSERT INTO local_state (
                    id, deviceId, deviceSequence, hlcWallMs, hlcCounter, revision,
                    canonicalTimerJson, historyJson, settingsJson, userJson, ownerUserId,
                    tasksJson, knownTasksJson, selectedTaskId
                ) VALUES (0, 'device-1', 7, 9000000000000, 4, 4, NULL, '[]',
                    '{"focusMinutes":30,"shortBreakMinutes":5,"longBreakMinutes":20}',
                    NULL, 'user-1', '[]', '[]', NULL)""",
            )
            close()
        }

        val migrated = migrationHelper.runMigrationsAndValidate(
            MigrationDatabaseName,
            4,
            true,
            PomodoroughDatabase.Migration3To4,
        )

        migrated.query(
            """SELECT phase, durationMs, occurredAt, hlcWallMs, hlcCounter
                FROM pending_duration_operations ORDER BY phase""",
        ).use {
            assertTrue(it.moveToFirst())
            assertEquals(TimerPhase.Focus, it.getString(0))
            assertEquals(1_800_000, it.getLong(1))
            assertEquals("1970-01-01T00:00:00Z", it.getString(2))
            assertEquals(0L, it.getLong(3))
            assertEquals(0L, it.getLong(4))
            assertTrue(it.moveToNext())
            assertEquals(TimerPhase.LongBreak, it.getString(0))
            assertEquals(1_200_000, it.getLong(1))
            assertEquals(0L, it.getLong(3))
            assertEquals(0L, it.getLong(4))
            assertTrue(!it.moveToNext())
        }
        migrated.query("SELECT hlcWallMs, hlcCounter FROM local_state").use {
            assertTrue(it.moveToFirst())
            assertEquals(9_000_000_000_000, it.getLong(0))
            assertEquals(4L, it.getLong(1))
        }
        migrated.close()
    }

    @Test
    fun migrationThreeToFourLeavesDefaultDurationsForCanonicalPull() {
        context.deleteDatabase(MigrationDatabaseName)
        migrationHelper.createDatabase(MigrationDatabaseName, 3).apply {
            execSQL(
                """INSERT INTO local_state (
                    id, deviceId, deviceSequence, hlcWallMs, hlcCounter, revision,
                    canonicalTimerJson, historyJson, settingsJson, userJson, ownerUserId,
                    tasksJson, knownTasksJson, selectedTaskId
                ) VALUES (0, 'device-1', 7, 100, 4, 4, NULL, '[]',
                    '{"focusMinutes":25,"shortBreakMinutes":5,"longBreakMinutes":15}',
                    NULL, 'user-1', '[]', '[]', NULL)""",
            )
            close()
        }

        val migrated = migrationHelper.runMigrationsAndValidate(
            MigrationDatabaseName,
            4,
            true,
            PomodoroughDatabase.Migration3To4,
        )

        migrated.query("SELECT COUNT(*) FROM pending_duration_operations").use {
            assertTrue(it.moveToFirst())
            assertEquals(0, it.getInt(0))
        }
        migrated.query("SELECT hlcWallMs, hlcCounter FROM local_state").use {
            assertTrue(it.moveToFirst())
            assertEquals(100, it.getLong(0))
            assertEquals(4, it.getLong(1))
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

    private fun taskOperation(id: String, wall: Long) = TaskOperation(
        id = id,
        taskId = "task-1",
        type = TaskOperationType.Upsert,
        title = "Ship",
        occurredAt = "2026-01-01T00:00:00Z",
        hlcWallMs = wall,
        hlcCounter = 0,
    )

    private fun durationOperation(
        id: String,
        phase: String,
        durationMs: Long,
        wall: Long,
    ) = DurationOperation(
        id = id,
        phase = phase,
        durationMs = durationMs,
        occurredAt = "2026-01-01T00:00:00Z",
        hlcWallMs = wall,
        hlcCounter = 0,
    )

    private companion object {
        const val MigrationDatabaseName = "migration-test"
    }
}
