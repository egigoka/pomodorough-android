package me.egigoka.pomodorough.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Required
import kotlinx.serialization.Serializable

object TimerPhase {
    const val Focus = "focus"
    const val ShortBreak = "short_break"
    const val LongBreak = "long_break"

    val all = listOf(Focus, ShortBreak, LongBreak)
}

object TimerStatus {
    const val Running = "running"
    const val Paused = "paused"
    const val Completed = "completed"
    const val Cancelled = "cancelled"
    const val Superseded = "superseded"
}

object CommandType {
    const val Start = "start"
    const val Pause = "pause"
    const val Resume = "resume"
    const val Finish = "finish"
    const val Cancel = "cancel"
    const val Clear = "clear"
}

@Serializable
data class NativeChallenge(
    val challenge: String,
    val nonce: String,
    val expiresAt: String,
)

@Serializable
data class NativeExchangeRequest(
    val idToken: String,
    val challenge: String,
    val deviceId: String,
    val platform: String,
)

@Serializable
data class RefreshRequest(val refreshToken: String)

@Serializable
data class TokenPair(
    val accessToken: String,
    val accessTokenExpiresAt: String,
    val refreshToken: String,
    val refreshTokenExpiresAt: String,
)

@Serializable
data class User(
    val id: String,
    val email: String,
    val name: String,
    val avatarUrl: String,
)

@Serializable
data class MeResponse(
    val user: User,
    val csrfToken: String,
)

@Serializable
data class TimerIntent(
    val type: String,
    val commandId: String,
    val occurredAt: String,
)

@Serializable
data class CanonicalTimer(
    val id: String,
    val phase: String,
    val status: String,
    val plannedDurationMs: Long,
    val elapsedAtAnchorMs: Long,
    val anchorAt: String,
    val taskId: String? = null,
    val lastIntent: TimerIntent? = null,
)

@Serializable
data class TimerCommand(
    val id: String,
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
)

@Serializable
data class HistoryItem(
    val id: String,
    val timerId: String,
    val commandId: String? = null,
    val phase: String,
    val status: String,
    val plannedDurationMs: Long,
    val completedAt: String? = null,
    val endedAt: String? = null,
    val pending: Boolean = false,
    val taskId: String? = null,
)

@Serializable
data class Acknowledgement(
    val commandId: String,
    val outcome: String,
    val reason: String,
)

object DurationLimits {
    const val MinuteMs = 60_000L
    const val MinMs = 60_000L
    const val MaxMs = 10_800_000L

    fun isValid(durationMs: Long): Boolean =
        durationMs in MinMs..MaxMs && durationMs % MinuteMs == 0L
}

@Serializable
data class DurationsMs(
    val focus: Long = 1_500_000L,
    @SerialName("short_break") val shortBreak: Long = 300_000L,
    @SerialName("long_break") val longBreak: Long = 900_000L,
) {
    fun forPhase(phase: String): Long = when (phase) {
        TimerPhase.ShortBreak -> shortBreak
        TimerPhase.LongBreak -> longBreak
        else -> focus
    }

    fun withDuration(phase: String, durationMs: Long): DurationsMs = when (phase) {
        TimerPhase.ShortBreak -> copy(shortBreak = durationMs)
        TimerPhase.LongBreak -> copy(longBreak = durationMs)
        else -> copy(focus = durationMs)
    }

    fun isValid(): Boolean = listOf(focus, shortBreak, longBreak).all {
        DurationLimits.isValid(it)
    }
}

@Serializable
data class DurationOperation(
    val id: String,
    val phase: String,
    val durationMs: Long,
    val occurredAt: String,
    val hlcWallMs: Long,
    val hlcCounter: Long,
)

@Serializable
data class DurationAcknowledgement(
    val operationId: String,
    val outcome: String,
    val reason: String,
)

@Serializable
data class AutoStartOperation(
    val id: String,
    val deviceId: String,
    val enabled: Boolean,
    val occurredAt: String,
    val hlcWallMs: Long,
    val hlcCounter: Long,
)

@Serializable
data class AutoStartAcknowledgement(
    val operationId: String,
    val outcome: String,
    val reason: String,
)

object TaskOperationType {
    const val Upsert = "upsert"
    const val Delete = "delete"
}

@Serializable
data class FocusTask(
    val id: String,
    val title: String,
)

