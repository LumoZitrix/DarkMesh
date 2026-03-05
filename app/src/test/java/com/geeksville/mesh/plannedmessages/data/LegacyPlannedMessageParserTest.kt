package com.geeksville.mesh.plannedmessages.data

import com.geeksville.mesh.plannedmessages.domain.WeeklyDays
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.DayOfWeek

class LegacyPlannedMessageParserTest {

    private val parser = LegacyPlannedMessageParser()

    @Test
    fun `parses italian legacy line with em dash`() {
        val parsed = parser.parseLine("LUN 13:00 — Hello world")

        assertNotNull(parsed)
        assertEquals(WeeklyDays.maskFor(DayOfWeek.MONDAY), parsed?.daysOfWeekMask)
        assertEquals(13, parsed?.hourOfDay)
        assertEquals(0, parsed?.minuteOfHour)
        assertEquals("Hello world", parsed?.messageText)
    }

    @Test
    fun `parses mojibake dash format from legacy prefs`() {
        val parsed = parser.parseLine("MAR 08:15 â€” check-in")

        assertNotNull(parsed)
        assertEquals(WeeklyDays.maskFor(DayOfWeek.TUESDAY), parsed?.daysOfWeekMask)
        assertEquals("check-in", parsed?.messageText)
    }

    @Test
    fun `rejects malformed lines`() {
        assertNull(parser.parseLine("INVALID LINE"))
        assertNull(parser.parseLine("LUN 26:99 - nope"))
        assertNull(parser.parseLine("XXX 12:00 - nope"))
    }
}
