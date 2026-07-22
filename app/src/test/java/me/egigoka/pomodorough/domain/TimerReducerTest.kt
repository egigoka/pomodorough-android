package me.egigoka.pomodorough.domain

import me.egigoka.pomodorough.data.CanonicalTimer
import me.egigoka.pomodorough.data.CommandType
import me.egigoka.pomodorough.data.TimerCommand
import me.egigoka.pomodorough.data.TimerPhase
import me.egigoka.pomodorough.data.TimerStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TimerReducerTest {
    @Test
    fun replayOrdersPendingCommandsByDeviceSequence() {
        val start = command(sequence = 1, type = CommandType.Start, occurredAt = "2026-01-01T00:00:00Z")
        val pause = command(
            sequence = 2,
            type = CommandType.Pause,
            occurredAt = "2026-01-01T00:05:00Z",
            observedElapsedMs = 300_000,
        )

        val projection = TimerReducer.replay(null, emptyList(), listOf(pause, start))

        assertEquals(TimerStatus.Paused, projection.timer?.status)
        assertEquals(300_000L, projection.timer?.elapsedAtAnchorMs)
    }

    @Test
    fun elapsedAddsWallTimeAndClampsToDuration() {
        val timer = timer(
            status = TimerStatus.Running,
            durationMs = 600_000,
            elapsedMs = 540_000,
            anchorAt = "2026-01-01T00:00:00Z",
        )

        assertEquals(600_000, TimerReducer.elapsedAt(timer, 1_767_225_700_000))
        assertEquals(540_000, TimerReducer.elapsedAt(timer, 1_767_225_500_000))
    }

    @Test
    fun finishCreatesQueuedCompletion() {
        val running = timer(status = TimerStatus.Running, taskId = "task-1")
        val finish = command(
            sequence = 1,
            type = CommandType.Finish,
            occurredAt = "2026-01-01T00:25:00Z",
            observedElapsedMs = 1_500_000,
        )

        val projection = TimerReducer.replay(running, emptyList(), listOf(finish))

        assertEquals(TimerStatus.Completed, projection.timer?.status)
        assertEquals(1_500_000L, projection.timer?.elapsedAtAnchorMs)
        assertEquals(1, projection.history.size)
        assertEquals("timer-1:command-1", projection.history.single().id)
        assertTrue(projection.history.single().pending)
        assertEquals("task-1", projection.history.single().taskId)
    }

    @Test
    fun supersededTimerCanResume() {
        val superseded = timer(status = TimerStatus.Superseded, elapsedMs = 120_000)
        val resume = command(
            sequence = 1,
            type = CommandType.Resume,
            observedElapsedMs = 180_000,
        )

        val projection = TimerReducer.replay(superseded, emptyList(), listOf(resume))

        assertEquals(TimerStatus.Running, projection.timer?.status)
        assertEquals(180_000L, projection.timer?.elapsedAtAnchorMs)
    }

    @Test
    fun clearOnlyRemovesTerminalTimer() {
        val clear = command(sequence = 1, type = CommandType.Clear)

        assertNull(TimerReducer.replay(timer(TimerStatus.Completed), emptyList(), listOf(clear)).timer)
        assertEquals(
            TimerStatus.Running,
            TimerReducer.replay(timer(TimerStatus.Running), emptyList(), listOf(clear)).timer?.status,
        )
    }

    @Test
    fun startReplacesCurrentTimer() {
        val start = command(
            sequence = 1,
            type = CommandType.Start,
            timerId = "replacement",
            phase = TimerPhase.ShortBreak,
            plannedDurationMs = 300_000,
        )

        val projection = TimerReducer.replay(timer(TimerStatus.Running), emptyList(), listOf(start))

        assertEquals("replacement", projection.timer?.id)
        assertEquals(TimerPhase.ShortBreak, projection.timer?.phase)
        assertEquals(300_000L, projection.timer?.plannedDurationMs)
        assertEquals(TimerStatus.Running, projection.timer?.status)
    }

    @Test
    fun mismatchedTimerCommandDoesNotChangeProjection() {
        val canonical = timer(TimerStatus.Running)
        val pause = command(
            sequence = 1,
            type = CommandType.Pause,
            timerId = "different-timer",
            observedElapsedMs = 60_000,
        )

        val projection = TimerReducer.replay(canonical, emptyList(), listOf(pause))

        assertSame(canonical, projection.timer)
    }

    @Test
    fun observedElapsedIsClampedForPauseAndCancel() {
        val pause = command(
            sequence = 1,
            type = CommandType.Pause,
            observedElapsedMs = 9_000_000,
        )
        val paused = TimerReducer.replay(timer(TimerStatus.Running), emptyList(), listOf(pause)).timer
        val cancel = command(
            sequence = 2,
            type = CommandType.Cancel,
            observedElapsedMs = -1,
        )

        val cancelled = TimerReducer.replay(paused, emptyList(), listOf(cancel)).timer

        assertEquals(1_500_000L, paused?.elapsedAtAnchorMs)
        assertEquals(0L, cancelled?.elapsedAtAnchorMs)
    }

    @Test
    fun duplicateFinishCommandCreatesOneHistoryEntry() {
        val finish = command(sequence = 1, type = CommandType.Finish)

        val projection = TimerReducer.replay(
            timer(TimerStatus.Running),
            emptyList(),
            listOf(finish, finish.copy(deviceSequence = 2)),
        )

        assertEquals(1, projection.history.size)
        assertEquals("command-1", projection.history.single().commandId)
    }

    @Test
    fun invalidAnchorKeepsStoredElapsed() {
        val timer = timer(
            status = TimerStatus.Running,
            elapsedMs = 42_000,
            anchorAt = "not-an-instant",
        )

        assertEquals(42_000, TimerReducer.elapsedAt(timer, Long.MAX_VALUE))
    }

    @Test
    fun pausedTimerDoesNotAccumulateWallTime() {
        val paused = timer(
            status = TimerStatus.Paused,
            elapsedMs = 120_000,
            anchorAt = "2020-01-01T00:00:00Z",
        )

        assertEquals(120_000, TimerReducer.elapsedAt(paused, Long.MAX_VALUE))
    }

    @Test
    fun unknownCommandLeavesStateAndHistoryUnchanged() {
        val canonical = timer(TimerStatus.Paused)
        val history = listOf(
            me.egigoka.pomodorough.data.HistoryItem(
                id = "history-1",
                timerId = "old-timer",
                phase = TimerPhase.Focus,
                status = TimerStatus.Completed,
                plannedDurationMs = 1_500_000,
            ),
        )

        val projection = TimerReducer.replay(
            canonical,
            history,
            listOf(command(sequence = 1, type = "unsupported")),
        )

        assertSame(canonical, projection.timer)
        assertEquals(history, projection.history)
    }

    private fun timer(
        status: String,
        durationMs: Long = 1_500_000,
        elapsedMs: Long = 0,
        anchorAt: String = "2026-01-01T00:00:00Z",
        taskId: String? = null,
    ) = CanonicalTimer(
        id = "timer-1",
        phase = TimerPhase.Focus,
        status = status,
        plannedDurationMs = durationMs,
        elapsedAtAnchorMs = elapsedMs,
        anchorAt = anchorAt,
        taskId = taskId,
    )

    private fun command(
        sequence: Long,
        type: String,
        timerId: String = "timer-1",
        phase: String = TimerPhase.Focus,
        plannedDurationMs: Long = 1_500_000,
        occurredAt: String = "2026-01-01T00:10:00Z",
        observedElapsedMs: Long = 0,
    ) = TimerCommand(
        id = "command-$sequence",
        deviceSequence = sequence,
        timerId = timerId,
        type = type,
        phase = phase,
        plannedDurationMs = plannedDurationMs,
        occurredAt = occurredAt,
        hlcWallMs = 1_767_225_600_000 + sequence,
        hlcCounter = 0,
        observedElapsedMs = observedElapsedMs,
    )
}
