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

    private data class TriggerComputation(
        val nextTriggerAtUtcEpochMs: Long?,
        val hadTimezoneFallback: Boolean,
    )

    private data class ZoneResolution(
        val zoneId: ZoneId,
        val hadTimezoneFallback: Boolean,
    )

    fun initializeForScheduling(
        message: PlannedMessageEntity,
        nowUtcEpochMs: Long = System.currentTimeMillis(),
        settings: PlannedMessageSettings = PlannedMessageSettings(),
    ): PlannedMessageEntity {
        val trigger = computeNextTrigger(message, nowUtcEpochMs, settings)
        return message.copy(
            isEnabled = message.isEnabled && trigger.nextTriggerAtUtcEpochMs != null,
            nextTriggerAtUtcEpochMs = trigger.nextTriggerAtUtcEpochMs,
            hadTimezoneFallback = message.hadTimezoneFallback || trigger.hadTimezoneFallback,
            updatedAtUtcEpochMs = nowUtcEpochMs,
        )
    }

    fun computeNextTriggerUtcEpochMs(
        message: PlannedMessageEntity,
        nowUtcEpochMs: Long = System.currentTimeMillis(),
        settings: PlannedMessageSettings = PlannedMessageSettings(),
    ): Long? {
        return computeNextTrigger(
            message = message,
            nowUtcEpochMs = nowUtcEpochMs,
            settings = settings,
        ).nextTriggerAtUtcEpochMs
    }

    private fun computeNextTrigger(
        message: PlannedMessageEntity,
        nowUtcEpochMs: Long,
        settings: PlannedMessageSettings,
    ): TriggerComputation {
        if (!message.isEnabled) return TriggerComputation(nextTriggerAtUtcEpochMs = null, hadTimezoneFallback = false)
        return when (message.scheduleType) {
            PlannedMessageScheduleType.ONE_SHOT -> computeOneShotTrigger(message, nowUtcEpochMs, settings)
            PlannedMessageScheduleType.WEEKLY -> computeWeeklyTrigger(message, nowUtcEpochMs, settings)
        }
    }

    fun applyExecutionPolicy(
        message: PlannedMessageEntity,
        nowUtcEpochMs: Long = System.currentTimeMillis(),
        settings: PlannedMessageSettings = PlannedMessageSettings(),
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
                shouldSendSkippedOccurrence(
                    dueAtUtcEpochMs = dueAt,
                    nowUtcEpochMs = nowUtcEpochMs,
                    settings = settings,
                )
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
                    isEnabled = message.isEnabled && next.nextTriggerAtUtcEpochMs != null,
                    nextTriggerAtUtcEpochMs = next.nextTriggerAtUtcEpochMs,
                    lastFiredAtUtcEpochMs = if (shouldSend) dueAt else message.lastFiredAtUtcEpochMs,
                    hadTimezoneFallback = message.hadTimezoneFallback || next.hadTimezoneFallback,
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
        settings: PlannedMessageSettings,
    ): TriggerComputation {
        val oneShotAt = message.oneShotAtUtcEpochMs
            ?: return TriggerComputation(nextTriggerAtUtcEpochMs = null, hadTimezoneFallback = false)
        val nextTriggerAt = when {
            oneShotAt >= nowUtcEpochMs -> oneShotAt
            message.deliveryPolicy == PlannedMessageDeliveryPolicy.CATCH_UP -> oneShotAt
            shouldSendSkippedOccurrence(
                dueAtUtcEpochMs = oneShotAt,
                nowUtcEpochMs = nowUtcEpochMs,
                settings = settings,
            ) -> oneShotAt
            else -> null
        }
        return TriggerComputation(nextTriggerAtUtcEpochMs = nextTriggerAt, hadTimezoneFallback = false)
    }

    private fun computeWeeklyTrigger(
        message: PlannedMessageEntity,
        nowUtcEpochMs: Long,
        settings: PlannedMessageSettings,
    ): TriggerComputation {
        if (message.daysOfWeekMask == 0) {
            return TriggerComputation(nextTriggerAtUtcEpochMs = null, hadTimezoneFallback = false)
        }

        val zoneResolution = resolveZoneId(message.timezoneId)
        val nowZoned = Instant.ofEpochMilli(nowUtcEpochMs).atZone(zoneResolution.zoneId)
        val nowLocal = nowZoned.toLocalDateTime()
        val latestCandidate = latestCandidateAtOrBefore(message, nowLocal, zoneResolution.zoneId)

        if (latestCandidate != null && latestCandidate <= nowUtcEpochMs) {
            val shouldUsePastOccurrence = when (message.deliveryPolicy) {
                PlannedMessageDeliveryPolicy.CATCH_UP -> true
                PlannedMessageDeliveryPolicy.SKIP_MISSED -> shouldSendSkippedOccurrence(
                    dueAtUtcEpochMs = latestCandidate,
                    nowUtcEpochMs = nowUtcEpochMs,
                    settings = settings,
                )
            }
            if (shouldUsePastOccurrence) {
                return TriggerComputation(
                    nextTriggerAtUtcEpochMs = latestCandidate,
                    hadTimezoneFallback = zoneResolution.hadTimezoneFallback,
                )
            }
        }

        return TriggerComputation(
            nextTriggerAtUtcEpochMs = nextCandidateAtOrAfter(
                message = message,
                localDateTime = nowLocal,
                zoneId = zoneResolution.zoneId,
            ),
            hadTimezoneFallback = zoneResolution.hadTimezoneFallback,
        )
    }

    private fun computeWeeklyFutureTrigger(
        message: PlannedMessageEntity,
        nowUtcEpochMs: Long,
    ): TriggerComputation {
        if (message.daysOfWeekMask == 0) {
            return TriggerComputation(nextTriggerAtUtcEpochMs = null, hadTimezoneFallback = false)
        }
        val zoneResolution = resolveZoneId(message.timezoneId)
        val nowZoned = Instant.ofEpochMilli(nowUtcEpochMs).atZone(zoneResolution.zoneId)
        return TriggerComputation(
            nextTriggerAtUtcEpochMs = nextCandidateAtOrAfter(
                message = message,
                localDateTime = nowZoned.toLocalDateTime(),
                zoneId = zoneResolution.zoneId,
            ),
            hadTimezoneFallback = zoneResolution.hadTimezoneFallback,
        )
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

    private fun resolveZoneId(timezoneId: String): ZoneResolution {
        val zoneId = runCatching { ZoneId.of(timezoneId) }.getOrNull()
        return if (zoneId != null) {
            ZoneResolution(zoneId = zoneId, hadTimezoneFallback = false)
        } else {
            ZoneResolution(zoneId = ZoneId.systemDefault(), hadTimezoneFallback = true)
        }
    }

    private fun shouldSendSkippedOccurrence(
        dueAtUtcEpochMs: Long,
        nowUtcEpochMs: Long,
        settings: PlannedMessageSettings,
    ): Boolean {
        if (nowUtcEpochMs <= dueAtUtcEpochMs) return true
        val lateByMs = nowUtcEpochMs - dueAtUtcEpochMs
        return when (settings.lateFireMode) {
            LateFireMode.SKIP -> false
            LateFireMode.FIRE_IMMEDIATELY -> true
            LateFireMode.FIRE_IF_WITHIN_GRACE -> lateByMs <= settings.skipMissedGraceMs
        }
    }
}