@Serializable
data class TaskOperation(
    val id: String,
    val taskId: String,
    val type: String,
    val title: String? = null,
    val occurredAt: String,
    val hlcWallMs: Long,
    val hlcCounter: Long,
)

@Serializable
data class TaskAcknowledgement(
    val operationId: String,
    val outcome: String,
    val reason: String,
)

data class TaskDailySummary(
    val task: FocusTask,
    val finishedPomodoros: Int,
    val timeSpentMs: Long,
)

@Serializable
data class SyncRequest(
    val deviceId: String,
    val lastRevision: Long,
    val commands: List<TimerCommand>,
    val durationOperations: List<DurationOperation>,
    val taskOperations: List<TaskOperation> = emptyList(),
    val autoStartOperations: List<AutoStartOperation> = emptyList(),
)

@Serializable
enum class BootstrapStrategy {
    @SerialName("keep_remote")
    KeepRemote,

    @SerialName("replace_remote")
    ReplaceRemote,

    @SerialName("merge")
    Merge,
}

enum class ResolutionRecovery { KeepRemote, Repreview }

@Serializable
data class BootstrapResolutionRequest(
    val requestId: String,
    val deviceId: String,
    val expectedRevision: Long,
    val strategy: BootstrapStrategy,
    val commands: List<TimerCommand>,
    val taskOperations: List<TaskOperation>,
    val durationOperations: List<DurationOperation>,
    val autoStartOperations: List<AutoStartOperation>? = null,
)

data class HistoryResolutionState(
    val localHistoryCount: Int,
    val remoteHistoryCount: Int,
    val pendingStrategy: BootstrapStrategy? = null,
    val requestId: String? = null,
    val submitting: Boolean = false,
    val corrupted: Boolean = false,
    val recovery: ResolutionRecovery? = null,
    val error: String? = null,
)

data class AccountSwitchState(
    val localAccount: String,
    val incomingAccount: String,
    val submitting: Boolean = false,
    val error: String? = null,
)

@Serializable
data class SyncResponse(
    val acknowledgements: List<Acknowledgement>,
    val revision: Long,
    val canonicalTimer: CanonicalTimer?,
    val history: List<HistoryItem>,
    val serverTime: String,
    val serverHlcWallMs: Long,
    val serverHlcCounter: Long,
    val durationAcknowledgements: List<DurationAcknowledgement>,
    val durationsMs: DurationsMs,
    val taskAcknowledgements: List<TaskAcknowledgement>,
    val tasks: List<FocusTask>,
    @Required val autoStartAcknowledgements: List<AutoStartAcknowledgement> = emptyList(),
    @Required val autoStartBreaks: Boolean = false,
)

@Serializable
data class HistoryResponse(val history: List<HistoryItem>)

@Serializable
data class ApiError(val error: String)

@Serializable
data class TimerSettings(
    val selectedPhase: String = TimerPhase.Focus,
    val focusMinutes: Int = 25,
    val shortBreakMinutes: Int = 5,
    val longBreakMinutes: Int = 15,
    val autoStartBreaks: Boolean = false,
    val durationsMs: DurationsMs? = null,
) {
    fun effectiveDurationsMs() = durationsMs ?: DurationsMs(
        focus = focusMinutes * 60_000L,
        shortBreak = shortBreakMinutes * 60_000L,
        longBreak = longBreakMinutes * 60_000L,
    )

    fun durationMsFor(phase: String): Long = effectiveDurationsMs().forPhase(phase)

    fun minutesFor(phase: String): Int = (durationMsFor(phase) / 60_000L).toInt()

    fun withMinutes(phase: String, minutes: Int): TimerSettings {
        return withDuration(phase, minutes.coerceIn(1, 180) * 60_000L)
    }

    fun withDuration(phase: String, durationMs: Long): TimerSettings {
        val bounded = durationMs.coerceIn(DurationLimits.MinMs, DurationLimits.MaxMs)
        return withDurations(effectiveDurationsMs().withDuration(phase, bounded))
    }

    fun withDurations(next: DurationsMs): TimerSettings {
        return copy(
            focusMinutes = (next.focus / 60_000L).toInt(),
            shortBreakMinutes = (next.shortBreak / 60_000L).toInt(),
            longBreakMinutes = (next.longBreak / 60_000L).toInt(),
            durationsMs = next,
        )
    }
}

data class TimerProjection(
    val timer: CanonicalTimer?,
    val history: List<HistoryItem>,
)
