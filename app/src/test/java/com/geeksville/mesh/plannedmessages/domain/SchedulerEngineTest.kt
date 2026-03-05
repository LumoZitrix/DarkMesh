package com.geeksville.mesh.plannedmessages.domain

import com.geeksville.mesh.plannedmessages.data.PlannedMessageEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class SchedulerEngineTest {

    private val engine = SchedulerEngine()

    @Test
    fun `weekly schedule keeps same day when time is still ahead`() {
        val zoneId = ZoneId.of("Europe/Rome")
        val now = LocalDate.of(2026, 2, 9).atTime(12, 0).atZone(zoneId).toInstant().toEpochMilli() // Monday
        val message = weeklyMessage(
            day = DayOfWeek.MONDAY,
            hour = 13,
            minute = 0,
            timezoneId = zoneId.id,
        )

        val next = engine.computeNextTriggerUtcEpochMs(message, now)
        val nextLocal = Instant.ofEpochMilli(next!!).atZone(zoneId)

        assertEquals(LocalDate.of(2026, 2, 9), nextLocal.toLocalDate())
        assertEquals(13, nextLocal.hour)
        assertEquals(0, nextLocal.minute)
    }

    @Test
    fun `weekly schedule rolls to next selected day when already passed`() {
        val zoneId = ZoneId.of("Europe/Rome")
        val now = LocalDate.of(2026, 2, 9).atTime(14, 0).atZone(zoneId).toInstant().toEpochMilli() // Monday
        val message = weeklyMessage(
            day = DayOfWeek.MONDAY,
            hour = 13,
            minute = 0,
            timezoneId = zoneId.id,
        )

        val next = engine.computeNextTriggerUtcEpochMs(message, now)
        val nextLocal = Instant.ofEpochMilli(next!!).atZone(zoneId)

        assertEquals(LocalDate.of(2026, 2, 16), nextLocal.toLocalDate())
        assertEquals(13, nextLocal.hour)
    }

    @Test
    fun `dst forward keeps weekly schedule valid by moving to next valid local time`() {
        val zoneId = ZoneId.of("Europe/Rome")
        val now = LocalDate.of(2026, 3, 28).atTime(10, 0).atZone(zoneId).toInstant().toEpochMilli()
        val message = weeklyMessage(
            day = DayOfWeek.SUNDAY,
            hour = 2,
            minute = 30,
            timezoneId = zoneId.id,
        )

        val next = engine.computeNextTriggerUtcEpochMs(message, now)
        val nextLocal = Instant.ofEpochMilli(next!!).atZone(zoneId)

        assertEquals(LocalDate.of(2026, 3, 29), nextLocal.toLocalDate())
        assertEquals(3, nextLocal.hour)
        assertEquals(30, nextLocal.minute)
    }

    @Test
    fun `timezone snapshot drives next trigger computation`() {
        val timezone = ZoneId.of("America/New_York")
        val nowUtc = Instant.parse("2026-01-01T20:00:00Z").toEpochMilli() // 15:00 in New York
        val message = weeklyMessage(
            day = DayOfWeek.THURSDAY,
            hour = 16,
            minute = 0,
            timezoneId = timezone.id,
        )

        val next = engine.computeNextTriggerUtcEpochMs(message, nowUtc)
        val nextLocal = Instant.ofEpochMilli(next!!).atZone(timezone)

        assertEquals(DayOfWeek.THURSDAY, nextLocal.dayOfWeek)
        assertEquals(16, nextLocal.hour)
    }

    @Test
    fun `one shot skip policy disables overdue message`() {
        val now = Instant.parse("2026-01-01T12:00:00Z").toEpochMilli()
        val due = Instant.parse("2026-01-01T10:00:00Z").toEpochMilli()
        val message = oneShotMessage(
            oneShotAt = due,
            policy = PlannedMessageDeliveryPolicy.SKIP_MISSED,
        ).copy(nextTriggerAtUtcEpochMs = due)

        val outcome = engine.applyExecutionPolicy(message, now)

        assertFalse(outcome.shouldSend)
        assertFalse(outcome.updatedMessage.isEnabled)
        assertEquals(null, outcome.updatedMessage.nextTriggerAtUtcEpochMs)
    }

    @Test
    fun `one shot catch up policy sends overdue message once`() {
        val now = Instant.parse("2026-01-01T12:00:00Z").toEpochMilli()
        val due = Instant.parse("2026-01-01T10:00:00Z").toEpochMilli()
        val message = oneShotMessage(
            oneShotAt = due,
            policy = PlannedMessageDeliveryPolicy.CATCH_UP,
        ).copy(nextTriggerAtUtcEpochMs = due)

        val outcome = engine.applyExecutionPolicy(message, now)

        assertTrue(outcome.shouldSend)
        assertEquals(due, outcome.scheduledOccurrenceUtcEpochMs)
        assertFalse(outcome.updatedMessage.isEnabled)
        assertNotNull(outcome.updatedMessage.lastFiredAtUtcEpochMs)
    }

    private fun weeklyMessage(
        day: DayOfWeek,
        hour: Int,
        minute: Int,
        timezoneId: String,
        policy: PlannedMessageDeliveryPolicy = PlannedMessageDeliveryPolicy.SKIP_MISSED,
    ): PlannedMessageEntity {
        return PlannedMessageEntity(
            destinationKey = "1234",
            messageText = "test",
            scheduleType = PlannedMessageScheduleType.WEEKLY,
            daysOfWeekMask = WeeklyDays.maskFor(day),
            hourOfDay = hour,
            minuteOfHour = minute,
            oneShotAtUtcEpochMs = null,
            timezoneId = timezoneId,
            deliveryPolicy = policy,
            isEnabled = true,
            nextTriggerAtUtcEpochMs = null,
        )
    }

    private fun oneShotMessage(
        oneShotAt: Long,
        policy: PlannedMessageDeliveryPolicy,
    ): PlannedMessageEntity {
        return PlannedMessageEntity(
            destinationKey = "1234",
            messageText = "test",
            scheduleType = PlannedMessageScheduleType.ONE_SHOT,
            daysOfWeekMask = 0,
            hourOfDay = 0,
            minuteOfHour = 0,
            oneShotAtUtcEpochMs = oneShotAt,
            timezoneId = "UTC",
            deliveryPolicy = policy,
            isEnabled = true,
            nextTriggerAtUtcEpochMs = null,
        )
    }
}
