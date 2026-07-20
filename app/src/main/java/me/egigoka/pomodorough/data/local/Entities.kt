package me.egigoka.pomodorough.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import me.egigoka.pomodorough.data.TimerCommand

@Entity(tableName = "local_state")
data class LocalStateEntity(
    @PrimaryKey val id: Int = 0,
    val deviceId: String,
    val deviceSequence: Long = 0,
    val hlcWallMs: Long = 0,
    val hlcCounter: Long = 0,
    val revision: Long = 0,
    val canonicalTimerJson: String? = null,
    val historyJson: String = "[]",
    val settingsJson: String,
    val userJson: String? = null,
    val ownerUserId: String? = null,
)

@Entity(tableName = "pending_commands")
data class PendingCommandEntity(
    @PrimaryKey val id: String,
    val deviceSequence: Long,
    val timerId: String,
    val type: String,
    val phase: String,
    val plannedDurationMs: Long,
    val occurredAt: String,
    val hlcWallMs: Long,
    val hlcCounter: Long,
    val observedElapsedMs: Long,
) {
    fun toModel() = TimerCommand(
        id = id,
        deviceSequence = deviceSequence,
        timerId = timerId,
        type = type,
        phase = phase,
        plannedDurationMs = plannedDurationMs,
        occurredAt = occurredAt,
        hlcWallMs = hlcWallMs,
        hlcCounter = hlcCounter,
        observedElapsedMs = observedElapsedMs,
    )

    companion object {
        fun from(command: TimerCommand) = PendingCommandEntity(
            id = command.id,
            deviceSequence = command.deviceSequence,
            timerId = command.timerId,
            type = command.type,
            phase = command.phase,
            plannedDurationMs = command.plannedDurationMs,
            occurredAt = command.occurredAt,
            hlcWallMs = command.hlcWallMs,
            hlcCounter = command.hlcCounter,
            observedElapsedMs = command.observedElapsedMs,
        )
    }
}
