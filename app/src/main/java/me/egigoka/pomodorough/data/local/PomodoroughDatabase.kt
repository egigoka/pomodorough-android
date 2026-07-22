package me.egigoka.pomodorough.data.local

import android.content.Context
import java.time.Instant
import java.util.UUID
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import org.json.JSONObject

@Dao
interface TimerDao {
    @Query("SELECT * FROM local_state WHERE id = 0")
    suspend fun localState(): LocalStateEntity?

    @Query("SELECT * FROM pending_commands ORDER BY deviceSequence")
    suspend fun pendingCommands(): List<PendingCommandEntity>

    @Query("SELECT * FROM pending_task_operations ORDER BY hlcWallMs, hlcCounter, id")
    suspend fun pendingTaskOperations(): List<PendingTaskOperationEntity>

    @Query("SELECT * FROM pending_duration_operations ORDER BY hlcWallMs, hlcCounter, id")
    suspend fun pendingDurationOperations(): List<PendingDurationOperationEntity>

    @Query("SELECT * FROM pending_bootstrap_resolution WHERE id = 0")
    suspend fun pendingBootstrapResolution(): PendingBootstrapResolutionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertState(state: LocalStateEntity)

    @Update
    suspend fun updateState(state: LocalStateEntity)

    @Insert
    suspend fun insertCommand(command: PendingCommandEntity)

    @Insert
    suspend fun insertTaskOperation(operation: PendingTaskOperationEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDurationOperation(operation: PendingDurationOperationEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertBootstrapResolution(resolution: PendingBootstrapResolutionEntity)

    @Delete
    suspend fun deleteCommands(commands: List<PendingCommandEntity>)

    @Delete
    suspend fun deleteTaskOperations(operations: List<PendingTaskOperationEntity>)

    @Query("DELETE FROM pending_commands")
    suspend fun deleteAllCommands()

    @Query("DELETE FROM pending_task_operations")
    suspend fun deleteAllTaskOperations()

    @Query("DELETE FROM pending_duration_operations WHERE id IN (:operationIds)")
    suspend fun deleteDurationOperationsById(operationIds: List<String>)

    @Query("DELETE FROM pending_duration_operations")
    suspend fun deleteAllDurationOperations()

    @Query("DELETE FROM pending_bootstrap_resolution")
    suspend fun deleteBootstrapResolution()

    @Transaction
    suspend fun persistCommand(command: PendingCommandEntity, state: LocalStateEntity) {
        insertCommand(command)
        updateState(state)
    }

    @Transaction
    suspend fun persistTaskOperation(
        operation: PendingTaskOperationEntity,
        state: LocalStateEntity,
    ) {
        insertTaskOperation(operation)
        updateState(state)
    }

    @Transaction
    suspend fun persistDurationOperation(
        operation: PendingDurationOperationEntity,
        state: LocalStateEntity,
    ) {
        upsertDurationOperation(operation)
        updateState(state)
    }

    @Transaction
    suspend fun applySync(
        acknowledged: List<PendingCommandEntity>,
        state: LocalStateEntity,
    ) {
        if (acknowledged.isNotEmpty()) deleteCommands(acknowledged)
        updateState(state)
    }

    @Transaction
    suspend fun applyFullSync(
        acknowledgedCommands: List<PendingCommandEntity>,
        acknowledgedTaskOperations: List<PendingTaskOperationEntity>,
        acknowledgedDurationOperationIds: List<String>,
        state: LocalStateEntity,
    ) {
        if (acknowledgedCommands.isNotEmpty()) deleteCommands(acknowledgedCommands)
        if (acknowledgedTaskOperations.isNotEmpty()) deleteTaskOperations(acknowledgedTaskOperations)
        if (acknowledgedDurationOperationIds.isNotEmpty()) {
            deleteDurationOperationsById(acknowledgedDurationOperationIds)
        }
        updateState(state)
    }

    @Transaction
    suspend fun clearAccount(state: LocalStateEntity) {
        deleteAllCommands()
        deleteAllTaskOperations()
        deleteAllDurationOperations()
        deleteBootstrapResolution()
        updateState(state)
    }

    @Transaction
    suspend fun applyBootstrapResolution(state: LocalStateEntity) {
        deleteAllCommands()
        deleteAllTaskOperations()
        deleteAllDurationOperations()
        deleteBootstrapResolution()
        updateState(state)
    }
}

@Database(
    entities = [
        LocalStateEntity::class,
        PendingCommandEntity::class,
        PendingTaskOperationEntity::class,
        PendingDurationOperationEntity::class,
        PendingBootstrapResolutionEntity::class,
    ],
    version = 5,
    exportSchema = true,
)
abstract class PomodoroughDatabase : RoomDatabase() {
    abstract fun timerDao(): TimerDao

    companion object {
        fun create(context: Context): PomodoroughDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                PomodoroughDatabase::class.java,
                "pomodorough.db",
            ).addMigrations(Migration1To2, Migration2To3, Migration3To4, Migration4To5).build()

        val Migration1To2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE local_state ADD COLUMN ownerUserId TEXT")
            }
        }

