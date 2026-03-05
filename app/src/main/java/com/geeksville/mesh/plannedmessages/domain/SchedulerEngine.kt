package com.geeksville.mesh.plannedmessages.domain

import com.geeksville.mesh.plannedmessages.data.PlannedMessageEntity
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import javax.inject.Inject
import kotlin.math.max

class SchedulerEngine @Inject constructor() {

    data class ExecutionOutcome(
        val shouldSend: Boolean,
        val scheduledOccurrenceUtcEpochMs: Long?,
        val updatedMessage: PlannedMessageEntity,
    )

    fun initializeForScheduling(
        message: PlannedMessageEntity,
        nowUtcEpochMs: Long = System.currentTimeMillis(),
    ): PlannedMessageEntity {
        val nextTrigger = computeNextTriggerUtcEpochMs(message, nowUtcEpochMs)
        return message.copy(
            isEnabled = message.isEnabled && nextTrigger != null,
            nextTriggerAtUtcEpochMs = nextTrigger,
            updatedAtUtcEpochMs = nowUtcEpochMs,
        )
    }

    fun computeNextTriggerUtcEpochMs(
        message: PlannedMessageEntity,
        nowUtcEpochMs: Long = System.currentTimeMillis(),
    ): Long? {
        if (!message.isEnabled) return null
        return when (message.scheduleType) {
            PlannedMessageScheduleType.ONE_SHOT -> computeOneShotTrigger(message, nowUtcEpochMs)
            PlannedMessageScheduleType.WEEKLY -> computeWeeklyTrigger(message, nowUtcEpochMs)
        }
    }

    fun applyExecutionPolicy(
        message: PlannedMessageEntity,
        nowUtcEpochMs: Long = System.currentTimeMillis(),
    ): ExecutionOutcome {
        val dueAt = message.nextTriggerAtUtcEpochMs
        if (!message.isEnabled || dueAt == null || nowUtcEpochMs < dueAt) {
            return ExecutionOutcome(
                shouldSend = false,
                scheduledOccurrenceUtcEpochMs = null,
                updatedMessage = message,
            )
        }

        val shouldSend = when (message.deliveryPolicy) {
            PlannedMessageDeliveryPolicy.CATCH_UP -> true
            PlannedMessageDeliveryPolicy.SKIP_MISSED ->
                nowUtcEpochMs - dueAt <= MAX_SKIP_GRACE_MS
        }

        val updated = when (message.scheduleType) {
            PlannedMessageScheduleType.ONE_SHOT -> message.copy(
                isEnabled = false,
                nextTriggerAtUtcEpochMs = null,
                lastFiredAtUtcEpochMs = if (shouldSend) dueAt else message.lastFiredAtUtcEpochMs,
                updatedAtUtcEpochMs = nowUtcEpochMs,
            )

            PlannedMessageScheduleType.WEEKLY -> {
                val reference = if (shouldSend) dueAt + 1 else max(nowUtcEpochMs, dueAt) + 1
                val next = computeWeeklyFutureTrigger(message, reference)
                message.copy(
                    isEnabled = message.isEnabled && next != null,
                    nextTriggerAtUtcEpochMs = next,
                    lastFiredAtUtcEpochMs = if (shouldSend) dueAt else message.lastFiredAtUtcEpochMs,
                    updatedAtUtcEpochMs = nowUtcEpochMs,
                )
            }
        }

        return ExecutionOutcome(
            shouldSend = shouldSend,
            scheduledOccurrenceUtcEpochMs = dueAt,
            updatedMessage = updated,
        )
    }

    private fun computeOneShotTrigger(
        message: PlannedMessageEntity,
        nowUtcEpochMs: Long,
    ): Long? {
        val oneShotAt = message.oneShotAtUtcEpochMs ?: return null
        return when {
            oneShotAt >= nowUtcEpochMs -> oneShotAt
            message.deliveryPolicy == PlannedMessageDeliveryPolicy.CATCH_UP -> oneShotAt
            else -> null
        }
    }

    private fun computeWeeklyTrigger(
        message: PlannedMessageEntity,
        nowUtcEpochMs: Long,
    ): Long? {
        if (message.daysOfWeekMask == 0) return null

        val zoneId = resolveZoneId(message.timezoneId)
        val nowZoned = Instant.ofEpochMilli(nowUtcEpochMs).atZone(zoneId)

        if (message.deliveryPolicy == PlannedMessageDeliveryPolicy.CATCH_UP) {
            latestCandidateAtOrBefore(message, nowZoned.toLocalDateTime(), zoneId)?.let {
                if (it <= nowUtcEpochMs) return it
            }
        }

        return nextCandidateAtOrAfter(message, nowZoned.toLocalDateTime(), zoneId)
    }

    private fun computeWeeklyFutureTrigger(
        message: PlannedMessageEntity,
        nowUtcEpochMs: Long,
    ): Long? {
        if (message.daysOfWeekMask == 0) return null
        val zoneId = resolveZoneId(message.timezoneId)
        val nowZoned = Instant.ofEpochMilli(nowUtcEpochMs).atZone(zoneId)
        return nextCandidateAtOrAfter(message, nowZoned.toLocalDateTime(), zoneId)
    }

    private fun nextCandidateAtOrAfter(
        message: PlannedMessageEntity,
        localDateTime: LocalDateTime,
        zoneId: ZoneId,
    ): Long? {
        val localTime = LocalTime.of(message.hourOfDay, message.minuteOfHour)
        for (dayOffset in 0..14) {
            val candidateDate = localDateTime.toLocalDate().plusDays(dayOffset.toLong())
            if (!WeeklyDays.contains(message.daysOfWeekMask, candidateDate.dayOfWeek)) continue
            val candidate = LocalDateTime.of(candidateDate, localTime).atZone(zoneId)
            if (!candidate.toLocalDateTime().isBefore(localDateTime)) {
                return candidate.toInstant().toEpochMilli()
            }
        }
        return null
    }

    private fun latestCandidateAtOrBefore(
        message: PlannedMessageEntity,
        localDateTime: LocalDateTime,
        zoneId: ZoneId,
    ): Long? {
        val localTime = LocalTime.of(message.hourOfDay, message.minuteOfHour)
        for (dayOffset in 0..7) {
            val candidateDate = localDateTime.toLocalDate().minusDays(dayOffset.toLong())
            if (!WeeklyDays.contains(message.daysOfWeekMask, candidateDate.dayOfWeek)) continue
            val candidate = LocalDateTime.of(candidateDate, localTime).atZone(zoneId)
            if (!candidate.toLocalDateTime().isAfter(localDateTime)) {
                return candidate.toInstant().toEpochMilli()
            }
        }
        return null
    }

    private fun resolveZoneId(timezoneId: String): ZoneId {
        return runCatching { ZoneId.of(timezoneId) }.getOrElse { ZoneId.systemDefault() }
    }

    companion object {
        // Alarm delivery can jitter a little while idle; do not classify tiny delays as missed.
        private const val MAX_SKIP_GRACE_MS = 2 * 60 * 1000L
    }
}
