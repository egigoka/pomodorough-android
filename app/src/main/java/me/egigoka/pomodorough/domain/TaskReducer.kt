package me.egigoka.pomodorough.domain

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.text.Normalizer
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import me.egigoka.pomodorough.data.FocusTask
import me.egigoka.pomodorough.data.HistoryItem
import me.egigoka.pomodorough.data.TaskDailySummary
import me.egigoka.pomodorough.data.TaskOperation
import me.egigoka.pomodorough.data.TaskOperationType
import me.egigoka.pomodorough.data.TimerPhase
import me.egigoka.pomodorough.data.TimerStatus

object TaskReducer {
    private const val Namespace = "pomodorough.task.v1\u0000"

    fun taskFromTitle(value: String): FocusTask? {
        val title = normalizeTitle(value)
        if (title.isEmpty() || title.toByteArray(StandardCharsets.UTF_8).size > 512) return null
        return FocusTask(id = idForTitle(title), title = title)
    }

    fun normalizeTitle(value: String): String {
        val normalized = Normalizer.normalize(value, Normalizer.Form.NFC)
        var start = 0
        while (start < normalized.length) {
            val codePoint = normalized.codePointAt(start)
            if (isPrintable(codePoint)) break
            start += Character.charCount(codePoint)
        }
        var end = normalized.length
        while (end > start) {
            val codePoint = normalized.codePointBefore(end)
            if (isPrintable(codePoint)) break
            end -= Character.charCount(codePoint)
        }
        return normalized.substring(start, end)
    }

    fun idForTitle(title: String): String {
        val normalized = normalizeTitle(title)
        val digest = MessageDigest.getInstance("SHA-256")
            .digest((Namespace + normalized).toByteArray(StandardCharsets.UTF_8))
            .copyOf(16)
        digest[6] = ((digest[6].toInt() and 0x0f) or 0x80).toByte()
        digest[8] = ((digest[8].toInt() and 0x3f) or 0x80).toByte()
        val buffer = ByteBuffer.wrap(digest)
        return UUID(buffer.long, buffer.long).toString()
    }

    fun replay(base: List<FocusTask>, operations: List<TaskOperation>): List<FocusTask> {
        val tasks = base.associateByTo(linkedMapOf(), FocusTask::id)
        operations.sortedWith(
            compareBy<TaskOperation> { it.hlcWallMs }
                .thenBy { it.hlcCounter }
                .thenBy { it.id },
        ).forEach { operation ->
            when (operation.type) {
                TaskOperationType.Upsert -> operation.title
                    ?.let(::taskFromTitle)
                    ?.takeIf { it.id == operation.taskId }
                    ?.let { tasks[operation.taskId] = it }
                TaskOperationType.Delete -> tasks.remove(operation.taskId)
            }
        }
        return tasks.values.sortedWith(compareBy<FocusTask> { it.title }.thenBy { it.id })
    }

    fun summariesToday(
        tasks: List<FocusTask>,
        history: List<HistoryItem>,
        now: Instant = Instant.now(),
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): List<TaskDailySummary> {
        val today = now.atZone(zoneId).toLocalDate()
        val totals = mutableMapOf<String, Pair<Int, Long>>()
        history.forEach { item ->
            val taskId = item.taskId ?: return@forEach
            if (item.status != TimerStatus.Completed || item.phase != TimerPhase.Focus) return@forEach
            val ended = item.completedAt ?: item.endedAt ?: return@forEach
            val date = runCatching { Instant.parse(ended).atZone(zoneId).toLocalDate() }.getOrNull()
                ?: return@forEach
            if (date != today) return@forEach
            val current = totals[taskId] ?: (0 to 0L)
            totals[taskId] = current.first + 1 to current.second + item.plannedDurationMs
        }
        return tasks.map { task ->
            val total = totals[task.id] ?: (0 to 0L)
            TaskDailySummary(task, total.first, total.second)
        }
    }

    private fun isPrintable(codePoint: Int): Boolean {
        if (codePoint == 0x20) return true
        return Character.getType(codePoint) in printableTypes
    }

    private val printableTypes = setOf(
        Character.UPPERCASE_LETTER.toInt(),
        Character.LOWERCASE_LETTER.toInt(),
        Character.TITLECASE_LETTER.toInt(),
        Character.MODIFIER_LETTER.toInt(),
        Character.OTHER_LETTER.toInt(),
        Character.NON_SPACING_MARK.toInt(),
        Character.COMBINING_SPACING_MARK.toInt(),
        Character.ENCLOSING_MARK.toInt(),
        Character.DECIMAL_DIGIT_NUMBER.toInt(),
        Character.LETTER_NUMBER.toInt(),
        Character.OTHER_NUMBER.toInt(),
        Character.CONNECTOR_PUNCTUATION.toInt(),
        Character.DASH_PUNCTUATION.toInt(),
        Character.START_PUNCTUATION.toInt(),
        Character.END_PUNCTUATION.toInt(),
        Character.INITIAL_QUOTE_PUNCTUATION.toInt(),
        Character.FINAL_QUOTE_PUNCTUATION.toInt(),
        Character.OTHER_PUNCTUATION.toInt(),
        Character.MATH_SYMBOL.toInt(),
        Character.CURRENCY_SYMBOL.toInt(),
        Character.MODIFIER_SYMBOL.toInt(),
        Character.OTHER_SYMBOL.toInt(),
    )
}
