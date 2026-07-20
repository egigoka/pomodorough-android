package me.egigoka.pomodorough.unit.positive

import me.egigoka.pomodorough.data.CommandType
import me.egigoka.pomodorough.data.TimerCommand
import me.egigoka.pomodorough.data.TimerIntent
import me.egigoka.pomodorough.data.TimerPhase
import me.egigoka.pomodorough.data.TimerStatus
import me.egigoka.pomodorough.domain.TimerReducer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TimerReducerPositiveTest {
    @Test
    fun replayCompletesLifecycleInDeviceSequenceOrder() {
        val start = command(
            sequence = 1,
            type = CommandType.Start,
            occurredAt = "2026-01-01T00:00:00Z",
        )
        val pause = command(
            sequence = 2,
            type = CommandType.Pause,
            occurredAt = "2026-01-01T00:01:00Z",
            observedElapsedMs = 60_000,
        )
        val resume = command(
            sequence = 3,
            type = CommandType.Resume,
            occurredAt = "2026-01-01T00:02:00Z",
            observedElapsedMs = 60_000,
        )
        val finish = command(
            sequence = 4,
            type = CommandType.Finish,
            occurredAt = "2026-01-01T00:05:00Z",
            observedElapsedMs = 300_000,
        )

        val projection = TimerReducer.replay(
            canonicalTimer = null,
            canonicalHistory = emptyList(),
            commands = listOf(finish, resume, pause, start),
        )

        assertEquals(TimerStatus.Completed, projection.timer?.status)
        assertEquals(300_000L, projection.timer?.elapsedAtAnchorMs)
        assertEquals(
            TimerIntent(CommandType.Finish, "command-4", "2026-01-01T00:05:00Z"),
            projection.timer?.lastIntent,
        )
        assertEquals(1, projection.history.size)
        assertEquals("timer-1:command-4", projection.history.single().id)
        assertEquals("command-4", projection.history.single().commandId)
        assertTrue(projection.history.single().pending)
    }

    private fun command(
        sequence: Long,
        type: String,
        occurredAt: String,
        observedElapsedMs: Long = 0,
    ) = TimerCommand(
        id = "command-$sequence",
        deviceSequence = sequence,
        timerId = "timer-1",
        type = type,
        phase = TimerPhase.ShortBreak,
        plannedDurationMs = 300_000,
        occurredAt = occurredAt,
        hlcWallMs = 1_767_225_600_000 + sequence,
        hlcCounter = 0,
        observedElapsedMs = observedElapsedMs,
    )
}
