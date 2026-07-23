package me.egigoka.pomodorough.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import me.egigoka.pomodorough.data.AutoStartOperation
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
    val canonicalAutoStartBreaks: Boolean = false,
    val ownedTimerId: String? = null,
)

@Entity(tableName = "pending_bootstrap_resolution")
data class PendingBootstrapResolutionEntity(
    @PrimaryKey val id: Int = 0,
    val requestId: String,
    val deviceId: String,
    val expectedRevision: Long,
    val strategy: String,
    val commandsJson: String,
    val taskOperationsJson: String,
    val durationOperationsJson: String,
    val ownerUserId: String,
    val userJson: String,
    val autoStartOperationsJson: String? = null,
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
    val generatedByFinishCommandId: String? = null,
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
        fun from(
            command: TimerCommand,
            generatedByFinishCommandId: String? = null,
        ) = PendingCommandEntity(
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
            generatedByFinishCommandId = generatedByFinishCommandId,
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

@Entity(tableName = "pending_auto_start_operations")
data class PendingAutoStartOperationEntity(
    @PrimaryKey val id: String,
    val deviceId: String,
    val enabled: Boolean,
    val occurredAt: String,
    val hlcWallMs: Long,
    val hlcCounter: Long,
) {
    fun toModel() = AutoStartOperation(
        id = id,
        deviceId = deviceId,
        enabled = enabled,
        occurredAt = occurredAt,
        hlcWallMs = hlcWallMs,
        hlcCounter = hlcCounter,
    )

    companion object {
        fun from(operation: AutoStartOperation) = PendingAutoStartOperationEntity(
            id = operation.id,
            deviceId = operation.deviceId,
            enabled = operation.enabled,
            occurredAt = operation.occurredAt,
            hlcWallMs = operation.hlcWallMs,
            hlcCounter = operation.hlcCounter,
        )
    }
}
