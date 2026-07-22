package me.egigoka.pomodorough.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import me.egigoka.pomodorough.data.DurationOperation
import me.egigoka.pomodorough.data.TaskOperation
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
    val tasksJson: String = "[]",
    val knownTasksJson: String = "[]",
    val selectedTaskId: String? = null,
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
    val taskId: String? = null,
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
        taskId = taskId,
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
            taskId = command.taskId,
        )
    }
}

@Entity(tableName = "pending_task_operations")
data class PendingTaskOperationEntity(
    @PrimaryKey val id: String,
    val taskId: String,
    val type: String,
    val title: String?,
    val occurredAt: String,
    val hlcWallMs: Long,
    val hlcCounter: Long,
) {
    fun toModel() = TaskOperation(
        id = id,
        taskId = taskId,
        type = type,
        title = title,
        occurredAt = occurredAt,
        hlcWallMs = hlcWallMs,
        hlcCounter = hlcCounter,
    )

    companion object {
        fun from(operation: TaskOperation) = PendingTaskOperationEntity(
            id = operation.id,
            taskId = operation.taskId,
            type = operation.type,
            title = operation.title,
            occurredAt = operation.occurredAt,
            hlcWallMs = operation.hlcWallMs,
            hlcCounter = operation.hlcCounter,
        )
    }
}

@Entity(
    tableName = "pending_duration_operations",
    indices = [Index(value = ["id"], unique = true)],
)
data class PendingDurationOperationEntity(
    @PrimaryKey val phase: String,
    val id: String,
    val durationMs: Long,
    val occurredAt: String,
    val hlcWallMs: Long,
    val hlcCounter: Long,
) {
    fun toModel() = DurationOperation(
        id = id,
        phase = phase,
        durationMs = durationMs,
        occurredAt = occurredAt,
        hlcWallMs = hlcWallMs,
        hlcCounter = hlcCounter,
    )

    companion object {
        fun from(operation: DurationOperation) = PendingDurationOperationEntity(
            phase = operation.phase,
            id = operation.id,
            durationMs = operation.durationMs,
            occurredAt = operation.occurredAt,
            hlcWallMs = operation.hlcWallMs,
            hlcCounter = operation.hlcCounter,
        )
    }
}
