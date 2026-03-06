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
    private val defaultSettings = PlannedMessageSettings()

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
    fun `dst backward keeps weekly schedule valid at repeated local time`() {
        val zoneId = ZoneId.of("Europe/Rome")
        val now = LocalDate.of(2026, 10, 24).atTime(10, 0).atZone(zoneId).toInstant().toEpochMilli()
        val message = weeklyMessage(
            day = DayOfWeek.SUNDAY,
            hour = 2,
            minute = 30,
            timezoneId = zoneId.id,
        )

        val next = engine.computeNextTriggerUtcEpochMs(message, now)
        val nextLocal = Instant.ofEpochMilli(next!!).atZone(zoneId)

        assertEquals(LocalDate.of(2026, 10, 25), nextLocal.toLocalDate())
        assertEquals(2, nextLocal.hour)
        assertEquals(30, nextLocal.minute)
    }

    @Test
    fun `one shot skip policy disables overdue message`() {
        val now = Instant.parse("2026-01-01T12:00:00Z").toEpochMilli()
        val due = Instant.parse("2026-01-01T10:00:00Z").toEpochMilli()
        val message = oneShotMessage(
            oneShotAt = due,
            policy = PlannedMessageDeliveryPolicy.SKIP_MISSED,
        ).copy(nextTriggerAtUtcEpochMs = due)

        val outcome = engine.applyExecutionPolicy(message, now, defaultSettings)

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

        val outcome = engine.applyExecutionPolicy(message, now, defaultSettings)

        assertTrue(outcome.shouldSend)
        assertEquals(due, outcome.scheduledOccurrenceUtcEpochMs)
        assertFalse(outcome.updatedMessage.isEnabled)
        assertNotNull(outcome.updatedMessage.lastFiredAtUtcEpochMs)
    }

    @Test
    fun `skip missed grace window is configurable for 5, 15 and 30 minutes`() {
        val due = Instant.parse("2026-01-01T10:00:00Z").toEpochMilli()
        val lateByTenMinutes = due + 10 * 60 * 1000L
        val message = oneShotMessage(
            oneShotAt = due,
            policy = PlannedMessageDeliveryPolicy.SKIP_MISSED,
        ).copy(nextTriggerAtUtcEpochMs = due)

        val grace5 = PlannedMessageSettings(skipMissedGraceMs = 5 * 60 * 1000L)
        val grace15 = PlannedMessageSettings(skipMissedGraceMs = 15 * 60 * 1000L)
        val grace30 = PlannedMessageSettings(skipMissedGraceMs = 30 * 60 * 1000L)

        val outcome5 = engine.applyExecutionPolicy(message, lateByTenMinutes, grace5)
        val outcome15 = engine.applyExecutionPolicy(message, lateByTenMinutes, grace15)
        val outcome30 = engine.applyExecutionPolicy(message, lateByTenMinutes, grace30)

        assertFalse(outcome5.shouldSend)
        assertTrue(outcome15.shouldSend)
        assertTrue(outcome30.shouldSend)
    }

    @Test
    fun `one shot skipped when beyond configured grace`() {
        val due = Instant.parse("2026-01-01T10:00:00Z").toEpochMilli()
        val lateByTwentyMinutes = due + 20 * 60 * 1000L
        val message = oneShotMessage(
            oneShotAt = due,
            policy = PlannedMessageDeliveryPolicy.SKIP_MISSED,
        ).copy(nextTriggerAtUtcEpochMs = due)

        val settings = PlannedMessageSettings(
            skipMissedGraceMs = 15 * 60 * 1000L,
            lateFireMode = LateFireMode.FIRE_IF_WITHIN_GRACE,
        )
        val outcome = engine.applyExecutionPolicy(
            message = message,
            nowUtcEpochMs = lateByTwentyMinutes,
            settings = settings,
        )

        assertFalse(outcome.shouldSend)
        assertFalse(outcome.updatedMessage.isEnabled)
        assertEquals(null, outcome.updatedMessage.nextTriggerAtUtcEpochMs)
    }

    @Test
    fun `weekly skipped beyond grace advances to next occurrence without touching last fired`() {
        val zoneId = ZoneId.of("Europe/Rome")
        val due = LocalDate.of(2026, 1, 5).atTime(8, 0).atZone(zoneId).toInstant().toEpochMilli() // Monday
        val now = due + 90 * 60 * 1000L // +90 minutes, beyond 15m grace
        val message = weeklyMessage(
            day = DayOfWeek.MONDAY,
            hour = 8,
            minute = 0,
            timezoneId = zoneId.id,
            policy = PlannedMessageDeliveryPolicy.SKIP_MISSED,
        ).copy(
            nextTriggerAtUtcEpochMs = due,
            lastFiredAtUtcEpochMs = null,
        )
        val settings = PlannedMessageSettings(
            skipMissedGraceMs = 15 * 60 * 1000L,
            lateFireMode = LateFireMode.FIRE_IF_WITHIN_GRACE,
        )

        val outcome = engine.applyExecutionPolicy(
            message = message,
            nowUtcEpochMs = now,
            settings = settings,
        )

        assertFalse(outcome.shouldSend)
        assertEquals(null, outcome.updatedMessage.lastFiredAtUtcEpochMs)
        val nextLocal = Instant.ofEpochMilli(outcome.updatedMessage.nextTriggerAtUtcEpochMs!!).atZone(zoneId)
        assertEquals(LocalDate.of(2026, 1, 12), nextLocal.toLocalDate())
        assertEquals(8, nextLocal.hour)
        assertEquals(0, nextLocal.minute)
    }

    @Test
    fun `late fire mode immediately sends even beyond grace`() {
        val due = Instant.parse("2026-01-01T10:00:00Z").toEpochMilli()
        val lateByTwoHours = due + 2 * 60 * 60 * 1000L
        val message = oneShotMessage(
            oneShotAt = due,
            policy = PlannedMessageDeliveryPolicy.SKIP_MISSED,
        ).copy(nextTriggerAtUtcEpochMs = due)
        val settings = PlannedMessageSettings(
            skipMissedGraceMs = 5 * 60 * 1000L,
            lateFireMode = LateFireMode.FIRE_IMMEDIATELY,
        )

        val outcome = engine.applyExecutionPolicy(
            message = message,
            nowUtcEpochMs = lateByTwoHours,
            settings = settings,
        )

        assertTrue(outcome.shouldSend)
        assertFalse(outcome.updatedMessage.isEnabled)
    }

    @Test
    fun `invalid timezone falls back to system zone and marks entity`() {
        val now = Instant.parse("2026-01-01T12:00:00Z").toEpochMilli()
        val message = weeklyMessage(
            day = DayOfWeek.THURSDAY,
            hour = 13,
            minute = 0,
            timezoneId = "Invalid/Timezone",
        )

        val initialized = engine.initializeForScheduling(message, now, defaultSettings)

        assertTrue(initialized.hadTimezoneFallback)
        assertNotNull(initialized.nextTriggerAtUtcEpochMs)
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
