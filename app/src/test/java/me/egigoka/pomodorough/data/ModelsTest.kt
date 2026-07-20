package me.egigoka.pomodorough.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ModelsTest {
    @Test
    fun durationChangesClampToSupportedUiRange() {
        val settings = TimerSettings()

        assertEquals(1, settings.withMinutes(TimerPhase.Focus, -10).focusMinutes)
        assertEquals(180, settings.withMinutes(TimerPhase.ShortBreak, 500).shortBreakMinutes)
    }

    @Test
    fun durationChangesOnlySelectedPhase() {
        val settings = TimerSettings()
            .withMinutes(TimerPhase.LongBreak, 20)

        assertEquals(25, settings.focusMinutes)
        assertEquals(5, settings.shortBreakMinutes)
        assertEquals(20, settings.longBreakMinutes)
    }

    @Test
    fun unknownPhaseFallsBackToFocus() {
        val settings = TimerSettings(focusMinutes = 40)

        assertEquals(40, settings.minutesFor("unknown"))
        assertEquals(50, settings.withMinutes("unknown", 50).focusMinutes)
    }
}
