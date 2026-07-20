package me.egigoka.pomodorough.data.local

import android.content.Context
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

@Dao
interface TimerDao {
    @Query("SELECT * FROM local_state WHERE id = 0")
    suspend fun localState(): LocalStateEntity?

    @Query("SELECT * FROM pending_commands ORDER BY deviceSequence")
    suspend fun pendingCommands(): List<PendingCommandEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertState(state: LocalStateEntity)

    @Update
    suspend fun updateState(state: LocalStateEntity)

    @Insert
    suspend fun insertCommand(command: PendingCommandEntity)

    @Delete
    suspend fun deleteCommands(commands: List<PendingCommandEntity>)

    @Query("DELETE FROM pending_commands")
    suspend fun deleteAllCommands()

    @Transaction
    suspend fun persistCommand(command: PendingCommandEntity, state: LocalStateEntity) {
        insertCommand(command)
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
    suspend fun clearAccount(state: LocalStateEntity) {
        deleteAllCommands()
        updateState(state)
    }
}

@Database(
    entities = [LocalStateEntity::class, PendingCommandEntity::class],
    version = 2,
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
            ).addMigrations(Migration1To2).build()

        val Migration1To2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE local_state ADD COLUMN ownerUserId TEXT")
            }
        }
    }
}
