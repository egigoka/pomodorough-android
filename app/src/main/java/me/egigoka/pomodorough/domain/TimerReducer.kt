package me.egigoka.pomodorough.domain

import java.time.Instant
import me.egigoka.pomodorough.data.CanonicalTimer
import me.egigoka.pomodorough.data.CommandType
import me.egigoka.pomodorough.data.HistoryItem
import me.egigoka.pomodorough.data.TimerCommand
import me.egigoka.pomodorough.data.TimerIntent
import me.egigoka.pomodorough.data.TimerProjection
import me.egigoka.pomodorough.data.TimerStatus

object TimerReducer {
    fun replay(
        canonicalTimer: CanonicalTimer?,
        canonicalHistory: List<HistoryItem>,
        commands: List<TimerCommand>,
    ): TimerProjection {
        var timer = canonicalTimer
        val history = canonicalHistory.toMutableList()
        commands.sortedBy(TimerCommand::deviceSequence).forEach { command ->
            timer = reduce(timer, history, command)
        }
        return TimerProjection(timer, history)
    }

    fun elapsedAt(timer: CanonicalTimer?, nowMs: Long = System.currentTimeMillis()): Long {
        if (timer == null) return 0
        var elapsed = timer.elapsedAtAnchorMs.coerceIn(0, timer.plannedDurationMs)
        if (timer.status == TimerStatus.Running) {
            val anchorMs = runCatching { Instant.parse(timer.anchorAt).toEpochMilli() }.getOrNull()
            if (anchorMs != null) elapsed += (nowMs - anchorMs).coerceAtLeast(0)
        }
        return elapsed.coerceIn(0, timer.plannedDurationMs)
    }

    private fun reduce(
        current: CanonicalTimer?,
        history: MutableList<HistoryItem>,
        command: TimerCommand,
    ): CanonicalTimer? {
        val intent = TimerIntent(command.type, command.id, command.occurredAt)
        return when (command.type) {
            CommandType.Start -> CanonicalTimer(
                id = command.timerId,
                phase = command.phase,
                status = TimerStatus.Running,
                plannedDurationMs = command.plannedDurationMs,
                elapsedAtAnchorMs = 0,
                anchorAt = command.occurredAt,
                taskId = command.taskId,
                lastIntent = intent,
            )

            CommandType.Pause -> current?.takeIf {
                it.id == command.timerId && it.status == TimerStatus.Running
            }?.let { timer ->
                timer.copy(
                    status = TimerStatus.Paused,
                    elapsedAtAnchorMs = command.observedElapsedMs.coerceIn(0, timer.plannedDurationMs),
                    anchorAt = command.occurredAt,
                    lastIntent = intent,
                )
            } ?: current

            CommandType.Resume -> current?.takeIf {
                it.id == command.timerId &&
                    (it.status == TimerStatus.Paused || it.status == TimerStatus.Superseded)
            }?.let { timer ->
                timer.copy(
                    status = TimerStatus.Running,
                    elapsedAtAnchorMs = command.observedElapsedMs.coerceIn(0, timer.plannedDurationMs),
                    anchorAt = command.occurredAt,
                    lastIntent = intent,
                )
            } ?: current

            CommandType.Finish -> current?.takeIf {
                it.id == command.timerId && it.status in activeStatuses
            }?.let { timer ->
                timer.copy(
                    status = TimerStatus.Completed,
                    elapsedAtAnchorMs = timer.plannedDurationMs,
                    anchorAt = command.occurredAt,
                    lastIntent = intent,
                )
            }?.also { completedTimer ->
                if (history.none { item -> item.commandId == command.id }) {
                    history.add(
                        0,
                        HistoryItem(
                            id = "${command.timerId}:${command.id}",
                            timerId = command.timerId,
                            commandId = command.id,
                            phase = command.phase,
                            status = TimerStatus.Completed,
                            plannedDurationMs = command.plannedDurationMs,
                            completedAt = command.occurredAt,
                            endedAt = command.occurredAt,
                            pending = true,
                            taskId = completedTimer.taskId,
                        ),
                    )
                }
            } ?: current

            CommandType.Cancel -> current?.takeIf {
                it.id == command.timerId && it.status in activeStatuses
            }?.let { timer ->
                timer.copy(
                    status = TimerStatus.Cancelled,
                    elapsedAtAnchorMs = command.observedElapsedMs.coerceIn(0, timer.plannedDurationMs),
                    anchorAt = command.occurredAt,
                    lastIntent = intent,
                )
            } ?: current

            CommandType.Clear -> if (
                current?.id == command.timerId && current.status !in activeStatuses
            ) null else current

            else -> current
        }
    }

    private val activeStatuses = setOf(TimerStatus.Running, TimerStatus.Paused)
}
