package me.egigoka.pomodorough.data

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
)

@Serializable
data class Acknowledgement(
    val commandId: String,
    val outcome: String,
    val reason: String,
)

@Serializable
data class SyncRequest(
    val deviceId: String,
    val lastRevision: Long,
    val commands: List<TimerCommand>,
)

@Serializable
data class SyncResponse(
    val acknowledgements: List<Acknowledgement>,
    val revision: Long,
    val canonicalTimer: CanonicalTimer?,
    val history: List<HistoryItem>,
    val serverTime: String,
    val serverHlcWallMs: Long,
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
) {
    fun minutesFor(phase: String): Int = when (phase) {
        TimerPhase.ShortBreak -> shortBreakMinutes
        TimerPhase.LongBreak -> longBreakMinutes
        else -> focusMinutes
    }

    fun withMinutes(phase: String, minutes: Int): TimerSettings {
        val bounded = minutes.coerceIn(1, 180)
        return when (phase) {
            TimerPhase.ShortBreak -> copy(shortBreakMinutes = bounded)
            TimerPhase.LongBreak -> copy(longBreakMinutes = bounded)
            else -> copy(focusMinutes = bounded)
        }
    }
}

data class TimerProjection(
    val timer: CanonicalTimer?,
    val history: List<HistoryItem>,
)