        val Migration2To3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE local_state ADD COLUMN tasksJson TEXT NOT NULL DEFAULT '[]'")
                db.execSQL("ALTER TABLE local_state ADD COLUMN knownTasksJson TEXT NOT NULL DEFAULT '[]'")
                db.execSQL("ALTER TABLE local_state ADD COLUMN selectedTaskId TEXT")
                db.execSQL("ALTER TABLE pending_commands ADD COLUMN taskId TEXT")
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS pending_task_operations (
                        id TEXT NOT NULL PRIMARY KEY,
                        taskId TEXT NOT NULL,
                        type TEXT NOT NULL,
                        title TEXT,
                        occurredAt TEXT NOT NULL,
                        hlcWallMs INTEGER NOT NULL,
                        hlcCounter INTEGER NOT NULL
                    )""".trimIndent(),
                )
            }
        }

        val Migration3To4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS pending_duration_operations (
                        phase TEXT NOT NULL PRIMARY KEY,
                        id TEXT NOT NULL,
                        durationMs INTEGER NOT NULL,
                        occurredAt TEXT NOT NULL,
                        hlcWallMs INTEGER NOT NULL,
                        hlcCounter INTEGER NOT NULL
                    )""".trimIndent(),
                )
                db.execSQL(
                    """CREATE UNIQUE INDEX IF NOT EXISTS index_pending_duration_operations_id
                        ON pending_duration_operations (id)""".trimIndent(),
                )

                db.query(
                    "SELECT settingsJson FROM local_state WHERE id = 0",
                ).use { cursor ->
                    if (!cursor.moveToFirst()) return
                    val settings = runCatching { JSONObject(cursor.getString(0)) }.getOrNull() ?: return
                    val customDurations = listOf(
                        Triple("focus", settings.optInt("focusMinutes", 25), 25),
                        Triple("short_break", settings.optInt("shortBreakMinutes", 5), 5),
                        Triple("long_break", settings.optInt("longBreakMinutes", 15), 15),
                    ).mapNotNull { (phase, minutes, defaultMinutes) ->
                        val bounded = minutes.coerceIn(1, 180)
                        if (bounded == defaultMinutes) null else phase to bounded * 60_000L
                    }
                    if (customDurations.isEmpty()) return

                    val occurredAt = Instant.EPOCH.toString()
                    customDurations.forEach { (phase, durationMs) ->
                        db.execSQL(
                            """INSERT INTO pending_duration_operations (
                                phase, id, durationMs, occurredAt, hlcWallMs, hlcCounter
                            ) VALUES (?, ?, ?, ?, ?, ?)""".trimIndent(),
                            arrayOf<Any>(
                                phase,
                                "duration-operation-${UUID.randomUUID()}",
                                durationMs,
                                occurredAt,
                                0L,
                                0L,
                            ),
                        )
                    }
                }
            }
        }

        val Migration4To5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS pending_bootstrap_resolution (
                        id INTEGER NOT NULL PRIMARY KEY,
                        requestId TEXT NOT NULL,
                        deviceId TEXT NOT NULL,
                        expectedRevision INTEGER NOT NULL,
                        strategy TEXT NOT NULL,
                        commandsJson TEXT NOT NULL,
                        taskOperationsJson TEXT NOT NULL,
                        durationOperationsJson TEXT NOT NULL,
                        ownerUserId TEXT NOT NULL,
                        userJson TEXT NOT NULL
                    )""".trimIndent(),
                )
            }
        }
    }
}
